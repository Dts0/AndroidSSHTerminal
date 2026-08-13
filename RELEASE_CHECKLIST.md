# RELEASE_CHECKLIST

## Blocking

- [ ] `./gradlew testDebugUnitTest` passes
- [ ] `./gradlew lint` passes after unit tests
- [ ] `./gradlew assembleDebug` passes after lint
- [ ] `./gradlew assembleRelease` passes after the debug build
- [ ] Real-device verification completed
- [ ] Production signing configured
- [ ] Host key trust prompt verified against a real SSH server
- [ ] Password auth verified
- [ ] Key auth verified
- [ ] Local and remote port mappings verified against a real OpenSSH server
- [ ] SFTP upload/download verified with Android system file providers
- [ ] Regression smoke test completed

## Port mapping

- [ ] Creating and editing a host exposes port mappings under the collapsible Advanced settings section
- [ ] Rules can be added, edited, enabled/disabled, and removed without losing the host editor draft after recreation
- [ ] Local mapping carries bidirectional traffic to a destination reachable from the SSH server
- [ ] Remote mapping carries bidirectional traffic to a destination reachable from the Android device
- [ ] Loopback is the default listen address; the UI warns before a non-loopback bind is saved
- [ ] `AllowTcpForwarding`/`GatewayPorts` rejection and a busy local port fail only the affected rule, not the terminal
- [ ] Enabled rules start once per connection and restart once after an explicit reconnect
- [ ] Saving rule changes during a connection does not hot-apply them and clearly indicates that reconnect is required
- [ ] Disconnect, reconnect, tab close, and host deletion remove listeners and cancel in-flight rule startup
- [ ] Two tabs using the same local listen port remain isolated and report the conflict without disconnecting either shell

## File transfer

- [ ] The terminal status row shows File transfer immediately left of the connect/disconnect controls
- [ ] File transfer opens from the current connected terminal session and is disabled while disconnected
- [ ] Remote home, parent navigation, refresh, Unicode names, spaces, and empty directories work
- [ ] Zero-byte, large, and Unicode-named files upload and download through the system picker
- [ ] Uploaded and downloaded files match the source SHA-256 digest
- [ ] Existing destinations require overwrite confirmation; declining leaves the destination unchanged
- [ ] Cancel, permission denial, disk-full/provider error, and SSH disconnect close streams and SFTP channels
- [ ] Failed/cancelled transfers clean up temporary or partial files where the provider/server permits it
- [ ] Leaving the transfer screen preserves progress; cancelling or disconnecting never interrupts terminal input by itself
- [ ] On a real device, returning from the document picker after Activity/Fragment recreation resumes the intended request
- [ ] On a real device, process recreation expires a lost picker request safely and removes a newly created empty download
- [ ] Disconnect during upload and download, reconnect, then verify the transfer state is not stuck and a new transfer completes

## Data upgrade

- [ ] Execute Room migration 1-to-2 against a real SQLite version-1 database, not only a recorded-SQL unit contract
- [ ] Existing host rows and credentials remain usable after upgrade; the forwarding table, host index, and cascade foreign key validate against the exported version-2 schema

## Quality

- [ ] Launcher icon finalized
- [ ] Monochrome icon added
- [ ] Remaining lint warnings reviewed
- [ ] Version/code finalized

## Security

- [x] Backup disabled
- [x] Cleartext traffic disabled
- [x] Secrets stored encrypted
- [x] Host key verification enabled
- [ ] No shell, mapping, or SFTP channel starts before host-key verification succeeds
- [ ] Non-loopback mapping exposure is documented and tested only on an isolated network
- [ ] Logs contain no credentials, file contents/provider URIs, private network targets, or remote terminal-control payloads (including OSC 52 and DCS)
