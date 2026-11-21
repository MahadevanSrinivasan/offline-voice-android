#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"
#include "ggml.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return static_cast<size_t>(AAsset_read(static_cast<AAsset *>(ctx), output, read_size));
}

static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64(static_cast<AAsset *>(ctx)) <= 0;
}

static void asset_close(void *ctx) {
    AAsset_close(static_cast<AAsset *>(ctx));
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_offlinevoice_app_whisper_WhisperBridge_initContextFromAsset(
        JNIEnv *env,
        jobject /* thiz */,
        jobject asset_manager,
        jstring asset_path_str) {
    if (asset_manager == nullptr || asset_path_str == nullptr) {
        LOGE("assetManager or assetPath is null");
        return 0;
    }

    const char *asset_path = env->GetStringUTFChars(asset_path_str, nullptr);
    AAssetManager *mgr = AAssetManager_fromJava(env, asset_manager);
    if (!mgr) {
        LOGE("Failed to get AAssetManager from Java");
        env->ReleaseStringUTFChars(asset_path_str, asset_path);
        return 0;
    }

    AAsset *asset = AAssetManager_open(mgr, asset_path, AASSET_MODE_BUFFER);
    if (!asset) {
        // Fallback to streaming mode if buffer mode fails
        asset = AAssetManager_open(mgr, asset_path, AASSET_MODE_STREAMING);
    }

    if (!asset) {
        LOGE("Failed to open asset '%s'", asset_path);
        env->ReleaseStringUTFChars(asset_path_str, asset_path);
        return 0;
    }

    LOGI("Opened asset '%s' (size=%lld bytes)", asset_path, (long long)AAsset_getLength64(asset));

    whisper_model_loader loader = {};
    loader.context = asset;
    loader.read = &asset_read;
    loader.eof = &asset_is_eof;
    loader.close = &asset_close;

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // strictly offline CPU inference
    struct whisper_context *ctx = whisper_init_with_params(&loader, cparams);

    env->ReleaseStringUTFChars(asset_path_str, asset_path);

    if (!ctx) {
        LOGE("whisper_init_with_params failed for asset");
        return 0;
    }

    LOGI("Whisper context initialized successfully from asset");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jlong JNICALL
Java_com_offlinevoice_app_whisper_WhisperBridge_initContextFromFile(
        JNIEnv *env,
        jobject /* thiz */,
        jstring model_path_str) {
    if (model_path_str == nullptr) {
        return 0;
    }

    const char *model_path = env->GetStringUTFChars(model_path_str, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(model_path, cparams);
    env->ReleaseStringUTFChars(model_path_str, model_path);

    if (!ctx) {
        LOGE("whisper_init_from_file_with_params failed for path");
        return 0;
    }

    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_offlinevoice_app_whisper_WhisperBridge_freeContext(
        JNIEnv * /* env */,
        jobject /* thiz */,
        jlong context_ptr) {
    if (context_ptr != 0) {
        auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
        whisper_free(ctx);
        LOGI("Whisper context freed");
    }
}

JNIEXPORT jstring JNICALL
Java_com_offlinevoice_app_whisper_WhisperBridge_transcribe(
        JNIEnv *env,
        jobject /* thiz */,
        jlong context_ptr,
        jfloatArray audio_data,
        jint num_threads) {
    if (context_ptr == 0 || audio_data == nullptr) {
        return env->NewStringUTF("");
    }

    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    const jsize audio_len = env->GetArrayLength(audio_data);
    jfloat *samples = env->GetFloatArrayElements(audio_data, nullptr);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = "en";
    params.n_threads = (num_threads > 0) ? num_threads : 4;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;

    whisper_reset_timings(ctx);

    LOGI("Running whisper_full on %d samples with %d threads", (int)audio_len, params.n_threads);
    int ret = whisper_full(ctx, params, samples, audio_len);
    env->ReleaseFloatArrayElements(audio_data, samples, JNI_ABORT);

    if (ret != 0) {
        LOGE("whisper_full failed with error code: %d", ret);
        return env->NewStringUTF("");
    }

    std::string full_text;
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char *seg_text = whisper_full_get_segment_text(ctx, i);
        if (seg_text) {
            full_text += seg_text;
        }
    }

    LOGI("Transcription result (%d segments): %s", n_segments, full_text.c_str());
    return env->NewStringUTF(full_text.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_offlinevoice_app_whisper_WhisperBridge_getSystemInfo(
        JNIEnv *env,
        jobject /* thiz */) {
    const char *info = whisper_print_system_info();
    return env->NewStringUTF(info ? info : "Unknown");
}

} // extern "C"
