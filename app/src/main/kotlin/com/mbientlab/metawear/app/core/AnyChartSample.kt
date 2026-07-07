package com.mbientlab.metawear.app.core

import com.mbientlab.metawear.model.CartesianFloat
import com.mbientlab.metawear.model.CorrectedCartesianFloat
import com.mbientlab.metawear.model.EulerAngles
import com.mbientlab.metawear.model.Quaternion
import com.mbientlab.metawear.model.Timestamped
import java.util.concurrent.atomic.AtomicLong
import kotlinx.datetime.Instant

/**
 * Type-erased chart sample: up to four float channels plus a wall-clock time.
 * Port of `AnyChartSample.swift`. Every live-stream value type funnels through
 * this so the ring buffers, chart, and CSV export stay monomorphic.
 */
data class AnyChartSample(
    /** Monotonic identity for stable Compose keys. */
    val id: Long,
    val time: Instant,
    val f0: Float,
    val f1: Float = 0f,
    val f2: Float = 0f,
    val f3: Float = 0f,
    /** Number of meaningful channels (1, 3, or 4). */
    val channelCount: Int,
) {
    /** Channel value by index (0..3). */
    fun channel(index: Int): Float = when (index) {
        0 -> f0
        1 -> f1
        2 -> f2
        else -> f3
    }

    companion object {
        private val nextId = AtomicLong(0)

        fun of(time: Instant, f0: Float, f1: Float = 0f, f2: Float = 0f, f3: Float = 0f, channelCount: Int) =
            AnyChartSample(nextId.incrementAndGet(), time, f0, f1, f2, f3, channelCount)

        // Distinct JVM names: generic erasure would otherwise collapse the
        // Timestamped<T> overloads into one signature.

        @JvmName("fromCartesian")
        fun from(ts: Timestamped<CartesianFloat>): AnyChartSample =
            of(ts.time, ts.value.x, ts.value.y, ts.value.z, channelCount = 3)

        @JvmName("fromQuaternion")
        fun from(ts: Timestamped<Quaternion>): AnyChartSample =
            of(ts.time, ts.value.w, ts.value.x, ts.value.y, ts.value.z, channelCount = 4)

        @JvmName("fromEuler")
        fun from(ts: Timestamped<EulerAngles>): AnyChartSample =
            of(ts.time, ts.value.heading, ts.value.pitch, ts.value.roll, ts.value.yaw, channelCount = 4)

        @JvmName("fromCorrected")
        fun from(ts: Timestamped<CorrectedCartesianFloat>): AnyChartSample =
            // Accuracy rides in f3; the chart plots only x/y/z, CSV export
            // appends the accuracy column.
            of(ts.time, ts.value.x, ts.value.y, ts.value.z, ts.value.accuracy.toFloat(), channelCount = 3)
    }
}
