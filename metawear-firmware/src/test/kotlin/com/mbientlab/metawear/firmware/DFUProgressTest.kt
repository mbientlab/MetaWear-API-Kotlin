package com.mbientlab.metawear.firmware

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

// Ported from DFUProgressTests.swift — coverage for the DFUProgress value
// type. It's a plain data class with defaults, but the defaults define what
// observers see during non-upload phases, so they're worth pinning.

/** Port of the "DFUProgress" suite. */
class DFUProgressTest {

    // ---- Defaults ----

    @Test
    fun `defaults zeroExceptForState`() {
        val progress = DFUProgress(state = DFUProgress.State.SCANNING)
        assertEquals(DFUProgress.State.SCANNING, progress.state)
        assertEquals(0.0, progress.percentComplete)
        assertEquals(1, progress.currentPart)
        assertEquals(1, progress.totalParts)
        assertEquals(0.0, progress.bytesPerSecond)
    }

    @Test
    fun `uploadingProgress carriesAllFields`() {
        val progress = DFUProgress(
            state = DFUProgress.State.UPLOADING,
            percentComplete = 42.0,
            currentPart = 1,
            totalParts = 2,
            bytesPerSecond = 12_345.0,
        )
        assertEquals(DFUProgress.State.UPLOADING, progress.state)
        assertEquals(42.0, progress.percentComplete)
        assertEquals(1, progress.currentPart)
        assertEquals(2, progress.totalParts)
        assertEquals(12_345.0, progress.bytesPerSecond)
    }

    // ---- Equality ----

    @Test
    fun `progress equalWhenAllFieldsMatch`() {
        val a = DFUProgress(state = DFUProgress.State.UPLOADING, percentComplete = 50.0)
        val b = DFUProgress(state = DFUProgress.State.UPLOADING, percentComplete = 50.0)
        assertEquals(a, b)
    }

    @Test
    fun `progress differWhenStatesDiffer`() {
        assertNotEquals(
            DFUProgress(state = DFUProgress.State.UPLOADING),
            DFUProgress(state = DFUProgress.State.COMPLETED),
        )
    }

    @Test
    fun `progress differWhenPercentDiffers`() {
        val a = DFUProgress(state = DFUProgress.State.UPLOADING, percentComplete = 50.0)
        val b = DFUProgress(state = DFUProgress.State.UPLOADING, percentComplete = 51.0)
        assertNotEquals(a, b)
    }

    // ---- State enum is exhaustive (compile-time pin) ----

    @Test
    fun `stateEnum coversFullDFULifecycle`() {
        // Touch every case so removing one fails compilation. If a new case
        // is added the when must be updated.
        val states = listOf(
            DFUProgress.State.FETCHING_CATALOG,
            DFUProgress.State.DOWNLOADING_FIRMWARE,
            DFUProgress.State.BOOTLOADER_HANDOFF,
            DFUProgress.State.SCANNING,
            DFUProgress.State.CONNECTING,
            DFUProgress.State.STARTING,
            DFUProgress.State.VALIDATING,
            DFUProgress.State.UPLOADING,
            DFUProgress.State.DISCONNECTING,
            DFUProgress.State.COMPLETED,
            DFUProgress.State.ABORTED,
        )
        for (state in states) {
            when (state) {
                DFUProgress.State.FETCHING_CATALOG,
                DFUProgress.State.DOWNLOADING_FIRMWARE,
                DFUProgress.State.BOOTLOADER_HANDOFF,
                DFUProgress.State.SCANNING,
                DFUProgress.State.CONNECTING,
                DFUProgress.State.STARTING,
                DFUProgress.State.VALIDATING,
                DFUProgress.State.UPLOADING,
                DFUProgress.State.DISCONNECTING,
                DFUProgress.State.COMPLETED,
                DFUProgress.State.ABORTED,
                -> Unit
            }
        }
        assertEquals(11, states.size)
        assertEquals(11, DFUProgress.State.entries.size)
    }
}
