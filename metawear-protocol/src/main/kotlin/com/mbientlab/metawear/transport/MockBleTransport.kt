package com.mbientlab.metawear.transport

import com.mbientlab.metawear.model.MetaWearException
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/**
 * In-memory BLE transport for unit tests. No hardware required.
 *
 * Inject board responses with [inject]; inspect commands the SDK sent via
 * [writtenData] / [writtenCommands]. A lock guards the mutable maps/lists so
 * tests and a device worker coroutine can touch the mock from different
 * threads.
 */
class MockBleTransport : BleTransport {

    /** One recorded GATT write. */
    data class Write(val data: ByteArray, val characteristic: UUID, val type: WriteType) {
        override fun equals(other: Any?): Boolean =
            other is Write && data.contentEquals(other.data) &&
                characteristic == other.characteristic && type == other.type

        override fun hashCode(): Int =
            31 * (31 * data.contentHashCode() + characteristic.hashCode()) + type.hashCode()
    }

    @Volatile var connectError: Throwable? = null
    @Volatile var disconnectError: Throwable? = null

    /**
     * Artificial delay injected at the start of [connect]. Set before calling
     * `connect()` to widen the window for tests that need to observe an
     * in-progress connection.
     */
    @Volatile var connectDelay: Duration = Duration.ZERO

    /** Mock RSSI returned by [readRSSI] (default −55 dBm). */
    @Volatile var mockRssi: Int = -55

    /**
     * Termination is delivered **in-band** (as channel elements) rather than via
     * `Channel.close(cause)`: closing a channel does not wake a receiver parked
     * on a kotlinx-coroutines-test `backgroundScope` dispatcher within the
     * test's virtual time, while `trySend` provably does. In-band markers also
     * guarantee ordering — packets buffered before the failure/completion are
     * delivered first, then the terminal event.
     */
    private sealed interface Event {
        class Packet(val bytes: ByteArray) : Event
        class Error(val cause: Throwable) : Event
        data object Complete : Event
    }

    private val lock = Any()
    private val readResponses = mutableMapOf<UUID, ByteArray>()
    private val writes = mutableListOf<Write>()
    private val notifyChannels = mutableMapOf<UUID, Channel<Event>>()

    /** All write calls in order. */
    val writtenData: List<Write> get() = synchronized(lock) { writes.toList() }

    /** Only the payload bytes from all write calls, in order. */
    val writtenCommands: List<ByteArray> get() = synchronized(lock) { writes.map { it.data } }

    /** Canned response returned by [read] for [characteristic]. */
    fun setReadResponse(characteristic: UUID, data: ByteArray) {
        synchronized(lock) { readResponses[characteristic] = data }
    }

    /** Forget recorded writes (useful between test phases). */
    fun clearWrites() {
        synchronized(lock) { writes.clear() }
    }

    // ---- BleTransport ----

    override fun scan(services: List<UUID>?): Flow<ScanResult> = emptyFlow()

    override suspend fun connect(identifier: String) {
        if (connectDelay > Duration.ZERO) delay(connectDelay)
        connectError?.let { throw it }
    }

    override suspend fun disconnect() {
        disconnectError?.let { throw it }
        val channels = synchronized(lock) {
            val snapshot = notifyChannels.values.toList()
            notifyChannels.clear()
            snapshot
        }
        channels.forEach {
            it.trySend(Event.Complete)
            it.close()
        }
    }

    override suspend fun write(data: ByteArray, characteristic: UUID, type: WriteType) {
        synchronized(lock) { writes.add(Write(data, characteristic, type)) }
    }

    override suspend fun read(characteristic: UUID): ByteArray =
        synchronized(lock) { readResponses[characteristic] }
            ?: throw MetaWearException.OperationFailed("No mock response for $characteristic")

    override fun notifications(characteristic: UUID): Flow<ByteArray> {
        // A fresh unbounded channel per subscription; the most recent
        // subscriber per characteristic receives injections.
        val channel = Channel<Event>(Channel.UNLIMITED)
        synchronized(lock) { notifyChannels[characteristic] = channel }
        return flow {
            for (event in channel) {
                when (event) {
                    is Event.Packet -> emit(event.bytes)
                    is Event.Error -> throw event.cause
                    Event.Complete -> return@flow
                }
            }
        }
    }

    override suspend fun readRSSI(): Int = mockRssi

    // ---- Test hooks ----

    /** Inject a notification packet, simulating a MetaWear response. */
    fun inject(notification: ByteArray, characteristic: UUID) {
        val channel = synchronized(lock) { notifyChannels[characteristic] }
        channel?.trySend(Event.Packet(notification))
    }

    /** Simulate a BLE disconnect mid-stream: all notification flows fail. */
    fun simulateDisconnect(error: Throwable? = null) {
        val cause = error ?: MetaWearException.OperationFailed("Simulated disconnect")
        val channels = synchronized(lock) {
            val snapshot = notifyChannels.values.toList()
            notifyChannels.clear()
            snapshot
        }
        channels.forEach {
            it.trySend(Event.Error(cause))
            it.close()
        }
    }
}
