package com.mbientlab.metawear.firmware

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Ported from StringMetaWearVersionTests.swift — coverage for the
// dotted-numeric-with-padding version comparison used to sort firmware
// revisions and gate `min-ios-version`.

/** Port of the "String — MetaWear version comparison" suite. */
class MetaWearVersionTest {

    // ---- Equal-length numeric compare ----

    @Test
    fun `equalVersions compareEqual`() {
        assertTrue("1.5.0".isMetaWearVersionEqualTo("1.5.0"))
        assertTrue("0.0.0".isMetaWearVersionEqualTo("0.0.0"))
        assertTrue("10.20.30".isMetaWearVersionEqualTo("10.20.30"))
    }

    @Test
    fun `differentVersions orderedCorrectly`() {
        assertTrue("1.5.0".isMetaWearVersionLessThan("1.5.1"))
        assertTrue("1.5.1".isMetaWearVersionGreaterThan("1.5.0"))
        assertTrue("1.4.99".isMetaWearVersionLessThan("1.5.0"))
    }

    @Test
    fun `numericComponents avoidLexicalSort`() {
        // Lexical sort would put "10" < "9" — numeric must put 9 < 10.
        assertTrue("9.0.0".isMetaWearVersionLessThan("10.0.0"))
        assertTrue("1.9.0".isMetaWearVersionLessThan("1.10.0"))
        assertTrue("1.0.9".isMetaWearVersionLessThan("1.0.10"))
    }

    // ---- Different-length comparisons (zero-padding) ----

    @Test
    fun `shortFormPadsToLong compareEqual`() {
        // "1.5" should compare equal to "1.5.0" because the comparator
        // right-pads the shorter side with zeros.
        assertTrue("1.5".isMetaWearVersionEqualTo("1.5.0"))
        assertTrue("1.5.0".isMetaWearVersionEqualTo("1.5"))
        assertTrue("1".isMetaWearVersionEqualTo("1.0.0"))
    }

    @Test
    fun `shortVsLong orderingPreserved`() {
        assertTrue("1.5".isMetaWearVersionLessThan("1.5.1"))
        assertTrue("1.5.1".isMetaWearVersionGreaterThan("1.5"))
        // Trailing zeros really are zeros — "2" < "2.0.1".
        assertTrue("2".isMetaWearVersionLessThan("2.0.1"))
    }

    // ---- Inclusive comparisons ----

    @Test
    fun `greaterThanOrEqual handlesEdges`() {
        assertTrue("1.5.0".isMetaWearVersionGreaterThanOrEqualTo("1.5.0"))
        assertTrue("1.5.1".isMetaWearVersionGreaterThanOrEqualTo("1.5.0"))
        assertFalse("1.4.99".isMetaWearVersionGreaterThanOrEqualTo("1.5.0"))
    }

    @Test
    fun `lessThanOrEqual handlesEdges`() {
        assertTrue("1.5.0".isMetaWearVersionLessThanOrEqualTo("1.5.0"))
        assertTrue("1.4.99".isMetaWearVersionLessThanOrEqualTo("1.5.0"))
        assertFalse("1.5.1".isMetaWearVersionLessThanOrEqualTo("1.5.0"))
    }

    // ---- Real-world MetaWear strings ----

    @Test
    fun `realWorldFirmwareStrings orderedCorrectly`() {
        // From actual catalog history. All vanilla MetaMotion R builds.
        val history = listOf("1.0.0", "1.2.5", "1.3.4", "1.4.3", "1.5.0", "1.7.3")
        for ((a, b) in history.zip(history.drop(1))) {
            assertTrue(a.isMetaWearVersionLessThan(b), "$a should sort before $b")
        }
    }

    @Test
    fun `sdkVersionGate realWorldExample`() {
        // Catalog entries carry `min-ios-version` like "3.0.0" or "3.2.0".
        // SDK 3.2.0 should accept both; SDK 2.9.0 should accept neither.
        assertTrue("3.2.0".isMetaWearVersionGreaterThanOrEqualTo("3.0.0"))
        assertTrue("3.2.0".isMetaWearVersionGreaterThanOrEqualTo("3.2.0"))
        assertFalse("2.9.0".isMetaWearVersionGreaterThanOrEqualTo("3.0.0"))
    }
}
