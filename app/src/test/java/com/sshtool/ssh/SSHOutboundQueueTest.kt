package com.sshtool.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SSHOutboundQueueTest {

    @Test
    fun tenThousandFramesFromConcurrentProducersStayWholeAndFifo() = runBlocking {
        val queue = SSHOutboundQueue()
        val output = RecordingOutputStream()
        val writer = launch(Dispatchers.Default) { queue.drainTo(output) }
        val expected = mutableListOf<ByteArray>()
        val submissionLock = Any()
        var sequence = 0
        val executor = Executors.newFixedThreadPool(PRODUCERS)

        try {
            val tasks = (0 until PRODUCERS).map { producer ->
                Callable {
                    repeat(FRAMES_PER_PRODUCER) {
                        synchronized(submissionLock) {
                            val frame = frame(sequence++, producer)
                            assertTrue(queue.enqueue(frame, 0, frame.size))
                            expected += frame
                        }
                    }
                }
            }
            executor.invokeAll(tasks).forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        queue.close()
        writer.join()

        assertEquals(TOTAL_FRAMES, output.frames.size)
        assertEquals(TOTAL_FRAMES, output.flushCount)
        expected.zip(output.frames).forEachIndexed { index, (queued, written) ->
            assertArrayEquals("frame $index was reordered or interleaved", queued, written)
        }
    }

    @Test
    fun enqueueCopiesOnlyRequestedSliceBeforeCallerCanMutateIt() = runBlocking {
        val queue = SSHOutboundQueue()
        val source = byteArrayOf(99, 1, 2, 3, 88)
        assertTrue(queue.enqueue(source, 1, 3))
        source.fill(0)
        queue.close()

        val output = RecordingOutputStream()
        queue.drainTo(output)

        assertEquals(1, output.frames.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.frames.single())
    }

    @Test
    fun slowWriterKeepsPendingBytesBoundedAndCloseRejectsNewFrames() = runBlocking {
        val queue = SSHOutboundQueue(maxPendingBytes = 10, maxWriteChunkBytes = 3)
        val output = BlockingOutputStream()

        // A logical write larger than total capacity is rejected before any
        // chunk enters the queue.
        assertFalse(queue.enqueue(ByteArray(11), 0, 11))
        assertTrue(queue.enqueue(byteArrayOf(1, 2, 3, 4, 5, 6), 0, 6))
        assertTrue(queue.enqueue(byteArrayOf(7, 8, 9, 10), 0, 4))
        val writer = launch(Dispatchers.Default) { queue.drainTo(output) }
        assertTrue(output.firstWriteStarted.await(5, TimeUnit.SECONDS))

        // The first frame is still pending while OutputStream.write blocks.
        // Capacity therefore cannot be reclaimed early by merely dequeuing it.
        assertFalse(queue.enqueue(byteArrayOf(11), 0, 1))
        queue.close()
        assertFalse(queue.enqueue(byteArrayOf(12), 0, 1))

        output.releaseFirstWrite.countDown()
        writer.join()
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
            concatenate(output.frames)
        )
    }

    @Test
    fun unicodeLogicalWriteOver64KiBIsAcceptedWholeThenSafelyChunked() = runBlocking {
        val queue = SSHOutboundQueue()
        val payload = "终端🙂e\u0301".repeat(12_000).toByteArray(Charsets.UTF_8)
        assertTrue(payload.size > 64 * 1024)
        assertTrue(payload.size < 512 * 1024)
        assertTrue(queue.enqueue(payload, 0, payload.size))
        queue.close()

        val output = RecordingOutputStream()
        queue.drainTo(output)

        assertTrue(output.frames.size > 1)
        assertTrue(output.frames.all { it.size <= 64 * 1024 })
        assertArrayEquals(payload, concatenate(output.frames))
    }

    @Test
    fun streamFailureIsPropagatedAndPermanentlyClosesAdmission() {
        val queue = SSHOutboundQueue(maxPendingBytes = 32, maxWriteChunkBytes = 4)
        val payload = ByteArray(12) { it.toByte() }
        assertTrue(queue.enqueue(payload, 0, payload.size))

        assertThrows(IOException::class.java) {
            runBlocking { queue.drainTo(FailingOutputStream()) }
        }

        assertFalse(queue.enqueue(byteArrayOf(1), 0, 1))
        queue.close() // Idempotent after the failure path.
    }

    private fun concatenate(parts: List<ByteArray>): ByteArray {
        val result = ByteArray(parts.sumOf { it.size })
        var offset = 0
        parts.forEach {
            it.copyInto(result, offset)
            offset += it.size
        }
        return result
    }

    private fun frame(sequence: Int, producer: Int): ByteArray =
        ByteBuffer.allocate(FRAME_SIZE)
            .putInt(FRAME_MAGIC)
            .putInt(sequence)
            .putInt(producer)
            .putInt(sequence xor producer xor FRAME_MAGIC)
            .array()

    private class RecordingOutputStream : OutputStream() {
        val frames = mutableListOf<ByteArray>()
        var flushCount = 0
            private set

        override fun write(value: Int) {
            throw AssertionError("writer must preserve frame writes")
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            synchronized(this) {
                frames += buffer.copyOfRange(offset, offset + length)
            }
        }

        override fun flush() {
            synchronized(this) { flushCount++ }
        }
    }

    private class BlockingOutputStream : OutputStream() {
        val firstWriteStarted = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val frames = mutableListOf<ByteArray>()
        private var first = true

        override fun write(value: Int) {
            throw AssertionError("writer must preserve frame writes")
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (first) {
                first = false
                firstWriteStarted.countDown()
                check(releaseFirstWrite.await(5, TimeUnit.SECONDS)) { "test writer was not released" }
            }
            synchronized(this) {
                frames += buffer.copyOfRange(offset, offset + length)
            }
        }
    }

    private class FailingOutputStream : OutputStream() {
        private var writes = 0

        override fun write(value: Int) {
            throw AssertionError("writer must preserve frame writes")
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (writes++ > 0) throw IOException("synthetic blocked transport failure")
        }
    }

    private companion object {
        const val PRODUCERS = 10
        const val FRAMES_PER_PRODUCER = 1_000
        const val TOTAL_FRAMES = PRODUCERS * FRAMES_PER_PRODUCER
        const val FRAME_SIZE = 16
        const val FRAME_MAGIC = 0x53534831
    }
}
