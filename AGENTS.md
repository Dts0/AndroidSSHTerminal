# AGENTS.md

## Build and verification
- This is a Gradle/Android repo. Use the root wrapper, not npm/yarn/pnpm: `./gradlew ...`.
- CI order is defined in `.github/workflows/android-ci.yml`: `testDebugUnitTest` -> `lint` -> `assembleDebug` -> `assembleRelease`.
- Verified module-specific tasks exist, so focused checks can use paths like `./gradlew :app:testDebugUnitTest` or `./gradlew :termux-terminal-emulator:testDebugUnitTest`.
- Gradle 8.4 is pinned in `gradle/wrapper/gradle-wrapper.properties`.

## Environment gotchas
- Build environment is documented in `~/.openclaw/ANDROID_BUILD_ENV_README.md`. Key paths:
  - `JAVA_HOME=/opt/toolchain/openjdk/jdk-17.0.18+8`
  - `ANDROID_SDK_ROOT=/opt/toolchain/android/Sdk`
  - `ANDROID_HOME=/opt/toolchain/android/Sdk`
  - `local.properties` should contain: `sdk.dir=/opt/toolchain/android/Sdk`
- Before running Gradle, export env vars:
  ```bash
  export JAVA_HOME=/opt/toolchain/openjdk/jdk-17.0.18+8
  export ANDROID_SDK_ROOT=/opt/toolchain/android/Sdk
  export ANDROID_HOME=/opt/toolchain/android/Sdk
  export PATH=$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH
  ```
- Stale build caches from a different checkout path (e.g. `/home/node/SSHTerminal`) can cause NDK/native build failures — the native cache embeds absolute paths. Clearing `app/build/` and `termux-terminal-emulator/build/` before building in a new checkout resolves this.
- `local.properties` is `.gitignore`'d and may be stale after repo moves — verify `sdk.dir` points at the correct path.

## Repo structure
- `app/`: Android app module.
- `termux-terminal-emulator/`: vendored Termux emulator library with native code.
- `termux-terminal-view/`: vendored Termux terminal view library depending on the emulator module.

## Real app entrypoints and flow
- Runtime entrypoint is `app/src/main/AndroidManifest.xml` -> `.ui.MainActivity`.
- Navigation starts at `hostListFragment` in `app/src/main/res/navigation/nav_graph.xml`.
- Main user flow is `HostListFragment` -> `HostEditorFragment` / `TerminalFragment`.
- `SSHToolApp` constructs a singleton `HostRepository`; fragments obtain it via `SSHToolApp.instance.hostRepository` rather than DI.
- Terminal behavior is split across:
  - `SSHConnectionManager` singleton for connect/disconnect/reattach
  - `SSHConnection` for JSch session + shell channel
  - `SshTerminalSession` for bridging SSH bytes into the vendored Termux `TerminalSession`

## Persistence and security constraints
- Host metadata is stored in Room (`HostRepository`, `HostDao`, `Host`), but passwords/private keys/passphrases are intentionally **not** stored in the DB.
- Secrets live in `EncryptedSharedPreferences` via `PasswordStore`; do not move secret fields into Room entities.
- Trusted SSH fingerprints are stored in `filesDir/trusted_host_keys.tsv` (`HostKeyTrustStore`). JSch known hosts are stored separately in `filesDir/known_hosts`.
- The first-connect trust prompt is part of the intended flow: `SSHConnection` emits `HostKeyConfirmationRequired`, and `TerminalFragment` handles the dialog.

## Editing conventions that matter here
- Navigation Safe Args is enabled (`androidx.navigation.safeargs.kotlin`); `*FragmentArgs` and `*Directions` are generated from `nav_graph.xml`.
- View Binding is enabled; fragments use binding classes like `FragmentTerminalBinding` instead of `findViewById`.
- Room uses `kotlin-kapt`; generated sources under `app/build/generated/` and anything under `**/build/**` are build outputs, not hand-edited source.

## Testing and release notes
- App unit tests are sparse in `app/src/test`; most existing unit coverage is in `termux-terminal-emulator/src/test` plus `app/src/test/java/com/sshtool/ssh/HostKeyTrustStoreTest.kt`.
- `RELEASE_CHECKLIST.md` and `README.md` both treat real-device SSH validation as required before a real release; do not treat emulator/unit-test success as sufficient release validation.
- `release.keystore` exists in the repo root, but `.gitignore` excludes `*.keystore`; do not modify or commit signing material casually.

## Release signing
- Production keystore: `/opt/toolchain/openjdk/key/release.keystore`
- Password hint: `note.txt` in the same directory
- Signing config reads from `keystore.properties` (gitignored) — create it with:
  ```properties
  storeFile=/opt/toolchain/openjdk/key/release.keystore
  storePassword=<from note.txt>
  keyAlias=release
  keyPassword=<same as storePassword>
  ```
- `assembleRelease` automatically signs with this config
