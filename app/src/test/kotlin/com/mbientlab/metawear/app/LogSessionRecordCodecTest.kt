package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.core.SensorKey
import com.mbientlab.metawear.app.core.SensorSelection
import com.mbientlab.metawear.app.data.LogSessionRecord
import com.mbientlab.metawear.app.data.LogSessionRecordCodec
import com.mbientlab.metawear.protocol.PolledLoggerHandles
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Pending-session persistence codec round trip. */
class LogSessionRecordCodecTest {

    private val streamed = LogSessionRecord(
        id = "id-1",
        deviceId = "AA:BB:CC:DD:EE:FF",
        selection = SensorSelection(SensorKey.ACCELEROMETER, hz = 100.0, range = 8f),
        startDate = Instant.fromEpochMilliseconds(1_700_000_000_000),
        status = LogSessionRecord.Status.RUNNING,
    )
    private val polled = LogSessionRecord(
        id = "id-2",
        deviceId = "AA:BB:CC:DD:EE:FF",
        selection = SensorSelection(SensorKey.TEMPERATURE).withPollInterval(30_000),
        startDate = Instant.fromEpochMilliseconds(1_700_000_100_000),
        status = LogSessionRecord.Status.STOPPED,
        polledHandles = PolledLoggerHandles(timerID = 2, eventID = 5, loggerIDs = listOf(3, 4)),
    )

    @Test
    fun `round trip preserves every field including polled handles`() {
        val decoded = LogSessionRecordCodec.decode(LogSessionRecordCodec.encode(listOf(streamed, polled)))
        assertEquals(listOf(streamed, polled), decoded)
        assertEquals(PolledLoggerHandles(2, 5, listOf(3, 4)), decoded[1].polledHandles)
        assertEquals(30_000L, decoded[1].selection.effectivePollIntervalMs)
    }

    @Test
    fun `empty string decodes to no records`() {
        assertEquals(emptyList<LogSessionRecord>(), LogSessionRecordCodec.decode(""))
    }

    @Test
    fun `unknown sensor keys are skipped, valid lines survive`() {
        val encoded = LogSessionRecordCodec.encode(listOf(streamed)) +
            "\nid-x\tMAC\tSOME_FUTURE_SENSOR\t1.0\t\t\t0\tRUNNING\t"
        val decoded = LogSessionRecordCodec.decode(encoded)
        assertEquals(listOf(streamed), decoded)
    }

    @Test
    fun `malformed lines are skipped`() {
        val encoded = "garbage line\n\n" + LogSessionRecordCodec.encode(listOf(polled))
        assertEquals(listOf(polled), LogSessionRecordCodec.decode(encoded))
    }

    @Test
    fun `group id and led event ids survive the round trip`() {
        val grouped = streamed.copy(
            id = "id-3",
            groupID = "batch-42",
            ledEventIds = listOf(4, 5),
        )
        val decoded = LogSessionRecordCodec.decode(LogSessionRecordCodec.encode(listOf(grouped, streamed)))
        assertEquals("batch-42", decoded[0].groupID)
        assertEquals(listOf(4, 5), decoded[0].ledEventIds)
        assertEquals(null, decoded[1].groupID)
        assertEquals(emptyList<Int>(), decoded[1].ledEventIds)
    }
}
