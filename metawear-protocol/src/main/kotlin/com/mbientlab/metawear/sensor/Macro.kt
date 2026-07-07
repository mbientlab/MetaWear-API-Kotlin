package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.CommandSequence
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet

// Port of MWMacro.swift — flash-stored command macros (module 0x0F).
// Macro recording commands (BEGIN's ADD/END phase) go out write-WITH-response
// via `writeMacroRaw`, matching the firmware's flash-write pacing requirement.

/**
 * A handle to an on-device macro created via [MetaWearDevice.recordMacro].
 *
 * Macros are sequences of commands stored in flash that execute atomically —
 * either manually triggered or automatically on every power-on.
 *
 * Typical flow:
 * ```kotlin
 * val macro = device.recordMacro(executeOnBoot = true, commands = listOf(setPattern, play))
 *
 * // Later, run manually:
 * device.executeMacro(macro)
 *
 * // Remove all macros:
 * device.eraseAllMacros()
 * ```
 */
data class Macro(
    /** Board-assigned macro ID. */
    val id: Int,
)

/**
 * A scratch pad that collects raw wire packets while a `recordMacro { ... }`
 * body runs.
 *
 * The closure-based [MetaWearDevice.recordMacro] overload hands the caller one
 * of these and then plays the collected packets back into a macro recording
 * session (BEGIN → ADD_COMMAND… → END). This is how a macro can embed actions
 * that would otherwise round-trip to the board for an ID — the recorder
 * captures the *bytes* you'd send, and lets the firmware assign IDs at replay
 * time.
 *
 * This is the only ergonomic way to nest [createEvent] (or any other
 * multi-write action that returns a board-assigned handle) inside a macro: the
 * embedded event/timer/processor is created fresh every time the macro runs,
 * so an [Event.id] cannot be known at recording time.
 *
 * ```kotlin
 * val macro = device.recordMacro(executeOnBoot = true) { recorder ->
 *     recorder.send(ledSetPattern)
 *     recorder.createEvent(
 *         source = EventSource.buttonChanged(),
 *         action = EventAction.from(ledPlay),
 *     )
 * }
 * ```
 *
 * On every reboot the firmware replays the macro, which:
 *   1. Re-applies the LED pattern, and
 *   2. Re-creates the button → LED Play binding.
 *
 * The Swift original is an `actor`; here the recorder is only touched
 * sequentially by the `recordMacro` body, which runs to completion before the
 * packets are drained.
 */
class MacroRecorder internal constructor() {

    /**
     * Captured wire packets, in the order they were recorded. `internal` so
     * the embedding device extension can drain them after the body returns;
     * outside callers don't need to see the buffer.
     */
    internal val packets = mutableListOf<ByteArray>()

    /**
     * Record a single command into the macro.
     *
     * The command's `commandData` bytes are buffered verbatim and replayed
     * during the macro recording session via `ADD_COMMAND` (split into
     * `ADD_PARTIAL` + `ADD_COMMAND` automatically for >13-byte payloads).
     */
    fun send(command: Command) {
        packets.add(command.commandData)
    }

    /**
     * Record a multi-write command sequence (e.g. an Enable/Disable pair for a
     * BMI270 feature). Each underlying packet is appended in order.
     */
    fun send(sequence: CommandSequence) {
        for (cmd in sequence.commands) packets.add(cmd)
    }

    /**
     * Record raw wire bytes. Escape hatch for callers that need to embed a
     * command for which there is no [Command] type yet (e.g. an opcode the SDK
     * doesn't model). The bytes go through the same ADD_COMMAND / ADD_PARTIAL
     * split logic as [send].
     */
    fun sendRaw(data: ByteArray) {
        packets.add(data.copyOf())
    }

    /**
     * Record an event-creation inside the macro.
     *
     * Mirrors the wire shape of [MetaWearDevice.createEvent] but writes the
     * bytes into the macro buffer instead of sending them live. When the macro
     * replays (manually via [MetaWearDevice.executeMacro] or automatically on
     * boot), the firmware processes the embedded ENTRY exactly as if the host
     * had just sent it — assigning a fresh event ID and wiring the source
     * signal to the destination action.
     *
     * Because the event ID is only assigned at replay time, this method
     * returns `Unit` — there's no handle to remove an embedded event
     * individually. Use [MetaWearDevice.removeAllEvents] to tear down the
     * bindings after they've been created (or just
     * [MetaWearDevice.eraseAllMacros] so they don't re-create on next boot).
     *
     * @param source The signal whose notification triggers the action.
     * @param action The command to execute when the source fires.
     * @param dataToken Optional source→destination byte slicing instructions.
     * @throws MetaWearException.OperationFailed if the action parameter payload
     *   is too large to fit in the ENTRY command's one-byte length field. A
     *   rejected event leaves no partial packets in the buffer.
     */
    fun createEvent(
        source: EventSource,
        action: EventAction,
        dataToken: EventDataToken? = null,
    ) {
        // Validates before anything is buffered, so a rejected embedded event
        // cannot leave partial macro packets behind.
        val entry = eventEntryCommand(source, action, dataToken)
        packets.add(entry)

        // CMD_PARAMETERS — only emitted if the action has a payload.
        if (action.params.isNotEmpty()) {
            packets.add(Packet.command(Module.EVENT, 0x03, action.params))
        }
    }
}

// ---- MetaWearDevice macro API ----

/**
 * Record a macro by composing actions inside a body function.
 *
 * The body receives a [MacroRecorder] whose [MacroRecorder.send] and
 * [MacroRecorder.createEvent] calls are buffered as raw wire packets, then
 * played back into a macro recording session once the body returns. This is
 * the only way to embed multi-write actions (notably `createEvent`) into a
 * macro — the list-form `recordMacro(executeOnBoot, commands)` overload only
 * takes single-command [Command] values and can't represent an ENTRY +
 * CMD_PARAMETERS pair.
 *
 * @param executeOnBoot Whether the macro runs automatically on every power-on.
 * @param body Composes the macro by calling methods on the recorder.
 * @return A [Macro] handle for later [executeMacro] calls.
 */
suspend fun MetaWearDevice.recordMacro(
    executeOnBoot: Boolean = false,
    body: suspend (MacroRecorder) -> Unit,
): Macro {
    val recorder = MacroRecorder()
    body(recorder)
    return commitMacro(executeOnBoot, recorder.packets.toList())
}

/**
 * Record a sequence of commands as a macro on the device.
 *
 * Each command is stored in flash. If [executeOnBoot] is `true`, the macro
 * runs automatically every time the board powers on; otherwise it only runs
 * when explicitly triggered with [executeMacro].
 *
 * Commands longer than 13 bytes are split into an ADD_PARTIAL + ADD_COMMAND
 * pair per the MetaWear protocol spec.
 *
 * For macros that need to embed a `createEvent` (or any other multi-write
 * action), use the body-based overload instead.
 *
 * @param executeOnBoot Whether to run on every power-on.
 * @param commands The commands to record, in order.
 * @return A [Macro] handle for later execution or identification.
 */
suspend fun MetaWearDevice.recordMacro(
    executeOnBoot: Boolean = false,
    commands: List<Command>,
): Macro = commitMacro(executeOnBoot, commands.map { it.commandData })

/** Execute a previously recorded macro by its board-assigned ID. */
suspend fun MetaWearDevice.executeMacro(macro: Macro) {
    writeRaw(Packet.command(Module.MACRO, 0x05, macro.id))
}

/** Erase all macros stored on the device. */
suspend fun MetaWearDevice.eraseAllMacros() {
    writeRaw(Packet.command(Module.MACRO, 0x08))
}

// ---- Internal ----

/**
 * Shared BEGIN → ADD_COMMAND… → END implementation. Used by both the
 * list-form and body-form `recordMacro` overloads.
 */
private suspend fun MetaWearDevice.commitMacro(executeOnBoot: Boolean, packets: List<ByteArray>): Macro {
    // BEGIN: [0x0F, 0x02, exec_on_boot] — responds with [0x0F, 0x02, macro_id]
    // as a plain notification (NOT [0x0F, 0x82, macro_id] / read response).
    // Same pattern as logger subscribe / processor add / event create / timer
    // create — every "create resource on register 0x02" reply lands without
    // the read bit, so we await on the notify waiters. `sendRead` would await
    // read responses and time out here.
    val beginCmd = Packet.command(Module.MACRO, 0x02, if (executeOnBoot) 0x01 else 0x00)
    val response = sendAndAwaitNotification(beginCmd, awaitModule = Module.MACRO, awaitRegister = 0x02)
    if (response.size < 3) {
        throw MetaWearException.OperationFailed("Macro begin response too short: ${response.size} bytes")
    }
    val macroId = response[2].toInt() and 0xFF

    // ADD commands
    for (packet in packets) {
        addMacroCommand(packet)
    }

    // END: [0x0F, 0x04]
    writeMacroRaw(Packet.command(Module.MACRO, 0x04))

    return Macro(id = macroId)
}

/**
 * Add a single command to an in-progress macro recording.
 *
 * Per spec, commands > 13 bytes are split:
 * - ADD_PARTIAL `[0x0F, 0x09, byte0, byte1]`
 * - ADD_COMMAND `[0x0F, 0x03, byte2..byteN]`
 */
private suspend fun MetaWearDevice.addMacroCommand(commandData: ByteArray) {
    val maxDirect = 13
    if (commandData.size <= maxDirect) {
        // ADD_COMMAND: [0x0F, 0x03, ...commandData...]
        writeMacroRaw(Packet.command(Module.MACRO, 0x03, commandData))
    } else {
        // ADD_PARTIAL: first 2 bytes
        writeMacroRaw(
            Packet.command(Module.MACRO, 0x09, commandData[0].toInt() and 0xFF, commandData[1].toInt() and 0xFF),
        )
        // ADD_COMMAND: remaining bytes
        writeMacroRaw(Packet.command(Module.MACRO, 0x03, commandData.copyOfRange(2, commandData.size)))
    }
}
