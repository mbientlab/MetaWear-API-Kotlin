package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.core.AnyChartSample
import com.mbientlab.metawear.app.core.SensorKey
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.app.export.LiveBufferCsvExporter
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Port of the pure parts of `CSVExporterTests.swift` (live-buffer flavor). */
class LiveBufferCsvExporterTest {

    private val time = Instant.parse("2024-12-25T14:30:45.123Z")

    @Test
    fun `cartesian export has time header and six-digit floats`() {
        val samples = listOf(
            AnyChartSample.of(time, 1f, 2f, 3f, channelCount = 3),
        )
        val csv = LiveBufferCsvExporter.export(samples, SensorSelection(SensorKey.ACCELEROMETER))
        val lines = csv.trimEnd().split("\n")
        assertEquals("time,x,y,z", lines[0])
        assertEquals("$time,1.000000,2.000000,3.000000", lines[1])
    }

    @Test
    fun `corrected export appends an integer accuracy column`() {
        val samples = listOf(
            AnyChartSample.of(time, 0.5f, -0.25f, 1f, 2f, channelCount = 3),
        )
        val csv = LiveBufferCsvExporter.export(samples, SensorSelection(SensorKey.FUSION_CORRECTED_ACC))
        val lines = csv.trimEnd().split("\n")
        assertEquals("time,x,y,z,accuracy", lines[0])
        assertEquals("$time,0.500000,-0.250000,1.000000,2", lines[1])
    }

    @Test
    fun `quaternion export carries four channels`() {
        val samples = listOf(
            AnyChartSample.of(time, 1f, 0f, 0f, 0f, channelCount = 4),
        )
        val csv = LiveBufferCsvExporter.export(samples, SensorSelection(SensorKey.FUSION_QUATERNION))
        val lines = csv.trimEnd().split("\n")
        assertEquals("time,w,x,y,z", lines[0])
        assertEquals("$time,1.000000,0.000000,0.000000,0.000000", lines[1])
    }
}
