package com.offlinevoice.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Manages audio recording via AudioRecord at 16 kHz mono PCM 16-bit,
 * normalizing samples to float32 [-1.0f, 1.0f] in 100 ms (1,600 samples) chunks.
 */
class AudioCaptureManager(
    private val ringBuffer: AudioRingBuffer,
    val sampleRate: Int = 16_000,
    val chunkDurationMs: Int = 100
) {
    private val TAG = "AudioCaptureManager"

    // 16,000 samples/sec * 0.100s = 1,600 samples per 100 ms chunk
    val chunkSize = (sampleRate * chunkDurationMs) / 1000

    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    /**
     * Listener callback invoked on every 100 ms audio chunk.
     * @param chunk Float array of 1,600 normalized audio samples.
     * @param rms Root-Mean-Square energy of the chunk for voice activity / silence detection.
     */
    var onChunkAvailable: ((chunk: FloatArray, rms: Float) -> Unit)? = null

    fun hasRecordPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(context: Context): Boolean {
        if (isRecording.get()) {
            Log.w(TAG, "Recording already in progress")
            return true
        }

        if (!hasRecordPermission(context)) {
            Log.e(TAG, "RECORD_AUDIO permission is not granted")
            return false
        }

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = max(minBufferSize, chunkSize * 2 * 4) // At least 4 chunks

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate AudioRecord", e)
            return false
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord state is not STATE_INITIALIZED")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord recording", e)
            audioRecord?.release()
            audioRecord = null
            return false
        }

        isRecording.set(true)
        recordThread = thread(name = "AudioRecordWorker", priority = Thread.MAX_PRIORITY) {
            readLoop()
        }

        Log.i(TAG, "Audio recording started successfully at $sampleRate Hz, chunk=$chunkSize samples ($chunkDurationMs ms)")
        return true
    }

    @Synchronized
    fun stop() {
        if (!isRecording.get()) return
        isRecording.set(false)

        try {
            recordThread?.interrupt()
            recordThread?.join(1000)
        } catch (e: Exception) {
            Log.w(TAG, "Interrupted while joining recordThread", e)
        }
        recordThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping/releasing AudioRecord", e)
        }
        audioRecord = null

        Log.i(TAG, "Audio recording stopped")
    }

    fun isCapturing(): Boolean = isRecording.get()

    private fun readLoop() {
        val shortBuffer = ShortArray(chunkSize)
        val floatBuffer = FloatArray(chunkSize)

        while (isRecording.get() && !Thread.currentThread().isInterrupted) {
            var totalRead = 0
            while (totalRead < chunkSize && isRecording.get()) {
                val read = audioRecord?.read(
                    shortBuffer,
                    totalRead,
                    chunkSize - totalRead
                ) ?: AudioRecord.ERROR_INVALID_OPERATION

                if (read < 0) {
                    Log.e(TAG, "AudioRecord read error: $read")
                    break
                }
                totalRead += read
            }

            if (totalRead > 0) {
                // Normalize PCM 16-bit to float32 [-1.0f, 1.0f]
                AudioRingBuffer.normalizePcm16(shortBuffer, floatBuffer, totalRead)

                // Push to thread-safe circular ring buffer
                ringBuffer.write(floatBuffer, 0, totalRead)

                // Calculate RMS energy
                val rms = calculateRms(floatBuffer, totalRead)

                // Notify listeners
                val chunkCopy = floatBuffer.copyOf(totalRead)
                onChunkAvailable?.invoke(chunkCopy, rms)
            }
        }
    }

    companion object {
        fun calculateRms(samples: FloatArray, length: Int = samples.size): Float {
            if (length == 0) return 0f
            var sumSquare = 0.0
            for (i in 0 until length) {
                val s = samples[i]
                sumSquare += (s * s)
            }
            return sqrt(sumSquare / length).toFloat()
        }
    }
}
