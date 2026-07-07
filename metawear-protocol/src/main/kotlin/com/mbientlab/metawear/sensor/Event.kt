package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet

// Port of MWEvent.swift — on-device event bindings (module 0x0A).

/**
 * A handle to an on-device event binding created via [MetaWearDevice.createEvent].
 *
 * Events bind a source signal to a destination command that executes
 * automatically on the MetaWear — no BLE connection required once set up.
 *
 * Typical flow:
 * ```kotlin
 * // Flash the LED every time timer 0 fires
 * val timer = device.createTimer(periodMs = 1000)
 * val event = device.createEvent(
 *     source = EventSource.timerFired(timer),
 *     action = EventAction.from(ledPlayCommand),
 * )
 * device.startTimer(timer)
 *
 * // Later, tear down:
 * device.removeEvent(event)
 * device.removeTimer(timer)
 * ```
 */
data class Event(
    /** Board-assigned ID used for removal. */
    val id: Int,
)

// ---- Source-module register opcodes referenced by event constructors ----
//
// Each event source identifies "module + register" of the signal that fires
// the event. The opcodes below name those signal registers so the convenience
// constructors don't read like a string of magic numbers.

private object SourceRegister {
    /** TIMER module — TIMER_NOTIFY: fires once per timer tick. Register 0x06. */
    const val TIMER_NOTIFY = 0x06

    /** SWITCH module — SWITCH_STATE: fires on every button state change. Register 0x01. */
    const val SWITCH_STATE = 0x01

    /** GPIO module — PIN_CHANGE_NOTIFY: fires on pin-change interrupt. Register 0x0A. */
    const val GPIO_PIN_CHANGE = 0x0A

    /** SETTINGS module — DISCONNECT_EVENT: fires when the host drops connection. Register 0x0A. */
    const val SETTINGS_DISCONNECT = 0x0A
}

/** Describes the signal that triggers an event. */
data class EventSource(
    val module: Module,
    /** Raw register byte (not OR'd with the read bit). */
    val register: Int,
    /** Signal instance ID. Use `0xFF` to match any instance. */
    val dataId: Int = 0xFF,
) {
    companion object {
        /** Fires each time the given timer ticks (`[0x0C, 0x06, timer_id]`). */
        fun timerFired(timer: MetaWearTimer): EventSource =
            EventSource(Module.TIMER, SourceRegister.TIMER_NOTIFY, timer.id)

        /** Fires on every button state change (`[0x01, 0x01, ...]`). */
        fun buttonChanged(): EventSource =
            EventSource(Module.SWITCH, SourceRegister.SWITCH_STATE, 0xFF)

        /** Fires on a GPIO pin-change notification for the given pin. */
        fun gpioChanged(pin: Int): EventSource =
            EventSource(Module.GPIO, SourceRegister.GPIO_PIN_CHANGE, pin)

        /**
         * Fires when the board is disconnected by the host (`[0x11, 0x0A, ...]`).
         * Bind this to a command to have the board run that command on
         * disconnect — e.g. stop advertising, save state. Requires settings
         * revision ≥ 2.
         */
        fun disconnected(): EventSource =
            EventSource(Module.SETTINGS, SourceRegister.SETTINGS_DISCONNECT, 0xFF)
    }
}

/**
 * Optional "data token" that slices bytes from the source signal's payload
 * into the destination command's parameter block at event-fire time.
 *
 * Without a token, the destination command runs with its parameters exactly as
 * built (straight passthrough — the source signal's payload is ignored). With
 * a token, the firmware copies [length] bytes starting at [sourceOffset] of
 * the source signal into the destination params starting at [destOffset],
 * overwriting whatever static bytes occupied that region.
 *
 * Wire format (2 bytes appended to the ENTRY command):
 * ```
 * byte 0: 0x01 | (length << 1) | (sourceOffset << 4)
 * byte 1: destOffset
 * ```
 * Bit 0 is the "token present" marker. `length` occupies bits 1–3 (max 7);
 * `sourceOffset` occupies bits 4–7 (max 15).
 *
 * @throws MetaWearException.OperationFailed if [length] or [sourceOffset] fall
 *   outside the bit-widths the wire format allows. Bad input is a recoverable
 *   error rather than a hard `require` crash in the host app.
 */
data class EventDataToken(
    /** Bytes of source data to copy into the destination command. Valid: 1..7. */
    val length: Int,
    /** Byte offset into the source signal's payload. Valid: 0..15. */
    val sourceOffset: Int = 0,
    /** Byte offset into the destination command's params where the slice is written. */
    val destOffset: Int = 0,
) {
    init {
        if (length !in 1..7) {
            throw MetaWearException.OperationFailed("EventDataToken length must fit in 3 bits (1...7); got $length")
        }
        if (sourceOffset !in 0..15) {
            throw MetaWearException.OperationFailed("EventDataToken sourceOffset must fit in 4 bits (0...15); got $sourceOffset")
        }
    }

    /** The 2-byte on-wire encoding appended to the ENTRY command. */
    val encodedBytes: ByteArray
        get() = byteArrayOf(
            (0x01 or (length shl 1) or (sourceOffset shl 4)).toByte(),
            destOffset.toByte(),
        )
}

/** Describes the command that executes when the source fires. */
class EventAction(
    val module: Module,
    /** Raw register byte (destination command register). */
    val register: Int,
    /** Parameter bytes that follow module+register in the BLE command. */
    val params: ByteArray = ByteArray(0),
) {
    companion object {
        /**
         * Build an action from any [Command] by splitting its `commandData`
         * into (module, register, params).
         *
         * @throws MetaWearException.OperationFailed if the command's
         *   `commandData` is shorter than the required module + register bytes.
         */
        fun from(command: Command): EventAction {
            val data = command.commandData
            if (data.size < 2) {
                throw MetaWearException.OperationFailed("Command.commandData must include module and register bytes")
            }
            return EventAction(
                module = Module.from(data[0].toInt() and 0xFF) ?: Module.DEBUG,
                register = data[1].toInt() and 0xFF,
                params = if (data.size > 2) data.copyOfRange(2, data.size) else ByteArray(0),
            )
        }
    }
}

/**
 * Build the ENTRY command shared by [MetaWearDevice.createEvent] (live) and
 * [MacroRecorder.createEvent] (buffered):
 * `[0x0A, 0x02, src_module, src_register, src_dataID, dst_module, dst_register,
 *   param_length, (optional 2-byte data token)]`.
 *
 * Validates the one-byte parameter-length field before building anything, so
 * neither caller sends (or buffers) a malformed packet.
 */
internal fun eventEntryCommand(
    source: EventSource,
    action: EventAction,
    dataToken: EventDataToken?,
): ByteArray {
    if (action.params.size > 0xFF) {
        throw MetaWearException.OperationFailed("Event action parameter payload cannot exceed 255 bytes")
    }
    val payload = byteArrayOf(
        source.module.value.toByte(), source.register.toByte(), source.dataId.toByte(),
        action.module.value.toByte(), action.register.toByte(), action.params.size.toByte(),
    ) + (dataToken?.encodedBytes ?: ByteArray(0))
    return Packet.command(Module.EVENT, 0x02, payload)
}

// ---- MetaWearDevice event API ----

/**
 * Bind a source signal to a destination command on the device.
 *
 * The board responds with an assigned event ID after receiving the ENTRY
 * command. If the action has parameters, they are written immediately after in
 * a CMD_PARAMETERS packet.
 *
 * Pass a [dataToken] to slice bytes from the source signal's payload into the
 * destination command's parameters at fire time (e.g. routing a processor
 * output value into part of a BLE advertising payload). Without a token, the
 * destination command's `params` are sent as-is on every fire — straight
 * passthrough.
 *
 * @param source The signal whose notification triggers the action.
 * @param action The command to execute when the source fires.
 * @param dataToken Optional source→destination byte slicing instructions.
 * @return An [Event] handle that can be used to remove the binding.
 */
suspend fun MetaWearDevice.createEvent(
    source: EventSource,
    action: EventAction,
    dataToken: EventDataToken? = null,
): Event {
    val entryCmd = eventEntryCommand(source, action, dataToken)

    // Board responds with [0x0A, 0x02, event_id] after processing ENTRY.
    // The reply is a plain notification (high bit clear), NOT a read response —
    // same shape as logger subscribe / processor add. `sendRead` would await
    // the read-response form (0x82) and time out.
    val response = sendAndAwaitNotification(entryCmd, awaitModule = Module.EVENT, awaitRegister = 0x02)
    if (response.size < 3) {
        throw MetaWearException.OperationFailed("Event create response too short: ${response.size} bytes")
    }
    val eventId = response[2].toInt() and 0xFF

    // Send parameters if the action command has a payload
    if (action.params.isNotEmpty()) {
        writeRaw(Packet.command(Module.EVENT, 0x03, action.params))
    }

    return Event(id = eventId)
}

/** Remove a specific event binding by its board-assigned ID. */
suspend fun MetaWearDevice.removeEvent(event: Event) =
    writeRaw(Packet.command(Module.EVENT, 0x04, event.id))

/** Remove all event bindings from the board. */
suspend fun MetaWearDevice.removeAllEvents() =
    writeRaw(Packet.command(Module.EVENT, 0x05))
