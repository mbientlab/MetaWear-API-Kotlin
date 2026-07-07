package com.mbientlab.metawear.app.core

/**
 * Fixed-capacity circular buffer.
 *
 * Appends are O(1): once full, the oldest element is overwritten and the head
 * index wraps. [elements] reconstructs insertion order (O(n) when wrapped) —
 * it is called once per UI tick from the throttle loop, never per sample.
 *
 * Not thread-safe by itself; [com.mbientlab.metawear.app.vm.Channel] guards
 * concurrent ingest/snapshot access with its own lock.
 */
class RingBuffer<T>(val capacity: Int) {

    init {
        require(capacity > 0) { "RingBuffer capacity must be positive" }
    }

    private val storage = ArrayList<T>(capacity)

    /** Index of the oldest element once the buffer has wrapped. */
    private var head = 0

    /** Number of elements currently stored (≤ [capacity]). */
    val count: Int get() = storage.size

    /** The most recently appended element, or `null` when empty. */
    val last: T?
        get() = when {
            storage.isEmpty() -> null
            storage.size < capacity -> storage.last()
            else -> storage[(head - 1 + capacity) % capacity]
        }

    /** All elements in insertion order (oldest first). */
    val elements: List<T>
        get() = if (storage.size < capacity || head == 0) {
            storage.toList()
        } else {
            storage.subList(head, storage.size) + storage.subList(0, head)
        }

    /** Append one element, overwriting the oldest when at capacity. */
    fun append(element: T) {
        if (storage.size < capacity) {
            storage.add(element)
        } else {
            storage[head] = element
            head = (head + 1) % capacity
        }
    }

    /** Append every element of [newElements] in order. */
    fun appendAll(newElements: Iterable<T>) {
        for (e in newElements) append(e)
    }

    /** Remove all elements, preserving capacity and resetting wrap state. */
    fun removeAll() {
        storage.clear()
        head = 0
    }
}
