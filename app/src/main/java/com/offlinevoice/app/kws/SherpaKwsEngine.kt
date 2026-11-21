package com.offlinevoice.app.kws

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class WakewordTrigger(
    val keyword: String,
    val displayLabel: String,
    val latencyMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

class SherpaKwsEngine(private val context: Context) {

    private val TAG = "SherpaKwsEngine"
    private val lock = ReentrantLock()

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    private val customKeywordsFile: File
        get() = File(context.filesDir, "keywords.txt")

    init {
        initialize()
    }

    @Synchronized
    fun initialize() {
        lock.withLock {
            release()

            val featConfig = FeatureConfig().apply {
                sampleRate = 16_000
                featureDim = 80
            }

            val transducer = OnlineTransducerModelConfig().apply {
                encoder = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                decoder = "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                joiner = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
            }

            val modelConfig = OnlineModelConfig().apply {
                this.transducer = transducer
                this.tokens = "tokens.txt"
                this.numThreads = 2
                this.provider = "cpu"
                this.debug = false
            }

            // Ensure custom keywords file exists with content from assets if absent
            ensureKeywordsFile()

            val currentKeywords = getActiveKeywordsContent()
            Log.i(TAG, "Initializing KeywordSpotter with active keywords:\n$currentKeywords")

            val kwsConfig = KeywordSpotterConfig().apply {
                this.featConfig = featConfig
                this.modelConfig = modelConfig
                this.maxActivePaths = 4
                this.keywordsScore = 1.0f
                this.keywordsThreshold = 0.25f
                this.numTrailingBlanks = 2
                // AssetManager is non-null below, so keep keywordsFile asset-relative;
                // customKeywordsFile content is passed directly to createStream(currentKeywords).
                this.keywordsFile = "keywords.txt"
            }

            try {
                // Initialize spotter using AssetManager
                spotter = KeywordSpotter(context.assets, kwsConfig)
                // Pass custom keywords to stream creation if available
                stream = spotter?.createStream(currentKeywords)
                Log.i(TAG, "Sherpa-ONNX streaming Zipformer KWS initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize KeywordSpotter", e)
            }
        }
    }

    /**
     * Feeds a 100 ms chunk of float32 samples to the streaming KWS engine.
     * Returns a [WakewordTrigger] if a keyword was spotted, or null otherwise.
     */
    fun processChunk(samples: FloatArray): WakewordTrigger? {
        lock.withLock {
            val spotterInstance = spotter ?: return null
            val streamInstance = stream ?: return null

            val startTime = System.nanoTime()

            streamInstance.acceptWaveform(samples, 16_000)
            while (spotterInstance.isReady(streamInstance)) {
                spotterInstance.decode(streamInstance)
            }

            val result = spotterInstance.getResult(streamInstance)
            val durationMs = (System.nanoTime() - startTime) / 1_000_000

            if (result.keyword.isNotBlank()) {
                val rawKeyword = result.keyword.trim()
                Log.i(TAG, "★ Wakeword spotted: '$rawKeyword' in ${durationMs}ms")

                // Reset stream for next wakeword detection
                spotterInstance.reset(streamInstance)

                val displayLabel = parseDisplayLabel(rawKeyword)
                return WakewordTrigger(
                    keyword = rawKeyword,
                    displayLabel = displayLabel,
                    latencyMs = durationMs
                )
            }

            return null
        }
    }

    /**
     * Reloads the KWS engine with the provided custom keywords text.
     */
    fun reloadKeywords(newKeywordsText: String): Boolean {
        return lock.withLock {
            try {
                // Ensure labels have no spaces
                val sanitized = sanitizeKeywordLabels(newKeywordsText)
                customKeywordsFile.writeText(sanitized)
                Log.i(TAG, "Saved new keywords to ${customKeywordsFile.absolutePath}")

                // Recreate stream with new keywords or reinitialize
                val spotterInstance = spotter
                if (spotterInstance != null) {
                    stream?.release()
                    stream = spotterInstance.createStream(sanitized)
                } else {
                    initialize()
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reload keywords", e)
                false
            }
        }
    }

    /**
     * Resets keywords to the default app/src/main/assets/keywords.txt
     */
    fun resetToDefaultKeywords(): String {
        return lock.withLock {
            val defaultText = try {
                context.assets.open("keywords.txt").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read default keywords from assets", e)
                DEFAULT_FALLBACK_KEYWORDS
            }

            try {
                customKeywordsFile.writeText(defaultText)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write default keywords to file", e)
            }

            val spotterInstance = spotter
            if (spotterInstance != null) {
                stream?.release()
                stream = spotterInstance.createStream(defaultText)
            } else {
                initialize()
            }

            defaultText
        }
    }

    fun getActiveKeywordsContent(): String {
        return if (customKeywordsFile.exists()) {
            customKeywordsFile.readText()
        } else {
            try {
                context.assets.open("keywords.txt").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                DEFAULT_FALLBACK_KEYWORDS
            }
        }
    }

    private fun ensureKeywordsFile() {
        if (!customKeywordsFile.exists()) {
            try {
                val assetContent = context.assets.open("keywords.txt").bufferedReader().use { it.readText() }
                customKeywordsFile.writeText(assetContent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not copy asset keywords.txt, writing default", e)
                customKeywordsFile.writeText(DEFAULT_FALLBACK_KEYWORDS)
            }
        }
    }

    @Synchronized
    fun release() {
        lock.withLock {
            try {
                stream?.release()
                stream = null
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing stream", e)
            }

            try {
                spotter?.release()
                spotter = null
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing spotter", e)
            }
        }
    }

    companion object {
        const val DEFAULT_FALLBACK_KEYWORDS = """▁HE LL O ▁WORLD @Hello_World
▁HE Y ▁S I RI @Hey_Siri
▁A LE X A @Alexa
▁HI ▁GO O G LE @Hi_Google
▁PLAY ▁MU S IC @Play_Music
▁GO ▁HOME @Go_Home"""

        /**
         * Converts label suffix like '@Hello_World' or tokens into a human-readable display string "Hello World".
         */
        fun parseDisplayLabel(raw: String): String {
            val atIndex = raw.lastIndexOf('@')
            if (atIndex != -1 && atIndex < raw.length - 1) {
                val label = raw.substring(atIndex + 1).trim()
                return label.replace('_', ' ')
            }
            // If raw tokens, clean up whitespace character \u2581
            return raw.replace("▁", "").replace(" ", "").trim()
        }

        /**
         * Ensures that any @Label suffix does not contain spaces by replacing internal spaces with underscores.
         */
        fun sanitizeKeywordLabels(text: String): String {
            return text.lines().joinToString("\n") { line ->
                val atIndex = line.indexOf('@')
                if (atIndex != -1) {
                    val tokensPart = line.substring(0, atIndex).trimEnd()
                    val labelPart = line.substring(atIndex + 1).trim()
                    val sanitizedLabel = labelPart.replace(' ', '_')
                    "$tokensPart @$sanitizedLabel"
                } else {
                    line
                }
            }
        }
    }
}
