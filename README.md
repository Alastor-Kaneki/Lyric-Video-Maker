# Lyric Video Maker

Native Android app that turns audio, artwork, and lyrics into a timed lyric video.

## Current milestone: embedded lyrics extraction

The first working milestone reads an audio file and extracts lyrics before transcription is attempted.

Supported metadata paths:

- MP3/ID3v2 `USLT`, `SYLT`, and lyric `TXXX` frames
- FLAC Vorbis comments
- Ogg Vorbis comments and OpusTags
- MP4/M4A `©lyr` and freeform lyric atoms
- WAV/RIFF ID3 and lyric INFO chunks
- APEv2 lyric fields
- LRC timestamps embedded inside any supported text field

The app uses Android's Storage Access Framework, so it does not require broad storage permission. Selected audio is copied to temporary app storage for parsing and deleted immediately afterward. Metadata is never modified.

## Planned pipeline

1. Extract embedded synchronized or unsynchronized lyrics.
2. Accept pasted/provided lyrics when metadata is missing or wrong.
3. Transcribe vocals locally or through a selectable transcription backend.
4. Align the trusted lyrics against transcription timestamps.
5. Add artwork, typography, motion, and lyric animations.
6. Preview and export an MP4 lyric video.

## Build

```bash
gradle testDebugUnitTest assembleDebug
```

GitHub Actions builds a debug APK and uploads it as the `Lyric-Video-Maker-debug` artifact.
