package com.mbientlab.metawear.sensor

import app.cash.turbine.test
import com.mbientlab.metawear.DEFAULT_PRESENT_MODULES
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.autoReply
import com.mbientlab.metawear.autoReplyModuleDiscovery
import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.makeConnectableTransport
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.transport.MockBleTransport
import com.mbientlab.metawear.transport.Uuids
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Data-processor suites. All expected byte sequences are
// taken directly from MetaWear-SDK-Cpp/test/:
//   test_processor.py     — test_freefall, test_led_controller
//   test_dataprocessor.py — individual processor config tests
//
// ADD command format:
//   [0x09, 0x02, src_module, src_reg, src_data_id, src_config, proc_type, config_bytes...]
//
// Source config formula (datasignal.cpp get_data_ubyte()):
//   src_config = ((n_channels * channel_size - 1) << 5) | offset

// ---- Source config byte ----

class DataProcessorSourceConfigTest {

    // Switch: 1ch × 1B = length 1 → (1-1)<<5 | 0 = 0x00
    @Test fun switchSignal_sourceConfigByte() =
        assertEquals(0x00, SwitchSignal().sourceConfigByte)

    // GPIO ADC: 1ch × 2B = length 2 → (2-1)<<5 | 0 = 0x20
    @Test fun gpioAnalogSignal_sourceConfigByte() =
        assertEquals(0x20, GpioAnalogSignal(pin = 0, mode = GpioAnalogSignal.Mode.ADC).sourceConfigByte)

    // Accelerometer: 3ch × 2B = length 6 → (6-1)<<5 | 0 = 0xA0
    @Test fun accelerometerSignal_sourceConfigByte() =
        assertEquals(0xA0, AccelerometerSignal().sourceConfigByte)

    // Temperature: 1ch × 2B = length 2 → 0x20
    @Test fun temperatureSignal_sourceConfigByte() =
        assertEquals(0x20, TemperatureSignal(channel = 0).sourceConfigByte)

    // Processor handle (RSS output, 1ch × 2B): 0x20
    @Test fun processorHandle_sourceConfigByte() =
        assertEquals(0x20, ProcessorHandle(id = 0, nChannels = 1, channelSize = 2, isSigned = false).sourceConfigByte)
}

// ---- ADD command layout ----

class DataProcessorAddCommandTest {

    // RSS of accelerometer (C++ test_freefall, step 1)
    @Test fun rss_ofAccelerometer_addCommand() =
        assertArrayEquals(
            bytes(0x09, 0x02, 0x03, 0x04, 0xFF, 0xA0, 0x07, 0xA5, 0x01),
            buildProcessorAddCommand(AccelerometerSignal(), DataProcessor.Rss()),
        )

    // RSS config byte breakdown:
    //   unit = 6/3 = 2 → s = (2-1)&0x3 = 1
    //   byte0 = s | (s<<2) | ((ch-1)<<4) | signed = 1 | 4 | (2<<4) | 0x80 = 0xA5
    //   byte1 = 0x01 (RSS mode)
    @Test fun rss_configByteBreakdown() =
        assertArrayEquals(
            bytes(0xA5, 0x01),
            DataProcessor.Rss().configBytes(inputLength = 6, inputChannels = 3, inputSigned = true),
        )

    // RMS shares the combiner layout; only byte 1 (mode) is 0x00.
    @Test fun rms_configByteBreakdown() =
        assertArrayEquals(
            bytes(0xA5, 0x00),
            DataProcessor.Rms().configBytes(inputLength = 6, inputChannels = 3, inputSigned = true),
        )

    // Counter of switch (C++ test_led_controller, step 1)
    @Test fun counter_ofSwitch_addCommand() =
        assertArrayEquals(
            bytes(0x09, 0x02, 0x01, 0x01, 0xFF, 0x00, 0x02, 0x10),
            buildProcessorAddCommand(SwitchSignal(), DataProcessor.Counter(outputSize = 1)),
        )

    // Counter config byte: ((outputSize-1)&0x3) | (1<<4); outputSize=1 → 0x10
    @Test fun counter_configByte_outputSize1() =
        assertArrayEquals(
            bytes(0x10),
            DataProcessor.Counter(outputSize = 1).configBytes(inputLength = 1, inputChannels = 1, inputSigned = false),
        )

    // ((4-1)&0x3) | (1<<4) = 3 | 0x10 = 0x13
    @Test fun counter_configByte_outputSize4() =
        assertArrayEquals(
            bytes(0x13),
            DataProcessor.Counter(outputSize = 4).configBytes(inputLength = 1, inputChannels = 1, inputSigned = false),
        )

    // Math (counter % 2) (C++ test_led_controller, step 2)
    //   byte0: output=4→3, input=1→0, unsigned=0 → 0x03 | (0<<2) | (0<<4) = 0x03
    //   op = modulo = 0x04, rhs=2 LE32, nch=0
    @Test fun math_modulo_configBytes() =
        assertArrayEquals(
            bytes(0x03, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00),
            DataProcessor.Math(operation = DataProcessor.Math.Operation.MODULO, rhs = 2, signed = false, outputSize = 4)
                .configBytes(inputLength = 1, inputChannels = 1, inputSigned = false),
        )

    // Average of RSS output (C++ test_freefall, step 2)
    //   src = processor handle id=0, 1ch×2B → src_config=0x20
    //   proc_type=0x03 (Average), config byte0: s=1, s|s<<2 = 0x05; byte1=4
    @Test fun average_ofProcessorHandle_addCommand() =
        assertArrayEquals(
            bytes(0x09, 0x02, 0x09, 0x03, 0x00, 0x20, 0x03, 0x05, 0x04),
            buildProcessorAddCommand(
                ProcessorHandle(id = 0, nChannels = 1, channelSize = 2, isSigned = false),
                DataProcessor.Average(sampleSize = 4),
            ),
        )

    @Test fun average_configBytes_2byteInput() =
        // inputLength=2, inputChannels=1 → unit=2, s=1 → byte0 = 0x01 | 0x04 = 0x05
        assertArrayEquals(
            bytes(0x05, 0x04),
            DataProcessor.Average(sampleSize = 4).configBytes(inputLength = 2, inputChannels = 1, inputSigned = false),
        )

    // Threshold BINARY (C++ test_freefall, step 3)
    //   byte0: (size-1)&0x3=1, signed=0, mode=binary=1 → (1) | (0<<2) | (1<<3) = 0x09
    //   boundary LE32: 0x00002000 → [0x00, 0x20, 0x00, 0x00]; hysteresis LE16: [0x00, 0x00]
    @Test fun threshold_binary_configBytes() =
        assertArrayEquals(
            bytes(0x09, 0x00, 0x20, 0x00, 0x00, 0x00, 0x00),
            DataProcessor.Threshold(boundary = 8192, hysteresis = 0, mode = DataProcessor.Threshold.Mode.BINARY, signed = false)
                .configBytes(inputLength = 2, inputChannels = 1, inputSigned = false),
        )

    // Comparator EQ -1 (signed) on 4-byte signed input (BINARY threshold output).
    // Multi-compare config (5 bytes):
    //   byte 0 bit-packed: is_signed=1 | length=3<<1 | op=EQ=0<<3 | mode=ABS=0<<6 = 0x07
    //   bytes 1-4: reference=-1 LE32 = 0xFF, 0xFF, 0xFF, 0xFF
    @Test fun comparator_eq_minusOne_signed_configBytes() =
        assertArrayEquals(
            bytes(0x07, 0xFF, 0xFF, 0xFF, 0xFF),
            DataProcessor.Comparator(operation = DataProcessor.Comparator.Operation.EQ, reference = -1, signed = true)
                .configBytes(inputLength = 4, inputChannels = 1, inputSigned = true),
        )

    // Comparator EQ +1 (signed) — same shape, different reference.
    @Test fun comparator_eq_plusOne_signed_configBytes() =
        assertArrayEquals(
            bytes(0x07, 0x01, 0x00, 0x00, 0x00),
            DataProcessor.Comparator(operation = DataProcessor.Comparator.Operation.EQ, reference = 1, signed = true)
                .configBytes(inputLength = 4, inputChannels = 1, inputSigned = true),
        )

    // Comparator EQ 1 (unsigned) on a 1-byte input (e.g. modulo-2 of a counter).
    // byte 0 = is_signed=0 | length=0<<1 | op=EQ=0<<3 | mode=ABS=0<<6 = 0x00; ref = [0x01]
    @Test fun comparator_eq_one_unsigned_oneByte_configBytes() =
        assertArrayEquals(
            bytes(0x00, 0x01),
            DataProcessor.Comparator(operation = DataProcessor.Comparator.Operation.EQ, reference = 1, signed = false)
                .configBytes(inputLength = 1, inputChannels = 1, inputSigned = false),
        )

    @Test fun comparator_eq_zero_unsigned_oneByte_configBytes() =
        assertArrayEquals(
            bytes(0x00, 0x00),
            DataProcessor.Comparator(operation = DataProcessor.Comparator.Operation.EQ, reference = 0, signed = false)
                .configBytes(inputLength = 1, inputChannels = 1, inputSigned = false),
        )
}

// ---- Full pipeline ADD command sequences ----

class DataProcessorPipelineTest {

    // C++ test_freefall — five ADD commands in sequence:
    //   1. RSS of accelerometer
    //   2. Average(4) of RSS output (handle id=0)
    //   3. Threshold(binary, boundary=8192) of Average output (handle id=1)
    //   4. Comparator(eq, -1) of Threshold output (handle id=2)
    //   5. Comparator(eq, +1) of Threshold output (handle id=2)

    @Test fun freefall_step1_rss() =
        assertArrayEquals(
            bytes(0x09, 0x02, 0x03, 0x04, 0xFF, 0xA0, 0x07, 0xA5, 0x01),
            buildProcessorAddCommand(AccelerometerSignal(), DataProcessor.Rss()),
        )

    @Test fun freefall_step2_average() =
        // RSS output: 1ch × 2B unsigned → sourceConfig=0x20, proc_id=0
        assertArrayEquals(
            bytes(0x09, 0x02, 0x09, 0x03, 0x00, 0x20, 0x03, 0x05, 0x04),
            buildProcessorAddCommand(
                ProcessorHandle(id = 0, nChannels = 1, channelSize = 2, isSigned = false),
                DataProcessor.Average(sampleSize = 4),
            ),
        )

    @Test fun freefall_step3_threshold() =
        // Average output: 1ch × 2B unsigned → sourceConfig=0x20, proc_id=1
        assertArrayEquals(
            bytes(0x09, 0x02, 0x09, 0x03, 0x01, 0x20, 0x0D, 0x09, 0x00, 0x20, 0x00, 0x00, 0x00, 0x00),
            buildProcessorAddCommand(
                ProcessorHandle(id = 1, nChannels = 1, channelSize = 2, isSigned = false),
                DataProcessor.Threshold(boundary = 8192, hysteresis = 0, mode = DataProcessor.Threshold.Mode.BINARY, signed = false),
            ),
        )

    @Test fun freefall_step4_comparatorEqMinusOne() {
        // Threshold BINARY output: 1ch × 4B signed → sourceConfig = (4-1)<<5 = 0x60, proc_id=2
        // header(7) + multi-compare config(5) — no trailing padding.
        val cmd = buildProcessorAddCommand(
            ProcessorHandle(id = 2, nChannels = 1, channelSize = 4, isSigned = true),
            DataProcessor.Comparator(operation = DataProcessor.Comparator.Operation.EQ, reference = -1, signed = true),
        )
        assertArrayEquals(bytes(0x09, 0x02, 0x09, 0x03, 0x02, 0x60, 0x06, 0x07, 0xFF, 0xFF, 0xFF, 0xFF), cmd)
        assertEquals(12, cmd.size)
    }

    @Test fun freefall_step5_comparatorEqPlusOne() =
        assertArrayEquals(
            bytes(0x09, 0x02, 0x09, 0x03, 0x02, 0x60, 0x06, 0x07, 0x01, 0x00, 0x00, 0x00),
            buildProcessorAddCommand(
                ProcessorHandle(id = 2, nChannels = 1, channelSize = 4, isSigned = true),
                DataProcessor.Comparator(operation = DataProcessor.Comparator.Operation.EQ, reference = 1, signed = true),
            ),
        )

    // C++ test_led_controller — two ADD commands:
    //   1. Counter(outputSize=1) of switch
    //   2. Math(modulo, rhs=2, unsigned, outputSize=4) of Counter output
    @Test fun ledController_step1_counter() =
        assertArrayEquals(
            bytes(0x09, 0x02, 0x01, 0x01, 0xFF, 0x00, 0x02, 0x10),
            buildProcessorAddCommand(SwitchSignal(), DataProcessor.Counter(outputSize = 1)),
        )

    @Test fun ledController_step2_math_modulo() =
        // Counter output: 1ch × 1B unsigned → sourceConfig=0x00, proc_id=0
        assertArrayEquals(
            bytes(0x09, 0x02, 0x09, 0x03, 0x00, 0x00, 0x09, 0x03, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00),
            buildProcessorAddCommand(
                ProcessorHandle(id = 0, nChannels = 1, channelSize = 1, isSigned = false),
                DataProcessor.Math(operation = DataProcessor.Math.Operation.MODULO, rhs = 2, signed = false, outputSize = 4),
            ),
        )
}

// ---- Processor output metadata ----

class DataProcessorOutputMetadataTest {

    @Test fun rss_outputLength_from6byteAccel() {
        val config = DataProcessor.Rss()
        assertEquals(2, config.outputLength(inputLength = 6, inputChannels = 3))
        assertEquals(1, config.outputChannels(inputLength = 6, inputChannels = 3))
        assertFalse(config.outputSigned(inputSigned = true))
    }

    @Test fun counter_outputLength() {
        val config = DataProcessor.Counter(outputSize = 1)
        assertEquals(1, config.outputLength(inputLength = 1, inputChannels = 1))
        assertEquals(1, config.outputChannels(inputLength = 1, inputChannels = 1))
    }

    @Test fun average_outputMatchesInput() {
        val config = DataProcessor.Average(sampleSize = 4)
        assertEquals(2, config.outputLength(inputLength = 2, inputChannels = 1))
        assertEquals(1, config.outputChannels(inputLength = 2, inputChannels = 1))
    }

    @Test fun threshold_binary_outputLength_is4() {
        val config = DataProcessor.Threshold(boundary = 0, mode = DataProcessor.Threshold.Mode.BINARY)
        assertEquals(4, config.outputLength(inputLength = 2, inputChannels = 1))
        assertEquals(1, config.outputChannels(inputLength = 2, inputChannels = 1))
        assertTrue(config.outputSigned(inputSigned = false))
    }

    @Test fun threshold_absolute_outputMatchesInput() {
        val config = DataProcessor.Threshold(boundary = 0, mode = DataProcessor.Threshold.Mode.ABSOLUTE)
        assertEquals(2, config.outputLength(inputLength = 2, inputChannels = 1))
    }

    @Test fun comparator_outputMatchesInput() {
        val config = DataProcessor.Comparator(operation = DataProcessor.Comparator.Operation.EQ, reference = 0)
        assertEquals(4, config.outputLength(inputLength = 4, inputChannels = 1))
    }

    @Test fun math_outputSize_overrideApplied() {
        val config = DataProcessor.Math(operation = DataProcessor.Math.Operation.MODULO, rhs = 2, signed = false, outputSize = 4)
        // input 1ch × 1B, override output to 4B → outputLength = 4 * 1ch = 4
        assertEquals(4, config.outputLength(inputLength = 1, inputChannels = 1))
    }
}

// ---- Passthrough ----

class DataProcessorPassthroughTest {

    @Test fun passthrough_all_configBytes() =
        assertArrayEquals(
            bytes(0x00, 0x00, 0x00),
            DataProcessor.Passthrough(mode = DataProcessor.Passthrough.Mode.ALL, count = 0)
                .configBytes(inputLength = 1, inputChannels = 1, inputSigned = false),
        )

    @Test fun passthrough_count_configBytes() =
        // mode=count(0x02), count=10 LE16
        assertArrayEquals(
            bytes(0x02, 0x0A, 0x00),
            DataProcessor.Passthrough(mode = DataProcessor.Passthrough.Mode.COUNT, count = 10)
                .configBytes(inputLength = 1, inputChannels = 1, inputSigned = false),
        )
}

// ---- Accumulator ----
// Not covered by a C++ reference vector; the expected bytes
// below are derived from the documented AccumulatorConfig layout:
//   {output_size-1 : 2, input_size-1 : 2, mode=0 : 3}

class DataProcessorAccumulatorTest {

    @Test fun accumulator_configByte_2byteInput_4byteOutput() =
        // ((4-1)&0x3) | (((2-1)&0x3)<<2) = 3 | 4 = 0x07
        assertArrayEquals(
            bytes(0x07),
            DataProcessor.Accumulator(outputSize = 4).configBytes(inputLength = 2, inputChannels = 1, inputSigned = false),
        )

    @Test fun accumulator_outputMetadata() {
        val config = DataProcessor.Accumulator(outputSize = 4)
        assertEquals(4, config.outputLength(inputLength = 2, inputChannels = 1))
        assertEquals(1, config.outputChannels(inputLength = 2, inputChannels = 1))
        assertTrue(config.outputSigned(inputSigned = true))
    }
}

// ---- Time ----

class DataProcessorTimeTest {

    @Test fun time_absolute_configBytes() =
        // inputLength=6 (accel), mode=absolute, period=500 ms
        // byte0: (6-1)&0x7 | (0<<3) = 0x05; period 500=0x1F4 LE32: [0xF4, 0x01, 0x00, 0x00]
        assertArrayEquals(
            bytes(0x05, 0xF4, 0x01, 0x00, 0x00),
            DataProcessor.Time(periodMs = 500, mode = DataProcessor.Time.Mode.ABSOLUTE)
                .configBytes(inputLength = 6, inputChannels = 3, inputSigned = true),
        )
}

// ---- Sample delay ----

class DataProcessorSampleTest {

    @Test fun sample_configBytes() =
        // [inputLength-1, binSize] = [5, 3]
        assertArrayEquals(
            bytes(0x05, 0x03),
            DataProcessor.Sample(binSize = 3).configBytes(inputLength = 6, inputChannels = 3, inputSigned = true),
        )
}

// ---- Delta ----
// DeltaConfig wire format (5 bytes):
//   byte 0: length-1 (2 bits) | is_signed (1 bit) | mode (3 bits) | padding (2 bits)
//   bytes 1-4: magnitude (int32 LE)

class DataProcessorDeltaTest {

    // Barometer pressure signal: 4-byte UInt32.
    // C++ TestDeltaSetPrevious: DIFFERENTIAL, magnitude 25331.25 Pa (×256 → 6484800 = 0x62F340).
    @Test fun delta_differential_configBytes() =
        // byte0 = (4-1)&0x3 | (unsigned<<2) | (DIFFERENTIAL=1 << 3) = 3 | 0 | 8 = 0x0B
        assertArrayEquals(
            bytes(0x0B, 0x40, 0xF3, 0x62, 0x00),
            DataProcessor.Delta(magnitude = 6_484_800, mode = DataProcessor.Delta.Mode.DIFFERENTIAL)
                .configBytes(inputLength = 4, inputChannels = 1, inputSigned = false),
        )

    @Test fun delta_absolute_signed_configBytes() =
        // Accelerometer Z (2 bytes, signed), magnitude 1024, ABSOLUTE.
        // byte0 = (2-1)&3 | (1<<2) | (0<<3) = 1 | 4 = 0x05
        assertArrayEquals(
            bytes(0x05, 0x00, 0x04, 0x00, 0x00),
            DataProcessor.Delta(magnitude = 1024, mode = DataProcessor.Delta.Mode.ABSOLUTE)
                .configBytes(inputLength = 2, inputChannels = 1, inputSigned = true),
        )

    @Test fun delta_binary_reducesOutputToInt8() {
        val config = DataProcessor.Delta(magnitude = 100, mode = DataProcessor.Delta.Mode.BINARY)
        assertEquals(1, config.outputLength(inputLength = 4, inputChannels = 1))
        assertTrue(config.outputSigned(inputSigned = false))
    }
}

// ---- Pulse ----

class DataProcessorPulseTest {

    // C++ test_acc_z_pulse_setup (AREA mode, threshold 2048, width 16 on acc Z axis).
    // [length-1=1, trigger=0, output=AREA(1), threshold=0x0800 LE, width=16 LE]
    @Test fun pulse_area_configBytes() =
        assertArrayEquals(
            bytes(0x01, 0x00, 0x01, 0x00, 0x08, 0x00, 0x00, 0x10, 0x00),
            DataProcessor.Pulse(output = DataProcessor.Pulse.Output.AREA, threshold = 2048, width = 16)
                .configBytes(inputLength = 2, inputChannels = 1, inputSigned = true),
        )

    // C++ test_pulse_setup (GPIO ADC PEAK 500, width 10).
    @Test fun pulse_peak_configBytes_gpioAdc() =
        assertArrayEquals(
            bytes(0x01, 0x00, 0x02, 0xF4, 0x01, 0x00, 0x00, 0x0A, 0x00),
            DataProcessor.Pulse(output = DataProcessor.Pulse.Output.PEAK, threshold = 500, width = 10)
                .configBytes(inputLength = 2, inputChannels = 1, inputSigned = false),
        )

    @Test fun pulse_widthMode_outputIsUInt32() {
        val config = DataProcessor.Pulse(output = DataProcessor.Pulse.Output.WIDTH, threshold = 1, width = 1)
        assertEquals(4, config.outputLength(inputLength = 2, inputChannels = 1))
        assertFalse(config.outputSigned(inputSigned = true))
    }

    @Test fun pulse_areaMode_preservesInputSign() {
        val config = DataProcessor.Pulse(output = DataProcessor.Pulse.Output.AREA, threshold = 1, width = 1)
        assertEquals(2, config.outputLength(inputLength = 2, inputChannels = 1))
        assertTrue(config.outputSigned(inputSigned = true))
    }
}

// ---- Buffer ----

class DataProcessorBufferTest {

    // C++ test_commands (fuser setup) — gyro buffer on 6-byte signal → (6-1) = 0x05
    @Test fun buffer_gyroConfigByte() =
        assertArrayEquals(
            bytes(0x05),
            DataProcessor.Buffer().configBytes(inputLength = 6, inputChannels = 3, inputSigned = true),
        )

    @Test fun buffer_singleByteInput() =
        assertArrayEquals(
            bytes(0x00),
            DataProcessor.Buffer().configBytes(inputLength = 1, inputChannels = 1, inputSigned = false),
        )
}

// ---- Packer ----

class DataProcessorPackerTest {

    // C++ TestPacker.test_create (temp signal 2B, count=4) → byte0 = 2-1, byte1 = 4-1
    @Test fun packer_tempCount4_configBytes() =
        assertArrayEquals(
            bytes(0x01, 0x03),
            DataProcessor.Packer(count = 4).configBytes(inputLength = 2, inputChannels = 1, inputSigned = true),
        )

    // C++ TestAccounter.test_count_and_time (accel 6B, count=2) → [0x05, 0x01]
    @Test fun packer_accCount2_configBytes() =
        assertArrayEquals(
            bytes(0x05, 0x01),
            DataProcessor.Packer(count = 2).configBytes(inputLength = 6, inputChannels = 3, inputSigned = true),
        )
}

// ---- Accounter ----

class DataProcessorAccounterTest {

    // C++ TestAccounter.test_create (time mode, accel signal)
    // byte0 = mode(1) | ((4-1) << 4) = 0x31, byte1 = prescale = 0x03
    @Test fun accounter_time_configBytes() =
        assertArrayEquals(
            bytes(0x31, 0x03),
            DataProcessor.Accounter(mode = DataProcessor.Accounter.Mode.TIME)
                .configBytes(inputLength = 6, inputChannels = 3, inputSigned = true),
        )

    // C++ TestAccounterCount.test_create (count mode) → byte0 = 0 | (3 << 4) = 0x30
    @Test fun accounter_count_configBytes() =
        assertArrayEquals(
            bytes(0x30, 0x03),
            DataProcessor.Accounter(mode = DataProcessor.Accounter.Mode.COUNT)
                .configBytes(inputLength = 6, inputChannels = 3, inputSigned = true),
        )

    @Test fun accounter_outputLength_includesAccountBytes() =
        // Accel 6B + 4-byte account = 10B output.
        assertEquals(10, DataProcessor.Accounter(mode = DataProcessor.Accounter.Mode.TIME).outputLength(inputLength = 6, inputChannels = 3))
}

// ---- Fuser ----

class DataProcessorFuserTest {

    // C++ TestFuserAccounter.test_commands (acc primary, one buffer ref with ID 0).
    // Config: count=1, references[0]=0, rest 0 (13 bytes total).
    @Test fun fuser_singleReference_configBytes() =
        assertArrayEquals(
            bytes(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            DataProcessor.Fuser(bufferIds = listOf(0)).configBytes(inputLength = 6, inputChannels = 3, inputSigned = true),
        )

    @Test fun fuser_twoReferences_configBytes() =
        // count=2, refs[0]=3, refs[1]=5, rest 0 — 13 bytes total.
        assertArrayEquals(
            bytes(0x02, 0x03, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            DataProcessor.Fuser(bufferIds = listOf(3, 5)).configBytes(inputLength = 6, inputChannels = 3, inputSigned = true),
        )

    @Test fun fuser_configBytes_alwaysThirteenBytes() {
        val bytes = DataProcessor.Fuser(bufferIds = emptyList()).configBytes(inputLength = 6, inputChannels = 3, inputSigned = true)
        assertEquals(13, bytes.size)
        assertEquals(0, bytes[0].toInt())
    }

    @Test fun fuser_rejectsMoreThanTwelveReferences() {
        val error = runCatching { DataProcessor.Fuser(bufferIds = List(13) { 0 }) }.exceptionOrNull()
        assertTrue(error is MetaWearException.OperationFailed)
        assertEquals("Operation failed: Fuser supports at most 12 buffer references; got 13", error?.message)
    }
}

// ---- Mock-device create / stream / remove ----

class DataProcessorDeviceTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    /** Data-processor module id 0x09 must be reported present for createProcessor. */
    private val modulesWithDataProcessor = DEFAULT_PRESENT_MODULES + 0x09

    private suspend fun TestScope.connectedDevice(
        presentModules: Set<Int> = modulesWithDataProcessor,
    ): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice(mac, transport, backgroundScope)
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport, presentModules)
        device.connect()
        discovery.cancel()
        transport.clearWrites()
        return device to transport
    }

    /** Reply to data-processor ADD commands with sequential board-assigned ids. */
    private fun addReplier(firstId: Int = 0): (ByteArray) -> ByteArray? {
        var nextId = firstId
        return { cmd ->
            if (cmd.size >= 2 && (cmd[0].toInt() and 0xFF) == 0x09 && (cmd[1].toInt() and 0xFF) == 0x02) {
                bytes(0x09, 0x02, nextId++)
            } else {
                null
            }
        }
    }

    @Test
    fun `createProcessor sends ADD command and returns board-assigned handle`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReply(transport, addReplier(firstId = 2))

        val handle = device.createProcessor(DataProcessor.Rss(), AccelerometerSignal())
        replies.cancel()

        // Board said id 2; RSS of a 6-byte accel signal outputs 1ch × 2B unsigned.
        assertEquals(2, handle.id)
        assertEquals(1, handle.nChannels)
        assertEquals(2, handle.channelSize)
        assertFalse(handle.isSigned)
        assertArrayEquals(bytes(0x09, 0x02, 0x03, 0x04, 0xFF, 0xA0, 0x07, 0xA5, 0x01), transport.writtenCommands[0])
    }

    @Test
    fun `createProcessor chains via the returned handle`() = runTest {
        val (device, transport) = connectedDevice()
        val replies = backgroundScope.autoReply(transport, addReplier())

        val rss = device.createProcessor(DataProcessor.Rss(), AccelerometerSignal())
        val avg = device.createProcessor(DataProcessor.Average(sampleSize = 4), rss)
        replies.cancel()

        assertEquals(0, rss.id)
        assertEquals(1, avg.id)
        // Second ADD command consumes the first handle as its source (freefall step 2).
        assertArrayEquals(bytes(0x09, 0x02, 0x09, 0x03, 0x00, 0x20, 0x03, 0x05, 0x04), transport.writtenCommands[1])
    }

    @Test
    fun `createProcessor throws when module absent`() = runTest {
        val (device, _) = connectedDevice(presentModules = DEFAULT_PRESENT_MODULES) // no 0x09

        val error = runCatching {
            device.createProcessor(DataProcessor.Rss(), AccelerometerSignal())
        }.exceptionOrNull()

        assertTrue(error is MetaWearException.OperationFailed)
        assertEquals("Operation failed: Data processor module not present on this board", error?.message)
    }

    @Test
    fun `streamProcessor sends both notify enables`() = runTest {
        val (device, transport) = connectedDevice()
        val handle = ProcessorHandle(id = 1, nChannels = 1, channelSize = 4, isSigned = false)

        device.streamProcessor(handle)

        // 1. NOTIFY_ENABLE for this processor id, 2. NOTIFY register subscribe.
        assertArrayEquals(bytes(0x09, 0x07, 0x01, 0x01), transport.writtenCommands[0])
        assertArrayEquals(bytes(0x09, 0x03, 0x01), transport.writtenCommands[1])
    }

    @Test
    fun `streamProcessor filters packets by processor id`() = runTest {
        val (device, transport) = connectedDevice()
        val handle = ProcessorHandle(id = 1, nChannels = 1, channelSize = 4, isSigned = false)

        val stream = device.streamProcessor(handle)

        stream.test {
            // Another processor's packet (id 0) must be filtered out.
            transport.inject(bytes(0x09, 0x03, 0x00, 0x11, 0x00, 0x00, 0x00), Uuids.notify)
            // Our packet (id 1) is delivered verbatim.
            transport.inject(bytes(0x09, 0x03, 0x01, 0x2A, 0x00, 0x00, 0x00), Uuids.notify)

            assertArrayEquals(bytes(0x09, 0x03, 0x01, 0x2A, 0x00, 0x00, 0x00), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stopStreamingProcessor sends notify disable`() = runTest {
        val (device, transport) = connectedDevice()
        val handle = ProcessorHandle(id = 3, nChannels = 1, channelSize = 1, isSigned = false)

        device.stopStreamingProcessor(handle)

        assertArrayEquals(bytes(0x09, 0x07, 0x03, 0x00), transport.writtenCommands[0])
    }

    @Test
    fun `removeProcessor sends remove command`() = runTest {
        val (device, transport) = connectedDevice()
        val handle = ProcessorHandle(id = 2, nChannels = 1, channelSize = 2, isSigned = false)

        device.removeProcessor(handle)

        assertArrayEquals(bytes(0x09, 0x06, 0x02), transport.writtenCommands[0])
    }

    @Test
    fun `removeAllProcessors sends remove-all command`() = runTest {
        val (device, transport) = connectedDevice()

        device.removeAllProcessors()

        assertArrayEquals(bytes(0x09, 0x08), transport.writtenCommands[0])
    }
}
