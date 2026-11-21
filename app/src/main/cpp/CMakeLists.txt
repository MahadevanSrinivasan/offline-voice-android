cmake_minimum_required(VERSION 3.22.1)
project("whisper_jni" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(WHISPER_ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp")

# Enforce build settings as requested
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static libraries" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "Do not build tests" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "Do not build examples" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "Do not build server" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "Disable Native optimizations" FORCE)

# Fire OS 7 / KFMUWI Cortex-A53 portable NEON for armeabi-v7a
if (ANDROID_ABI STREQUAL "armeabi-v7a")
    add_compile_options(-mfpu=neon)
endif()

# Add whisper.cpp source tree
add_subdirectory("${WHISPER_ROOT_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/whisper_build" EXCLUDE_FROM_ALL)

find_library(log-lib log)
find_library(android-lib android)

# Shared JNI library
add_library(whisper_jni SHARED
    whisper_jni.cpp
)

target_include_directories(whisper_jni PRIVATE
    "${WHISPER_ROOT_DIR}/include"
    "${WHISPER_ROOT_DIR}/ggml/include"
)

target_link_libraries(whisper_jni PRIVATE
    whisper
    ${log-lib}
    ${android-lib}
)
