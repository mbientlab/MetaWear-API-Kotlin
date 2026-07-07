package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.core.AnyChartSample
import com.mbientlab.metawear.app.core.SensorKey
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.app.vm.Channel
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Port of `ChannelDecimationTests.swift`. */
class ChannelDecimationTest {

    private fun sample(index: Int, hz: Double): AnyChartSample {
        val time = Instant.fromEpochMilliseconds(0) + (index * 1000.0 / hz).milliseconds
        return AnyChartSample.of(time, f0 = index.toFloat(), f1 = 0f, f2 = 0f, channelCount = 3)
    }

    @Test
    fun `stride scales with configured rate`() {
        assertEquals(7, Channel.displayStride(200.0))
        assertEquals(3, Channel.displayStride(100.0))
        assertEquals(2, Channel.displayStride(50.0))
        assertEquals(1, Channel.displayStride(25.0))
        assertEquals(1, Channel.displayStride(1.0))
    }

    @Test
    fun `ingest keeps full resolution but thins the display series`() {
        val channel = Channel(SensorSelection(SensorKey.ACCELEROMETER, hz = 100.0))
        repeat(30) { channel.ingest(sample(it, 100.0)) }
        channel.publish()

        val ui = channel.ui.value
        assertEquals(30, ui.totalSamples)
        assertEquals(30, channel.captureBuffer().size)
        // Stride 3: samples 3, 6, 9, … 30 → 10 plotted points.
        assertEquals(10, ui.displayBuffer.size)
    }

    @Test
    fun `displayed points are real samples and stay stable as new ones arrive`() {
        val channel = Channel(SensorSelection(SensorKey.ACCELEROMETER, hz = 100.0))
        repeat(30) { channel.ingest(sample(it, 100.0)) }
        channel.publish()
        val firstSnapshot = channel.ui.value.displayBuffer.map { it.f0 }

        repeat(9) { channel.ingest(sample(30 + it, 100.0)) }
        channel.publish()
        val secondSnapshot = channel.ui.value.displayBuffer.map { it.f0 }

        // Every decimated point is one of the ingested values (index = f0,
        // 1-in-3 kept → f0 ≡ 2 mod 3), and earlier points did not change.
        secondSnapshot.forEach { value -> assertEquals(2f, value.mod(3f)) }
        assertEquals(firstSnapshot, secondSnapshot.subList(0, firstSnapshot.size))
    }

    @Test
    fun `low-rate sensor keeps every sample`() {
        val channel = Channel(SensorSelection(SensorKey.MAGNETOMETER, hz = 10.0))
        repeat(12) { channel.ingest(sample(it, 10.0)) }
        channel.publish()
        assertEquals(12, channel.ui.value.displayBuffer.size)
    }
}
