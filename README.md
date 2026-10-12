# SSHTerminal

[English](README.md) | [简体中文](docs/README_zh.md)

Android SSH terminal client with multiple sessions, port forwarding, and SFTP file transfers.

## Interface and languages

- Compact terminal header with session tabs, connection status, and a session actions menu.
- Two rows of shortcut keys, including arrows, PgUp/PgDn, Delete, Enter, and Paste. Direction keys repeat after a
  400 ms hold, then every 50 ms. Screenshots and screen recording are allowed.
- Interface languages: English, Simplified Chinese, Traditional Chinese, and Japanese.
- Open the **⋮ menu on the home screen → Settings → Language** to change the language.
  **Auto (follow system)** is the default: supported system languages are matched in priority order, with English
  as the fallback. A manual choice takes effect immediately and is retained across restarts.
- On Android 13 and later, the in-app choice is shared with Android’s per-app language settings. Language changes
  rebuild the interface without intentionally closing existing SSH sessions or cancelling transfers.
- Host names, file names, server output, and third-party technical error details are not translated.
- Documentation is available in English and Simplified Chinese only.

## Current architecture

This project includes:

- encrypted credential storage via Android Keystore
- release minification/shrinking
- backup disabled
- cleartext traffic disabled
- explicit host key trust prompt on first connection
- local and remote SSH port mappings configured per host and started with the SSH connection
- SFTP upload/download through Android's system file picker
- mature terminal rendering based on vendored Termux components:
  - `termux-terminal-emulator`
  - `termux-terminal-view`

### Port mapping and file-transfer boundaries

- When creating or editing a host, expand **Advanced settings** to add, edit, enable, or remove local and remote
  mappings. Enabled mappings start automatically after that host's SSH connection succeeds. Saving changes does not
  modify an already connected transport; the terminal marks the configuration as pending until an explicit reconnect.
- In a connected terminal, open the **⋮ session actions menu → Files** to browse SFTP for that exact terminal
  session. The menu also contains the connection status and connect/disconnect actions. File transfer is disabled
  while the session is not connected.
- A local mapping listens on the Android device and reaches a destination from the SSH server side. A remote mapping
  listens on the SSH server and reaches a destination from the Android side.
- New mappings should use a loopback listen address unless network exposure is intentional. A non-loopback local bind
  can expose the forwarded service to the device's network; remote exposure additionally depends on the server's
  `AllowTcpForwarding` and `GatewayPorts` policy.
- SFTP channels reuse the authenticated, host-key-verified SSH connection. Files are streamed through Android's Storage
  Access Framework; the app does not request broad storage access.
- Remote symbolic links can be opened: directory links navigate to the canonical target directory, and file links
  download the target contents using the link name as the suggested local filename. Broken, cyclic, inaccessible,
  and special-file targets report an error. Uploading over a symbolic link remains prohibited.
- Port mappings and transfers have the lifetime of the app's SSH session. They are not background services and stop if
  the session disconnects or Android terminates the app process.
- Passwords, private keys, passphrases, file contents, file-provider URIs, and terminal control-sequence payloads must
  never be written to logs.

## Build

This machine uses:

- JDK: `/opt/toolchain/openjdk/jdk-17.0.18+8`
- Android SDK: `/opt/toolchain/android/Sdk`

Before running Gradle locally in a fresh shell, export the complete Android build environment:

```bash
export JAVA_HOME=/opt/toolchain/openjdk/jdk-17.0.18+8
export ANDROID_SDK_ROOT=/opt/toolchain/android/Sdk
export ANDROID_HOME=/opt/toolchain/android/Sdk
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
```

Create or verify the gitignored project-local `local.properties` file after cloning or moving the checkout:

```properties
sdk.dir=/opt/toolchain/android/Sdk
```

Use the root Gradle wrapper and follow the same order as CI:

```bash
./gradlew testDebugUnitTest
./gradlew lint
./gradlew assembleDebug
./gradlew assembleRelease
```

## Current status

Local build environment and validation notes:

- JDK and Android SDK are available on disk
- the active checkout is `/workspace/prj/SSHTerminal`
- stale native build output from another checkout can embed obsolete absolute paths; clear module `build/` directories before
  diagnosing NDK failures after moving the repo
- port mapping and SFTP changes require the full CI sequence below plus the real-device checks; an earlier build result from
  another checkout is not release evidence for the current changes

Still recommended before a real public release:

- run `./gradlew testDebugUnitTest`, `./gradlew lint`, `./gradlew assembleDebug`, then `./gradlew assembleRelease`
- validate the Room 1-to-2 migration against a real SQLite database and confirm existing hosts survive the upgrade
- real-device SSH, port-mapping, and SFTP validation against a real OpenSSH server
- production signing
- real-device language switching on Android versions below and above 13, including active SSH sessions and transfers
- optional launcher icon polish

## Release checklist

- complete all four CI stages in order: unit tests, lint, debug assembly, and release assembly
- verify password auth on real device
- verify private key auth on real device
- verify first-connect host key trust prompt
- verify reconnect after trusting fingerprint
- verify tab completion / backspace / paste / ctrl keys on device
- verify disconnect/reconnect/background behavior
- verify all four interface languages, Auto/system-language changes, and persisted manual selections
- verify language changes preserve sessions, terminal buffers, navigation state, and active transfers
- verify local and remote port mappings, including server-policy rejection and port-conflict isolation
- verify SFTP upload/download with Android file providers and compare source/destination hashes
- verify document-picker return after Activity/Fragment recreation and safe expiry after process recreation
- interrupt an active transfer by disconnecting, reconnect, and verify another transfer can start and complete
- verify disconnect/cancel cleanup leaves no active listeners, SFTP channels, or partial temporary files
- sign release APK/AAB with production keystore
- final icon polish / store assets
