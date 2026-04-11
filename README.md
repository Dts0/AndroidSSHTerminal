# SSHTerminal

Android SSH terminal client.

## Current architecture

This project includes:
- encrypted credential storage via Android Keystore
- release minification/shrinking
- backup disabled
- cleartext traffic disabled
- explicit host key trust prompt on first connection
- mature terminal rendering based on vendored Termux components:
  - `termux-terminal-emulator`
  - `termux-terminal-view`

## Build

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew lint
./gradlew testDebugUnitTest
```

## Current status

This repository is at a release-candidate style engineering baseline:
- debug/release builds pass
- app lint passes
- unit tests pass
- terminal rendering no longer relies on a custom TextView-based emulator

Still required before a real public release:
- real-device SSH interaction validation
- production signing
- optional launcher icon polish

## Release checklist

- verify password auth on real device
- verify private key auth on real device
- verify first-connect host key trust prompt
- verify reconnect after trusting fingerprint
- verify tab completion / backspace / paste / ctrl keys on device
- verify disconnect/reconnect/background behavior
- sign release APK/AAB with production keystore
- final icon polish / store assets
