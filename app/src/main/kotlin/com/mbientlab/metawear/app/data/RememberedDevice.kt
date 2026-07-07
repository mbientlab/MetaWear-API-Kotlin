package com.mbientlab.metawear.app.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A board the user connected to before. Android identifies peripherals by
 * MAC directly, so no peripheral-UUID reconciliation or dedupe sweeps are
 * needed — MAC is the primary key, full stop.
 */
data class RememberedDevice(
    /** Board MAC address — the identifier used across the whole SDK. */
    val mac: String,
    /** Advertised name at last connect. */
    val name: String,
    /** Wall-clock ms of the most recent successful connect. */
    val lastConnectedEpochMs: Long,
    val serialNumber: String? = null,
    val firmwareRevision: String? = null,
    val modelNumber: String? = null,
)

/**
 * Line-per-device, tab-separated persistence codec. Pure Kotlin so the round
 * trip is unit-tested on the JVM (SharedPreferences only stores the string).
 */
object RememberedDeviceCodec {

    private const val FIELD_SEP = "\t"
    private const val LINE_SEP = "\n"

    /** Strip separator characters that would corrupt the flat encoding. */
    private fun clean(value: String): String =
        value.replace(FIELD_SEP, " ").replace(LINE_SEP, " ")

    fun encode(devices: List<RememberedDevice>): String =
        devices.joinToString(LINE_SEP) { d ->
            listOf(
                clean(d.mac),
                clean(d.name),
                d.lastConnectedEpochMs.toString(),
                d.serialNumber?.let(::clean) ?: "",
                d.firmwareRevision?.let(::clean) ?: "",
                d.modelNumber?.let(::clean) ?: "",
            ).joinToString(FIELD_SEP)
        }

    fun decode(encoded: String): List<RememberedDevice> =
        encoded.split(LINE_SEP).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val f = line.split(FIELD_SEP)
            if (f.size < 3) return@mapNotNull null
            RememberedDevice(
                mac = f[0],
                name = f[1],
                lastConnectedEpochMs = f[2].toLongOrNull() ?: 0L,
                serialNumber = f.getOrNull(3)?.ifEmpty { null },
                firmwareRevision = f.getOrNull(4)?.ifEmpty { null },
                modelNumber = f.getOrNull(5)?.ifEmpty { null },
            )
        }

    /**
     * Upsert by MAC, newest-first ordering. Pure so it is unit-testable: an
     * existing row for the same MAC is replaced (non-null fields win), and the
     * result is sorted by last-connected descending.
     */
    fun upsert(devices: List<RememberedDevice>, device: RememberedDevice): List<RememberedDevice> {
        val existing = devices.firstOrNull { it.mac == device.mac }
        val merged = if (existing == null) device else device.copy(
            serialNumber = device.serialNumber ?: existing.serialNumber,
            firmwareRevision = device.firmwareRevision ?: existing.firmwareRevision,
            modelNumber = device.modelNumber ?: existing.modelNumber,
        )
        return (devices.filterNot { it.mac == device.mac } + merged)
            .sortedByDescending { it.lastConnectedEpochMs }
    }
}

/** SharedPreferences-backed store exposing remembered devices as a StateFlow. */
class RememberedDeviceStore(private val prefs: SharedPreferences) {

    private companion object {
        const val KEY = "remembered_devices"
    }

    private val _devices = MutableStateFlow(RememberedDeviceCodec.decode(prefs.getString(KEY, "") ?: ""))
    val devices: StateFlow<List<RememberedDevice>> = _devices.asStateFlow()

    fun upsert(device: RememberedDevice) {
        val updated = RememberedDeviceCodec.upsert(_devices.value, device)
        _devices.value = updated
        prefs.edit().putString(KEY, RememberedDeviceCodec.encode(updated)).apply()
    }

    fun forget(mac: String) {
        val updated = _devices.value.filterNot { it.mac == mac }
        _devices.value = updated
        prefs.edit().putString(KEY, RememberedDeviceCodec.encode(updated)).apply()
    }
}
