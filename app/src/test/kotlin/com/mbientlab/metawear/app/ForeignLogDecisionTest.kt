package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.data.ForeignLogDecision
import com.mbientlab.metawear.app.data.foreignLogDecision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The pure decision table for logs found on a connected board. */
class ForeignLogDecisionTest {

    @Test
    fun `local pending record always leaves the board alone`() {
        assertEquals(
            ForeignLogDecision.LeaveAlone,
            foreignLogDecision(
                entryCount = 500, hasActiveLoggers = true,
                isLoggingEnabled = true, hasLocalPendingRecord = true,
            ),
        )
    }

    @Test
    fun `loggers with entries surface`() {
        assertEquals(
            ForeignLogDecision.Surface(isActivelyLogging = false),
            foreignLogDecision(500, hasActiveLoggers = true, isLoggingEnabled = false, hasLocalPendingRecord = false),
        )
    }

    @Test
    fun `active logging with zero entries surfaces`() {
        // MMS boards buffer the first flash page in RAM — a running session
        // can legitimately read LOG_LENGTH == 0.
        assertEquals(
            ForeignLogDecision.Surface(isActivelyLogging = true),
            foreignLogDecision(0, hasActiveLoggers = true, isLoggingEnabled = true, hasLocalPendingRecord = false),
        )
    }

    @Test
    fun `armed but never started loggers are left alone`() {
        assertEquals(
            ForeignLogDecision.LeaveAlone,
            foreignLogDecision(0, hasActiveLoggers = true, isLoggingEnabled = false, hasLocalPendingRecord = false),
        )
    }

    @Test
    fun `entries without loggers clear silently`() {
        // No logger triggers = nothing can decode those entries: garbage.
        assertEquals(
            ForeignLogDecision.SilentClear,
            foreignLogDecision(1, hasActiveLoggers = false, isLoggingEnabled = false, hasLocalPendingRecord = false),
        )
    }

    @Test
    fun `clean board is a no-op`() {
        assertEquals(
            ForeignLogDecision.LeaveAlone,
            foreignLogDecision(0, hasActiveLoggers = false, isLoggingEnabled = false, hasLocalPendingRecord = false),
        )
    }

    @Test
    fun `failed logger enumeration errs on surfacing`() {
        // A wrong "surface" costs a dialog; a wrong clear costs data.
        assertEquals(
            ForeignLogDecision.Surface(isActivelyLogging = false),
            foreignLogDecision(500, hasActiveLoggers = null, isLoggingEnabled = false, hasLocalPendingRecord = false),
        )
        assertEquals(
            ForeignLogDecision.Surface(isActivelyLogging = true),
            foreignLogDecision(0, hasActiveLoggers = null, isLoggingEnabled = true, hasLocalPendingRecord = false),
        )
        assertEquals(
            ForeignLogDecision.LeaveAlone,
            foreignLogDecision(0, hasActiveLoggers = null, isLoggingEnabled = false, hasLocalPendingRecord = false),
        )
    }
}
