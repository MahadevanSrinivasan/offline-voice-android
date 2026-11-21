package com.offlinevoice.app.audio

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * Thread-safe circular ring buffer for float32 audio samples.
 *
 * @param capacity Maximum number of samples to retain. Default is 80,000 (5 seconds at 16 kHz).
 */
class AudioRingBuffer(val capacity: Int = 80_000) {

    private val buffer = FloatArray(capacity)
    private var writeIndex = 0
    private var totalWritten = 0L
    private val lock = ReentrantLock()

    /**
     * Writes incoming float audio samples into the ring buffer, overwriting the oldest
     * samples when the capacity is exceeded.
     */
    fun write(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
        if (length <= 0) return
        lock.withLock {
            for (i in 0 until length) {
                buffer[writeIndex] = samples[offset + i]
                writeIndex = (writeIndex + 1) % capacity
                totalWritten++
            }
        }
    }

    /**
     * Writes 16-bit PCM shorts normalized to [-1.0f, 1.0f] into the ring buffer.
     */
    fun writePcm16(pcmSamples: ShortArray, offset: Int = 0, length: Int = pcmSamples.size - offset) {
        if (length <= 0) return
        lock.withLock {
            for (i in 0 until length) {
                buffer[writeIndex] = normalizeSample(pcmSamples[offset + i])
                writeIndex = (writeIndex + 1) % capacity
                totalWritten++
            }
        }
    }

    /**
     * Returns the most recent [count] samples without removing them.
     * If fewer samples are available than [count], returns all available samples.
     */
    fun getLatest(count: Int): FloatArray {
        if (count <= 0) return FloatArray(0)
        lock.withLock {
            val available = min(totalWritten, capacity.toLong()).toInt()
            val toRead = min(available, count)
            val result = FloatArray(toRead)
            if (toRead == 0) return result

            // The oldest sample in the requested window starts at (writeIndex - toRead + capacity) % capacity
            var readIndex = (writeIndex - toRead) % capacity
            if (readIndex < 0) readIndex += capacity

            for (i in 0 until toRead) {
                result[i] = buffer[readIndex]
                readIndex = (readIndex + 1) % capacity
            }
            return result
        }
    }

    /**
     * Returns the total number of samples currently stored in the buffer (up to [capacity]).
     */
    fun size(): Int {
        lock.withLock {
            return min(totalWritten, capacity.toLong()).toInt()
        }
    }

    /**
     * Returns the lifetime count of samples written to this buffer.
     */
    fun getTotalWritten(): Long {
        lock.withLock {
            return totalWritten
        }
    }

    /**
     * Clears all samples in the ring buffer.
     */
    fun clear() {
        lock.withLock {
            buffer.fill(0f)
            writeIndex = 0
            totalWritten = 0L
        }
    }

    companion object {
        /**
         * Converts 16-bit PCM short [-32768, 32767] to normalized float32 [-1.0f, 1.0f].
         */
        fun normalizeSample(sample: Short): Float {
            return (sample.toFloat() / 32768.0f).coerceIn(-1.0f, 1.0f)
        }

        /**
         * Normalizes an entire array of 16-bit PCM shorts to float32.
         */
        fun normalizePcm16(pcm: ShortArray, output: FloatArray, length: Int = pcm.size) {
            val count = min(length, min(pcm.size, output.size))
            for (i in 0 until count) {
                output[i] = normalizeSample(pcm[i])
            }
        }
    }
}
