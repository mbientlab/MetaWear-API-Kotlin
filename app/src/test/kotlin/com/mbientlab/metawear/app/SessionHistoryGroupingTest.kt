package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.core.SessionHistoryGrouping
import com.mbientlab.metawear.persistence.SessionSnapshot
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Board-identity grouping rules for the session history list. */
class SessionHistoryGroupingTest {

    private var nextId = 0

    private fun snap(
        serial: String,
        name: String?,
        start: Long,
        deviceID: String = "device-${nextId}",
    ): SessionSnapshot {
        nextId += 1
        return SessionSnapshot(
            id = "session-$nextId",
            deviceID = deviceID,
            sensorKind = "cartesian",
            startDate = Instant.fromEpochSeconds(start),
            endDate = Instant.fromEpochSeconds(start + 60),
            sampleCount = 10,
            deviceSerial = serial,
            deviceModel = "8",
            deviceFirmware = "1.7.3",
            deviceName = name,
        )
    }

    @Test
    fun `two default-named boards get separate disambiguated sections`() {
        val sections = SessionHistoryGrouping.sections(
            listOf(snap("0123FF", "MetaWear", 200), snap("045A2C", "MetaWear", 100)),
        )
        assertEquals(2, sections.size)
        assertEquals("MetaWear · 0123FF", sections[0].title)
        assertEquals("MetaWear · 045A2C", sections[1].title)
    }

    @Test
    fun `legacy and new records of one board share a section`() {
        val sections = SessionHistoryGrouping.sections(
            listOf(snap("0123FF", "bob", 200), snap("0123FF", null, 100)),
        )
        assertEquals(1, sections.size)
        assertEquals("bob", sections[0].title)
        assertEquals(2, sections[0].sessions.size)
    }

    @Test
    fun `unique renamed board keeps plain title`() {
        val sections = SessionHistoryGrouping.sections(
            listOf(snap("0123FF", "bob", 200), snap("045A2C", "MetaWear", 100)),
        )
        assertEquals(listOf("bob", "MetaWear"), sections.map { it.title })
    }

    @Test
    fun `rename wins for the section title`() {
        // Sessions are newest-first, so the freshest stamped name leads.
        val sections = SessionHistoryGrouping.sections(
            listOf(snap("0123FF", "bob", 300), snap("0123FF", "MetaWear", 100)),
        )
        assertEquals(1, sections.size)
        assertEquals("bob", sections[0].title)
    }

    @Test
    fun `nameless board falls back to serial then unknown`() {
        val sections = SessionHistoryGrouping.sections(
            listOf(snap("0123FF", null, 200), snap("", null, 100, deviceID = "some-device-id")),
        )
        assertEquals("0123FF", sections[0].title)
        assertEquals("Unknown board", sections[1].title)
        assertEquals("some-device-id", sections[1].id)
    }

    @Test
    fun `titles prefer MAC when known but grouping stays serial-keyed`() {
        val sections = SessionHistoryGrouping.sections(
            listOf(snap("0123FF", "MetaWear", 200), snap("045A2C", "MetaWear", 100)),
            macBySerial = mapOf("0123FF" to "CD:2E:12:34:56:78"),
        )
        assertEquals("MetaWear · CD:2E:12:34:56:78", sections[0].title)
        assertEquals("MetaWear · 045A2C", sections[1].title)   // untranslated keeps serial
        assertEquals("0123FF", sections[0].id)
    }

    @Test
    fun `sections order newest first`() {
        val sections = SessionHistoryGrouping.sections(
            listOf(snap("AAAA01", "old board", 100), snap("BBBB02", "new board", 900)),
        )
        assertEquals(listOf("new board", "old board"), sections.map { it.title })
    }
}
