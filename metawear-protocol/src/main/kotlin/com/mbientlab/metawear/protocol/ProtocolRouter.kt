package com.mbientlab.metawear.protocol

import com.mbientlab.metawear.model.MetaWearException
import com.mbientlab.metawear.model.ModuleInfo
import com.mbientlab.metawear.transport.BleTransport
import com.mbientlab.metawear.transport.Uuids
import com.mbientlab.metawear.transport.WriteType
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Routing key: (module opcode, register with bit 7 stripped). */
internal data class ModuleRegisterKey(val module: Int, val register: Int)

/**
 * Routes all BLE notifications to the correct handlers. Sits between the
 * [BleTransport] and the sensor modules.
 *
 * - Read responses (register bit 7 set) resume the FIFO-queued suspended reader.
 * - Unsolicited notifications go to one-shot notify waiters (I2C/SPI reads) and
 *   to ongoing subscription channels (streaming sensors).
 * - All waiters and streams are failed when [stop] is called or the transport
 *   notification flow terminates.
 *
 * Timeout safety: [withTimeout] cancels the parked continuation directly and
 * `invokeOnCancellation` is atomic with respect to resume — the waiter map is
 * simply pruned on cancellation, so it stays consistent on every exit path.
 */
internal class ProtocolRouter(
    private val transport: BleTransport,
    private val scope: CoroutineScope,
) {
    companion object {
        /** Maximum time to wait for a read response before throwing [MetaWearException.Timeout]. */
        val READ_TIMEOUT: Duration = 5.seconds

        /** Per-key buffer for subscription channels. Sensor notifications arrive at
         * up to ~200 packets/s; if the consumer stalls, shed the oldest samples
         * rather than growing without bound. */
        private const val STREAM_BUFFER = 256
    }

    private class ReadWaiter(val id: Long, val continuation: CancellableContinuation<ByteArray>)
    private class NotifyStream(val generation: Long, val channel: Channel<ByteArray>)

    private val lock = Any()
    private val readWaiters = mutableMapOf<ModuleRegisterKey, ArrayDeque<ReadWaiter>>()
    private val notifyWaiters = mutableMapOf<ModuleRegisterKey, ArrayDeque<ReadWaiter>>()
    private val notifyStreams = mutableMapOf<ModuleRegisterKey, NotifyStream>()
    private var streamGeneration = 0L
    private val nextWaiterId = AtomicLong(0)
    private var routerJob: Job? = null

    /** Called when the BLE connection drops unexpectedly (not via [stop]). */
    @Volatile private var onUnexpectedDisconnect: ((Throwable) -> Unit)? = null

    // ---- Lifecycle ----

    fun start() {
        routerJob = scope.launch {
            try {
                transport.notifications(Uuids.notify).collect { route(it) }
                // Flow ended cleanly — transport disconnected without error.
                terminate(MetaWearException.OperationFailed("BLE connection closed"))
            } catch (e: CancellationException) {
                throw e // stop() cancelled us — waiters already failed there
            } catch (e: Throwable) {
                terminate(e)
            }
        }
    }

    fun stop() {
        routerJob?.cancel()
        routerJob = null
        onUnexpectedDisconnect = null
        failAllWaiters(MetaWearException.OperationFailed("Protocol layer stopped"))
    }

    fun setDisconnectHandler(handler: (Throwable) -> Unit) {
        onUnexpectedDisconnect = handler
    }

    fun clearDisconnectHandler() {
        onUnexpectedDisconnect = null
    }

    // ---- Sending ----

    /** Send a write-without-response command. */
    suspend fun write(data: ByteArray) {
        transport.write(data, Uuids.command, WriteType.WITHOUT_RESPONSE)
    }

    /** Send a macro command (write-with-response). */
    suspend fun writeMacro(data: ByteArray) {
        transport.write(data, Uuids.command, WriteType.WITH_RESPONSE)
    }

    // ---- Reading ----

    /**
     * Send a read command and await the matching bit-7 response notification.
     * Throws [MetaWearException.Timeout] if no response arrives within [READ_TIMEOUT].
     */
    suspend fun read(module: Module, register: Int, vararg payload: Int): ByteArray =
        sendAndAwait(Packet.read(module, register, *payload), readWaiters, key(module.value, register), READ_TIMEOUT)

    /**
     * Write an arbitrary command and await a bit-7 response on `(awaitModule, awaitRegister)`.
     * Pass a shorter [timeout] for probe-style reads where no response is an expected outcome.
     */
    suspend fun writeAndRead(
        command: ByteArray,
        awaitModule: Module,
        awaitRegister: Int,
        timeout: Duration = READ_TIMEOUT,
    ): ByteArray = sendAndAwait(command, readWaiters, key(awaitModule.value, awaitRegister), timeout)

    /**
     * Write a command and await a **plain notification** (bit 7 NOT set) on
     * `(awaitModule, awaitRegister)` — I2C/SPI reads respond this way.
     */
    suspend fun writeAndAwaitNotification(
        command: ByteArray,
        awaitModule: Module,
        awaitRegister: Int,
    ): ByteArray = sendAndAwait(command, notifyWaiters, key(awaitModule.value, awaitRegister), READ_TIMEOUT)

    private suspend fun sendAndAwait(
        command: ByteArray,
        waiters: MutableMap<ModuleRegisterKey, ArrayDeque<ReadWaiter>>,
        key: ModuleRegisterKey,
        timeout: Duration,
    ): ByteArray {
        val id = nextWaiterId.incrementAndGet()
        try {
            return withTimeout(timeout) {
                suspendCancellableCoroutine { cont ->
                    // Park the waiter BEFORE the command goes out, so a fast
                    // response can never race an unregistered continuation.
                    synchronized(lock) { waiters.getOrPut(key) { ArrayDeque() }.add(ReadWaiter(id, cont)) }
                    cont.invokeOnCancellation { removeWaiter(waiters, key, id) }
                    scope.launch {
                        try {
                            write(command)
                        } catch (e: Throwable) {
                            failWaiter(waiters, key, id, e)
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw MetaWearException.Timeout
        }
    }

    private fun removeWaiter(
        waiters: MutableMap<ModuleRegisterKey, ArrayDeque<ReadWaiter>>,
        key: ModuleRegisterKey,
        id: Long,
    ) {
        synchronized(lock) {
            val queue = waiters[key] ?: return
            queue.removeAll { it.id == id }
            if (queue.isEmpty()) waiters.remove(key)
        }
    }

    private fun failWaiter(
        waiters: MutableMap<ModuleRegisterKey, ArrayDeque<ReadWaiter>>,
        key: ModuleRegisterKey,
        id: Long,
        error: Throwable,
    ) {
        val waiter = synchronized(lock) {
            val queue = waiters[key] ?: return
            val idx = queue.indexOfFirst { it.id == id }
            if (idx < 0) return
            val w = queue.removeAt(idx)
            if (queue.isEmpty()) waiters.remove(key)
            w
        }
        waiter.continuation.resumeWithException(error)
    }

    // ---- Subscribing ----

    /**
     * Subscribe to ongoing notifications from a module/register.
     *
     * Re-subscribing to the same key completes the previous flow (its consumer
     * sees a clean end rather than suspending forever). When the consumer stops
     * collecting, the registration is removed so abandoned flows don't linger.
     */
    fun subscribe(module: Module, register: Int): Flow<ByteArray> {
        val k = key(module.value, register)
        val channel = Channel<ByteArray>(capacity = STREAM_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val generation: Long
        val previous: Channel<ByteArray>?
        synchronized(lock) {
            previous = notifyStreams[k]?.channel
            generation = ++streamGeneration
            notifyStreams[k] = NotifyStream(generation, channel)
        }
        previous?.close()
        return channel.receiveAsFlow().onCompletion {
            // Only remove the entry if it still belongs to this flow (a newer
            // subscribe may have replaced it).
            synchronized(lock) {
                if (notifyStreams[k]?.generation == generation) notifyStreams.remove(k)
            }
        }
    }

    fun unsubscribe(module: Module, register: Int) {
        val k = key(module.value, register)
        val channel = synchronized(lock) {
            notifyStreams.remove(k)?.channel
        }
        channel?.close()
    }

    // ---- Module discovery ----

    /**
     * Read every module's info row, one request at a time.
     *
     * Sequential on purpose. Discovery reads are write-without-response, so
     * firing all 21 concurrently puts 21 packets on the air within one
     * connection interval — more than the board's inbound command queue
     * holds, and it silently drops some. Observed on a MetaMotion S: 21 sent
     * in 3 ms, 18–20 answered, the rest never; the missing reply then times
     * out and fails the whole connect. Awaiting each reply before sending the
     * next keeps at most one command in flight (the same request/response
     * discipline as the reference C++ SDK) and costs about a second on
     * connect.
     */
    suspend fun discoverModules(): Map<Module, ModuleInfo> {
        val result = LinkedHashMap<Module, ModuleInfo>(Module.entries.size)
        for (module in Module.entries) {
            val info = readModuleInfo(module)
            result[info.module] = info
        }
        return result
    }

    private suspend fun readModuleInfo(module: Module): ModuleInfo {
        val response = read(module, 0x00)
        // Response layout: [module, 0x80, impl, rev, extra...]
        val impl = if (response.size > 2) response[2].toInt() and 0xFF else 0xFF
        val rev = if (response.size > 3) response[3].toInt() and 0xFF else 0x00
        val extra = if (response.size > 4) response.drop(4).map { it.toInt() and 0xFF } else emptyList()
        return ModuleInfo(module = module, implementation = impl, revision = rev, extra = extra)
    }

    // ---- Routing ----

    private fun route(packet: ByteArray) {
        if (packet.size < 2) return
        val moduleId = packet[0].toInt() and 0xFF
        val registerByte = packet[1].toInt() and 0xFF
        val isRead = (registerByte and 0x80) != 0
        val k = ModuleRegisterKey(moduleId, registerByte and 0x3F)

        if (isRead) {
            popWaiter(readWaiters, k)?.continuation?.resume(packet)
        } else {
            // One-shot notify waiters first (I2C/SPI responses)…
            popWaiter(notifyWaiters, k)?.continuation?.resume(packet)
            // …and ongoing subscription streams (streaming sensors).
            val channel = synchronized(lock) { notifyStreams[k]?.channel }
            channel?.trySend(packet)
        }
    }

    private fun popWaiter(
        waiters: MutableMap<ModuleRegisterKey, ArrayDeque<ReadWaiter>>,
        key: ModuleRegisterKey,
    ): ReadWaiter? = synchronized(lock) {
        val queue = waiters[key] ?: return null
        val waiter = queue.removeFirstOrNull()
        if (queue.isEmpty()) waiters.remove(key)
        waiter
    }

    private fun terminate(error: Throwable) {
        failAllWaiters(error)
        onUnexpectedDisconnect?.invoke(error)
    }

    private fun failAllWaiters(error: Throwable) {
        val allWaiters: List<ReadWaiter>
        val allChannels: List<Channel<ByteArray>>
        synchronized(lock) {
            allWaiters = readWaiters.values.flatten() + notifyWaiters.values.flatten()
            readWaiters.clear()
            notifyWaiters.clear()
            allChannels = notifyStreams.values.map { it.channel }
            notifyStreams.clear()
        }
        allWaiters.forEach { it.continuation.resumeWithException(error) }
        allChannels.forEach { it.close(error) }
    }

    private fun key(module: Int, register: Int) = ModuleRegisterKey(module, register and 0x3F)
}
