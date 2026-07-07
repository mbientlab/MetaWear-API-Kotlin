package com.mbientlab.metawear.app.vm

import com.mbientlab.metawear.app.core.AnyChartSample
import com.mbientlab.metawear.app.core.EffectiveHz
import com.mbientlab.metawear.app.core.RingBuffer
import com.mbientlab.metawear.app.core.SensorSelection
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One charted sensor stream in a live session.
 *
 * Separates high-frequency sample ingestion from observed UI state: raw
 * samples accumulate in plain (non-state) ring buffers via [ingest] on the
 * BLE consume coroutine, and the stream ViewModel's ~33 ms throttle loop
 * calls [publish] to snapshot them into the single observable [ui] StateFlow.
 * Nothing is emitted into Compose state at sensor rate — at 100 Hz × several
 * sensors, per-sample recomposition churn would visibly lag the chart.
 */
class Channel(
    val selection: SensorSelection,
    capacity: Int = FULL_CAPACITY,
) {
    companion object {
        /** Full-resolution capture depth (~6 s at 100 Hz). */
        const val FULL_CAPACITY = 600

        /** Plotted series depth — about one chart-width of points. */
        const val DISPLAY_CAPACITY = 180

        /** Target plotted rate in Hz; high-rate sensors are thinned to this. */
        const val TARGET_DISPLAY_HZ = 30.0

        /** 1-in-N decimation keeping the plotted series near [TARGET_DISPLAY_HZ]. */
        fun displayStride(hz: Double): Int = max(1, (hz / TARGET_DISPLAY_HZ).roundToInt())
    }

    val id: String get() = selection.key.name

    /**
     * Keep 1 of every [displayStride] samples in the display ring. A live
     * chart resolves only a few hundred points, so a 200 Hz accel thins to
     * ~30 plotted Hz while low-rate sensors keep every sample (stride 1).
     */
    val displayStride: Int = displayStride(selection.hz)

    // ---- Non-observed hot path ----
    // Guarded by [lock]: the consume coroutine appends, the throttle loop
    // snapshots. Deliberately NOT Compose/StateFlow state (see class docs).

    private val lock = Any()

    /** Full-resolution capture buffer — drives archive-to-history and true-rate. */
    private val ring = RingBuffer<AnyChartSample>(capacity)

    /**
     * Capped, decimated copy of [ring] — the actual plotted series. Fed one
     * real sample at a time (1 of every [displayStride]) and only ever
     * appended to, so older points scroll FIFO and keep their values instead
     * of jumping around the way a per-frame re-downsample would.
     */
    private val displayRing = RingBuffer<AnyChartSample>(DISPLAY_CAPACITY)

    /** Samples received since the session started (survives ring rollover). */
    private var receivedCount = 0

    // ---- Observed (UI-bound) state, updated only from the throttle loop ----

    data class Ui(
        val displayBuffer: List<AnyChartSample> = emptyList(),
        val latest: AnyChartSample? = null,
        /** Effective sample rate (Hz) over the most recent samples. */
        val effectiveHz: Double = 0.0,
        /** Total samples received since the session started. */
        val totalSamples: Int = 0,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    /**
     * Ingest a freshly received sample (hot path — called at sensor rate from
     * the BLE consume coroutine). Stores full resolution in the capture ring;
     * mirrors every [displayStride]-th sample into the plotted ring.
     */
    fun ingest(sample: AnyChartSample) {
        synchronized(lock) {
            ring.append(sample)
            receivedCount += 1
            if (receivedCount % displayStride == 0) {
                displayRing.append(sample)
            }
        }
    }

    /** Snapshot the hot-path buffers into [ui]. Called by the ~33 ms ticker. */
    fun publish() {
        val snapshot = synchronized(lock) {
            Ui(
                displayBuffer = displayRing.elements,
                latest = ring.last,
                effectiveHz = EffectiveHz.compute(ring.elements.map { it.time }),
                totalSamples = receivedCount,
            )
        }
        _ui.value = snapshot
    }

    /** Copy of the full-resolution capture buffer (for archive/export). */
    fun captureBuffer(): List<AnyChartSample> = synchronized(lock) { ring.elements }

    /** Total samples received (thread-safe read for aggregation). */
    fun totalReceived(): Int = synchronized(lock) { receivedCount }
}
