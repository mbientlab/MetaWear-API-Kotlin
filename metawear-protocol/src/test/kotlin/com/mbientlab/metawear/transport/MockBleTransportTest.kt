package com.mbientlab.metawear.transport

import app.cash.turbine.test
import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.model.MetaWearException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Semantics of the mock transport seam — the contract the protocol layer
 * (Step 3) and downstream app tests rely on.
 */
class MockBleTransportTest {

    private val transport = MockBleTransport()

    // ---- writes ----

    @Test
    fun `write records payload, characteristic, and type in order`() = runTest {
        transport.write(bytes(0x03, 0x01, 0x01), Uuids.command, WriteType.WITHOUT_RESPONSE)
        transport.write(bytes(0x02, 0x01), Uuids.command, WriteType.WITH_RESPONSE)

        assertEquals(2, transport.writtenData.size)
        assertArrayEquals(bytes(0x03, 0x01, 0x01), transport.writtenData[0].data)
        assertEquals(Uuids.command, transport.writtenData[0].characteristic)
        assertEquals(WriteType.WITHOUT_RESPONSE, transport.writtenData[0].type)
        assertEquals(WriteType.WITH_RESPONSE, transport.writtenData[1].type)

        assertArrayEquals(bytes(0x02, 0x01), transport.writtenCommands[1])
    }

    @Test
    fun `clearWrites empties the record`() = runTest {
        transport.write(bytes(0x01), Uuids.command, WriteType.WITHOUT_RESPONSE)
        transport.clearWrites()
        assertTrue(transport.writtenCommands.isEmpty())
    }

    // ---- reads ----

    @Test
    fun `read returns canned response`() = runTest {
        transport.setReadResponse(Uuids.firmwareRevision, "1.7.3".toByteArray())
        assertArrayEquals("1.7.3".toByteArray(), transport.read(Uuids.firmwareRevision))
    }

    @Test
    fun `read without canned response throws OperationFailed`() = runTest {
        val e = runCatching { transport.read(Uuids.modelNumber) }.exceptionOrNull()
        assertTrue(e is MetaWearException.OperationFailed)
        assertTrue(e!!.message!!.contains("No mock response"))
    }

    // ---- notifications ----

    @Test
    fun `inject yields packet to subscriber`() = runTest {
        transport.notifications(Uuids.notify).test {
            transport.inject(bytes(0x03, 0x04, 0x00, 0x40, 0x00, 0x00, 0x00, 0x20), Uuids.notify)
            assertArrayEquals(bytes(0x03, 0x04, 0x00, 0x40, 0x00, 0x00, 0x00, 0x20), awaitItem())
        }
    }

    @Test
    fun `multiple injects preserve FIFO order`() = runTest {
        transport.notifications(Uuids.notify).test {
            transport.inject(bytes(0x01), Uuids.notify)
            transport.inject(bytes(0x02), Uuids.notify)
            transport.inject(bytes(0x03), Uuids.notify)
            assertArrayEquals(bytes(0x01), awaitItem())
            assertArrayEquals(bytes(0x02), awaitItem())
            assertArrayEquals(bytes(0x03), awaitItem())
        }
    }

    @Test
    fun `inject before subscription is dropped, not buffered`() = runTest {
        transport.inject(bytes(0xAA), Uuids.notify)
        transport.notifications(Uuids.notify).test {
            transport.inject(bytes(0xBB), Uuids.notify)
            assertArrayEquals(bytes(0xBB), awaitItem())
        }
    }

    @Test
    fun `inject to a different characteristic is not delivered`() = runTest {
        transport.notifications(Uuids.notify).test {
            transport.inject(bytes(0x01), Uuids.batteryLevel)
            expectNoEvents()
        }
    }

    @Test
    fun `simulateDisconnect fails the stream with OperationFailed`() = runTest {
        transport.notifications(Uuids.notify).test {
            transport.simulateDisconnect()
            val error = awaitError()
            assertTrue(error is MetaWearException.OperationFailed)
            assertTrue(error.message!!.contains("Simulated disconnect"))
        }
    }

    @Test
    fun `simulateDisconnect delivers buffered packets before the error`() = runTest {
        transport.notifications(Uuids.notify).test {
            transport.inject(bytes(0x01), Uuids.notify)
            transport.simulateDisconnect()
            assertArrayEquals(bytes(0x01), awaitItem())
            assertTrue(awaitError() is MetaWearException.OperationFailed)
        }
    }

    @Test
    fun `disconnect completes streams normally`() = runTest {
        transport.notifications(Uuids.notify).test {
            transport.disconnect()
            awaitComplete()
        }
    }

    @Test
    fun `fresh subscription works after disconnect`() = runTest {
        // First subscription torn down by disconnect (reconnect scenario)...
        val first = transport.notifications(Uuids.notify)
        transport.disconnect()
        assertTrue(first.toList().isEmpty())
        // ...a new subscription receives injections again.
        transport.notifications(Uuids.notify).test {
            transport.inject(bytes(0x07), Uuids.notify)
            assertArrayEquals(bytes(0x07), awaitItem())
        }
    }

    // ---- connect / disconnect errors and delay ----

    @Test
    fun `connect throws configured error`() = runTest {
        transport.connectError = MetaWearException.Timeout
        val e = runCatching { transport.connect("AA:BB:CC:DD:EE:FF") }.exceptionOrNull()
        assertTrue(e is MetaWearException.Timeout)
    }

    @Test
    fun `disconnect throws configured error`() = runTest {
        transport.disconnectError = MetaWearException.OperationFailed("nope")
        val e = runCatching { transport.disconnect() }.exceptionOrNull()
        assertTrue(e is MetaWearException.OperationFailed)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // currentTime
    @Test
    fun `connectDelay suspends connect for the configured duration`() = runTest {
        transport.connectDelay = 2.seconds
        val before = currentTime
        transport.connect("AA:BB:CC:DD:EE:FF")
        assertEquals(2000, currentTime - before) // virtual time in runTest
    }

    // ---- rssi / scan ----

    @Test
    fun `readRSSI returns mock value`() = runTest {
        assertEquals(-55, transport.readRSSI())
        transport.mockRssi = -80
        assertEquals(-80, transport.readRSSI())
    }

    @Test
    fun `scan emits nothing and completes`() = runTest {
        assertTrue(transport.scan(listOf(Uuids.service)).toList().isEmpty())
    }
}
