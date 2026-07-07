package com.mbientlab.metawear.app

import com.mbientlab.metawear.app.core.RingBuffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Fixed-capacity ring buffer behavior. */
class RingBufferTest {

    @Test
    fun `append caps at capacity, oldest overwritten`() {
        val buffer = RingBuffer<Int>(3)
        listOf(1, 2, 3, 4).forEach { buffer.append(it) }
        assertEquals(listOf(2, 3, 4), buffer.elements)
        assertEquals(3, buffer.count)
    }

    @Test
    fun `appendAll appends every element`() {
        val buffer = RingBuffer<Int>(2)
        buffer.appendAll(listOf(1, 2, 3, 4, 5))
        assertEquals(listOf(4, 5), buffer.elements)
    }

    @Test
    fun `removeAll keeps capacity`() {
        val buffer = RingBuffer<Int>(3)
        listOf(1, 2, 3).forEach { buffer.append(it) }
        buffer.removeAll()
        assertEquals(0, buffer.count)
        assertEquals(3, buffer.capacity)
        assertEquals(emptyList<Int>(), buffer.elements)
    }

    @Test
    fun `elements stay ordered across multiple wraps`() {
        val buffer = RingBuffer<Int>(3)
        (1..8).forEach { buffer.append(it) }
        assertEquals(listOf(6, 7, 8), buffer.elements)
    }

    @Test
    fun `last tracks newest after wrap`() {
        val buffer = RingBuffer<Int>(3)
        assertEquals(null, buffer.last)
        (1..5).forEach {
            buffer.append(it)
            assertEquals(it, buffer.last)
        }
    }

    @Test
    fun `removeAll resets wrap state`() {
        val buffer = RingBuffer<Int>(3)
        (1..5).forEach { buffer.append(it) }   // wrapped
        buffer.removeAll()
        (10..12).forEach { buffer.append(it) }
        assertEquals(listOf(10, 11, 12), buffer.elements)
        // Wrap once more after the reset.
        buffer.append(13)
        assertEquals(listOf(11, 12, 13), buffer.elements)
    }
}
