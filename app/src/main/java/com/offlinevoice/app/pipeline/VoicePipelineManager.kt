package com.offlinevoice.app.pipeline

import android.content.Context
import android.util.Log
import com.offlinevoice.app.audio.AudioCaptureManager
import com.offlinevoice.app.audio.AudioRingBuffer
import com.offlinevoice.app.kws.SherpaKwsEngine
import com.offlinevoice.app.kws.WakewordTrigger
import com.offlinevoice.app.whisper.TranscriptionResult
import com.offlinevoice.app.whisper.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class PipelineState {
    IDLE,
    LISTENING_FOR_WAKEWORD,
    RECORDING_UTTERANCE,
    TRANSCRIBING
}

data class UiState(
    val state: PipelineState = PipelineState.IDLE,
    val currentRms: Float = 0f,
    val lastWakeword: String? = null,
    val lastWakewordLabel: String? = null,
    val stage1LatencyMs: Long? = null,
    val isManualTrigger: Boolean = false,
    val lastTranscription: String = "",
    val stage2LatencyMs: Long? = null,
    val utteranceDurationSeconds: Float? = null,
    val keywordsText: String = "",
    val errorMessage: String? = null
)

class VoicePipelineManager(private val context: Context) {

    private val TAG = "VoicePipelineManager"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Thread-safe 5s circular ring buffer (80,000 samples at 16 kHz)
    val ringBuffer = AudioRingBuffer(capacity = 80_000)

    val captureManager = AudioCaptureManager(
        ringBuffer = ringBuffer,
        sampleRate = 16_000,
        chunkDurationMs = 100 // 100 ms chunks = 1600 samples
    )

    val kwsEngine = SherpaKwsEngine(context)
    val whisperEngine = WhisperEngine(context)

    private val _uiState = MutableStateFlow(UiState(keywordsText = kwsEngine.getActiveKeywordsContent()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Endpointing configuration
    val silenceRmsThreshold = 0.015f
    val silenceChunksRequired = 8 // 8 * 100ms = 800 ms of silence
    val minUtteranceChunks = 5    // 5 * 100ms = 500 ms minimum utterance
    val maxUtteranceChunks = 70   // 7.0 seconds maximum utterance

    private val isUtteranceRecording = AtomicBoolean(false)
    private val utteranceChunks = mutableListOf<FloatArray>()
    private var preRollAudio: FloatArray = FloatArray(0)
    private var silentChunkCounter = 0
    private var currentStage1LatencyMs: Long? = null
    private var currentWakewordLabel: String? = null
    private var currentIsManual: Boolean = false

    init {
        // Asynchronously initialize Whisper model
        scope.launch {
            whisperEngine.initialize()
        }

        // Wire incoming 100 ms chunks from microphone
        captureManager.onChunkAvailable = { chunk, rms ->
            handleAudioChunk(chunk, rms)
        }
    }

    fun startListening(): Boolean {
        if (!captureManager.hasRecordPermission(context)) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Microphone permission (RECORD_AUDIO) not granted"
            )
            return false
        }

        val started = captureManager.start(context)
        if (started) {
            _uiState.value = _uiState.value.copy(
                state = PipelineState.LISTENING_FOR_WAKEWORD,
                errorMessage = null
            )
            Log.i(TAG, "Pipeline started listening for wakeword")
        }
        return started
    }

    fun stopListening() {
        captureManager.stop()
        isUtteranceRecording.set(false)
        _uiState.value = _uiState.value.copy(
            state = PipelineState.IDLE,
            currentRms = 0f
        )
        Log.i(TAG, "Pipeline stopped")
    }

    /**
     * Triggered manually via UI button or programmatically.
     */
    fun triggerManualTranscription() {
        if (!captureManager.isCapturing()) {
            val started = startListening()
            if (!started) return
        }

        triggerUtteranceCapture(
            wakeword = "Manual Trigger",
            label = "Manual",
            stage1Latency = 0L,
            isManual = true
        )
    }

    private fun handleAudioChunk(chunk: FloatArray, rms: Float) {
        _uiState.value = _uiState.value.copy(currentRms = rms)

        if (isUtteranceRecording.get()) {
            // Stage 2: Collecting post-trigger utterance
            handleUtteranceChunk(chunk, rms)
        } else {
            // Stage 1: Always-on Wakeword Spotting
            val trigger = kwsEngine.processChunk(chunk)
            if (trigger != null) {
                triggerUtteranceCapture(
                    wakeword = trigger.keyword,
                    label = trigger.displayLabel,
                    stage1Latency = trigger.latencyMs,
                    isManual = false
                )
            }
        }
    }

    private fun triggerUtteranceCapture(
        wakeword: String,
        label: String,
        stage1Latency: Long,
        isManual: Boolean
    ) {
        if (isUtteranceRecording.getAndSet(true)) return

        Log.i(TAG, "Triggering Stage 2 Utterance Capture! (wakeword=$label, stage1Latency=${stage1Latency}ms)")

        // 1. Capture exactly 1s pre-roll (16,000 samples) from 5s circular ring buffer
        preRollAudio = ringBuffer.getLatest(16_000)

        // Reset utterance accumulator
        synchronized(utteranceChunks) {
            utteranceChunks.clear()
        }
        silentChunkCounter = 0
        currentStage1LatencyMs = stage1Latency
        currentWakewordLabel = label
        currentIsManual = isManual

        _uiState.value = _uiState.value.copy(
            state = PipelineState.RECORDING_UTTERANCE,
            lastWakeword = wakeword,
            lastWakewordLabel = label,
            stage1LatencyMs = stage1Latency,
            isManualTrigger = isManual
        )
    }

    private fun handleUtteranceChunk(chunk: FloatArray, rms: Float) {
        var shouldFinish = false

        synchronized(utteranceChunks) {
            utteranceChunks.add(chunk)
            val chunkCount = utteranceChunks.size

            if (rms < silenceRmsThreshold) {
                silentChunkCounter++
            } else {
                silentChunkCounter = 0
            }

            // Endpoint condition: silence timeout reached after minimum recording duration, OR max limit reached
            if (chunkCount >= minUtteranceChunks && silentChunkCounter >= silenceChunksRequired) {
                Log.i(TAG, "Endpoint detected: Silence for ${silentChunkCounter * 100}ms after ${chunkCount * 100}ms total")
                shouldFinish = true
            } else if (chunkCount >= maxUtteranceChunks) {
                Log.i(TAG, "Endpoint detected: Max utterance limit reached (${maxUtteranceChunks * 100}ms)")
                shouldFinish = true
            }
        }

        if (shouldFinish) {
            finishUtteranceAndTranscribe()
        }
    }

    private fun finishUtteranceAndTranscribe() {
        if (!isUtteranceRecording.getAndSet(false)) return

        val chunksCopy = synchronized(utteranceChunks) {
            val totalSamples = utteranceChunks.sumOf { it.size }
            val postAudio = FloatArray(totalSamples)
            var offset = 0
            for (c in utteranceChunks) {
                System.arraycopy(c, 0, postAudio, offset, c.size)
                offset += c.size
            }
            postAudio
        }

        // Combine: 1s Pre-roll + Post-trigger Utterance
        val combinedAudio = FloatArray(preRollAudio.size + chunksCopy.size)
        System.arraycopy(preRollAudio, 0, combinedAudio, 0, preRollAudio.size)
        System.arraycopy(chunksCopy, 0, combinedAudio, preRollAudio.size, chunksCopy.size)

        val durationSec = combinedAudio.size / 16000f
        Log.i(TAG, "Utterance recording complete: pre-roll=${preRollAudio.size} + post=${chunksCopy.size} = ${combinedAudio.size} samples (${durationSec}s)")

        _uiState.value = _uiState.value.copy(
            state = PipelineState.TRANSCRIBING,
            utteranceDurationSeconds = durationSec
        )

        // Run Stage 2 Offline Whisper transcription on background thread
        scope.launch {
            val result: TranscriptionResult = whisperEngine.transcribe(combinedAudio)

            _uiState.value = _uiState.value.copy(
                state = if (captureManager.isCapturing()) PipelineState.LISTENING_FOR_WAKEWORD else PipelineState.IDLE,
                lastTranscription = if (result.text.isBlank()) "(Silence / No speech detected)" else result.text,
                stage2LatencyMs = result.latencyMs,
                utteranceDurationSeconds = result.durationSeconds
            )
        }
    }

    fun reloadKeywords(newKeywordsText: String): Boolean {
        val success = kwsEngine.reloadKeywords(newKeywordsText)
        if (success) {
            _uiState.value = _uiState.value.copy(
                keywordsText = kwsEngine.getActiveKeywordsContent()
            )
        }
        return success
    }

    fun resetKeywords(): String {
        val defaultText = kwsEngine.resetToDefaultKeywords()
        _uiState.value = _uiState.value.copy(
            keywordsText = defaultText
        )
        return defaultText
    }

    fun release() {
        stopListening()
        kwsEngine.release()
        whisperEngine.release()
    }
}
