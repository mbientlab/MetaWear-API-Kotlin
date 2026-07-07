package com.mbientlab.metawear.protocol

import app.cash.turbine.test
import com.mbientlab.metawear.autoReplyModuleDiscovery
import com.mbientlab.metawear.bytes
import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.transport.MockBleTransport
import com.mbientlab.metawear.transport.Uuids
import com.mbientlab.metawear.transport.WriteType
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Protocol-router behavior: command/response pairing, waiter parking, and
 * notification routing. `runCurrent()` on the virtual scheduler makes the
 * sequencing deterministic instead of fixed sleeps or poll loops.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent
class ProtocolRouterTest {

    private fun TestScope.makePair(): Pair<MockBleTransport, ProtocolRouter> {
        val transport = MockBleTransport()
        val router = ProtocolRouter(transport, backgroundScope)
        router.start()
        runCurrent() // let the routing collector subscribe
        return transport to router
    }

    // ---- Notification routing ----

    @Test
    fun `read response resumes suspended reader`() = runTest {
        val (transport, router) = makePair()

        val response = async { router.read(Module.ACCELEROMETER, 0x03) }
        runCurrent() // waiter parked, read command written

        // Inject the response: module=0x03, register=0x83 (0x03 | 0x80)
        val reply = bytes(0x03, 0x83, 0xAA, 0xBB)
        transport.inject(reply, Uuids.notify)

        assertArrayEquals(reply, response.await())
    }

    @Test
    fun `notification routes to subscriber`() = runTest {
        val (transport, router) = makePair()

        router.subscribe(Module.ACCELEROMETER, 0x04).test {
            val packet = bytes(0x03, 0x04, 0x00, 0x40, 0x00, 0x00, 0x00, 0x20)
            transport.inject(packet, Uuids.notify)
            assertArrayEquals(packet, awaitItem())
        }
    }

    @Test
    fun `unsubscribe finishes stream`() = runTest {
        val (_, router) = makePair()

        val stream = router.subscribe(Module.GYRO, 0x05)
        router.unsubscribe(Module.GYRO, 0x05)

        stream.test { awaitComplete() }
    }

    @Test
    fun `stop fails all parked readers`() = runTest {
        val (_, router) = makePair()

        // Failure is expected — catch inside the async so the child doesn't
        // fail the test scope (async propagates to its parent regardless of await).
        val pending = async { runCatching { router.read(Module.ACCELEROMETER, 0x03) } }
        runCurrent()
        router.stop()

        val error = pending.await().exceptionOrNull()
        assertTrue(error is MetaWearException.OperationFailed)
        assertTrue(error!!.message!!.contains("Protocol layer stopped"))
    }

    @Test
    fun `stop fails all subscriber streams`() = runTest {
        val (_, router) = makePair()

        router.subscribe(Module.BAROMETER, 0x01).test {
            router.stop()
            assertTrue(awaitError() is MetaWearException.OperationFailed)
        }
    }

    @Test
    fun `concurrent readers on same key resolve FIFO`() = runTest {
        val (transport, router) = makePair()

        val first = async { router.read(Module.ACCELEROMETER, 0x00) }
        val second = async { router.read(Module.ACCELEROMETER, 0x00) }
        runCurrent() // both waiters parked (enqueue happens before the write)
        assertEquals(2, transport.writtenCommands.size)

        val reply1 = bytes(0x03, 0x80, 0x01)
        val reply2 = bytes(0x03, 0x80, 0x02)
        transport.inject(reply1, Uuids.notify)
        transport.inject(reply2, Uuids.notify)

        // FIFO: first waiter gets the first reply.
        assertArrayEquals(reply1, first.await())
        assertArrayEquals(reply2, second.await())
    }

    @Test
    fun `read bit stripped for routing, full packet preserved`() = runTest {
        val (transport, router) = makePair()

        val response = async { router.read(Module.ACCELEROMETER, 0x04) }
        runCurrent()

        transport.inject(bytes(0x03, 0x84, 0xFF), Uuids.notify)

        assertEquals(0x84, response.await()[1].toInt() and 0xFF)
    }

    @Test
    fun `read times out with Timeout exception`() = runTest {
        val (_, router) = makePair()

        // No reply ever injected — virtual time skips the 5 s wait instantly.
        val error = runCatching { router.read(Module.ACCELEROMETER, 0x03) }.exceptionOrNull()
        assertTrue(error is MetaWearException.Timeout)
    }

    @Test
    fun `timed-out waiter does not steal a later response`() = runTest {
        val (transport, router) = makePair()

        // First read times out; its waiter must be pruned from the queue.
        val e = runCatching { router.read(Module.ACCELEROMETER, 0x03) }.exceptionOrNull()
        assertTrue(e is MetaWearException.Timeout)

        // A second read on the same key must receive the (late) response.
        val response = async { router.read(Module.ACCELEROMETER, 0x03) }
        runCurrent()
        val reply = bytes(0x03, 0x83, 0x42)
        transport.inject(reply, Uuids.notify)
        assertArrayEquals(reply, response.await())
    }

    @Test
    fun `resubscribe completes the previous stream`() = runTest {
        val (transport, router) = makePair()

        val firstStream = router.subscribe(Module.ACCELEROMETER, 0x04)
        firstStream.test {
            val second = router.subscribe(Module.ACCELEROMETER, 0x04)
            awaitComplete() // first consumer sees a clean end

            second.test {
                val packet = bytes(0x03, 0x04, 0x01)
                transport.inject(packet, Uuids.notify)
                assertArrayEquals(packet, awaitItem())
            }
        }
    }

    // ---- Module discovery ----

    @Test
    fun `discoverModules parses all responses`() = runTest {
        val (transport, router) = makePair()
        val replies = backgroundScope.autoReplyModuleDiscovery(transport)

        val modules = router.discoverModules()
        replies.cancel()

        assertEquals(Module.entries.size, modules.size)
        val acc = modules[Module.ACCELEROMETER]!!
        assertEquals(0x01, acc.implementation)
        assertEquals(0x00, acc.revision)
        assertTrue(acc.isPresent)
    }

    @Test
    fun `moduleInfo impl 0xFF marks absent`() = runTest {
        val (transport, router) = makePair()
        // Only the accelerometer is present.
        val replies = backgroundScope.autoReplyModuleDiscovery(transport, presentModules = setOf(0x03))

        val modules = router.discoverModules()
        replies.cancel()

        assertFalse(modules[Module.GYRO]!!.isPresent)
        assertTrue(modules[Module.ACCELEROMETER]!!.isPresent)
    }

    // ---- Write passthrough ----

    @Test
    fun `write forwards to transport without response`() = runTest {
        val (transport, router) = makePair()

        val cmd = bytes(0x03, 0x01, 0x01)
        router.write(cmd)

        assertEquals(1, transport.writtenData.size)
        assertArrayEquals(cmd, transport.writtenData[0].data)
        assertEquals(Uuids.command, transport.writtenData[0].characteristic)
        assertEquals(WriteType.WITHOUT_RESPONSE, transport.writtenData[0].type)
    }

    @Test
    fun `writeMacro uses write-with-response`() = runTest {
        val (transport, router) = makePair()

        router.writeMacro(bytes(0x0F, 0x02))

        assertEquals(WriteType.WITH_RESPONSE, transport.writtenData.last().type)
    }

    // ---- Unexpected disconnect ----

    @Test
    fun `transport error triggers disconnect handler and fails waiters`() = runTest {
        val (transport, router) = makePair()
        var disconnectError: Throwable? = null
        router.setDisconnectHandler { disconnectError = it }

        val pending = async { runCatching { router.read(Module.ACCELEROMETER, 0x03) } }
        runCurrent()

        transport.simulateDisconnect()
        runCurrent()

        assertTrue(pending.await().exceptionOrNull() is MetaWearException)
        assertTrue(disconnectError is MetaWearException.OperationFailed)
    }
}
