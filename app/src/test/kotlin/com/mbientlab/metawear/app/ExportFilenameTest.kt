package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.export.ExportFilename
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Port of `ExportFilenameTests.swift`. */
class ExportFilenameTest {

    // 2024-12-25T14:30:45Z
    private val timestamp = Instant.parse("2024-12-25T14:30:45Z")
    private val utc = TimeZone.UTC

    @Test
    fun `filename uses dashes only and ends in csv`() {
        val name = ExportFilename.make("MetaWear", "accel", timestamp, utc)
        assertEquals("MetaWear-accel-2024-12-25-14-30-45.csv", name)
        assertFalse(name.contains(":"))
        assertTrue(name.endsWith(".csv"))
    }

    @Test
    fun `empty device name defaults to MetaWear`() {
        val name = ExportFilename.make("", "gyro", timestamp, utc)
        assertTrue(name.startsWith("MetaWear-gyro-"))
    }

    @Test
    fun `unsafe characters are stripped`() {
        val name = ExportFilename.make("My Wear/#1", "fusion-quat", timestamp, utc)
        assertTrue(name.startsWith("MyWear1-fusion-quat-"))
        assertFalse(name.contains(" "))
        assertFalse(name.contains("/"))
        assertFalse(name.contains("#"))
    }
}
