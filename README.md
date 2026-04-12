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

This machine uses:
- JDK: `/opt/toolchain/openjdk/jdk-17.0.18+8`
- Android SDK: `/opt/toolchain/android/Sdk`

Before running Gradle locally in a fresh shell, export Java explicitly:

```bash
export JAVA_HOME=/opt/toolchain/openjdk/jdk-17.0.18+8
export PATH="$JAVA_HOME/bin:$PATH"
```

Project-local Android SDK path is already configured in `local.properties`:

```properties
sdk.dir=/opt/toolchain/android/Sdk
```

Then run:

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew lint
./gradlew testDebugUnitTest
```

## Current status

Current workstation reality as of 2026-04-12:
- JDK and Android SDK are available on disk
- the repo builds successfully again from its current location `/home/node/prj/SSHTerminal`
- verified successfully on this machine:
  - `./gradlew assembleDebug`
  - `./gradlew testDebugUnitTest`
- a previous failure was caused by stale native build cache/generated state still pointing at the old repo path `/home/node/SSHTerminal`; clearing build caches resolved the NDK path issue
- a previous app compile failure was caused by `TerminalFragment.kt` using `isChecked` on a plain `AppCompatButton`; that has been replaced with normal button-state handling

Still recommended before a real public release:
- re-run `./gradlew assembleRelease`
- re-run `./gradlew lint`
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
