package com.mbientlab.metawear.app.demo

import com.mbientlab.metawear.transport.BleTransport
import com.mbientlab.metawear.transport.ScanResult
import com.mbientlab.metawear.transport.Uuids
import com.mbientlab.metawear.transport.WriteType
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A protocol-level MetaWear emulator used as a JVM test fixture — no hardware,
 * no Bluetooth. Lives in the test source set only; the shipping app never
 * constructs it.
 *
 * Behaves like a connected MetaMotion S on firmware 1.7.3: it answers module
 * discovery, Device Information reads, battery/MAC/log reads, streams
 * synthetic-but-plausible sensor waveforms when sensors are configured and
 * started (including packed registers and every sensor-fusion output), and
 * emulates the logging round trip (trigger allocation, LOG_LENGTH growth
 * while "recording", paged readout with progress).
 *
 * Pure Kotlin (no Android imports) so the whole app device stack is exercised
 * by JVM unit tests through the real [com.mbientlab.metawear.MetaWearDevice].
 *
 * Each instance wears an [Identity] — [Identity.board] mints up to 16
 * distinguishable boards (identifier, serial, MAC, waveform phase) so
 * multi-board flows are testable with a simulated fleet; the default is the
 * legacy single demo board.
 */
class DemoBleTransport(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val identity: Identity = Identity.board(0),
) : BleTransport {

    companion object {
        /**
         * Stable identifier of the default board — equal to
         * `Identity.board(0).identifier`, the legacy single demo board.
         */
        const val DEVICE_IDENTIFIER: String = "DE:30:DE:30:DE:30"

        const val DEVICE_NAME: String = "Simulated MetaWear"
    }

    /**
     * Identity worn by one simulated board. Multiple demo boards differ in
     * identifier, serial, MAC, and waveform phase, so multi-board flows
     * (group logging, per-board attribution) are testable with a fleet of
     * distinguishable fakes.
     */
    data class Identity(
        val identifier: String,
        val serial: String,
        /** 6-byte address in wire order (LSB first) — served for Settings register 0x0B reads. */
        val macLSBFirst: List<Int>,
        /** Seconds added to the waveform clock so each board's traces differ. */
        val phaseOffset: Double,
    ) {
        init {
            require(macLSBFirst.size == 6) { "MAC must be 6 bytes" }
        }

        companion object {
            /**
             * Stable identity for demo board [index] (0..15). `board(0)` is
             * byte-for-byte the legacy single demo device (identifier
             * [DEVICE_IDENTIFIER], serial "DEMO01", MAC …:E0:01).
             */
            fun board(index: Int): Identity {
                require(index in 0..15) { "demo board index out of range" }
                return Identity(
                    identifier = "DE:30:DE:30:DE:3%X".format(index),
                    serial = "DEMO%02d".format(index + 1),
                    macLSBFirst = listOf(0x01 + index, 0xE0, 0x0D, 0x0E, 0x3D, 0xDE),
                    phaseOffset = index * 0.9,
                )
            }
        }
    }

    // ---- State ----

    private val lock = Any()
    private var notifyChannel: Channel<ByteArray>? = null
    private var connected = false

    /**
     * Responses produced before the host's notification collector subscribes
     * (a real cold-connect race: module-discovery reads can fire while the
     * subscription coroutine is still being scheduled). Buffered and flushed
     * into the channel on subscribe so no reply is ever silently dropped.
     */
    private val preSubscribeBacklog = mutableListOf<ByteArray>()

    private data class ModuleRegister(val module: Int, val register: Int)

    /** Registers the host subscribed to via `[module, register, 0x01]`. */
    private val subscriptions = mutableSetOf<ModuleRegister>()

    /** One emitter job per running module. */
    private val emitters = mutableMapOf<Int, Job>()

    /** Reference instant for waveform phase (monotonic). */
    private val epochNanos = System.nanoTime()

    // Sensor configs (parsed from config writes; defaults match the app's).
    private var accelScale = 4096f    // ±8 g
    private var gyroScale = 16.4f     // ±2000 dps

    // Logging emulation.
    private data class DemoLogger(val module: Int, val register: Int, val index: Int, val packed: Int)

    private val loggers = mutableMapOf<Int, DemoLogger>()
    private var nextLoggerId = 0
    private var loggingEnabled = false
    private var loggingStartedNanos: Long? = null
    private var storedEntryCount = 0L
    private var nextTimerId = 0
    private var nextEventId = 0
    private var nextMacroId = 0

    // ---- BleTransport ----

    override fun scan(services: List<UUID>?): Flow<ScanResult> = emptyFlow() // demo devices are injected, not discovered

    override suspend fun connect(identifier: String) {
        synchronized(lock) { connected = true }
    }

    override suspend fun disconnect() {
        val channel: Channel<ByteArray>?
        synchronized(lock) {
            connected = false
            emitters.values.forEach { it.cancel() }
            emitters.clear()
            subscriptions.clear()
            preSubscribeBacklog.clear()
            channel = notifyChannel
            notifyChannel = null
        }
        channel?.close()
    }

    override suspend fun readRSSI(): Int = -52 - Random.nextInt(0, 7)

    override suspend fun read(characteristic: UUID): ByteArray = when (characteristic) {
        Uuids.manufacturerName -> "MbientLab Inc".toByteArray()
        Uuids.modelNumber -> "8".toByteArray()           // MetaMotion S
        Uuids.serialNumber -> identity.serial.toByteArray()
        Uuids.firmwareRevision -> "1.7.3".toByteArray()
        Uuids.hardwareRevision -> "0.4".toByteArray()
        else -> ByteArray(0)
    }

    override fun notifications(characteristic: UUID): Flow<ByteArray> {
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        val backlog: List<ByteArray>
        synchronized(lock) {
            notifyChannel = channel
            backlog = preSubscribeBacklog.toList()
            preSubscribeBacklog.clear()
        }
        backlog.forEach { channel.trySend(it) }
        return flow {
            for (packet in channel) emit(packet)
        }
    }

    override suspend fun write(data: ByteArray, characteristic: UUID, type: WriteType) {
        if (data.size < 2) return
        handle(data)
    }

    // ---- Command handling ----

    private fun emit(bytes: List<Int>) {
        val packet = ByteArray(bytes.size) { bytes[it].toByte() }
        val channel = synchronized(lock) {
            val current = notifyChannel
            if (current == null && connected && preSubscribeBacklog.size < PRE_SUBSCRIBE_BACKLOG_LIMIT) {
                preSubscribeBacklog.add(packet)
            }
            current
        }
        channel?.trySend(packet)
    }

    private fun handle(command: ByteArray) {
        val module = command[0].toInt() and 0xFF
        val register = command[1].toInt() and 0xFF
        val payload = IntArray(command.size - 2) { command[it + 2].toInt() and 0xFF }

        // One-shot reads (bit 7).
        if (register and 0x80 != 0) {
            handleRead(module, register, payload)
            return
        }

        synchronized(lock) {
            when {
                // ---- Per-register notify subscribe ([module, register, 0/1]) ----
                payload.size == 1 && (payload[0] == 0 || payload[0] == 1) &&
                    isSubscribableRegister(module, register) -> {
                    val key = ModuleRegister(module, register)
                    if (payload[0] == 1) subscriptions.add(key) else subscriptions.remove(key)
                }

                // ---- Sensor configs ----
                module == 0x03 && register == 0x03 && payload.size >= 2 ->   // accel ODR/range (BMI270: 0-based)
                    accelScale = (16384 shr minOf(payload[1] and 0x3, 3)).toFloat()
                module == 0x13 && register == 0x03 && payload.size >= 2 -> { // gyro ODR/range
                    val table = floatArrayOf(16.4f, 32.8f, 65.6f, 131.2f, 262.4f)
                    gyroScale = table[minOf(payload[1] and 0x7, 4)]
                }

                // ---- Logging ----
                module == 0x0B && register == 0x01 -> {
                    val enable = payload.firstOrNull() == 1
                    if (enable && !loggingEnabled) loggingStartedNanos = System.nanoTime()
                    if (!enable && loggingEnabled) {
                        storedEntryCount = currentEntryCount()
                        loggingStartedNanos = null
                    }
                    loggingEnabled = enable
                }
                module == 0x0B && register == 0x02 && payload.size >= 4 -> { // add trigger
                    val id = nextLoggerId
                    nextLoggerId += 1
                    loggers[id] = DemoLogger(payload[0], payload[1], payload[2], payload[3])
                    emit(listOf(0x0B, 0x02, id))
                }
                module == 0x0B && register == 0x06 && payload.size >= 4 -> { // readout
                    val requested = payload[0].toLong() or (payload[1].toLong() shl 8) or
                        (payload[2].toLong() shl 16) or (payload[3].toLong() shl 24)
                    streamLogReadout(minOf(requested, currentEntryCount()))
                }
                module == 0x0B && (register == 0x09 || register == 0x0A) -> { // drop entries / remove triggers
                    storedEntryCount = 0
                    loggingStartedNanos = if (loggingEnabled) System.nanoTime() else null
                    if (register == 0x0A) {
                        loggers.clear()
                        nextLoggerId = 0
                    }
                    // Firmware signals Drop Entries completion with a
                    // page-completed notification (spec: 0x0D "sent … after
                    // the Drop Entries command completes") — clearLog waits
                    // for it on MMS-revision boards, and this emulator
                    // reports MMS revision.
                    if (register == 0x09 && ModuleRegister(0x0B, 0x0D) in subscriptions) {
                        emit(listOf(0x0B, 0x0D))
                    }
                }

                // ---- Timer / Event / Macro allocation ----
                module == 0x0C && register == 0x02 -> {
                    emit(listOf(0x0C, 0x02, nextTimerId)); nextTimerId += 1
                }
                module == 0x0A && register == 0x02 -> {
                    emit(listOf(0x0A, 0x02, nextEventId)); nextEventId += 1
                }
                module == 0x0F && register == 0x02 -> {
                    emit(listOf(0x0F, 0x02, nextMacroId)); nextMacroId += 1
                }

                // ---- Module starts/stops ----
                register == 0x01 && module in intArrayOf(0x03, 0x13, 0x15, 0x19) ->
                    if (payload.firstOrNull() == 1) startEmitter(module) else stopEmitter(module)
                module == 0x12 && register == 0x04 ->                        // barometer cyclic [enable, altitude]
                    if (payload.firstOrNull() == 1) startEmitter(module) else stopEmitter(module)
                module == 0x14 && register == 0x01 ->                        // ambient light enable
                    if (payload.firstOrNull() == 1) startEmitter(module) else stopEmitter(module)

                else -> Unit // LED, haptic, settings writes, interrupt enables: fire-and-forget
            }
        }
    }

    /**
     * Data registers the host subscribes to with the 1-byte enable write.
     * Excludes registers whose 1-byte payload means something else (module
     * starts, logging enable, …) — those are matched explicitly above.
     */
    private fun isSubscribableRegister(module: Int, register: Int): Boolean = when {
        module == 0x03 && (register == 0x04 || register == 0x05) -> true   // accel data / packed
        module == 0x13 && (register == 0x04 || register == 0x05) -> true   // gyro data / packed (BMI270)
        module == 0x13 && register == 0x07 -> true                          // gyro packed (BMI160)
        module == 0x15 && (register == 0x05 || register == 0x09) -> true   // mag data / packed
        module == 0x12 && (register == 0x01 || register == 0x02) -> true   // pressure / altitude
        module == 0x14 && register == 0x03 -> true                          // illuminance
        module == 0x19 && register in 0x04..0x0A -> true                    // fusion outputs
        module == 0x01 && register == 0x01 -> true                          // switch
        module == 0x09 && register == 0x03 -> true                          // processor notify
        module == 0x0B && (register == 0x07 || register == 0x08 || register == 0x0D) -> true // readout channels
        else -> false
    }

    // ---- One-shot reads ----

    private fun handleRead(module: Int, register: Int, payload: IntArray) {
        val reg = register and 0x3F
        synchronized(lock) {
            when {
                reg == 0x00 -> emitModuleInfo(module, register)
                module == 0x11 && reg == 0x0C ->   // battery: 87 %, 4.08 V
                    emit(listOf(0x11, register, 87, 0xF0, 0x0F))
                module == 0x11 && reg == 0x0B ->   // MAC (7-byte form: type 0x01 + LE address)
                    emit(listOf(0x11, register, 0x01) + identity.macLSBFirst)
                module == 0x0B && reg == 0x01 ->   // logging enabled? (foreign-session detection)
                    emit(listOf(0x0B, register, if (loggingEnabled) 1 else 0))
                module == 0x0B && reg == 0x04 -> { // logging time: tick + reset uid
                    // phaseOffset is a waveform-shape knob ONLY — the logging
                    // clock must stay wall-true, or each board's
                    // logReferenceDate skews by its offset and "simultaneous"
                    // demo logs land misaligned across the fleet (reads as a
                    // cross-board attribution bug in exactly the group flows
                    // the fleet exists to validate).
                    val tick = ((elapsed() - identity.phaseOffset) * 1000.0 / 1.46484375).toLong()
                    emit(listOf(0x0B, register) + le32(tick) + listOf(0x01))
                }
                module == 0x0B && reg == 0x05 ->   // log length
                    emit(listOf(0x0B, register) + le32(currentEntryCount()))
                module == 0x0B && reg == 0x02 -> { // trigger slot query
                    val id = payload.firstOrNull() ?: 0xFF
                    val logger = loggers[id]
                    if (logger != null) {
                        emit(listOf(0x0B, register, logger.module, logger.register, logger.index, logger.packed))
                    } else {
                        emit(listOf(0x0B, register, 0xFF, 0xFF, 0xFF, 0xFF))
                    }
                }
                module == 0x09 && reg == 0x02 ->   // processor slot query: none installed
                    emit(listOf(0x09, register, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF))
                module == 0x04 && reg == 0x01 -> { // temperature read: 22.5 °C wobble
                    val channel = payload.firstOrNull() ?: 0
                    val celsius = 22.5 + 0.4 * sin(elapsed() * 0.4)
                    val raw = (celsius * 8).toInt()
                    emit(listOf(0x04, register, channel) + le16(raw))
                }
                module == 0x12 && reg == 0x01 ->   // barometer pressure one-shot
                    emit(listOf(0x12, register) + le32(pressureRaw()))
                module == 0x16 && reg == 0x01 ->   // BME280 humidity one-shot (% × 1024)
                    emit(listOf(0x16, register) + le32(humidityRaw(elapsed())))
                module == 0x14 && reg == 0x03 -> { // ambient light one-shot (milli-lux)
                    val milliLux = ((320.0 + 90.0 * sin(elapsed() * 0.5)) * 1000).toLong()
                    emit(listOf(0x14, register) + le32(milliLux))
                }
                module == 0x19 && reg == 0x0B ->   // fusion calibration state: all high
                    emit(listOf(0x19, register, 3, 3, 3))
                else -> Unit // unanswered reads time out — mirrors absent registers
            }
        }
    }

    private fun emitModuleInfo(module: Int, readRegister: Int) {
        // MetaMotion S module map (implementation, revision, extra…).
        val info: Map<Int, List<Int>> = mapOf(
            0x01 to listOf(0, 0), 0x02 to listOf(0, 1, 3), 0x03 to listOf(4, 0),
            0x04 to listOf(1, 0, 0, 3, 1, 2), 0x05 to listOf(0, 2, 3, 3),
            0x07 to listOf(0, 0), 0x08 to listOf(0, 0), 0x09 to listOf(0, 3, 0x1C),
            0x0A to listOf(0, 0, 0x1C), 0x0B to listOf(0, 3, 0x08) + le32(0x0400_0000L) + listOf(0x04, 0x00),
            0x0C to listOf(0, 0, 8), 0x0D to listOf(0, 1), 0x0F to listOf(0, 2),
            0x11 to listOf(0, 10, 0x03, 0x00), 0x12 to listOf(0, 0), 0x13 to listOf(1, 0),
            0x14 to listOf(0, 0), 0x15 to listOf(0, 2), 0x16 to listOf(0, 0),
            0x19 to listOf(0, 3, 0, 0, 0, 0, 0, 0, 0, 0), 0xFE to listOf(0, 6),
        )
        val extra = info[module]
        if (extra != null) {
            emit(listOf(module, readRegister) + extra)
        } else {
            emit(listOf(module, readRegister))   // absent module: empty info
        }
    }

    // ---- Streaming emitters ----

    /** Callers must hold [lock]. */
    private fun startEmitter(module: Int) {
        if (emitters.containsKey(module)) return
        emitters[module] = scope.launch {
            while (isActive) {
                delay(40)   // 25 Hz
                emitSamples(module)
            }
        }
    }

    /** Callers must hold [lock]. */
    private fun stopEmitter(module: Int) {
        emitters.remove(module)?.cancel()
    }

    private fun emitSamples(module: Int) {
        val t = elapsed()
        synchronized(lock) {
            when (module) {
                0x03 -> emitCartesian(0x03, dataReg = 0x04, packedReg = 0x05, sample = accelRaw(t))
                0x13 -> emitCartesian(0x13, dataReg = 0x04, packedReg = 0x05, sample = gyroRaw(t))
                0x15 -> emitCartesian(0x15, dataReg = 0x05, packedReg = 0x09, sample = magRaw(t))
                0x12 -> {
                    if (ModuleRegister(0x12, 0x01) in subscriptions) {
                        emit(listOf(0x12, 0x01) + le32(pressureRaw()))
                    }
                    if (ModuleRegister(0x12, 0x02) in subscriptions) {
                        val altitude = ((112.0 + 2.0 * sin(t * 0.2)) * 256).toLong()
                        emit(listOf(0x12, 0x02) + le32(altitude))
                    }
                }
                0x14 -> {
                    if (ModuleRegister(0x14, 0x03) in subscriptions) {
                        val milliLux = ((320.0 + 90.0 * sin(t * 0.5)) * 1000).toLong()
                        emit(listOf(0x14, 0x03) + le32(milliLux))
                    }
                }
                0x19 -> emitFusion(t)
            }
        }
    }

    /** Callers must hold [lock]. */
    private fun emitCartesian(module: Int, dataReg: Int, packedReg: Int, sample: Triple<Int, Int, Int>) {
        val bytes = le16(sample.first) + le16(sample.second) + le16(sample.third)
        if (ModuleRegister(module, packedReg) in subscriptions) {
            emit(listOf(module, packedReg) + bytes + bytes + bytes)   // 3 identical-ish samples
        } else if (ModuleRegister(module, dataReg) in subscriptions) {
            emit(listOf(module, dataReg) + bytes)
        }
    }

    /** Callers must hold [lock]. */
    private fun emitFusion(t: Double) {
        val heading = (t * 24).mod(360.0)
        val pitch = 18 * sin(t * 0.6)
        val roll = 12 * cos(t * 0.45)

        if (ModuleRegister(0x19, 0x07) in subscriptions) {
            // Quaternion from slowly-precessing Euler angles.
            val h = heading * PI / 180 / 2
            val p = pitch * PI / 180 / 2
            val r = roll * PI / 180 / 2
            val w = (cos(h) * cos(p) * cos(r) + sin(h) * sin(p) * sin(r)).toFloat()
            val x = (cos(h) * sin(p) * cos(r) + sin(h) * cos(p) * sin(r)).toFloat()
            val y = (sin(h) * cos(p) * cos(r) - cos(h) * sin(p) * sin(r)).toFloat()
            val z = (cos(h) * cos(p) * sin(r) - sin(h) * sin(p) * cos(r)).toFloat()
            emit(listOf(0x19, 0x07) + leFloat(w) + leFloat(x) + leFloat(y) + leFloat(z))
        }
        if (ModuleRegister(0x19, 0x08) in subscriptions) {
            emit(
                listOf(0x19, 0x08) + leFloat(heading.toFloat()) + leFloat(pitch.toFloat()) +
                    leFloat(roll.toFloat()) + leFloat(heading.toFloat()),
            )
        }
        if (ModuleRegister(0x19, 0x09) in subscriptions) {
            val g = 9.80665
            emit(
                listOf(0x19, 0x09) + leFloat((g * sin(pitch * PI / 180)).toFloat()) +
                    leFloat((-g * sin(roll * PI / 180)).toFloat()) + leFloat((g * 0.98).toFloat()),
            )
        }
        if (ModuleRegister(0x19, 0x0A) in subscriptions) {
            emit(
                listOf(0x19, 0x0A) + leFloat((0.4 * sin(t * 3)).toFloat()) +
                    leFloat((0.3 * cos(t * 2.2)).toFloat()) + leFloat((0.2 * sin(t * 1.7)).toFloat()),
            )
        }
        for (reg in 0x04..0x06) {
            if (ModuleRegister(0x19, reg) !in subscriptions) continue
            val scale = if (reg == 0x04) 1000f else 1f     // corrected acc in mg
            emit(
                listOf(0x19, reg) +
                    leFloat((0.05 * sin(t)).toFloat() * scale) +
                    leFloat((0.04 * cos(t * 0.8)).toFloat() * scale) +
                    leFloat((if (reg == 0x04) 1.0f else 0.3f) * scale) +
                    listOf(3),                             // accuracy: high
            )
        }
    }

    // ---- Logging readout emulation ----

    /** Callers must hold [lock]. */
    private fun currentEntryCount(): Long {
        val started = loggingStartedNanos
        if (!loggingEnabled || started == null) return storedEntryCount
        val seconds = (System.nanoTime() - started) / 1e9
        // 25 samples/s × 2 flash entries per cartesian sample (the common case).
        return storedEntryCount + (seconds * 50).toLong()
    }

    /**
     * Emit a paged readout: paired 9-byte entries, periodic progress, a page
     * complete at the end, then progress 0.
     */
    private fun streamLogReadout(count: Long) {
        val toSend = minOf(count, 4000L)
        scope.launch { performLogReadout(toSend) }
    }

    private suspend fun performLogReadout(count: Long) {
        // Replays
        // any 2-chunk cartesian logger pair (accel/gyro/mag) plus every
        // single-chunk environmental logger — temperature (Int16 °C × 8),
        // humidity (UInt32 % × 1024), pressure (UInt32 Pa × 256) — so both
        // streamed and polled logging round-trip through the emulator.
        class EnvProducer(val loggerId: Int, val rawData: (Double) -> Long)

        val cartesianPair: List<Int>
        val envProducers: List<EnvProducer>
        synchronized(lock) {
            val byModule = loggers.entries.groupBy({ it.value.module }, { it.key })
            cartesianPair = listOf(0x03, 0x13, 0x15)
                .firstNotNullOfOrNull { m -> byModule[m]?.takeIf { it.size >= 2 } }
                ?.sorted() ?: emptyList()
            envProducers = buildList {
                byModule[0x04]?.minOrNull()?.let { id ->
                    add(
                        EnvProducer(id) { t ->
                            val celsius = 22.5 + 0.4 * sin(t * 0.4)
                            (celsius * 8).toLong() and 0xFFFF
                        },
                    )
                }
                byModule[0x16]?.minOrNull()?.let { id ->
                    add(EnvProducer(id) { t -> humidityRaw(t) })
                }
                byModule[0x12]?.minOrNull()?.let { id ->
                    add(EnvProducer(id) { t -> ((101_325.0 + 14.0 * sin(t * 0.3)) * 256).toLong() })
                }
                byModule[0x14]?.minOrNull()?.let { id ->   // ambient light (milli-lux)
                    add(EnvProducer(id) { t -> ((320.0 + 90.0 * sin(t * 0.5)) * 1000).toLong() })
                }
            }
        }
        if (cartesianPair.size < 2 && envProducers.isEmpty()) {
            emit(listOf(0x0B, 0x0D))
            emit(listOf(0x0B, 0x08) + le32(0))
            return
        }

        var remaining = count
        var tick = 1000L
        var sampleIndex = 0
        while (remaining > 0) {
            val t = sampleIndex / 25.0
            if (cartesianPair.size >= 2) {
                val s = accelRaw(t)
                val xy = le16(s.first) + le16(s.second)
                val zp = le16(s.third) + listOf(0, 0)
                emit(
                    listOf(0x0B, 0x07, cartesianPair[0]) + le32(tick) + xy +
                        listOf(cartesianPair[1]) + le32(tick) + zp,
                )
                remaining = if (remaining >= 2) remaining - 2 else 0
            }
            for (producer in envProducers) {
                if (remaining <= 0) break
                emit(listOf(0x0B, 0x07, producer.loggerId) + le32(tick) + le32(producer.rawData(t)))
                remaining -= 1
            }
            tick += (1.0 / 25.0 * 1000.0 / 1.46484375).toLong()
            sampleIndex += 1
            if (sampleIndex % 40 == 0) {
                emit(listOf(0x0B, 0x08) + le32(remaining))
                delay(10)
            }
        }
        emit(listOf(0x0B, 0x0D))                  // page complete
        emit(listOf(0x0B, 0x08) + le32(0))        // final progress
        synchronized(lock) {
            storedEntryCount = 0
            if (loggingEnabled) loggingStartedNanos = System.nanoTime()
        }
    }

    // ---- Waveforms ----

    // The per-identity phase offset desynchronises the fleet's waveforms —
    // three demo boards must not chart identical traces.
    private fun elapsed(): Double = (System.nanoTime() - epochNanos) / 1e9 + identity.phaseOffset

    private fun accelRaw(t: Double): Triple<Int, Int, Int> {
        val x = 0.08 * sin(t * 1.3)
        val y = 0.06 * cos(t * 0.9)
        val z = 1.0 + 0.02 * sin(t * 2.1)
        return Triple((x * accelScale).toInt(), (y * accelScale).toInt(), (z * accelScale).toInt())
    }

    private fun gyroRaw(t: Double): Triple<Int, Int, Int> {
        val x = 40.0 * sin(t * 0.8)
        val y = 25.0 * cos(t * 0.6)
        val z = 60.0 * sin(t * 0.3)
        return Triple((x * gyroScale).toInt(), (y * gyroScale).toInt(), (z * gyroScale).toInt())
    }

    private fun magRaw(t: Double): Triple<Int, Int, Int> {
        // µT × 16 LSB; slow rotation around a ~50 µT field.
        val x = (22.0 + 18.0 * sin(t * 0.25)) * 16
        val y = (-8.0 + 18.0 * cos(t * 0.25)) * 16
        val z = 42.0 * 16
        return Triple(x.toInt(), y.toInt(), z.toInt())
    }

    private fun pressureRaw(): Long = ((101_325.0 + 14.0 * sin(elapsed() * 0.3)) * 256).toLong()

    /** BME280 humidity raw value: (45 ± 6) % × 1024. */
    private fun humidityRaw(t: Double): Long = ((45.0 + 6.0 * sin(t * 0.3)) * 1024).toLong()

    // ---- Byte helpers (values as unsigned ints 0..255) ----

    private fun le16(value: Int): List<Int> {
        val v = value and 0xFFFF
        return listOf(v and 0xFF, (v shr 8) and 0xFF)
    }

    private fun le32(value: Long): List<Int> {
        val v = value and 0xFFFFFFFFL
        return listOf(
            (v and 0xFF).toInt(),
            ((v shr 8) and 0xFF).toInt(),
            ((v shr 16) and 0xFF).toInt(),
            ((v shr 24) and 0xFF).toInt(),
        )
    }

    private fun leFloat(value: Float): List<Int> = le32(value.toRawBits().toLong() and 0xFFFFFFFFL)
}

/** Upper bound on responses buffered before the host subscribes (see emit). */
private const val PRE_SUBSCRIBE_BACKLOG_LIMIT = 64
