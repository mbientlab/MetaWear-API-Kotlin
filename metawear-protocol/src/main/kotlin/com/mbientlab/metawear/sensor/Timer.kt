package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

// On-device timers (module 0x0C).

/**
 * A handle to an on-device timer created via [MetaWearDevice.createTimer].
 *
 * Timers execute entirely on the MetaWear — no BLE connection required once
 * started. They fire `[0x0C, 0x06, timer_id]` notifications at the configured
 * interval.
 *
 * Typical flow:
 * ```kotlin
 * val timer = device.createTimer(periodMs = 1000, repetitions = MetaWearTimer.INFINITE)
 * device.startTimer(timer)
 *
 * // Stream notifications while connected:
 * device.streamTimer(timer).collect { ... }
 *
 * // Stop and clean up when done:
 * device.stopTimer(timer)
 * device.removeTimer(timer)
 * ```
 */
data class MetaWearTimer(
    /** Board-assigned ID (0-based). */
    val id: Int,
    /** Period in milliseconds. */
    val periodMs: Long,
    /** How many times the timer fires before stopping. `0xFFFF` = infinite. */
    val repetitions: Int,
    /** Whether the first fire happens immediately (`true`) or after one period. */
    val immediate: Boolean,
) {
    companion object {
        /** A repetition count meaning "fire indefinitely". */
        const val INFINITE: Int = 0xFFFF
    }
}

// ---- MetaWearDevice timer API ----

/**
 * Create an on-device timer and return a handle.
 *
 * @param periodMs How often the timer fires, in milliseconds.
 * @param repetitions Number of times to fire. Pass [MetaWearTimer.INFINITE] (0xFFFF) for indefinite.
 * @param immediate If `true`, the first fire happens at t=0; otherwise after one period.
 */
suspend fun MetaWearDevice.createTimer(
    periodMs: Long,
    repetitions: Int = MetaWearTimer.INFINITE,
    immediate: Boolean = false,
): MetaWearTimer {
    // [0x0C, 0x02, period(4 LE), repetitions(2 LE), immediate]
    val cmd = Packet.command(
        Module.TIMER, 0x02,
        (periodMs and 0xFF).toInt(), ((periodMs shr 8) and 0xFF).toInt(),
        ((periodMs shr 16) and 0xFF).toInt(), ((periodMs shr 24) and 0xFF).toInt(),
        repetitions and 0xFF, (repetitions shr 8) and 0xFF,
        if (immediate) 0x01 else 0x00,
    )
    // Response: [0x0C, 0x02, timer_id] — plain notification, NOT a read
    // response. Same firmware pattern as logger subscribe / processor add /
    // event create: every "create resource on register 0x02" reply lands
    // without the 0x80 read bit, so we await on the notify waiters, not the
    // read waiters. `sendRead` would time out here.
    val response = sendAndAwaitNotification(cmd, awaitModule = Module.TIMER, awaitRegister = 0x02)
    if (response.size < 3) {
        throw MetaWearException.OperationFailed("Timer create response too short: ${response.size} bytes")
    }
    return MetaWearTimer(
        id = response[2].toInt() and 0xFF,
        periodMs = periodMs,
        repetitions = repetitions,
        immediate = immediate,
    )
}

/** Start a previously created timer. */
suspend fun MetaWearDevice.startTimer(timer: MetaWearTimer) = send(TimerCommand.start(timer))

/** Stop a running timer (the timer_id remains valid; call [startTimer] to resume). */
suspend fun MetaWearDevice.stopTimer(timer: MetaWearTimer) = send(TimerCommand.stop(timer))

/** Remove the timer from the board, freeing its ID. Cannot be restarted after this. */
suspend fun MetaWearDevice.removeTimer(timer: MetaWearTimer) = send(TimerCommand.remove(timer))

/**
 * Send a remove command for every timer slot the firmware exposes (8 IDs, per
 * the C++ SDK pool). Unknown IDs are silently ignored by the board, so this is
 * a safe broad-stroke cleanup after a crash or at the start of a fresh session.
 */
suspend fun MetaWearDevice.removeAllTimers() {
    for (id in 0 until 8) {
        val stub = MetaWearTimer(id = id, periodMs = 0, repetitions = MetaWearTimer.INFINITE, immediate = false)
        runCatching { removeTimer(stub) }
    }
}

/**
 * Subscribe to tick notifications from a running timer. Each element in the
 * flow is the timer ID that fired. The flow ends when the caller cancels it or
 * the device disconnects. Call [setTimerNotify] first so the board actually
 * sends the `[0x0C, 0x06, timer_id]` packets.
 */
fun MetaWearDevice.streamTimer(timer: MetaWearTimer): Flow<Int> =
    subscribeRaw(Module.TIMER, 0x06).mapNotNull { packet ->
        // [0x0C, 0x06, timer_id]
        if (packet.size >= 3 && (packet[2].toInt() and 0xFF) == timer.id) timer.id else null
    }

/**
 * Enable / disable timer-fired notifications on the board. Call before
 * streaming timer ticks.
 */
suspend fun MetaWearDevice.setTimerNotify(timer: MetaWearTimer, enabled: Boolean) =
    writeRaw(Packet.command(Module.TIMER, 0x07, timer.id, if (enabled) 0x01 else 0x00))

// ---- Timer commands (internal) ----

internal object TimerCommand {
    fun start(timer: MetaWearTimer): Command = TimerCmd(register = 0x03, id = timer.id)
    fun stop(timer: MetaWearTimer): Command = TimerCmd(register = 0x04, id = timer.id)
    fun remove(timer: MetaWearTimer): Command = TimerCmd(register = 0x05, id = timer.id)

    private class TimerCmd(private val register: Int, private val id: Int) : Command {
        override val commandData: ByteArray get() = Packet.command(Module.TIMER, register, id)
    }
}
