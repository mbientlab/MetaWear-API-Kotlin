package com.mbientlab.metawear.app.data

import com.mbientlab.metawear.protocol.Command
import com.mbientlab.metawear.protocol.LogChunk
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser
import com.mbientlab.metawear.protocol.Pollable
import com.mbientlab.metawear.protocol.PolledLoggable

/**
 * Polled-loggable barometer pressure (Pascals). App-side adapter: the SDK
 * ships `BarometerPressureRead` ([Pollable] only) and the streamed
 * `Barometer` ([Loggable]); this class implements [PolledLoggable] over the
 * same one-shot read register so pressure joins temperature/humidity in the
 * timer-driven `PolledLogger` chain. The BMP280/BME280 must be running in
 * cyclic mode for the one-shot read to return fresh values — send the
 * [com.mbientlab.metawear.sensor.Barometer] configure/start commands first
 * (`ConfiguredSensor` wires that as prepare/teardown).
 */
class PolledPressure : Pollable<Float>, PolledLoggable<Float> {

    override val module: Module = Module.BAROMETER
    override val dataRegister: Int = 0x01           // PRESSURE
    override val packedDataRegister: Int? = null

    /** `[0x12, 0x81]` — pressure register with the read bit set. */
    override val readCommand: ByteArray get() = Packet.read(Module.BAROMETER, 0x01)

    override fun parseSample(packet: ByteArray): Float = PacketParser.parsePressure(packet)

    // Pressure reads return a 4-byte UInt32 (Pa × 256) — one flash entry.
    override val logDataChunks: List<LogChunk> = listOf(LogChunk(0, 4))
}

/** Wraps raw SDK command bytes for `device.send` (prepare/teardown writes). */
class RawCommand(override val commandData: ByteArray) : Command
