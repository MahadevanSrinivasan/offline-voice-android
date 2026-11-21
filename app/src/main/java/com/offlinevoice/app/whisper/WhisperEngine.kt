package com.offlinevoice.app.whisper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

data class TranscriptionResult(
    val text: String,
    val latencyMs: Long,
    val sampleCount: Int,
    val durationSeconds: Float
)

class WhisperEngine(private val context: Context) {

    private val TAG = "WhisperEngine"
    private val mutex = Mutex()
    private var contextPtr: Long = 0L

    val isInitialized: Boolean
        get() = contextPtr != 0L

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            internalInitLocked()
        }
    }

    suspend fun transcribe(audioData: FloatArray, numThreads: Int = getDefaultThreadCount()): TranscriptionResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (contextPtr == 0L) {
                Log.w(TAG, "Context not initialized. Re-initializing...")
                val ok = internalInitLocked()
                if (!ok || contextPtr == 0L) {
                    return@withContext TranscriptionResult(
                        text = "[Error: Model failed to load]",
                        latencyMs = 0L,
                        sampleCount = audioData.size,
                        durationSeconds = audioData.size / 16000f
                    )
                }
            }

            val startTime = System.currentTimeMillis()
            val text = WhisperBridge.transcribe(contextPtr, audioData, numThreads).trim()
            val latencyMs = System.currentTimeMillis() - startTime

            Log.i(TAG, "Transcription complete in ${latencyMs}ms (${audioData.size} samples, text='$text')")

            TranscriptionResult(
                text = text,
                latencyMs = latencyMs,
                sampleCount = audioData.size,
                durationSeconds = audioData.size / 16000f
            )
        }
    }

    private fun internalInitLocked(): Boolean {
        if (contextPtr != 0L) return true

        Log.i(TAG, "Initializing Whisper.cpp context from asset 'ggml-tiny.en-q5_1.bin'...")
        val startTime = System.currentTimeMillis()

        try {
            // Try initializing directly from asset via AAssetManager
            contextPtr = WhisperBridge.initContextFromAsset(context.assets, "ggml-tiny.en-q5_1.bin")
        } catch (e: Exception) {
            Log.w(TAG, "Asset loading threw exception, will try file copy fallback", e)
        }

        // Fallback: If asset reading via AAsset returned 0, copy model to app filesDir and load from file
        if (contextPtr == 0L) {
            Log.w(TAG, "Direct asset open returned 0. Extracting to cache/internal storage fallback...")
            val modelFile = File(context.filesDir, "ggml-tiny.en-q5_1.bin")
            if (!modelFile.exists() || modelFile.length() == 0L) {
                try {
                    context.assets.open("ggml-tiny.en-q5_1.bin").use { input ->
                        FileOutputStream(modelFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "Model extracted to ${modelFile.absolutePath} (${modelFile.length()} bytes)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed copying model to internal storage", e)
                }
            }
            if (modelFile.exists()) {
                contextPtr = WhisperBridge.initContextFromFile(modelFile.absolutePath)
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        return if (contextPtr != 0L) {
            Log.i(TAG, "✓ Whisper.cpp context initialized successfully in ${elapsed}ms (ptr=$contextPtr)")
            true
        } else {
            Log.e(TAG, "✗ Failed to initialize Whisper.cpp context")
            false
        }
    }

    fun release() {
        if (contextPtr != 0L) {
            WhisperBridge.freeContext(contextPtr)
            contextPtr = 0L
            Log.i(TAG, "Whisper context released")
        }
    }

    private fun getDefaultThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return max(2, minOf(4, cores))
    }
}
