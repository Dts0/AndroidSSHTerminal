# SSHTerminal

Android SSH terminal client.

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
- In a connected terminal, the **File transfer** button appears in the top status row immediately to the left of the
  connect/disconnect controls. It opens the SFTP browser for that exact terminal session and is disabled while the
  session is not connected.
- A local mapping listens on the Android device and reaches a destination from the SSH server side. A remote mapping
  listens on the SSH server and reaches a destination from the Android side.
- New mappings should use a loopback listen address unless network exposure is intentional. A non-loopback local bind
  can expose the forwarded service to the device's network; remote exposure additionally depends on the server's
  `AllowTcpForwarding` and `GatewayPorts` policy.
- SFTP channels reuse the authenticated, host-key-verified SSH connection. Files are streamed through Android's Storage
  Access Framework; the app does not request broad storage access.
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

Current workstation reality as of 2026-08-13:

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
- optional launcher icon polish

## Release checklist

- complete all four CI stages in order: unit tests, lint, debug assembly, and release assembly
- verify password auth on real device
- verify private key auth on real device
- verify first-connect host key trust prompt
- verify reconnect after trusting fingerprint
- verify tab completion / backspace / paste / ctrl keys on device
- verify disconnect/reconnect/background behavior
- verify local and remote port mappings, including server-policy rejection and port-conflict isolation
- verify SFTP upload/download with Android file providers and compare source/destination hashes
- verify document-picker return after Activity/Fragment recreation and safe expiry after process recreation
- interrupt an active transfer by disconnecting, reconnect, and verify another transfer can start and complete
- verify disconnect/cancel cleanup leaves no active listeners, SFTP channels, or partial temporary files
- sign release APK/AAB with production keystore
- final icon polish / store assets
