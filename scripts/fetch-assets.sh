#!/usr/bin/env bash
set -euo pipefail

# Determine repository root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

LIBS_DIR="${REPO_ROOT}/app/libs"
ASSETS_DIR="${REPO_ROOT}/app/src/main/assets"
THIRD_PARTY_DIR="${REPO_ROOT}/third_party"

mkdir -p "${LIBS_DIR}"
mkdir -p "${ASSETS_DIR}"
mkdir -p "${THIRD_PARTY_DIR}"

echo "=== 1. Fetching sherpa-onnx AAR (v2.0.2) ==="
SHERPA_AAR="${LIBS_DIR}/sherpa-onnx-2.0.2.aar"
if [ ! -f "${SHERPA_AAR}" ] || [ ! -s "${SHERPA_AAR}" ]; then
  echo "Downloading sherpa-onnx-2.0.2.aar..."
  curl -sSL -o "${SHERPA_AAR}.tmp" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.2/sherpa-onnx-2.0.2.aar"
  mv "${SHERPA_AAR}.tmp" "${SHERPA_AAR}"
  echo "✓ Downloaded sherpa-onnx-2.0.2.aar"
else
  echo "✓ sherpa-onnx-2.0.2.aar already exists."
fi

echo "=== 2. Fetching sherpa-onnx KWS gigaspeech models ==="
ENCODER="${ASSETS_DIR}/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
DECODER="${ASSETS_DIR}/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
JOINER="${ASSETS_DIR}/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
TOKENS="${ASSETS_DIR}/tokens.txt"

if [ ! -f "${ENCODER}" ] || [ ! -f "${DECODER}" ] || [ ! -f "${JOINER}" ] || [ ! -f "${TOKENS}" ]; then
  echo "Downloading sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2..."
  TMP_DIR="$(mktemp -d)"
  curl -sSL -o "${TMP_DIR}/kws.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
  
  tar -xjf "${TMP_DIR}/kws.tar.bz2" -C "${TMP_DIR}"
  KWS_EXTRACTED="${TMP_DIR}/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
  
  cp "${KWS_EXTRACTED}/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "${ENCODER}"
  cp "${KWS_EXTRACTED}/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "${DECODER}"
  cp "${KWS_EXTRACTED}/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "${JOINER}"
  cp "${KWS_EXTRACTED}/tokens.txt" "${TOKENS}"
  
  if [ ! -f "${ASSETS_DIR}/keywords.txt" ]; then
    cp "${KWS_EXTRACTED}/keywords.txt" "${ASSETS_DIR}/keywords.txt"
  fi
  
  rm -rf "${TMP_DIR}"
  echo "✓ Extracted sherpa-onnx KWS int8 models and tokens"
else
  echo "✓ sherpa-onnx KWS models already exist."
fi

# Ensure default keywords.txt exists with label suffixes without spaces
if [ ! -f "${ASSETS_DIR}/keywords.txt" ]; then
  echo "Creating default keywords.txt with @Label suffixes..."
  cat <<'EOF' > "${ASSETS_DIR}/keywords.txt"
▁HE LL O ▁WORLD @Hello_World
▁HE Y ▁S I RI @Hey_Siri
▁A LE X A @Alexa
▁HI ▁GO O G LE @Hi_Google
▁PLAY ▁MU S IC @Play_Music
▁GO ▁HOME @Go_Home
EOF
fi

echo "=== 3. Fetching whisper ggml model (ggml-tiny.en-q5_1.bin) ==="
WHISPER_MODEL="${ASSETS_DIR}/ggml-tiny.en-q5_1.bin"
if [ ! -f "${WHISPER_MODEL}" ] || [ ! -s "${WHISPER_MODEL}" ]; then
  echo "Downloading ggml-tiny.en-q5_1.bin..."
  curl -sSL -o "${WHISPER_MODEL}.tmp" \
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin"
  mv "${WHISPER_MODEL}.tmp" "${WHISPER_MODEL}"
  echo "✓ Downloaded ggml-tiny.en-q5_1.bin"
else
  echo "✓ ggml-tiny.en-q5_1.bin already exists."
fi

echo "=== 4. Fetching whisper.cpp source (v1.9.5) ==="
WHISPER_SRC="${THIRD_PARTY_DIR}/whisper.cpp"
if [ ! -d "${WHISPER_SRC}/.git" ] && [ ! -f "${WHISPER_SRC}/CMakeLists.txt" ]; then
  echo "Cloning whisper.cpp repository at tag v1.9.5..."
  git clone --depth 1 --branch v1.9.5 https://github.com/ggml-org/whisper.cpp.git "${WHISPER_SRC}"
  echo "✓ Cloned whisper.cpp v1.9.5"
else
  echo "✓ whisper.cpp source already exists."
fi

echo "=== All assets and sources successfully fetched! ==="
