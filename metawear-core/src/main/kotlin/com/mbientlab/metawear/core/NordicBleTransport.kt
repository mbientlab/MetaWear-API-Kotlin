package com.mbientlab.metawear.core

import android.annotation.SuppressLint
import android.content.Context
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.transport.BleTransport
import com.mbientlab.metawear.transport.ScanResult
import com.mbientlab.metawear.transport.Uuids
import com.mbientlab.metawear.transport.WriteType
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.kotlin.ble.client.main.callback.ClientBleGatt
import no.nordicsemi.android.kotlin.ble.client.main.service.ClientBleGattCharacteristic
import no.nordicsemi.android.kotlin.ble.core.data.BleGattConnectOptions
import no.nordicsemi.android.kotlin.ble.core.data.BleWriteType
import no.nordicsemi.android.kotlin.ble.core.data.GattConnectionState
import no.nordicsemi.android.kotlin.ble.core.data.util.DataByteArray

/**
 * [BleTransport] for a **single** MetaWear peripheral, backed by the Nordic
 * Kotlin BLE Library (`no.nordicsemi.android.kotlin.ble`). The Android
 * counterpart of the Swift `CoreBluetoothPeripheralTransport` actor.
 *
 * Production code obtains instances indirectly through
 * [AndroidMetaWear.scanner]; nothing here scans — see the class-level docs on
 * [AndroidBleScanSource] for the scanning half of the seam.
 *
 * ### Connect flow (mirrors the Swift transport)
 * 1. GATT connect with `autoConnect = false`, bounded retry to absorb
 *    Android's transient status-133 (`GATT_ERROR`) failures.
 * 2. Full service discovery; every characteristic of every service is indexed
 *    by UUID, so later [read]s of Device Information Service characteristics
 *    (which live on service 0x180A, not the MetaWear service) resolve without
 *    callers naming a service.
 * 3. MTU request for [REQUESTED_MTU] bytes. The default BLE MTU of 23 leaves
 *    a 20-byte payload — too small for packed streaming, where the board puts
 *    3 samples (18 bytes) plus the 2-byte module/register header in one
 *    notification and firmware revisions past 1.4 pad further; 247 is the
 *    Nordic-recommended maximum and comfortably fits every MetaWear packet.
 * 4. Notifications enabled on the MetaWear notify characteristic
 *    (`326A9006-…`), matching the Swift central's connect sequence, so the
 *    protocol router can subscribe without an extra descriptor round-trip.
 *
 * ### Serialization
 * Android allows one outstanding GATT operation per connection. The Nordic
 * client already serializes every operation (read / write / descriptor write /
 * MTU / RSSI) behind an internal per-connection mutex
 * (`no.nordicsemi.android.kotlin.ble.core.mutex.MutexWrapper`), so this class
 * adds no additional locking of its own.
 *
 * ### Permissions
 * The library declares but does **not** request permissions. The hosting app
 * must hold `BLUETOOTH_CONNECT` (API 31+) — or legacy `BLUETOOTH` +
 * `ACCESS_FINE_LOCATION` (API ≤ 30) — before calling [connect].
 */
@SuppressLint("MissingPermission") // runtime permission grant is the caller's contract, see KDoc
class NordicBleTransport(
    context: Context,
    /** MAC address of the peripheral this transport is bound to. */
    private val identifier: String,
    /**
     * Scope hosting the Nordic client's internal event pumps. Defaults to a
     * dedicated supervisor scope so one device's teardown can't cancel another's.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : BleTransport {

    private val appContext: Context = context.applicationContext

    /** Everything owned by one established link; replaced wholesale on reconnect. */
    private class Connection(
        val gatt: ClientBleGatt,
        /** Characteristics of *all* discovered services, keyed by UUID. */
        val characteristics: Map<UUID, ClientBleGattCharacteristic>,
        /**
         * Shared notification flow for the MetaWear notify characteristic,
         * created (and CCC-descriptor-enabled) once during [connect]. Collecting
         * it does not re-enable notifications, so the router can subscribe and
         * unsubscribe freely for the lifetime of this connection.
         */
        val notifySource: Flow<DataByteArray>,
    )

    @Volatile
    private var connection: Connection? = null

    // ---- BleTransport: scan (not supported on a per-device transport) ----

    override fun scan(services: List<UUID>?): Flow<ScanResult> =
        throw UnsupportedOperationException(
            "NordicBleTransport is bound to one peripheral and does not scan; " +
                "use AndroidMetaWear.scanner(context) / AndroidBleScanSource instead",
        )

    // ---- BleTransport: connect / disconnect ----

    override suspend fun connect(identifier: String) {
        require(identifier == this.identifier) {
            "This transport is bound to ${this.identifier}, got $identifier"
        }
        if (connection != null) {
            throw MetaWearException.InvalidState("Already connected to $identifier")
        }

        val gatt = connectWithRetry()
        try {
            // Order mirrors the Swift transport: discovery first, then MTU,
            // then notify enable — see the class KDoc for the rationale.
            val services = gatt.discoverServices()
            val characteristics = buildMap {
                for (service in services.services) {
                    for (characteristic in service.characteristics) {
                        // First occurrence wins; MetaWear boards don't reuse
                        // characteristic UUIDs across services.
                        putIfAbsent(characteristic.uuid, characteristic)
                    }
                }
            }

            // MTU 247: packed 3-sample streaming does not fit the 23-byte
            // default (see class KDoc). The peripheral may negotiate down; the
            // MetaWear firmware accepts 247 on every supported board.
            gatt.requestMtu(REQUESTED_MTU)

            val notifyCharacteristic = characteristics[Uuids.notify]
                ?: throw MetaWearException.OperationFailed(
                    "MetaWear notify characteristic ${Uuids.notify} not found on $identifier " +
                        "— not a MetaWear board?",
                )
            // Writes the CCC descriptor once; the returned flow is shared.
            val notifySource = notifyCharacteristic.getNotifications()

            connection = Connection(gatt, characteristics, notifySource)
        } catch (e: Throwable) {
            // Connection was up but setup failed — tear the link down so the
            // device converges on Disconnected instead of leaking a half-open GATT.
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
            throw e
        }
    }

    /**
     * Establish the raw GATT link, retrying on failure.
     *
     * The Nordic `connect` resumes *normally* with a disconnected client when
     * the link fails (it maps the failure into `connectionStateWithStatus`
     * instead of throwing), so failure is detected by checking `isConnected`
     * on the returned client. Android's stack sporadically fails a first
     * attempt with status 133 (`GATT_ERROR`), especially right after the board
     * stops advertising — a bounded retry absorbs that.
     */
    private suspend fun connectWithRetry(): ClientBleGatt {
        var lastError: Throwable = MetaWearException.OperationFailed("Connect to $identifier failed")
        repeat(CONNECT_ATTEMPTS) { attempt ->
            val gatt = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                ClientBleGatt.connect(
                    appContext,
                    identifier,
                    scope,
                    BleGattConnectOptions(
                        autoConnect = false,
                        // Nordic's per-characteristic notification buffer size.
                        // The default (10) is tight for 100 Hz packed streaming
                        // if the collector hiccups; 64 gives ~2 s of headroom.
                        bufferSize = 64,
                    ),
                )
            }
            when {
                gatt == null -> lastError = MetaWearException.Timeout
                gatt.isConnected -> return gatt
                else -> {
                    val status = gatt.connectionStateWithStatus.value?.status
                    runCatching { gatt.close() }
                    lastError = MetaWearException.OperationFailed(
                        "Connect to $identifier failed (status $status, attempt ${attempt + 1})",
                    )
                }
            }
            if (attempt < CONNECT_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        throw lastError
    }

    override suspend fun disconnect() {
        val active = connection ?: return
        connection = null
        val gatt = active.gatt
        if (gatt.isConnected) {
            gatt.disconnect()
            // Wait for the stack to confirm the drop so a follow-up connect()
            // doesn't race the teardown; bounded so a wedged stack can't hang us.
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                gatt.connectionStateWithStatus
                    .filterNotNull()
                    .first { it.state == GattConnectionState.STATE_DISCONNECTED }
            }
        }
        // closeOnDisconnect (default true) normally closes the GATT for us;
        // this is a belt-and-braces close for never-fully-connected edges.
        // BluetoothGatt.close() is idempotent.
        runCatching { gatt.close() }
    }

    // ---- BleTransport: write / read / RSSI ----

    override suspend fun write(data: ByteArray, characteristic: UUID, type: WriteType) {
        val writeType = when (type) {
            WriteType.WITH_RESPONSE -> BleWriteType.DEFAULT
            WriteType.WITHOUT_RESPONSE -> BleWriteType.NO_RESPONSE
        }
        // Nordic suspends until the stack confirms the write (both types fire
        // onCharacteristicWrite once the local buffer accepts the packet), so
        // write-without-response gets back-pressure for free — the Kotlin
        // equivalent of the Swift transport's canSendWriteWithoutResponse queue.
        characteristicFor(characteristic).write(DataByteArray(data), writeType)
    }

    override suspend fun read(characteristic: UUID): ByteArray =
        characteristicFor(characteristic).read().value

    override suspend fun readRSSI(): Int = activeConnection().gatt.readRssi()

    // ---- BleTransport: notifications ----

    override fun notifications(characteristic: UUID): Flow<ByteArray> = channelFlow {
        val active = activeConnection()

        // BleTransport contract: the flow must terminate with an exception on
        // BLE disconnect. Nordic's notification flow just goes quiet, so a
        // watcher races it and closes the channel with an error on link drop.
        launch {
            active.gatt.connectionStateWithStatus
                .filterNotNull()
                .first { it.state == GattConnectionState.STATE_DISCONNECTED }
            close(MetaWearException.OperationFailed("BLE link to $identifier dropped"))
        }

        val source = if (characteristic == Uuids.notify) {
            // Already CCC-enabled during connect(); shared across subscribers.
            active.notifySource
        } else {
            // Rarely used path (e.g. battery-level 0x2A19 notifications):
            // enables notifications on first use, disables them on completion.
            characteristicFor(characteristic).getNotifications()
        }
        source.collect { send(it.value) }
    }

    // ---- Helpers ----

    private fun activeConnection(): Connection =
        connection ?: throw MetaWearException.InvalidState("Not connected to $identifier")

    private fun characteristicFor(uuid: UUID): ClientBleGattCharacteristic =
        activeConnection().characteristics[uuid]
            ?: throw MetaWearException.OperationFailed("Characteristic $uuid not available on $identifier")

    private companion object {
        const val CONNECT_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 500L
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val DISCONNECT_TIMEOUT_MS = 5_000L

        /**
         * Requested ATT MTU. 247 (244-byte payload) is the nRF52 maximum and
         * the value both the official Android SDK and the Swift SDK request;
         * anything ≥ 23+... that fits a packed 3-sample accelerometer packet
         * (2-byte header + 18 bytes of samples) works, but bigger MTUs also
         * speed up log downloads considerably.
         */
        const val REQUESTED_MTU = 247
    }
}
