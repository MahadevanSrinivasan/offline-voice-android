package com.offlinevoice.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AudioRingBufferTest {

    @Test
    fun testInitialState() {
        val ring = AudioRingBuffer(capacity = 80_000)
        assertEquals(80_000, ring.capacity)
        assertEquals(0, ring.size())
        assertEquals(0L, ring.getTotalWritten())
        val latest = ring.getLatest(100)
        assertEquals(0, latest.size)
    }

    @Test
    fun testWriteAndReadLatest() {
        val ring = AudioRingBuffer(capacity = 100)
        val data = FloatArray(50) { i -> i.toFloat() }
        ring.write(data)

        assertEquals(50, ring.size())
        assertEquals(50L, ring.getTotalWritten())

        val latest10 = ring.getLatest(10)
        assertEquals(10, latest10.size)
        for (i in 0 until 10) {
            assertEquals(40f + i, latest10[i], 0.0001f)
        }

        val latestAll = ring.getLatest(50)
        assertArrayEquals(data, latestAll, 0.0001f)

        // Requesting more than available should return available
        val latest100 = ring.getLatest(100)
        assertEquals(50, latest100.size)
        assertArrayEquals(data, latest100, 0.0001f)
    }

    @Test
    fun testCircularWraparound() {
        val capacity = 80_000
        val ring = AudioRingBuffer(capacity = capacity)

        // Write 100,000 samples (20,000 past capacity)
        val totalToWrite = 100_000
        val chunk = FloatArray(1000) { i -> (i + 1).toFloat() }
        for (step in 0 until 100) {
            val chunkWithOffset = FloatArray(1000) { i -> (step * 1000 + i).toFloat() }
            ring.write(chunkWithOffset)
        }

        assertEquals(capacity, ring.size())
        assertEquals(totalToWrite.toLong(), ring.getTotalWritten())

        // The latest 16,000 samples (1s at 16kHz) should be samples from 84,000 to 99,999
        val oneSecondPreRoll = ring.getLatest(16_000)
        assertEquals(16_000, oneSecondPreRoll.size)
        assertEquals(84_000f, oneSecondPreRoll[0], 0.0001f)
        assertEquals(99_999f, oneSecondPreRoll[15_999], 0.0001f)
    }

    @Test
    fun testClear() {
        val ring = AudioRingBuffer(capacity = 500)
        ring.write(FloatArray(300) { 1.0f })
        assertEquals(300, ring.size())

        ring.clear()
        assertEquals(0, ring.size())
        assertEquals(0L, ring.getTotalWritten())
        assertEquals(0, ring.getLatest(100).size)
    }

    @Test
    fun testPcm16Normalization() {
        val pcm = shortArrayOf(
            0,
            32767,
            -32768,
            16384,
            -16384
        )
        val normalized = FloatArray(pcm.size)
        AudioRingBuffer.normalizePcm16(pcm, normalized)

        assertEquals(0.0f, normalized[0], 0.001f)
        assertEquals(0.999969f, normalized[1], 0.001f)
        assertEquals(-1.0f, normalized[2], 0.001f)
        assertEquals(0.5f, normalized[3], 0.001f)
        assertEquals(-0.5f, normalized[4], 0.001f)

        val ring = AudioRingBuffer(10)
        ring.writePcm16(pcm)
        assertEquals(5, ring.size())
        val latest = ring.getLatest(5)
        assertArrayEquals(normalized, latest, 0.001f)
    }

    @Test
    fun testConcurrentWriteAndRead() {
        val ring = AudioRingBuffer(capacity = 80_000)
        val writeCount = 500
        val chunkSize = 160 // 10ms at 16kHz
        val running = AtomicBoolean(true)
        val latch = CountDownLatch(2)

        // Writer thread simulating AudioRecord callback
        val writer = thread {
            var counter = 0f
            for (step in 0 until writeCount) {
                val chunk = FloatArray(chunkSize) { counter++ }
                ring.write(chunk)
                Thread.sleep(1)
            }
            running.set(false)
            latch.countDown()
        }

        // Reader thread simulating wakeword / pre-roll consumer
        val reader = thread {
            var readsDone = 0
            while (running.get() || ring.size() > 0) {
                val snapshot = ring.getLatest(1600) // 100ms
                assertTrue(snapshot.size <= 1600)
                readsDone++
                Thread.sleep(2)
                if (!running.get() && readsDone > 20) break
            }
            latch.countDown()
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        assertTrue("Concurrent operations should complete cleanly without deadlock", completed)
        assertEquals((writeCount * chunkSize).toLong(), ring.getTotalWritten())
    }
}
