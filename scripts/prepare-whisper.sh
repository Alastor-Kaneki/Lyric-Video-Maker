#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="$ROOT/.whispercpp"
VERSION="v1.8.6"

if [[ ! -f "$TARGET/examples/whisper.android/lib/build.gradle" ]]; then
  rm -rf "$TARGET"
  git clone --depth 1 --branch "$VERSION" https://github.com/ggml-org/whisper.cpp.git "$TARGET"
else
  echo "whisper.cpp $VERSION is already prepared."
fi

# The app only ships the two Android ABIs requested for this project.
sed -i "s/'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'/'arm64-v8a', 'armeabi-v7a'/" \
  "$TARGET/examples/whisper.android/lib/build.gradle"

# Debug APKs are installed directly, so compile the native inference engine with
# release-grade optimization instead of CMake's extremely slow -O0 debug flags.
cp "$ROOT/whisper-overrides/CMakeLists.txt" \
  "$TARGET/examples/whisper.android/lib/src/main/jni/whisper/CMakeLists.txt"

# Avoid real-time native logging during inference. Segment timestamps remain
# available through the JNI getters used by the Kotlin wrapper.
sed -i 's/params.print_realtime = true;/params.print_realtime = false;/' \
  "$TARGET/examples/whisper.android/lib/src/main/jni/whisper/jni.c"
sed -i 's/params.print_timestamps = true;/params.print_timestamps = false;/' \
  "$TARGET/examples/whisper.android/lib/src/main/jni/whisper/jni.c"

echo "Prepared optimized whisper.cpp $VERSION in $TARGET"
