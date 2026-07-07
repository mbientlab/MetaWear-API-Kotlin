package com.mbientlab.metawear.model

import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.PacketParser

// Pure (no I/O) reconstruction of `List<AnonymousSignal>` from queried
// logger + processor state plus the live sensor scale factors. Kept separate
// from `MetaWearDevice` so it's exhaustively testable.

internal object AnonymousSignalBuilder {

    /**
     * Live per-sensor scale factors needed to convert raw int16 readings
     * into physical units (g, dps, µT). `null` means the sensor wasn't present
     * on the board — its signals will be skipped.
     */
    data class Scales(
        /** LSB/g. */
        val accel: Float? = null,
        /** LSB/dps. */
        val gyro: Float? = null,
        /** LSB/µT — fixed at 16.0 for the BMM150. */
        val mag: Float? = null,
    ) {
        fun scaleFor(module: Module): Float? = when (module) {
            Module.ACCELEROMETER -> accel
            Module.GYRO -> gyro
            Module.MAGNETOMETER -> mag
            else -> null
        }
    }

    /** Group key for a signal: multiple chunks of the same signal share this key. */
    private data class GroupKey(val module: Module, val register: Int, val channel: Int)

    /** Main entry. Build the full signal list from live board metadata. */
    fun build(
        loggers: List<ActiveLogger>,
        processors: List<ActiveProcessor>,
        scales: Scales,
    ): List<AnonymousSignal> {
        val procByID = processors.associateBy { it.processorID }

        // Preserve board-assigned order by remembering first-seen index per key.
        val order = mutableListOf<GroupKey>()
        val byKey = mutableMapOf<GroupKey, MutableList<ActiveLogger>>()
        for (logger in loggers) {
            val key = GroupKey(module = logger.module, register = logger.register, channel = logger.channel)
            if (key !in byKey) order.add(key)
            byKey.getOrPut(key) { mutableListOf() }.add(logger)
        }

        val signals = mutableListOf<AnonymousSignal>()
        for (key in order) {
            val groupChunks = byKey.getValue(key).sortedBy { it.chunkOffset }
            // Partition chunks into (a) a primary signal built from chunks
            // that form a contiguous stream starting at offset 0, and
            // (b) any leftover chunks — each becomes its own standalone
            // single-axis (or state-capture) signal. This mirrors the C++
            // SDK behaviour where loggers at non-zero offsets with no
            // predecessor chunk are independent signals.
            val primary = mutableListOf<ActiveLogger>()
            val leftover = mutableListOf<ActiveLogger>()
            var cursor = 0
            val cursorActive = groupChunks.firstOrNull()?.chunkOffset == 0
            for (chunk in groupChunks) {
                if (cursorActive && chunk.chunkOffset == cursor) {
                    primary.add(chunk)
                    cursor += chunk.chunkLength
                } else {
                    leftover.add(chunk)
                }
            }
            if (primary.isNotEmpty()) {
                makeSignal(chunks = primary, processors = procByID, scales = scales)
                    ?.let { signals.add(it) }
            }
            for (chunk in leftover) {
                makeSignal(chunks = listOf(chunk), processors = procByID, scales = scales)
                    ?.let { signals.add(it) }
            }
        }
        return signals
    }

    // ---- Signal construction ----

    private fun makeSignal(
        chunks: List<ActiveLogger>,
        processors: Map<Int, ActiveProcessor>,
        scales: Scales,
    ): AnonymousSignal? {
        val first = chunks.firstOrNull() ?: return null

        // Case 1: logger source is another data processor.
        if (AnonymousSignalScheme.isProcessorDataCapture(first.module, first.register) ||
            AnonymousSignalScheme.isProcessorStateCapture(first.module, first.register)
        ) {
            return makeChainedSignal(chunks = chunks, processors = processors, scales = scales)
        }

        // Case 2: logger source is a root sensor.
        return makeFlatSignal(chunks = chunks, scales = scales)
    }

    // ---- Flat (non-chained) signals ----

    private fun makeFlatSignal(
        chunks: List<ActiveLogger>,
        scales: Scales,
    ): AnonymousSignal? {
        val first = chunks.first()
        val totalLength = chunks.sumOf { it.chunkLength }

        val identifier = AnonymousSignalScheme.rootIdentifier(
            module = first.module,
            register = first.register,
            channel = first.channel,
            chunkOffset = first.chunkOffset,
            chunkLength = totalLength.coerceIn(0, 255),
        ) ?: return null

        val signalChunks = chunks.map {
            AnonymousSignal.Chunk(id = it.loggerID, byteCount = it.chunkLength)
        }

        val decoder = flatDecoder(
            module = first.module,
            register = first.register,
            totalLength = totalLength,
            scales = scales,
        )

        return AnonymousSignal(
            identifier = identifier,
            rootModule = first.module,
            chunks = signalChunks,
            decode = decoder,
        )
    }

    /**
     * Build the decode closure for a flat (root sensor) signal.
     * The payload passed to the closure is the concatenation of chunk bytes
     * in `chunkOffset` order; its length equals [totalLength].
     */
    private fun flatDecoder(
        module: Module,
        register: Int,
        totalLength: Int,
        scales: Scales,
    ): (ByteArray) -> List<AnonymousSignal.Output> = when {

        (module == Module.ACCELEROMETER && register == 0x04) ||
            (module == Module.GYRO && register == 0x05) ||
            (module == Module.MAGNETOMETER && register == 0x05) -> {
            val scale = scales.scaleFor(module) ?: 1f
            // A full XYZ signal needs 6 bytes; a single axis needs 2.
            if (totalLength >= 6) {
                { payload ->
                    if (payload.size < 6) {
                        emptyList()
                    } else {
                        listOf(
                            AnonymousSignal.Output.Cartesian(
                                CartesianFloat(
                                    x = PacketParser.parseInt16LE(payload, 0).toFloat() / scale,
                                    y = PacketParser.parseInt16LE(payload, 2).toFloat() / scale,
                                    z = PacketParser.parseInt16LE(payload, 4).toFloat() / scale,
                                ),
                            ),
                        )
                    }
                }
            } else {
                { payload ->
                    if (payload.size < 2) {
                        emptyList()
                    } else {
                        listOf(
                            AnonymousSignal.Output.Scalar(
                                PacketParser.parseInt16LE(payload, 0).toFloat() / scale,
                            ),
                        )
                    }
                }
            }
        }

        module == Module.TEMPERATURE && register == 0xC1 -> {
            // Temperature sample: 2 bytes LE, scale = 8 LSB/°C (temperature module convention)
            { payload ->
                if (payload.size < 2) {
                    emptyList()
                } else {
                    listOf(
                        AnonymousSignal.Output.Scalar(
                            PacketParser.parseInt16LE(payload, 0).toFloat() / 8.0f,
                        ),
                    )
                }
            }
        }

        module == Module.SENSOR_FUSION && register == 0x07 -> { // quaternion
            { payload ->
                if (payload.size < 16) {
                    emptyList()
                } else {
                    listOf(
                        AnonymousSignal.Output.Quaternion(
                            Quaternion(
                                w = decodeFloatQ16x16(payload, 0),
                                x = decodeFloatQ16x16(payload, 4),
                                y = decodeFloatQ16x16(payload, 8),
                                z = decodeFloatQ16x16(payload, 12),
                            ),
                        ),
                    )
                }
            }
        }

        module == Module.SENSOR_FUSION && register == 0x08 -> { // euler angles
            { payload ->
                if (payload.size < 16) {
                    emptyList()
                } else {
                    listOf(
                        AnonymousSignal.Output.Euler(
                            EulerAngles(
                                heading = decodeFloatQ16x16(payload, 0),
                                pitch = decodeFloatQ16x16(payload, 4),
                                roll = decodeFloatQ16x16(payload, 8),
                                yaw = decodeFloatQ16x16(payload, 12),
                            ),
                        ),
                    )
                }
            }
        }

        module == Module.SENSOR_FUSION && (register == 0x09 || register == 0x0A) -> {
            // gravity / linear-accel
            { payload ->
                if (payload.size < 12) {
                    emptyList()
                } else {
                    listOf(
                        AnonymousSignal.Output.Cartesian(
                            CartesianFloat(
                                x = decodeFloatQ16x16(payload, 0),
                                y = decodeFloatQ16x16(payload, 4),
                                z = decodeFloatQ16x16(payload, 8),
                            ),
                        ),
                    )
                }
            }
        }

        else -> {
            { _ ->
                throw MetaWearException.OperationFailed(
                    "Anonymous signal decode not implemented for " +
                        "module=${module.name.lowercase()} register=${register.toString(16)}",
                )
            }
        }
    }

    // ---- Chained (processor-output) signals ----

    private fun makeChainedSignal(
        chunks: List<ActiveLogger>,
        processors: Map<Int, ActiveProcessor>,
        scales: Scales,
    ): AnonymousSignal? {
        val first = chunks.first()
        val terminalProcID = first.channel
        val isStateCapture = AnonymousSignalScheme.isProcessorStateCapture(first.module, first.register)

        // Walk the chain from terminal processor back to the root sensor.
        val chainTop = mutableListOf<ActiveProcessor>()
        var cursor = terminalProcID
        var safety = 0
        var rootProc: ActiveProcessor? = null
        while (true) {
            val proc = processors[cursor] ?: break
            chainTop.add(proc)
            if (proc.parentIsProcessor) {
                cursor = proc.parentProcessorID
            } else {
                rootProc = proc
                break
            }
            safety += 1
            if (safety > 64) return null // cycle guard
        }
        val root = rootProc ?: return null
        val chain = chainTop.reversed() // root-first order

        // Identifier for the root sensor the chain ultimately reads from.
        val rootIdentifier = AnonymousSignalScheme.rootIdentifier(
            module = root.parentModule,
            register = root.parentRegister,
            channel = 0xFF,
            chunkOffset = root.chunkOffset,
            chunkLength = root.chunkLength,
        ) ?: return null

        val links = chain.map {
            AnonymousSignalScheme.ProcessorLink(
                type = it.processorType,
                id = it.processorID,
                config = it.configBytes,
            )
        }

        val identifier = AnonymousSignalScheme.compose(
            root = rootIdentifier,
            chain = links,
            captureStateOfTerminalBuffer = isStateCapture,
        ) ?: return null

        val signalChunks = chunks.sortedBy { it.chunkOffset }.map {
            AnonymousSignal.Chunk(id = it.loggerID, byteCount = it.chunkLength)
        }
        val terminal = chain.last()
        val decoder = chainedDecoder(
            terminal = terminal,
            rootSensorModule = root.parentModule,
            scales = scales,
        )

        return AnonymousSignal(
            identifier = identifier,
            rootModule = root.parentModule,
            chunks = signalChunks,
            decode = decoder,
        )
    }

    /**
     * Build the decode closure for a chained signal. The shape depends on the
     * terminal processor's type and whether the logger is reading the processor's
     * data output or its state register.
     */
    private fun chainedDecoder(
        terminal: ActiveProcessor,
        rootSensorModule: Module,
        scales: Scales,
    ): (ByteArray) -> List<AnonymousSignal.Output> {
        val rootScale = scales.scaleFor(rootSensorModule) ?: 1.0f

        // Fuser (0x1B) — fused entries arrive as concatenated 4-byte chunks per
        // logger entry; each block decodes as int16 axes scaled as the root
        // sensor. Entries arrive across multiple log notifications and must be
        // paired by the caller; for direct `decode(payload)` we emit a single
        // sample (z = 0 when only 4 bytes are present).
        if (terminal.processorType == 0x1B) {
            return { payload ->
                if (payload.size < 4) {
                    emptyList()
                } else {
                    val a = PacketParser.parseInt16LE(payload, 0)
                    val b = PacketParser.parseInt16LE(payload, 2)
                    if (payload.size >= 6) {
                        val c = PacketParser.parseInt16LE(payload, 4)
                        listOf(
                            AnonymousSignal.Output.Cartesian(
                                CartesianFloat(
                                    x = a.toFloat() / rootScale,
                                    y = b.toFloat() / rootScale,
                                    z = c.toFloat() / rootScale,
                                ),
                            ),
                        )
                    } else {
                        listOf(
                            AnonymousSignal.Output.Cartesian(
                                CartesianFloat(
                                    x = a.toFloat() / rootScale,
                                    y = b.toFloat() / rootScale,
                                    z = 0f,
                                ),
                            ),
                        )
                    }
                }
            }
        }

        // Time (0x08) — the Python reference decodes the downloaded payload as
        // a 4-byte unsigned integer and divides by the root scale. Output is a
        // scalar "magnitude in parent units".
        if (terminal.processorType == 0x08) {
            return { payload ->
                if (payload.size < 4) {
                    emptyList()
                } else {
                    listOf(
                        AnonymousSignal.Output.Scalar(
                            PacketParser.parseUInt32LE(payload, 0).toFloat() / rootScale,
                        ),
                    )
                }
            }
        }

        // Accumulator / RMS / buffer-state / count / most other scalar-yielding
        // processors: interpret the payload as an unsigned 32-bit value scaled
        // by the root sensor's LSB/unit ratio.
        return { payload ->
            when {
                payload.size >= 4 -> listOf(
                    AnonymousSignal.Output.Scalar(
                        PacketParser.parseUInt32LE(payload, 0).toFloat() / rootScale,
                    ),
                )
                payload.size >= 2 -> listOf(
                    AnonymousSignal.Output.Scalar(
                        PacketParser.parseUInt16LE(payload, 0).toFloat() / rootScale,
                    ),
                )
                else -> emptyList()
            }
        }
    }

    // ---- Helpers ----

    /** Q16.16 fixed-point → Float (sensor-fusion logged payloads). */
    private fun decodeFloatQ16x16(data: ByteArray, offset: Int): Float =
        PacketParser.parseInt32LE(data, offset).toFloat() / 65536.0f
}
