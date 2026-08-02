package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.core.QuaternionFrame
import com.mbientlab.metawear.app.core.QuaternionFrame.Quat
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tare / frame-map math behind the 3D orientation view. */
class QuaternionFrameTest {

    private val half = (sqrt(2.0) / 2).toFloat()

    private fun assertQuatEquals(expected: Quat, actual: Quat, eps: Float = 1e-5f) {
        // q and −q are the same rotation; compare up to sign.
        val direct = abs(expected.w - actual.w) + abs(expected.x - actual.x) +
            abs(expected.y - actual.y) + abs(expected.z - actual.z)
        val negated = abs(expected.w + actual.w) + abs(expected.x + actual.x) +
            abs(expected.y + actual.y) + abs(expected.z + actual.z)
        assertTrue(minOf(direct, negated) < eps, "expected $expected but was $actual")
    }

    @Test
    fun `validity gate rejects near-zero samples and normalizes the rest`() {
        assertNull(QuaternionFrame.quatOrNull(0f, 0f, 0f, 0f))
        assertNull(QuaternionFrame.quatOrNull(0.1f, 0.1f, 0.1f, 0.1f))   // length 0.2 < 0.5

        val doubled = QuaternionFrame.quatOrNull(2f, 0f, 0f, 0f)
        assertNotNull(doubled)
        assertEquals(1f, QuaternionFrame.length(doubled!!), 1e-6f)
        assertEquals(1f, doubled.w, 1e-6f)
    }

    @Test
    fun `at the tare pose the cube reads identity`() {
        val pose = QuaternionFrame.quatOrNull(0.4f, 0.3f, -0.5f, 0.7f)!!
        assertQuatEquals(QuaternionFrame.IDENTITY, QuaternionFrame.rendered(pose, pose))
    }

    @Test
    fun `motion since tare cancels the absolute frame`() {
        // Reference and sample both pre-rotated by an arbitrary world offset:
        // the delta must depend only on the relative motion.
        val world = QuaternionFrame.quatOrNull(0.9f, 0.1f, -0.2f, 0.3f)!!
        val motion = Quat(half, 0f, 0f, half)    // +90° about body z
        val reference = world
        val sample = QuaternionFrame.multiply(world, motion)

        // Body z is preserved by the +90°-about-Z frame map, so the rendered
        // rotation is the same +90° about scene z.
        assertQuatEquals(motion, QuaternionFrame.rendered(reference, sample))
    }

    @Test
    fun `frame map carries chip x-axis motion onto the scene y-axis`() {
        // Delta = +90° about chip x. Conjugating by BODY_TO_SCENE (+90° about
        // z, which maps x→y) must yield +90° about scene y.
        val deltaAboutX = Quat(half, half, 0f, 0f)
        val rendered = QuaternionFrame.rendered(QuaternionFrame.IDENTITY, deltaAboutX)
        assertQuatEquals(Quat(half, 0f, half, 0f), rendered)
    }

    @Test
    fun `rendered orientation stays unit length`() {
        val reference = QuaternionFrame.quatOrNull(0.7f, -0.3f, 0.55f, 0.32f)!!
        val sample = QuaternionFrame.quatOrNull(-0.2f, 0.8f, 0.4f, 0.4f)!!
        val rendered = QuaternionFrame.rendered(reference, sample)
        assertEquals(1f, QuaternionFrame.length(rendered), 1e-4f)
    }
}
