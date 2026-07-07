package com.mbientlab.metawear.sensor

import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.protocol.Module
import com.mbientlab.metawear.protocol.Packet
import com.mbientlab.metawear.protocol.PacketParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

// Port of the processor half of MWDataProcessor.swift — the config protocol,
// every processor-stage configuration type, and the MetaWearDevice extension
// functions that create / stream / remove processors on the board.
//
// All expected byte sequences were verified against MetaWear-SDK-Cpp/test/
// (test_processor.py, test_dataprocessor.py) — see DataProcessorTest.kt.

/**
 * Configuration for one data processor stage. Port of `MWDataProcessorConfig`.
 */
interface DataProcessorConfig {
    /** Wire type ID (from the MetaWear-SDK-Cpp `type_to_id` map). */
    val typeId: Int

    /** Produce the config bytes that follow the type ID byte in the ADD command. */
    fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray

    /** Total output length in bytes. */
    fun outputLength(inputLength: Int, inputChannels: Int): Int

    /** Number of output channels. */
    fun outputChannels(inputLength: Int, inputChannels: Int): Int

    /** Signedness of the output. */
    fun outputSigned(inputSigned: Boolean): Boolean
}

/**
 * Namespace for all data processor configuration types. Port of the Swift
 * `MWDataProcessor` caseless enum.
 *
 * Each nested class ([Passthrough], [Accumulator], [Counter], [Average],
 * [Rms], [Rss], [Time], [Math], [Sample], [Comparator], [Threshold], [Delta],
 * [Pulse], [Buffer], [Packer], [Accounter], [Fuser]) configures a single
 * on-board processor stage. Pass one to `MetaWearDevice.createProcessor` to
 * instantiate it, and chain processors by using the returned [ProcessorHandle]
 * as the source of a subsequent call.
 */
object DataProcessor {

    // ---- Passthrough (type 0x01) ----

    /**
     * Gates data flow — pass all, pass conditionally, or pass N times then stop.
     *
     * Chain after any source to throttle delivery, or combine with an event
     * that toggles the gate at runtime to implement an on/off switch in the
     * processor graph.
     */
    class Passthrough(
        val mode: Mode = Mode.ALL,
        /** Pass-count for [Mode.COUNT] mode (ignored otherwise). */
        val count: Int = 0,
    ) : DataProcessorConfig {

        /** How the passthrough gate decides whether to forward a sample. */
        enum class Mode(val value: Int) {
            /** Forward every sample. */
            ALL(0),

            /** Forward only while an external `count` write opens the gate. */
            CONDITIONAL(1),

            /** Forward the first `count` samples then stop. */
            COUNT(2),
        }

        override val typeId: Int = 0x01

        override fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray =
            byteArrayOf(mode.value.toByte(), (count and 0xFF).toByte(), ((count shr 8) and 0xFF).toByte())

        override fun outputLength(inputLength: Int, inputChannels: Int): Int = inputLength
        override fun outputChannels(inputLength: Int, inputChannels: Int): Int = inputChannels
        override fun outputSigned(inputSigned: Boolean): Boolean = inputSigned
    }

    // ---- Accumulator / Counter (type 0x02) ----

    /**
     * Accumulates (sums) values. The mode bits in the accumulator config are 0 (SUM).
     *
     * Each incoming sample is added to the running total. Useful for integrating
     * magnitude over time (e.g. step count proxy from RSS-of-accel) or any
     * "total movement" metric. Pair with a [Comparator] to fire when the sum
     * passes a threshold.
     *
     * Config byte: `{output_size-1 : 2, input_size-1 : 2, mode=0 : 3}`.
     */
    class Accumulator(outputSize: Int = 4) : DataProcessorConfig {
        /** Output width in bytes: 1, 2, or 4 (clamped to 4). */
        val outputSize: Int = minOf(outputSize, 4)

        override val typeId: Int = 0x02

        override fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray {
            val unitSize = inputLength / maxOf(inputChannels, 1)
            return byteArrayOf((((outputSize - 1) and 0x3) or (((unitSize - 1) and 0x3) shl 2)).toByte()) // mode=0
        }

        override fun outputLength(inputLength: Int, inputChannels: Int): Int = outputSize
        override fun outputChannels(inputLength: Int, inputChannels: Int): Int = 1
        override fun outputSigned(inputSigned: Boolean): Boolean = inputSigned
    }

    /**
     * Counts events (mode = 1 = COUNT in the accumulator config).
     *
     * Emits a monotonically-increasing tally — one increment per input sample,
     * regardless of the input's value. Often chained with [Math] `MODULO` to
     * drive an alternating output (odd/even LED toggle) or with [Comparator]
     * to fire every Nth event.
     *
     * Config byte: `{output_size-1 : 2, 0 : 2, mode=1 : 3}`.
     *
     * Reference test: C++ `test_led_controller` — switch counter, outputSize=1
     * → config byte `0x10`.
     */
    class Counter(outputSize: Int = 4) : DataProcessorConfig {
        /** Output width in bytes: 1, 2, or 4 (clamped to 4). */
        val outputSize: Int = minOf(outputSize, 4)

        override val typeId: Int = 0x02

        override fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray =
            // mode = ACCUMULATOR_COUNT = 1 → bits 4-6 = 001
            byteArrayOf((((outputSize - 1) and 0x3) or (1 shl 4)).toByte())

        override fun outputLength(inputLength: Int, inputChannels: Int): Int = outputSize
        override fun outputChannels(inputLength: Int, inputChannels: Int): Int = 1
        override fun outputSigned(inputSigned: Boolean): Boolean = false
    }

    // ---- Average / low-pass filter (type 0x03) ----

    /**
     * Computes a rolling average (low-pass filter) over the last [sampleSize] inputs.
     *
     * Use to smooth a noisy stream on-device, reducing both jitter and BLE
     * traffic. Chain after [Math] or [Rss] to clean their output before a
     * [Threshold] or [Comparator] consumes it.
     *
     * Config: byte0 `{output-1:2, input-1:2, _:1, mode=0:1, _:2}`, byte1 `sampleSize`.
     *
     * Reference test: C++ `test_freefall` (average of RSS output, sampleSize=4)
     * → config `[0x05, 0x04]`.
     */
    class Average(
        /** Averaging window. */
        val sampleSize: Int = 4,
    ) : DataProcessorConfig {

        override val typeId: Int = 0x03

        override fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray {
            val unitSize = inputLength / maxOf(inputChannels, 1)
            val s = (unitSize - 1) and 0x3
            return byteArrayOf((s or (s shl 2)).toByte(), sampleSize.toByte()) // output==input, mode=0 (LPF)
        }

        override fun outputLength(inputLength: Int, inputChannels: Int): Int = inputLength
        override fun outputChannels(inputLength: Int, inputChannels: Int): Int = inputChannels
        override fun outputSigned(inputSigned: Boolean): Boolean = inputSigned
    }

    // ---- RMS / RSS (type 0x07) ----

    /**
     * Root-mean-square combiner. Reduces a multi-axis signal to a scalar magnitude.
     *
     * Use on a 3-axis sensor (accel/gyro/mag) to collapse it to a single
     * "energy" value. [Rss] is more common for pure magnitude; RMS divides by
     * N first, which suits power-style metrics.
     *
     * Combiner wire layout (2 bytes — shared with [Rss]):
     * ```
     * byte 0:  bits [1:0] = output_unit - 1     (size of each output scalar)
     *          bits [3:2] = input_unit  - 1     (size of each input scalar)
     *          bits [6:4] = n_channels - 1      (1..8 channels → 0..7)
     *          bit  [7]   = input_signed flag
     * byte 1:  mode                             (0 = RMS, 1 = RSS)
     * ```
     */
    class Rms : DataProcessorConfig {
        override val typeId: Int = 0x07

        override fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray {
            val unit = inputLength / maxOf(inputChannels, 1)
            val s = (unit - 1) and 0x3
            val byte0 = s or (s shl 2) or (((inputChannels - 1) and 0x7) shl 4) or (if (inputSigned) 0x80 else 0)
            return byteArrayOf(byte0.toByte(), 0x00) // mode=0 (RMS)
        }

        override fun outputLength(inputLength: Int, inputChannels: Int): Int = inputLength / maxOf(inputChannels, 1)
        override fun outputChannels(inputLength: Int, inputChannels: Int): Int = 1
        override fun outputSigned(inputSigned: Boolean): Boolean = false
    }

    /**
     * Root-sum-square combiner. Reduces a multi-axis signal to its vector magnitude.
     *
     * Computes `sqrt(x² + y² + z²)` on-device — the standard "how much is it
     * accelerating" value. Chain into [Threshold] or [Pulse] to detect motion /
     * free-fall / step events without burning BLE.
     *
     * Reference test: C++ `test_freefall` (accelerometer RSS) → `[0xA5, 0x01]`
     * — output=2, input=2, ch=3, signed=1, mode=RSS.
     */
    class Rss : DataProcessorConfig {
        override val typeId: Int = 0x07

        override fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray {
            // See Rms.configBytes for the byte 0 layout — RSS uses the same
            // combiner type (0x07); only byte 1 (mode) is 0x01.
            val unit = inputLength / maxOf(inputChannels, 1)
            val s = (unit - 1) and 0x3
            val byte0 = s or (s shl 2) or (((inputChannels - 1) and 0x7) shl 4) or (if (inputSigned) 0x80 else 0)
            return byteArrayOf(byte0.toByte(), 0x01) // mode=1 (RSS)
        }

        override fun outputLength(inputLength: Int, inputChannels: Int): Int = inputLength / maxOf(inputChannels, 1)
        override fun outputChannels(inputLength: Int, inputChannels: Int): Int = 1
        override fun outputSigned(inputSigned: Boolean): Boolean = false
    }

    // ---- Time delay / throttle (type 0x08) ----

    /**
     * Down-samples a stream by emitting at most one sample per [periodMs] milliseconds.
     *
     * Drop a high-rate sensor's effective rate (e.g. 100 Hz accel → 10 Hz)
     * without changing the underlying ODR. [Mode.DIFFERENTIAL] emits the
     * difference between the current and previous sample instead of the value
     * itself — useful for computing rate-of-change cheaply on-device.
     *
     * Wire layout: byte0 `{data_length-1:3, mode:3, _:2}` + `period_ms` (UInt32 LE).
     */
    class Time(
        val periodMs: Long,
        val mode: Mode = Mode.ABSOLUTE,
    ) : DataProcessorConfig {

        /** How the time-delay stage selects its output value. */
        enum class Mode(val value: Int) {
            /** Emit the latest input sample verbatim at each tick. */
            ABSOLUTE(0),

            /** Emit `current - previous` at each tick (on-device differentiation). */
            DIFFERENTIAL(1),
        }

        override val typeId: Int = 0x08

        override fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray {
            val byte0 = ((inputLength - 1) and 0x7) or ((mode.value and 0x7) shl 3)
            return byteArrayOf(byte0.toByte()) + PacketParser.le32(periodMs)
        }

        override fun outputLength(inputLength: Int, inputChannels: Int): Int = inputLength
        override fun outputChannels(inputLength: Int, inputChannels: Int): Int = inputChannels
        override fun outputSigned(inputSigned: Boolean): Boolean = inputSigned
    }

    // ---- Math (type 0x09) ----

    /**
     * Arithmetic transform applied per sample — `output = op(input, rhs)`.
     *
     * Use to scale, offset, mod, or otherwise reshape a stream before it
     * reaches a downstream stage. Combined with [Counter] and [Operation.MODULO]
     * it implements a divide-by-N event splitter (C++ `test_led_controller`).
     *
     * Wire layout (7 bytes):
     * ```
     * byte 0:  bits [1:0] = output_unit - 1   (per-channel output byte size)
     *          bits [3:2] = input_unit - 1    (per-channel input byte size)
     *          bit  [4]   = signed flag
     *          bits [7:5] = reserved
     * byte 1:  operation code
     * bytes 2–5: rhs (Int32 little-endian, sign-preserving)
     * byte 6:  n_channels - 1  (0 if N == 1)
     * ```
     */
    class Math(
        val operation: Operation,
        /** Right-hand-side operand (treated as signed Int32 on the wire). */
        val rhs: Int = 0,
        val signed: Boolean = true,
        /** Override output size (bytes). `null` = same as input unit size. */
        val outputSize: Int? = null,
    ) : DataProcessorConfig {

        /**
         * Per-sample arithmetic operator. Most take [rhs]; some are unary.
         *
         * Raw values are the **firmware op codes**, written to the wire verbatim
         * (verified against `MblMwMathOperation` in MetaWear-SDK-Cpp, where the
         * enum starts at 1 and `MathConfig.operation = op` with no translation).
         * Note the non-obvious ordering: `SUBTRACT` is 9, not 1. There are no
         * negate/floor/ceil/round operations in firmware.
         */
        enum class Operation(val value: Int) {
            /** `output = input + rhs`. */
            ADD(1),

            /** `output = input * rhs`. */
            MULTIPLY(2),

            /** `output = input / rhs`. */
            DIVIDE(3),

            /** `output = input % rhs`. */
            MODULO(4),

            /** `output = input ^ rhs`. */
            EXPONENT(5),

            /** `output = sqrt(input)` (rhs ignored). */
            SQRT(6),

            /** Left bit-shift: `output = input << rhs`. */
            LSHIFT(7),

            /** Right bit-shift: `output = input >> rhs`. */
            RSHIFT(8),

            /** `output = input - rhs`. */
            SUBTRACT(9),

            /** `output = |input|` (rhs ignored). */
            ABS(10),

            /** `output = rhs` (input ignored — emits a constant on every fire). */
            CONSTANT(11),
        }

        override val typeId: Int = 0x09

        override fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray {
            val unitIn = inputLength / maxOf(inputChannels, 1)
            val unitOut = outputSize ?: unitIn
            val byte0 = ((unitOut - 1) and 0x3) or
                (((unitIn - 1) and 0x3) shl 2) or
                ((if (signed) 1 else 0) shl 4)
            val nch = if (inputChannels > 1) inputChannels - 1 else 0
            return byteArrayOf(byte0.toByte(), operation.value.toByte()) +
                PacketParser.le32(rhs.toLong()) +
                byteArrayOf(nch.toByte())
        }

        override fun outputLength(inputLength: Int, inputChannels: Int): Int {
            val unitIn = inputLength / maxOf(inputChannels, 1)
            return (outputSize ?: unitIn) * inputChannels
        }

        override fun outputChannels(inputLength: Int, inputChannels: Int): Int = inputChannels
        override fun outputSigned(inputSigned: Boolean): Boolean = signed
    }

    // ---- Sample delay (type 0x0A) ----

    /**
     * Buffers [binSize] samples and emits them in one burst when full.
     *
     * Useful for ML-style windowing — capture N consecutive samples then ship
     * them as a single packet for further on-device processing or BLE delivery.
     *
     * Wire layout: `{data_length-1, bin_size}`.
     */
    class Sample(val binSize: Int) : DataProcessorConfig {
        override val typeId: Int = 0x0A

        override fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray =
            byteArrayOf((inputLength - 1).toByte(), binSize.toByte())

        override fun outputLength(inputLength: Int, inputChannels: Int): Int = inputLength
        override fun outputChannels(inputLength: Int, inputChannels: Int): Int = inputChannels
        override fun outputSigned(inputSigned: Boolean): Boolean = inputSigned
    }

    // ---- Comparator (type 0x06) ----

    /**
     * Compares each sample against a fixed reference, passing samples that
     * satisfy the condition.
     *
     * On firmware ≥ 1.2.3 (everything shipped to MetaMotion R / RL / S / C
     * since 2017) the firmware expects the **multi-comparator** config layout —
     * even when there's only one reference. The legacy single-comparator config
     * (`is_signed(1), op(1), padding(1), ref[4]`) was deprecated, and modern
     * firmware misreads its bytes, causing both comparators in a chain to
     * compare against the same value.
     *
     * Wire format we emit (multi-comparator with a single reference):
     * ```
     * byte 0 (bit-packed, LSB first):
     *   bit 0      is_signed
     *   bits 1-2   length     (size_per_ref - 1: 0=1B, 1=2B, 3=4B)
     *   bits 3-5   operation  (eq=0, neq=1, lt=2, lte=3, gt=4, gte=5)
     *   bits 6-7   mode       (always 0 = ABSOLUTE — emit input on match)
     * bytes 1..N: reference[size_per_ref], little-endian
     * ```
     * Reference size per scalar is `inputLength / max(inputChannels, 1)`.
     */
    class Comparator(
        val operation: Operation,
        /** Value to compare each input against (already scaled to board units). */
        val reference: Int,
        /** Whether the input values are signed (determines compare semantics). */
        val signed: Boolean = true,
    ) : DataProcessorConfig {

        /** Comparison predicate applied to each input against the [reference] value. */
        enum class Operation(val value: Int) {
            /** Pass samples where `input == reference`. */
            EQ(0),

            /** Pass samples where `input != reference`. */
            NEQ(1),

            /** Pass samples where `input < reference`. */
            LT(2),

            /** Pass samples where `input <= reference`. */
            LTE(3),

            /** Pass samples where `input > reference`. */
            GT(4),

            /** Pass samples where `input >= reference`. */
            GTE(5),
        }

        override val typeId: Int = 0x06

        override fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray {
            // Per-scalar size: 1, 2, or 4 bytes. Clamp so totally-unexpected
            // widths don't produce out-of-range length bits.
            val unitSize = (inputLength / maxOf(inputChannels, 1)).coerceIn(1, 4)
            val lengthBits = (unitSize - 1) and 0x3
            val opBits = operation.value and 0x7
            val byte0 = (if (signed) 1 else 0) or (lengthBits shl 1) or (opBits shl 3)
            // mode bits 6-7 = 0 (ABSOLUTE)
            return byteArrayOf(byte0.toByte()) + PacketParser.le32(reference.toLong()).copyOfRange(0, unitSize)
        }

        override fun outputLength(inputLength: Int, inputChannels: Int): Int = inputLength
        override fun outputChannels(inputLength: Int, inputChannels: Int): Int = inputChannels
        override fun outputSigned(inputSigned: Boolean): Boolean = signed
    }

    // ---- Threshold (type 0x0D) ----

    /**
     * Emits a value only when the input crosses a boundary, with optional hysteresis.
     *
     * Unlike [Comparator] (which fires for every sample satisfying the
     * predicate), Threshold only fires on the transition across [boundary].
     * Pair [Mode.BINARY] with an Event to trigger an LED/haptic on crossing.
     * Use `hysteresis > 0` to debounce noisy inputs near the boundary.
     *
     * Wire layout (7 bytes):
     * ```
     * byte 0:  bits [1:0] = data_size - 1  (per-scalar bytes, 1..4 → 0..3)
     *          bit  [2]   = is_signed flag
     *          bits [5:3] = mode            (0 absolute, 1 binary)
     *          bits [7:6] = reserved
     * bytes 1–4: boundary (Int32 little-endian)
     * bytes 5–6: hysteresis (UInt16 little-endian)
     * ```
     * Reference test: C++ `test_freefall` (BINARY, boundary=8192, hysteresis=0)
     * → `[0x09, 0x00, 0x20, 0x00, 0x00, 0x00, 0x00]`.
     */
    class Threshold(
        /** Threshold value (already scaled to board units / LSBs). */
        val boundary: Int,
        /** Dead-band around [boundary]: input must move this far past the line to fire again. */
        val hysteresis: Int = 0,
        val mode: Mode = Mode.BINARY,
        /** Whether the input values are signed. */
        val signed: Boolean = true,
    ) : DataProcessorConfig {

        /** How the threshold stage encodes a boundary crossing. */
        enum class Mode(val value: Int) {
            /** Output the raw value (filtered to only when crossing). */
            ABSOLUTE(0),

            /** Output +1 when above, -1 when below. */
            BINARY(1),
        }

        override val typeId: Int = 0x0D

        override fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray {
            val unitSize = inputLength / maxOf(inputChannels, 1)
            val byte0 = ((unitSize - 1) and 0x3) or
                ((if (signed) 1 else 0) shl 2) or
                ((mode.value and 0x7) shl 3)
            return byteArrayOf(byte0.toByte()) +
                PacketParser.le32(boundary.toLong()) +
                byteArrayOf((hysteresis and 0xFF).toByte(), ((hysteresis shr 8) and 0xFF).toByte())
        }

        override fun outputLength(inputLength: Int, inputChannels: Int): Int =
            // BINARY mode outputs Int32 (±1); ABSOLUTE outputs same as input.
            if (mode == Mode.BINARY) 4 else inputLength

        override fun outputChannels(inputLength: Int, inputChannels: Int): Int = 1
        override fun outputSigned(inputSigned: Boolean): Boolean = if (mode == Mode.BINARY) true else signed
    }

    // ---- Delta (type 0x0C) ----

    /**
     * Emits a sample only when the input has moved at least [magnitude] from
     * the last value it emitted ("change-on-delta" filter).
     *
     * Use to suppress redundant samples from a noisy or slow-moving signal —
     * e.g. only stream a barometer reading when pressure changes by ≥10 Pa.
     * Each emission also updates the internal reference so deltas accumulate
     * from the last fire, not the start of the stream.
     *
     * Wire layout (5 bytes): byte0 `{length-1:2, is_signed:1, mode:3, _:2}`,
     * bytes1-4 `magnitude` (Int32 LE).
     */
    class Delta(
        /** Magnitude expressed in already-scaled board units (LSBs). */
        val magnitude: Int,
        val mode: Mode = Mode.ABSOLUTE,
    ) : DataProcessorConfig {

        /** What value the delta stage emits when the threshold is crossed. */
        enum class Mode(val value: Int) {
            /** Output the raw input on each threshold crossing. */
            ABSOLUTE(0),

            /** Output the raw delta from the last reference. */
            DIFFERENTIAL(1),

            /** Output +1 when above, -1 when below. */
            BINARY(2),
        }

        override val typeId: Int = 0x0C

        override fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray {
            val byte0 = ((inputLength - 1) and 0x3) or
                ((if (inputSigned) 1 else 0) shl 2) or
                ((mode.value and 0x7) shl 3)
            return byteArrayOf(byte0.toByte()) + PacketParser.le32(magnitude.toLong())
        }

        override fun outputLength(inputLength: Int, inputChannels: Int): Int =
            // BINARY emits int8 (±1); otherwise matches input width.
            if (mode == Mode.BINARY) 1 else inputLength

        override fun outputChannels(inputLength: Int, inputChannels: Int): Int = inputChannels
        override fun outputSigned(inputSigned: Boolean): Boolean = if (mode == Mode.BINARY) true else inputSigned
    }

    // ---- Pulse (type 0x0B) ----

    /**
     * Detects pulses (sustained excursions above [threshold] for ≥ [width]
     * samples) and emits one summary value per pulse.
     *
     * Classic use: a step-detector — RSS-of-accel into a Pulse with the right
     * threshold/width emits one sample per step. The [output] field controls
     * what each emission represents (duration, area, peak, or just a
     * "happened" flag).
     *
     * Wire layout (9 bytes): `length, trigger_mode=0, output_mode, threshold[4], width[2]`.
     *
     * Reference tests: C++ `test_acc_z_pulse_setup` (AREA, threshold 2048,
     * width 16) → `[0x01, 0x00, 0x01, 0x00, 0x08, 0x00, 0x00, 0x10, 0x00]`;
     * `test_pulse_setup` (GPIO ADC PEAK 500, width 10) →
     * `[0x01, 0x00, 0x02, 0xF4, 0x01, 0x00, 0x00, 0x0A, 0x00]`.
     */
    class Pulse(
        val output: Output,
        /** Threshold in already-scaled board units (LSBs). */
        val threshold: Int,
        /** Minimum number of samples above threshold to qualify as a pulse. */
        val width: Int,
    ) : DataProcessorConfig {

        /** What value the pulse-detector emits per detected pulse. */
        enum class Output(val value: Int) {
            /** Pulse duration (samples above threshold). */
            WIDTH(0),

            /** Integrated area above threshold. */
            AREA(1),

            /** Peak value during the pulse. */
            PEAK(2),

            /** Boolean pulse-detected indicator (UInt32). */
            ON_DETECT(3),
        }

        override val typeId: Int = 0x0B

        override fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray =
            byteArrayOf((inputLength - 1).toByte(), 0x00, output.value.toByte()) +
                PacketParser.le32(threshold.toLong()) +
                byteArrayOf((width and 0xFF).toByte(), ((width shr 8) and 0xFF).toByte())

        override fun outputLength(inputLength: Int, inputChannels: Int): Int =
            // AREA/PEAK preserve input unit width; WIDTH/ON_DETECT emit UInt32.
            if (output == Output.AREA || output == Output.PEAK) inputLength else 4

        override fun outputChannels(inputLength: Int, inputChannels: Int): Int = 1

        override fun outputSigned(inputSigned: Boolean): Boolean =
            if (output == Output.AREA || output == Output.PEAK) inputSigned else false
    }

    // ---- Buffer (type 0x0F) ----

    /**
     * Holds the most recent sample without emitting anything on its own.
     *
     * The buffered value can be pulled by a separate Event/read, or referenced
     * as a secondary input of a [Fuser] to bundle that sensor's latest reading
     * alongside another stream — without ever having to subscribe to the
     * buffered signal directly.
     *
     * Wire layout (1 byte): `{length-1:5, _:3}`.
     */
    class Buffer : DataProcessorConfig {
        override val typeId: Int = 0x0F

        override fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray =
            byteArrayOf(((inputLength - 1) and 0x1F).toByte())

        override fun outputLength(inputLength: Int, inputChannels: Int): Int = inputLength
        override fun outputChannels(inputLength: Int, inputChannels: Int): Int = inputChannels
        override fun outputSigned(inputSigned: Boolean): Boolean = inputSigned
    }

    // ---- Packer (type 0x10) ----

    /**
     * Bundles [count] consecutive samples into a single BLE packet to reduce overhead.
     *
     * BLE has high per-packet overhead; packing 4–8 samples at a time can
     * significantly raise sustainable streaming rates. The downstream parser
     * must split the packet back into individual samples.
     *
     * Wire layout (2 bytes): byte0 `{length-1:5, _:3}`, byte1 `{count-1:5, _:3}`.
     *
     * Reference test: C++ `TestPacker.test_create` (temperature, count=4,
     * input=2) → `[0x01, 0x03]`.
     */
    class Packer(
        /** Number of samples combined per emission (1-32). */
        val count: Int,
    ) : DataProcessorConfig {

        override val typeId: Int = 0x10

        override fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray =
            byteArrayOf(((inputLength - 1) and 0x1F).toByte(), ((count - 1) and 0x1F).toByte())

        override fun outputLength(inputLength: Int, inputChannels: Int): Int = inputLength
        override fun outputChannels(inputLength: Int, inputChannels: Int): Int = inputChannels
        override fun outputSigned(inputSigned: Boolean): Boolean = inputSigned
    }

    // ---- Accounter (type 0x11) ----

    /**
     * Prepends a timestamp or packet counter to each sample so the logger can
     * reconstruct precise timing when log entries are downloaded out of order.
     *
     * Required upstream of the logger when multiple signals share a single
     * logger channel (e.g. a [Fuser]'s output). [Mode.TIME] is the usual
     * choice; [Mode.COUNT] is for verifying packet ordering / drop detection.
     *
     * Wire layout (2 bytes): byte0 `{mode:4, length-1:2, _:2}`, byte1 `{prescale:4, _:4}`.
     * Firmware pins length to 4 bytes and prescale to 3 to match the logger's
     * wire format.
     *
     * Reference tests: C++ `TestAccounter.test_create` (time) → `[0x31, 0x03]`;
     * `TestAccounterCount.test_create` (count) → `[0x30, 0x03]`.
     */
    class Accounter(val mode: Mode = Mode.TIME) : DataProcessorConfig {

        /** What value the accounter prepends to each sample. */
        enum class Mode(val value: Int) {
            /** Prepend a monotonically-increasing packet counter. */
            COUNT(0),

            /** Prepend a compact epoch offset (ms since boot). */
            TIME(1),
        }

        /** Pinned to 4 bytes as firmware / logger expect. */
        val length: Int = 4

        /** Pinned to 3 (matches the C++ logger default). */
        val prescale: Int = 3

        override val typeId: Int = 0x11

        override fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray {
            val byte0 = (mode.value and 0x0F) or (((length - 1) and 0x3) shl 4)
            val byte1 = prescale and 0x0F
            return byteArrayOf(byte0.toByte(), byte1.toByte())
        }

        override fun outputLength(inputLength: Int, inputChannels: Int): Int = inputLength + length
        override fun outputChannels(inputLength: Int, inputChannels: Int): Int = inputChannels
        override fun outputSigned(inputSigned: Boolean): Boolean = inputSigned
    }

    // ---- Fuser (type 0x1B) ----

    /**
     * Bundles the primary source with the latest samples from one or more
     * [Buffer] stages into a single multi-part packet.
     *
     * Use to align time-synchronous data from different sensors (e.g. accel +
     * gyro arriving in one packet). Each auxiliary signal must first be
     * wrapped in a [Buffer] so the fuser has a stable "latest" value to pull
     * on each primary fire.
     *
     * Wire layout (13 bytes): byte0 `{count:4, _:4}`, bytes1-12 `references[12]`
     * — the board-assigned processor IDs of a matching number of [Buffer]
     * stages that hold the auxiliary signals.
     *
     * Reference test: C++ `TestFuserAccounter.test_commands` — acc fused with
     * gyro-via-buffer(id 0) → `[0x01, 0x00, ..., 0x00]`.
     *
     * @throws MetaWearException.OperationFailed if more than 12 buffer
     *   references are supplied. The fuser config has exactly 12 reference slots.
     */
    class Fuser(
        /** Buffer processor IDs (max 12) that feed the secondary inputs, in order. */
        val bufferIds: List<Int>,
    ) : DataProcessorConfig {

        init {
            if (bufferIds.size > 12) {
                throw MetaWearException.OperationFailed(
                    "Fuser supports at most 12 buffer references; got ${bufferIds.size}",
                )
            }
        }

        override val typeId: Int = 0x1B

        override fun configBytes(inputLength: Int, inputChannels: Int, inputSigned: Boolean): ByteArray {
            val out = ByteArray(13)
            out[0] = (bufferIds.size and 0x0F).toByte()
            for (i in bufferIds.indices) out[1 + i] = bufferIds[i].toByte()
            return out
        }

        // Fuser output is a concatenation of all inputs — the length isn't a
        // single simple scalar, so we report inputLength (primary) and let
        // downstream consumers parse multi-part data. n_channels unchanged.
        override fun outputLength(inputLength: Int, inputChannels: Int): Int = inputLength
        override fun outputChannels(inputLength: Int, inputChannels: Int): Int = inputChannels
        override fun outputSigned(inputSigned: Boolean): Boolean = inputSigned
    }
}

// ---- Data-processor register map ----

private const val DP_ADD = 0x02
private const val DP_NOTIFY = 0x03
private const val DP_REMOVE = 0x06
private const val DP_NOTIFY_ENABLE = 0x07
private const val DP_REMOVE_ALL = 0x08

// ---- ADD command builder ----

/**
 * Build the ADD command bytes:
 * `[0x09, 0x02, src_module, src_reg, src_data_id, src_config, proc_type, config...]`.
 */
internal fun buildProcessorAddCommand(source: Signal, config: DataProcessorConfig): ByteArray {
    val header = byteArrayOf(
        Module.DATA_PROCESSOR.value.toByte(), // 0x09
        DP_ADD.toByte(),                      // ADD register
        source.moduleId.toByte(),
        source.registerId.toByte(),
        source.dataId.toByte(),
        source.sourceConfigByte.toByte(),
        config.typeId.toByte(),
    )
    return header + config.configBytes(source.dataLength, source.nChannels, source.isSigned)
}

// ---- MetaWearDevice data processor API ----

/**
 * Create a data processor on the board and return a handle to it.
 *
 * The handle can be passed as the `source` of a subsequent [createProcessor]
 * call to chain processors, or passed to [streamProcessor] to receive live data.
 *
 * The board acknowledges the ADD command with a plain notification
 * `[0x09, 0x02, id]` on the ADD register carrying the assigned processor id.
 *
 * @param config The processor type and its configuration.
 * @param source The input signal (a sensor signal or a previous processor handle).
 * @return A handle containing the board-assigned processor ID and output metadata.
 */
suspend fun MetaWearDevice.createProcessor(
    config: DataProcessorConfig,
    source: Signal,
): ProcessorHandle {
    if (moduleInfo(Module.DATA_PROCESSOR)?.isPresent != true) {
        throw MetaWearException.OperationFailed("Data processor module not present on this board")
    }
    val response = sendAndAwaitNotification(
        command = buildProcessorAddCommand(source, config),
        awaitModule = Module.DATA_PROCESSOR,
        awaitRegister = DP_ADD,
    )
    if (response.size < 3) {
        throw MetaWearException.OperationFailed("Data processor ADD response too short (${response.size} bytes)")
    }
    val pid = response[2].toInt() and 0xFF
    val outLen = config.outputLength(source.dataLength, source.nChannels)
    val outCh = config.outputChannels(source.dataLength, source.nChannels)
    val outSigned = config.outputSigned(source.isSigned)
    val unitSize = if (outCh > 0) outLen / outCh else outLen
    return ProcessorHandle(id = pid, nChannels = outCh, channelSize = unitSize, isSigned = outSigned)
}

/**
 * Enable notifications from a processor and return a flow of raw data packets.
 *
 * Each element is a raw BLE packet `[0x09, 0x03, processorID, data...]`.
 * Parse `data` according to the processor's output type.
 *
 * Implementation note: where the Swift SDK demultiplexes the shared NOTIFY
 * register inside the device (one per-id continuation map fed by a single
 * demux task), this port subscribes the NOTIFY register and filters packets
 * by processor id client-side. Consequence: all processor flows share one
 * underlying `(0x09, 0x03)` subscription, and starting a second processor
 * stream replaces that subscription — the earlier flow completes. Collect
 * concurrent processors from a single [streamProcessor] flow per subscription,
 * or re-call [streamProcessor] for the processor you want to keep.
 */
suspend fun MetaWearDevice.streamProcessor(handle: ProcessorHandle): Flow<ByteArray> {
    // Subscribe before enabling so no early packet is dropped.
    val raw = subscribeRaw(Module.DATA_PROCESSOR, DP_NOTIFY)
    // Two enables, mirroring C++ `MblMwDataProcessor::subscribe()`:
    //  1. NOTIFY_ENABLE [0x09, 0x07, proc_id, 0x01] — route this processor's
    //     output to the NOTIFY register.
    //  2. NOTIFY [0x09, 0x03, 0x01] — subscribe the NOTIFY register itself
    //     (the standard per-register notify-enable write).
    // Without #2 the board emits NOTHING for any processor — verified on MMS
    // firmware 1.7.2, where omitting it produced zero notifications from an
    // actively-fed counter.
    writeRaw(Packet.command(Module.DATA_PROCESSOR, DP_NOTIFY_ENABLE, handle.id, 0x01))
    writeRaw(Packet.command(Module.DATA_PROCESSOR, DP_NOTIFY, 0x01))
    return raw.filter { it.size >= 3 && (it[2].toInt() and 0xFF) == handle.id }
}

/** Disable notifications from a processor. */
suspend fun MetaWearDevice.stopStreamingProcessor(handle: ProcessorHandle) {
    writeRaw(Packet.command(Module.DATA_PROCESSOR, DP_NOTIFY_ENABLE, handle.id, 0x00))
}

/** Remove one processor from the board. */
suspend fun MetaWearDevice.removeProcessor(handle: ProcessorHandle) {
    writeRaw(Packet.command(Module.DATA_PROCESSOR, DP_REMOVE, handle.id))
}

/** Remove all processors from the board. */
suspend fun MetaWearDevice.removeAllProcessors() {
    writeRaw(Packet.command(Module.DATA_PROCESSOR, DP_REMOVE_ALL))
}
