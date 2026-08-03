#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="$ROOT/.whispercpp"
VERSION="v1.8.6"

if [[ -f "$TARGET/examples/whisper.android/lib/build.gradle" ]]; then
  echo "whisper.cpp $VERSION is already prepared."
  exit 0
fi

rm -rf "$TARGET"
git clone --depth 1 --branch "$VERSION" https://github.com/ggml-org/whisper.cpp.git "$TARGET"

# The app only ships the two Android ABIs requested for this project.
sed -i "s/'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'/'arm64-v8a', 'armeabi-v7a'/" \
  "$TARGET/examples/whisper.android/lib/build.gradle"

echo "Prepared whisper.cpp $VERSION in $TARGET"
