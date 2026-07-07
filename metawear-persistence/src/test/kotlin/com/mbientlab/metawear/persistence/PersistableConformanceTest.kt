package com.mbientlab.metawear.persistence

import com.mbientlab.metawear.model.CartesianFloat
import com.mbientlab.metawear.model.CorrectedCartesianFloat
import com.mbientlab.metawear.model.EulerAngles
import com.mbientlab.metawear.model.Quaternion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Encode/decode round-trip tests for the Persistable codecs. These verify
// that persistenceKind, persistenceValues and fromPersistence are consistent
// for every supported type — no database needed.

/** Kind strings. */
class PersistableKindTest {

    @Test
    fun `cartesian persistenceKind`() {
        assertEquals("cartesian", CartesianFloatPersistable.persistenceKind)
    }

    @Test
    fun `quaternion persistenceKind`() {
        assertEquals("quaternion", QuaternionPersistable.persistenceKind)
    }

    @Test
    fun `eulerAngles persistenceKind`() {
        assertEquals("euler", EulerAnglesPersistable.persistenceKind)
    }

    @Test
    fun `correctedCartesian persistenceKind`() {
        assertEquals("corrected-cartesian", CorrectedCartesianFloatPersistable.persistenceKind)
    }

    @Test
    fun `float persistenceKind`() {
        assertEquals("float", FloatPersistable.persistenceKind)
    }

    @Test
    fun `bool persistenceKind`() {
        assertEquals("bool", BoolPersistable.persistenceKind)
    }
}

/** Encode/decode round-trips. */
class PersistableRoundTripTest {

    @Test
    fun `cartesian roundTrip`() {
        val original = CartesianFloat(x = 1.5f, y = -0.25f, z = 9.81f)
        val v = CartesianFloatPersistable.persistenceValues(original)
        val decoded = CartesianFloatPersistable.fromPersistence(v.f0, v.f1, v.f2, v.f3, v.accuracy)
        assertEquals(original.x, decoded.x, 1e-5f)
        assertEquals(original.y, decoded.y, 1e-5f)
        assertEquals(original.z, decoded.z, 1e-5f)
    }

    @Test
    fun `cartesian f3 isZero`() {
        val v = CartesianFloatPersistable.persistenceValues(CartesianFloat(x = 1f, y = 2f, z = 3f))
        assertEquals(0f, v.f3)
        assertEquals(0, v.accuracy)
    }

    @Test
    fun `quaternion roundTrip`() {
        val original = Quaternion(w = 0.707f, x = 0.0f, y = 0.707f, z = 0.0f)
        val v = QuaternionPersistable.persistenceValues(original)
        val decoded = QuaternionPersistable.fromPersistence(v.f0, v.f1, v.f2, v.f3, v.accuracy)
        assertEquals(original.w, decoded.w, 1e-5f)
        assertEquals(original.x, decoded.x, 1e-5f)
        assertEquals(original.y, decoded.y, 1e-5f)
        assertEquals(original.z, decoded.z, 1e-5f)
    }

    @Test
    fun `quaternion mapping w is f0`() {
        val q = Quaternion(w = 10f, x = 20f, y = 30f, z = 40f)
        val v = QuaternionPersistable.persistenceValues(q)
        assertEquals(10f, v.f0) // w
        assertEquals(20f, v.f1) // x
        assertEquals(30f, v.f2) // y
        assertEquals(40f, v.f3) // z
    }

    @Test
    fun `eulerAngles roundTrip`() {
        val original = EulerAngles(heading = 45.0f, pitch = -10.0f, roll = 5.0f, yaw = 90.0f)
        val v = EulerAnglesPersistable.persistenceValues(original)
        val decoded = EulerAnglesPersistable.fromPersistence(v.f0, v.f1, v.f2, v.f3, v.accuracy)
        assertEquals(original.heading, decoded.heading, 1e-5f)
        assertEquals(original.pitch, decoded.pitch, 1e-5f)
        assertEquals(original.roll, decoded.roll, 1e-5f)
        assertEquals(original.yaw, decoded.yaw, 1e-5f)
    }

    @Test
    fun `correctedCartesian roundTrip preserves accuracy`() {
        val original = CorrectedCartesianFloat(x = 0.1f, y = 0.2f, z = 0.3f, accuracy = 3)
        val v = CorrectedCartesianFloatPersistable.persistenceValues(original)
        val decoded =
            CorrectedCartesianFloatPersistable.fromPersistence(v.f0, v.f1, v.f2, v.f3, v.accuracy)
        assertEquals(original.x, decoded.x, 1e-5f)
        assertEquals(original.y, decoded.y, 1e-5f)
        assertEquals(original.z, decoded.z, 1e-5f)
        assertEquals(original.accuracy, decoded.accuracy)
    }

    @Test
    fun `correctedCartesian f3 isZero`() {
        val v = CorrectedCartesianFloatPersistable.persistenceValues(
            CorrectedCartesianFloat(x = 1f, y = 2f, z = 3f, accuracy = 2),
        )
        assertEquals(0f, v.f3)
    }

    @Test
    fun `float roundTrip`() {
        val original = 3.14159f
        val v = FloatPersistable.persistenceValues(original)
        val decoded = FloatPersistable.fromPersistence(v.f0, v.f1, v.f2, v.f3, v.accuracy)
        assertEquals(original, decoded, 1e-5f)
    }

    @Test
    fun `float padding isZero`() {
        val pv = FloatPersistable.persistenceValues(42.0f)
        assertEquals(0f, pv.f1)
        assertEquals(0f, pv.f2)
        assertEquals(0f, pv.f3)
        assertEquals(0, pv.accuracy)
    }

    @Test
    fun `bool true roundTrip`() {
        val v = BoolPersistable.persistenceValues(true)
        val decoded = BoolPersistable.fromPersistence(v.f0, v.f1, v.f2, v.f3, v.accuracy)
        assertTrue(decoded)
        assertEquals(1f, v.f0)
    }

    @Test
    fun `bool false roundTrip`() {
        val v = BoolPersistable.persistenceValues(false)
        val decoded = BoolPersistable.fromPersistence(v.f0, v.f1, v.f2, v.f3, v.accuracy)
        assertFalse(decoded)
        assertEquals(0f, v.f0)
    }
}
