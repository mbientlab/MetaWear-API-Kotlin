package com.mbientlab.metawear.app.core

import com.mbientlab.metawear.sensor.SensorFusionCalibration

/**
 * Fusion-calibration coaching logic behind the live-stream badge.
 *
 * The bar is MEDIUM (level ≥ [USABLE_LEVEL]), not HIGH: HIGH is a live,
 * re-evaluated score and the magnetometer gets demoted near indoor
 * electronics, so "all green forever" is unachievable and must not be nagged.
 */
object CalibrationReadiness {

    /** Accuracy level considered good enough to record. */
    const val USABLE_LEVEL = 2

    enum class State { FULLY_CALIBRATED, READY, CALIBRATING }

    /** Per-sensor coaching line for a sensor still below the bar. */
    data class Coaching(val sensor: String, val advice: String)

    fun state(calibration: SensorFusionCalibration): State = with(calibration) {
        when {
            accelerometer >= 3 && gyroscope >= 3 && magnetometer >= 3 -> State.FULLY_CALIBRATED
            accelerometer >= USABLE_LEVEL && gyroscope >= USABLE_LEVEL && magnetometer >= USABLE_LEVEL ->
                State.READY
            else -> State.CALIBRATING
        }
    }

    fun title(state: State): String = when (state) {
        State.FULLY_CALIBRATED -> "Fully Calibrated"
        State.READY -> "Ready To Record"
        State.CALIBRATING -> "Calibrating…"
    }

    /** Footnote for the READY state (no per-sensor coaching needed). */
    const val READY_FOOTNOTE =
        "Accuracy is good enough to record. Nearby electronics can lower the " +
            "magnetometer rating — that's normal indoors."

    /** Coaching lines, only for sensors below the bar. Empty unless CALIBRATING. */
    fun coaching(calibration: SensorFusionCalibration): List<Coaching> = buildList {
        if (calibration.accelerometer < USABLE_LEVEL) {
            add(Coaching("A", "Rest the board on each of its faces for a few seconds, like rolling a die."))
        }
        if (calibration.gyroscope < USABLE_LEVEL) {
            add(Coaching("G", "Set the board down and keep it still for a moment."))
        }
        if (calibration.magnetometer < USABLE_LEVEL) {
            add(
                Coaching(
                    "M",
                    "Trace a slow figure-8 in the air — away from metal, chargers, and other electronics.",
                ),
            )
        }
    }

    /** Accessibility name for a 0–3 accuracy level. */
    fun levelName(level: Int): String = when {
        level <= 0 -> "unreliable"
        level == 1 -> "low"
        level == 2 -> "medium"
        else -> "high"
    }
}
