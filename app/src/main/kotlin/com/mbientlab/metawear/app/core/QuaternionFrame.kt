package com.mbientlab.metawear.app.core

import kotlin.math.sqrt

/**
 * Tare (re-zero) frame math for the live 3D orientation view.
 *
 * The fusion quaternion's absolute world frame is not stable session-to-session
 * (magnetic-referenced heading; axis-isolated motions land on different
 * quaternion components across hardware sessions), so the view never renders
 * the raw quaternion — it renders **rotation since a reference pose**:
 *
 * ```
 * raw   = normalize(q)                      // rejected unless |q| > 0.5
 * delta = ref⁻¹ · raw                       // motion in the reference pose's body axes
 * out   = M · delta · M⁻¹                   // re-expressed in the render frame
 * ```
 *
 * `M` ([BODY_TO_SCENE]) corrects the IMU's mounting: the chip sits rotated 90°
 * about the case's face normal (chip X lies along the case length), so pitch
 * and roll would otherwise render swapped (front-back rock showing as
 * left-right). Conjugating by a +90°-about-Z quaternion carries chip axes onto
 * case axes.
 */
object QuaternionFrame {

    /** A quaternion as (w, x, y, z) — Hamilton convention, w first. */
    data class Quat(val w: Float, val x: Float, val y: Float, val z: Float)

    /** The identity orientation. */
    val IDENTITY = Quat(1f, 0f, 0f, 0f)

    /**
     * IMU-mounting correction: +90° about Z (the face normal), i.e.
     * (w=cos45°, z=sin45°). For our Canvas cube — x right, y up, z toward the
     * viewer, orthographic — this maps chip-frame pitch onto the scene's
     * left-right axis and vice versa, fixing the swapped-axes symptom. The
     * SIGN of the 90° is the one free parameter: if hardware shows front-back
     * and left-right landing on the correct axes but inverted in direction,
     * flip `z` here to `-0.7071068f`.
     */
    val BODY_TO_SCENE = Quat(0.7071068f, 0f, 0f, 0.7071068f)

    /** Minimum raw length for a sample to count as a valid orientation. */
    const val MIN_VALID_LENGTH = 0.5f

    fun length(q: Quat): Float = sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z)

    /**
     * Parse a sample's (w, x, y, z) into a normalized quaternion, or `null`
     * when the sample is malformed (near-zero length — dropped packets and
     * uninitialized rows decode as zeros).
     */
    fun quatOrNull(w: Float, x: Float, y: Float, z: Float): Quat? {
        val raw = Quat(w, x, y, z)
        val len = length(raw)
        if (len <= MIN_VALID_LENGTH) return null
        return Quat(w / len, x / len, y / len, z / len)
    }

    /** Hamilton product a·b. */
    fun multiply(a: Quat, b: Quat): Quat = Quat(
        w = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
        x = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
        y = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
        z = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
    )

    /** Inverse of a unit quaternion (its conjugate). */
    fun inverse(q: Quat): Quat = Quat(q.w, -q.x, -q.y, -q.z)

    /**
     * Orientation to render for [sample], given the tare pose [reference]
     * (both normalized): `M · (ref⁻¹ · sample) · M⁻¹`. At the tare pose the
     * result is the identity — the cube shows face-on.
     */
    fun rendered(reference: Quat, sample: Quat): Quat {
        val delta = multiply(inverse(reference), sample)
        return multiply(multiply(BODY_TO_SCENE, delta), inverse(BODY_TO_SCENE))
    }
}
