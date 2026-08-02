package com.mbientlab.metawear.app.data

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.sensor.Event
import com.mbientlab.metawear.sensor.EventAction
import com.mbientlab.metawear.sensor.EventSource
import com.mbientlab.metawear.sensor.Led
import com.mbientlab.metawear.sensor.LedPattern
import com.mbientlab.metawear.sensor.createEvent
import com.mbientlab.metawear.sensor.removeEvent
import com.mbientlab.metawear.sensor.setLed
import com.mbientlab.metawear.sensor.stopLed

/**
 * The "recording" LED heartbeat: a short red pulse every 5 seconds while a
 * board logs, surviving disconnects.
 *
 * The firmware stops LED playback the moment the BLE link drops, so a plain
 * `setLed` dies with the connection. [arm] therefore also records two
 * disconnect-triggered events on the board — re-set the red pattern, then
 * play — so the board re-arms its own blink on every future disconnect with
 * no phone involved. [disarm] must remove those events by their persisted
 * ids, or the board relights on every disconnect forever.
 *
 * Everything here is best-effort: an LED hiccup must never fail a logging
 * session.
 */
object RecordingHeartbeat {

    /**
     * ~100 ms pulse every 5 s, forever, dim duty — deliberately gentle so it
     * can't dent a multi-hour logging battery.
     */
    val PATTERN = LedPattern(
        highIntensity = 31,
        lowIntensity = 0,
        riseTime = 0,
        highTime = 100,
        fallTime = 0,
        pulseDuration = 5_000,
        repeatCount = 0xFF,
    )

    /** Settings-module revision floor for disconnect events. */
    const val DISCONNECT_EVENT_REVISION = 2

    /**
     * Start the heartbeat now and arm it across disconnects. Returns the
     * board-assigned event ids to persist (empty when arming was skipped or
     * failed — cleanup then has nothing to do).
     */
    suspend fun arm(device: MetaWearDevice): List<Int> {
        // Blink for the connected window regardless of event support.
        runCatching { device.setLed(red = PATTERN) }

        val settingsRevision = device.moduleInfo(Module.SETTINGS)?.revision ?: 0
        if (settingsRevision < DISCONNECT_EVENT_REVISION) return emptyList()

        return runCatching {
            val pattern = device.createEvent(
                source = EventSource.disconnected(),
                action = ledCommandAction(Led.SetPattern(Led.Color.RED, PATTERN).commandData),
            )
            val play = device.createEvent(
                source = EventSource.disconnected(),
                action = ledCommandAction(Led.Play().commandData),
            )
            listOf(pattern.id, play.id)
        }.getOrDefault(emptyList())
    }

    /**
     * Stop the blink and remove the disconnect events. LED first, so the
     * light can't outlive the session even if event removal fails.
     */
    suspend fun disarm(device: MetaWearDevice, eventIds: List<Int>) {
        runCatching { device.stopLed(clearPattern = true) }
        for (id in eventIds) {
            runCatching { device.removeEvent(Event(id = id)) }
        }
    }

    /** Split full LED command bytes `[module, register, params…]` into an [EventAction]. */
    private fun ledCommandAction(command: ByteArray): EventAction = EventAction(
        module = Module.LED,
        register = command[1].toInt() and 0xFF,
        params = if (command.size > 2) command.copyOfRange(2, command.size) else ByteArray(0),
    )
}
