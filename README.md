# Offline Voice Android

A high-performance 2-stage offline Android voice assistant app built with **Kotlin** and **Jetpack Compose**, featuring:

- **100% Offline & Private:** Zero Google Play Services, zero cloud dependencies, zero external network calls during runtime.
- **Stage 1 (Always-On Streaming Wakeword Spotting):** Powered by `k2-fsa/sherpa-onnx` streaming Zipformer KWS (`sherpa-onnx-2.0.2.aar`) with `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` int8 ONNX models.
- **Dynamic Keywords Configuration:** Configured via `app/src/main/assets/keywords.txt` with a live in-app editor supporting on-the-fly reload and reset.
- **Stage 2 (On-Trigger Transcription):** Integrated with `ggml-org/whisper.cpp` (v1.9.5) compiled via CMake JNI (`libwhisper_jni.so`) with `ggml-tiny.en-q5_1.bin`.
- **Pre-Roll Audio Ring Buffer:** Thread-safe circular audio buffer retaining ~5 seconds (80,000 samples) at 16 kHz mono. When a wakeword is triggered (or test button tapped), exactly 1 second of pre-roll is captured and stitched to the post-wakeword utterance.
- **RMS Silence Endpointing:** Real-time root-mean-square energy tracking to detect natural speech termination.
- **Latency Tracking:** Displays real-time Stage 1 and Stage 2 latency in milliseconds directly in Jetpack Compose.
- **Multi-ABI Support:** Native binaries for `armeabi-v7a` (with portable `-mfpu=neon` for Fire OS 7 / KFMUWI Cortex-A53), `arm64-v8a`, and `x86_64` (emulator testing).

---

## Architecture Overview

```
                      +-----------------------------+
                      |   AudioRecord (16 kHz)     |
                      |   100 ms chunks (PCM-16)    |
                      +--------------+--------------+
                                     |
                                     v
                      +-----------------------------+
                      |   AudioRingBuffer (5s /     |
                      |   80,000 float32 samples)   |
                      +--------------+--------------+
                                     |
            +------------------------+------------------------+
            |                                                 |
            v (streaming chunks)                              |
+------------------------------+                              |
| Stage 1: Sherpa-ONNX KWS     |                              |
| Zipformer gigaspeech int8    |                              |
+--------------+---------------+                              |
               |                                              |
               | (Wakeword Trigger /                          |
               |  Manual Test Button)                         |
               v                                              |
+------------------------------------+                        |
| Utterance Capture & RMS Endpointing|<-----------------------+
| (Captures 1s Pre-roll from Ring    |   (1s Pre-roll + Post-utterance)
|  Buffer + Post-trigger speech)     |
+--------------+---------------------+
               |
               v
+------------------------------------+
| Stage 2: Whisper.cpp JNI           |
| (ggml-tiny.en-q5_1.bin)            |
+--------------+---------------------+
               |
               v
+------------------------------------+
| Jetpack Compose UI                 |
| (Wakeword, Text, Latencies in ms)  |
+------------------------------------+
```

---

## Technical Specifications

- **OS / Toolchain:** minSdk 26, targetSdk/compileSdk 35, Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21, NDK 27.2.12479018, CMake 3.22.1.
- **Native JNI:** `BUILD_SHARED_LIBS=OFF`, `GGML_OPENMP=OFF`, `GGML_NATIVE=OFF`, `ANDROID_STL=c++_shared`, `pickFirsts` for `libc++_shared.so`.
- **Packaging:** `noCompress` for `bin`, `onnx`, `txt` to enable direct zero-copy memory mapping.
- **Keywords Format:** Uses BPE/SentencePiece tokens (` ` / `\u2581`) with `@Label` suffixes without spaces (e.g. `@Hello_World`, `@Hey_Siri`, `@Alexa`).

---

## Building Locally

### 1. Fetch Assets and Models
Run the idempotent asset fetching script:
```bash
./scripts/fetch-assets.sh
```
This fetches:
- `sherpa-onnx-2.0.2.aar` into `app/libs/`
- Zipformer KWS int8 models (`encoder`, `decoder`, `joiner`, `tokens.txt`) into `app/src/main/assets/`
- `ggml-tiny.en-q5_1.bin` into `app/src/main/assets/`
- Clones `whisper.cpp` v1.9.5 into `third_party/whisper.cpp`

### 2. Run Unit Tests
```bash
./gradlew testDebugUnitTest
```

### 3. Build Release APK
```bash
./gradlew assembleRelease
```
The signed APK will be generated at `app/build/outputs/apk/release/app-release.apk`.

---

## CI / CD Workflow

The GitHub Actions workflow (`.github/workflows/build-android.yml`) automatically:
1. Sets up JDK 21, Android SDK, NDK 27.2.12479018, and CMake 3.22.1.
2. Runs `./scripts/fetch-assets.sh` to download models and dependencies.
3. Configures release signing using `KEYSTORE_BASE64` secrets (or creates an ephemeral CI release keystore fallback).
4. Executes `./gradlew testDebugUnitTest`.
5. Compiles and signs `./gradlew assembleRelease`.
6. Verifies the APK using `apksigner verify --verbose`.
7. Uploads the release artifact via `actions/upload-artifact@v4`.
8. Publishes a GitHub Prerelease on tags matching `v*`.

---

## License

Apache License 2.0.
