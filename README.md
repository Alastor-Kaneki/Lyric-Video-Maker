# Lyric Video Maker

A native Android lyric-video studio built with Kotlin, Jetpack Compose, Media3, and whisper.cpp.

## Complete workflow

1. Select an audio file.
2. Extract embedded lyrics and embedded cover artwork automatically.
3. Use the embedded cover, choose another image, or replace it later.
4. Use synchronized embedded lyrics immediately, or import/paste trusted lyrics.
5. Download a small Whisper model once inside the app.
6. Transcribe the audio fully on-device with no API key or upload.
7. Compare the transcription against the trusted lyrics and align every line and word.
8. Preview lyric timing while the audio plays.
9. Render an H.264/AAC MP4 with karaoke highlighting.

## Audio metadata support

The embedded-lyrics parser supports ID3 `USLT`, `SYLT`, lyric `TXXX` frames, FLAC/Vorbis comments, Ogg/Opus comments, MP4/M4A lyric atoms, WAV/RIFF chunks, APEv2, and LRC timestamps. Android's media metadata stack extracts common embedded cover-art formats such as ID3 APIC, FLAC pictures, and MP4 cover atoms.

## Offline transcription

- No OpenAI API key is requested or stored.
- Audio is decoded to 16 kHz mono PCM on the device.
- whisper.cpp performs transcription locally.
- Tiny multilingual and Tiny English quantized models are downloadable from the official whisper.cpp model repository.
- The app verifies the downloaded model with SHA-256 before using it.
- Once a model is installed, transcription works offline.

## Video behavior

- The output aspect ratio follows the selected or embedded image automatically.
- Large images are scaled to an encoder-friendly maximum long side of 1920 pixels while preserving their aspect ratio.
- Odd dimensions are rounded to even values for H.264 compatibility.
- The image is not darkened by default. Background dimming is an explicit optional control.
- Output uses Android Media3 Transformer, H.264 video, and AAC audio.

## Build

The native dependency is pinned to whisper.cpp `v1.8.6` and is downloaded into an ignored build directory:

```bash
bash scripts/prepare-whisper.sh
gradle testDebugUnitTest lintDebug assembleDebug
```

The GitHub Actions workflow installs the Android NDK/CMake toolchain, prepares whisper.cpp, runs tests and lint, builds the APK, and uploads `Lyric-Video-Maker-offline-debug` as an artifact.
