package com.mbientlab.metawear.app.core

import kotlin.math.sqrt

/**
 * Pure math behind the 3D orientation cube — the dependency-free port of
 * `QuaternionRealityView.swift`'s intent: rotate a unit cube by the fusion
 * quaternion and orthographically project it for a 2D canvas. No GL/SceneView.
 */
object QuaternionCube {

    /** A 3-vector as (x, y, z). */
    data class Vec3(val x: Float, val y: Float, val z: Float)

    /** The eight corners of a unit cube centered on the origin (side 2). */
    val VERTICES: List<Vec3> = listOf(
        Vec3(-1f, -1f, -1f), Vec3(1f, -1f, -1f), Vec3(1f, 1f, -1f), Vec3(-1f, 1f, -1f),
        Vec3(-1f, -1f, 1f), Vec3(1f, -1f, 1f), Vec3(1f, 1f, 1f), Vec3(-1f, 1f, 1f),
    )

    /** The cube's 12 edges as vertex-index pairs into [VERTICES]. */
    val EDGES: List<Pair<Int, Int>> = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 0,   // back face  (z = -1)
        4 to 5, 5 to 6, 6 to 7, 7 to 4,   // front face (z = +1)
        0 to 4, 1 to 5, 2 to 6, 3 to 7,   // connecting edges
    )

    /**
     * Row-major 3×3 rotation matrix for a (w, x, y, z) quaternion. The input
     * is normalized first; a degenerate (near-zero) quaternion yields the
     * identity instead of NaNs.
     */
    fun rotationMatrix(w: Float, x: Float, y: Float, z: Float): FloatArray {
        val norm = sqrt(w * w + x * x + y * y + z * z)
        if (norm < 1e-6f) {
            return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        val qw = w / norm
        val qx = x / norm
        val qy = y / norm
        val qz = z / norm
        return floatArrayOf(
            1f - 2f * (qy * qy + qz * qz), 2f * (qx * qy - qw * qz), 2f * (qx * qz + qw * qy),
            2f * (qx * qy + qw * qz), 1f - 2f * (qx * qx + qz * qz), 2f * (qy * qz - qw * qx),
            2f * (qx * qz - qw * qy), 2f * (qy * qz + qw * qx), 1f - 2f * (qx * qx + qy * qy),
        )
    }

    /** Apply a row-major 3×3 matrix to a vector. */
    fun rotate(m: FloatArray, v: Vec3): Vec3 = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    /**
     * All eight cube vertices rotated by the quaternion. Orthographic
     * projection is then just (x, y) — z is kept for depth cueing.
     */
    fun rotatedVertices(w: Float, x: Float, y: Float, z: Float): List<Vec3> {
        val m = rotationMatrix(w, x, y, z)
        return VERTICES.map { rotate(m, it) }
    }
}
