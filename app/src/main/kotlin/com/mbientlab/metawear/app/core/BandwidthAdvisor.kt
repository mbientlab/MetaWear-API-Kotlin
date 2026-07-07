package com.mbientlab.metawear.app.core

/**
 * BLE bandwidth guidance for multi-sensor streaming sessions. Port of
 * `BandwidthAdvisor.swift`: a notification-based BLE link comfortably moves
 * ~100 samples/second in aggregate; beyond that the radio drops packets.
 */
object BandwidthAdvisor {

    /** Aggregate sample-rate ceiling considered safe over BLE. */
    const val BLE_SAFE_CEILING_HZ: Double = 100.0

    /** Lowest ODR the IMUs support (BMI under-sampling floor). */
    const val MIN_HZ: Double = 0.78125

    /** Sum of all selected sample rates in Hz. */
    fun aggregateHz(selections: List<SensorSelection>): Double =
        selections.sumOf { it.hz }

    /** True when the combined rate exceeds [BLE_SAFE_CEILING_HZ]. */
    fun isOverCeiling(selections: List<SensorSelection>): Boolean =
        aggregateHz(selections) > BLE_SAFE_CEILING_HZ

    /** Every selection at half rate (clamped to [MIN_HZ]) — the throttle offer. */
    fun halved(selections: List<SensorSelection>): List<SensorSelection> =
        selections.map { it.copy(hz = maxOf(MIN_HZ, it.hz / 2.0)) }
}
