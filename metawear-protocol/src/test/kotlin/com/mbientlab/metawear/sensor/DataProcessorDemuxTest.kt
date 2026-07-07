package com.mbientlab.metawear.sensor

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import com.mbientlab.metawear.DEFAULT_PRESENT_MODULES
import com.mbientlab.metawear.MetaWearDevice
import com.mbientlab.metawear.autoReplyModuleDiscovery
import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.makeConnectableTransport
import com.mbientlab.metawear.transport.MockBleTransport
import com.mbientlab.metawear.transport.Uuids
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Proof of the per-id data-processor demux — the Kotlin port of the Swift
// `processorDemuxTask` / `processorContinuations` pair (MetaWearDevice.swift).
// One shared (0x09, 0x03) router subscription fans NOTIFY packets out to
// per-processor-id flows, so multiple processors stream simultaneously;
// streams are torn down on disconnect (cleanly when intentional, with the
// underlying error when unexpected).

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent
class DataProcessorDemuxTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    /** Data-processor module id 0x09 must be reported present. */
    private val modulesWithDataProcessor = DEFAULT_PRESENT_MODULES + 0x09

    private suspend fun TestScope.connectedDevice(): Pair<MetaWearDevice, MockBleTransport> {
        val transport = makeConnectableTransport()
        val device = MetaWearDevice(mac, transport, backgroundScope)
        val discovery = backgroundScope.autoReplyModuleDiscovery(transport, modulesWithDataProcessor)
        device.connect()
        discovery.cancel()
        transport.clearWrites()
        return device to transport
    }

    private fun handle(id: Int) =
        ProcessorHandle(id = id, nChannels = 1, channelSize = 4, isSigned = false)

    /** A NOTIFY packet `[0x09, 0x03, id, payload...]` for processor [id]. */
    private fun packet(id: Int, payload: Int) =
        bytes(0x09, 0x03, id, payload, 0x00, 0x00, 0x00)

    // ---- Concurrent streams (the Swift-parity headline) ----

    @Test
    fun `two simultaneous processor streams receive only their own packets`() = runTest {
        val (device, transport) = connectedDevice()
        val counterFlow = device.streamProcessor(handle(0))
        val averageFlow = device.streamProcessor(handle(1))

        turbineScope {
            val counter = counterFlow.testIn(backgroundScope)
            val average = averageFlow.testIn(backgroundScope)
            runCurrent()

            // Interleaved packets for both processors on the shared register.
            transport.inject(packet(0, 0x11), Uuids.notify)
            transport.inject(packet(1, 0x22), Uuids.notify)
            transport.inject(packet(0, 0x33), Uuids.notify)
            transport.inject(packet(1, 0x44), Uuids.notify)
            runCurrent()

            // Each stream sees exactly its own packets, in order.
            assertArrayEquals(packet(0, 0x11), counter.awaitItem())
            assertArrayEquals(packet(0, 0x33), counter.awaitItem())
            assertArrayEquals(packet(1, 0x22), average.awaitItem())
            assertArrayEquals(packet(1, 0x44), average.awaitItem())

            counter.cancelAndIgnoreRemainingEvents()
            average.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `starting a second processor stream does not terminate the first`() = runTest {
        val (device, transport) = connectedDevice()
        val first = device.streamProcessor(handle(0))

        turbineScope {
            val turbine = first.testIn(backgroundScope)
            runCurrent()

            // Regression guard: the pre-demux implementation re-subscribed the
            // shared (0x09, 0x03) register here, completing the first flow.
            device.streamProcessor(handle(1))
            runCurrent()

            transport.inject(packet(0, 0x2A), Uuids.notify)
            assertArrayEquals(packet(0, 0x2A), turbine.awaitItem())
            turbine.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `demux drops runt and unclaimed-id packets without disturbing streams`() = runTest {
        val (device, transport) = connectedDevice()
        val stream = device.streamProcessor(handle(1))

        stream.test {
            transport.inject(bytes(0x09, 0x03), Uuids.notify) // runt — no id byte
            transport.inject(packet(7, 0x55), Uuids.notify) // id nobody registered
            transport.inject(packet(1, 0x66), Uuids.notify) // ours

            assertArrayEquals(packet(1, 0x66), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `re-subscribing the same processor id completes the previous flow`() = runTest {
        val (device, transport) = connectedDevice()
        val first = device.streamProcessor(handle(2))

        turbineScope {
            val old = first.testIn(backgroundScope)
            runCurrent()

            val replacement = device.streamProcessor(handle(2))
            runCurrent()
            old.awaitComplete()

            replacement.test {
                transport.inject(packet(2, 0x77), Uuids.notify)
                assertArrayEquals(packet(2, 0x77), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    // ---- Per-stream teardown ----

    @Test
    fun `stopStreamingProcessor finishes only that stream`() = runTest {
        val (device, transport) = connectedDevice()
        val stoppedFlow = device.streamProcessor(handle(0))
        val survivingFlow = device.streamProcessor(handle(1))

        turbineScope {
            val stopped = stoppedFlow.testIn(backgroundScope)
            val surviving = survivingFlow.testIn(backgroundScope)
            runCurrent()

            device.stopStreamingProcessor(handle(0))
            runCurrent()
            stopped.awaitComplete()

            // The other processor's stream keeps flowing.
            transport.inject(packet(1, 0x55), Uuids.notify)
            assertArrayEquals(packet(1, 0x55), surviving.awaitItem())
            surviving.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removeProcessor finishes that processor's stream`() = runTest {
        val (device, transport) = connectedDevice()
        val stream = device.streamProcessor(handle(3))

        turbineScope {
            val turbine = stream.testIn(backgroundScope)
            runCurrent()

            device.removeProcessor(handle(3))
            runCurrent()
            turbine.awaitComplete()

            assertArrayEquals(bytes(0x09, 0x06, 0x03), transport.writtenCommands.last())
        }
    }

    @Test
    fun `removeAllProcessors finishes all streams and the demux restarts after`() = runTest {
        val (device, transport) = connectedDevice()
        val firstFlow = device.streamProcessor(handle(0))
        val secondFlow = device.streamProcessor(handle(1))

        turbineScope {
            val first = firstFlow.testIn(backgroundScope)
            val second = secondFlow.testIn(backgroundScope)
            runCurrent()

            device.removeAllProcessors()
            runCurrent()
            first.awaitComplete()
            second.awaitComplete()
        }

        // A fresh stream relaunches the demux and receives data again.
        val relaunched = device.streamProcessor(handle(2))
        relaunched.test {
            transport.inject(packet(2, 0x77), Uuids.notify)
            assertArrayEquals(packet(2, 0x77), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- Disconnect teardown ----

    @Test
    fun `intentional disconnect completes processor streams cleanly`() = runTest {
        val (device, _) = connectedDevice()
        val firstFlow = device.streamProcessor(handle(0))
        val secondFlow = device.streamProcessor(handle(1))

        turbineScope {
            val first = firstFlow.testIn(backgroundScope)
            val second = secondFlow.testIn(backgroundScope)
            runCurrent()

            device.disconnect()
            runCurrent()

            first.awaitComplete()
            second.awaitComplete()
        }
    }

    @Test
    fun `unexpected disconnect fails processor streams with the underlying error`() = runTest {
        val (device, transport) = connectedDevice()
        val stream = device.streamProcessor(handle(0))

        turbineScope {
            val turbine = stream.testIn(backgroundScope)
            runCurrent()

            transport.simulateDisconnect()
            runCurrent()

            val error = turbine.awaitError()
            assertTrue(
                error is MetaWearException.OperationFailed,
                "expected OperationFailed, got $error",
            )
        }
    }

    // ---- Wire compatibility ----

    @Test
    fun `demuxed streams still send one notify-enable pair per processor`() = runTest {
        val (device, transport) = connectedDevice()

        device.streamProcessor(handle(0))
        device.streamProcessor(handle(1))

        // Same command sequence as the Swift SDK: NOTIFY_ENABLE per processor
        // id plus the shared NOTIFY-register subscribe write.
        assertArrayEquals(bytes(0x09, 0x07, 0x00, 0x01), transport.writtenCommands[0])
        assertArrayEquals(bytes(0x09, 0x03, 0x01), transport.writtenCommands[1])
        assertArrayEquals(bytes(0x09, 0x07, 0x01, 0x01), transport.writtenCommands[2])
        assertArrayEquals(bytes(0x09, 0x03, 0x01), transport.writtenCommands[3])
        assertEquals(4, transport.writtenCommands.size)
    }
}
