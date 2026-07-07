package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.core.QuaternionCube
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Quaternion → rotation-matrix → projection math behind the 3D cube view. */
class QuaternionCubeTest {

    private fun assertVec(expected: QuaternionCube.Vec3, actual: QuaternionCube.Vec3, eps: Float = 1e-5f) {
        assertEquals(expected.x, actual.x, eps)
        assertEquals(expected.y, actual.y, eps)
        assertEquals(expected.z, actual.z, eps)
    }

    @Test
    fun `identity quaternion leaves vertices unchanged`() {
        val m = QuaternionCube.rotationMatrix(1f, 0f, 0f, 0f)
        assertTrue(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f).zip(m.toList()).all { (e, a) -> abs(e - a) < 1e-6f })
        QuaternionCube.rotatedVertices(1f, 0f, 0f, 0f).forEachIndexed { i, v ->
            assertVec(QuaternionCube.VERTICES[i], v)
        }
    }

    @Test
    fun `90 degrees about Z maps x-axis onto y-axis`() {
        val half = (sqrt(2.0) / 2).toFloat()   // cos(45°) = sin(45°)
        val m = QuaternionCube.rotationMatrix(half, 0f, 0f, half)
        assertVec(QuaternionCube.Vec3(0f, 1f, 0f), QuaternionCube.rotate(m, QuaternionCube.Vec3(1f, 0f, 0f)))
        assertVec(QuaternionCube.Vec3(-1f, 0f, 0f), QuaternionCube.rotate(m, QuaternionCube.Vec3(0f, 1f, 0f)))
        assertVec(QuaternionCube.Vec3(0f, 0f, 1f), QuaternionCube.rotate(m, QuaternionCube.Vec3(0f, 0f, 1f)))
    }

    @Test
    fun `matrix stays orthonormal for unnormalized input`() {
        // Deliberately unnormalized quaternion — the matrix must normalize.
        val m = QuaternionCube.rotationMatrix(2f, 1.2f, -0.6f, 0.4f)
        fun row(i: Int) = floatArrayOf(m[3 * i], m[3 * i + 1], m[3 * i + 2])
        fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        for (i in 0..2) {
            assertEquals(1f, dot(row(i), row(i)), 1e-4f)             // unit rows
            for (j in i + 1..2) {
                assertEquals(0f, dot(row(i), row(j)), 1e-4f)         // orthogonal rows
            }
        }
        // Rotation preserves length: a cube corner stays at distance √3.
        val corner = QuaternionCube.rotate(m, QuaternionCube.Vec3(1f, 1f, 1f))
        assertEquals(sqrt(3f), sqrt(corner.x * corner.x + corner.y * corner.y + corner.z * corner.z), 1e-4f)
    }

    @Test
    fun `degenerate quaternion falls back to identity`() {
        val m = QuaternionCube.rotationMatrix(0f, 0f, 0f, 0f)
        assertVec(QuaternionCube.Vec3(1f, 2f, 3f), QuaternionCube.rotate(m, QuaternionCube.Vec3(1f, 2f, 3f)))
    }

    @Test
    fun `cube topology has 8 vertices and 12 edges`() {
        assertEquals(8, QuaternionCube.VERTICES.size)
        assertEquals(12, QuaternionCube.EDGES.size)
        // Every vertex participates in exactly 3 edges.
        val degree = IntArray(8)
        QuaternionCube.EDGES.forEach { (a, b) ->
            degree[a]++
            degree[b]++
        }
        assertTrue(degree.all { it == 3 })
    }
}
