package com.offlinevoice.app.whisper

import android.content.res.AssetManager
import android.util.Log

object WhisperBridge {
    private const val TAG = "WhisperBridge"

    init {
        try {
            Log.i(TAG, "Loading native library libwhisper_jni.so...")
            System.loadLibrary("whisper_jni")
            Log.i(TAG, "libwhisper_jni.so loaded successfully! System info: ${getSystemInfo()}")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libwhisper_jni.so", e)
        }
    }

    external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
    external fun initContextFromFile(modelPath: String): Long
    external fun freeContext(contextPtr: Long)
    external fun transcribe(contextPtr: Long, audioData: FloatArray, numThreads: Int): String
    external fun getSystemInfo(): String
}
