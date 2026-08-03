# Lyric Video Maker

A native Android lyric-video studio built with Kotlin, Jetpack Compose, and Media3.

## Complete workflow

1. Select an audio file.
2. Extract embedded lyrics and embedded cover artwork automatically.
3. Use the embedded cover, choose another image, or replace it later.
4. Use synchronized embedded lyrics immediately, or import/paste trusted lyrics.
5. Transcribe the audio with word timestamps using the user's own OpenAI API key.
6. Compare the transcription against the trusted lyrics and align every line and word.
7. Preview lyric timing while the audio plays.
8. Render an H.264/AAC MP4 with karaoke highlighting.

## Audio metadata support

The embedded-lyrics parser supports ID3 `USLT`, `SYLT`, lyric `TXXX` frames, FLAC/Vorbis comments, Ogg/Opus comments, MP4/M4A lyric atoms, WAV/RIFF chunks, APEv2, and LRC timestamps. Android's media metadata stack extracts common embedded cover-art formats such as ID3 APIC, FLAC pictures, and MP4 cover atoms.

## Video behavior

- The output aspect ratio follows the selected or embedded image automatically.
- Large images are scaled to an encoder-friendly maximum long side of 1920 pixels while preserving their aspect ratio.
- Odd dimensions are rounded to even values for H.264 compatibility.
- The image is not darkened by default. Background dimming is an explicit optional control.
- Output uses Android Media3 Transformer, H.264 video, and AAC audio.

## Transcription privacy

No API key is bundled in the app or committed to the repository. The entered key is held in the running UI and sent only in the transcription request. Audio is uploaded to the configured transcription API only when the user taps **Transcribe audio**.

## Build

The GitHub Actions workflow runs unit tests and produces `Lyric-Video-Maker-debug.apk` as an artifact.
