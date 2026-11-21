package com.offlinevoice.app

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.offlinevoice.app.kws.SherpaKwsEngine
import com.offlinevoice.app.kws.WakewordTrigger
import com.offlinevoice.app.whisper.WhisperEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class OfflineVoicePipelineInstrumentedTest {

    companion object {
        private const val TAG = "OfflineVoicePipelineTest"
    }

    private fun loadWav16kMono(context: Context, assetName: String): FloatArray {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        require(bytes.size >= 44) { "WAV file '$assetName' too short (${bytes.size} bytes)" }

        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        val wave = String(bytes, 8, 4, Charsets.US_ASCII)
        require(riff == "RIFF" && wave == "WAVE") {
            "Invalid WAV header in '$assetName': riff='$riff', wave='$wave'"
        }

        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, offset + 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
            val dataOffset = offset + 8

            if (chunkId == "data") {
                val safeSize = minOf(chunkSize, bytes.size - dataOffset)
                val sampleCount = safeSize / 2
                val samples = FloatArray(sampleCount)
                val shortBuf = ByteBuffer.wrap(bytes, dataOffset, sampleCount * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                for (i in 0 until sampleCount) {
                    samples[i] = shortBuf.get(i) / 32768.0f
                }
                return samples
            }

            // RIFF chunks are word-aligned (padded to even byte boundary).
            offset = dataOffset + chunkSize + (chunkSize and 1)
        }

        throw IllegalStateException("WAV 'data' chunk not found in '$assetName'")
    }

    @Test
    fun stage1_sherpaKwsSpotsRealKeywordOnDevice() {
        val targetCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val engine = SherpaKwsEngine(targetCtx)

        try {
            val testKeywords = testCtx.assets.open("test_keywords.txt").bufferedReader().use { it.readText() }
            assertTrue("reloadKeywords(test_keywords.txt) failed", engine.reloadKeywords(testKeywords))

            val samples = loadWav16kMono(testCtx, "0.wav")
            val triggers = mutableListOf<WakewordTrigger>()
            val chunkSize = 1600 // 100 ms at 16 kHz

            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + chunkSize, samples.size)
                val chunk = samples.copyOfRange(offset, end)
                engine.processChunk(chunk)?.let { trigger ->
                    triggers.add(trigger)
                    Log.i(
                        TAG,
                        "Stage 1 detected keyword='${trigger.keyword}' label='${trigger.displayLabel}' latencyMs=${trigger.latencyMs}"
                    )
                }
                offset = end
            }

            assertTrue("Expected at least one KWS trigger from 0.wav", triggers.isNotEmpty())
            assertTrue(
                "Expected trigger containing 'light up', got: ${triggers.map { it.keyword }}",
                triggers.any { it.keyword.lowercase().contains("light up") }
            )
        } finally {
            engine.resetToDefaultKeywords()
            engine.release()
        }
    }

    @Test
    fun stage2_whisperTranscribesJfkOnDevice() {
        val targetCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val engine = WhisperEngine(targetCtx)

        try {
            runBlocking {
                assertTrue("WhisperEngine.initialize() failed", engine.initialize())

                val samples = loadWav16kMono(testCtx, "jfk.wav")
                val result = engine.transcribe(samples)

                Log.i(
                    TAG,
                    "Stage 2 Whisper transcribed in ${result.latencyMs}ms (${result.durationSeconds}s audio): '${result.text}'"
                )

                assertTrue(
                    "Expected transcription to contain 'fellow americans', got: '${result.text}'",
                    result.text.lowercase().contains("fellow americans")
                )
            }
        } finally {
            engine.release()
        }
    }
}
