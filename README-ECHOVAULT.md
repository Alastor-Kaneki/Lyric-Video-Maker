# EchoVault GitHub Android build

A temporary, isolated build branch for EchoVault. Original Android source is packaged in `source.tar.xz.b64` to transfer it for this build. The `echovault.yml` workflow verifies SHA-256, unpacks source, and compiles the debug APK. The `main` branch of this repository is unchanged.

This offline app does not circumvent Suno access or download quotas. It accepts Suno share links and supports authorized local audio, lyric timing, and TXT/LRC/SRT exports.
