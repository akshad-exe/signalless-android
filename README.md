# SignalLess for Android

Offline-first, peer-to-peer campus messaging. Messages travel over a local
Bluetooth mesh when there is no network, and fall back to Nostr relays when one
is available. No accounts, no phone numbers, no central servers.

SignalLess is built on the mesh transport, Noise XX encryption, and binary wire
protocol of [BitChat for Android](https://github.com/permissionlesstech/bitchat-android).
See [NOTICE](NOTICE) for attribution and licensing.

## Status

This is a working fork with an Android-only build. It compiles and its unit
suite passes (572 tests, 0 failures), including the protocol golden vectors.

Not yet done:

- **Durable outbox.** Queued messages live in memory, so an unsent message is
  lost if the process dies. Wiring a SQLite outbox is the top priority.
- **Emergency SOS.** No priority-broadcast packet type.
- **Scored transport selection.** Transports are picked by binary
  ready/connected checks rather than link quality.
- **Hardware validation.** No Mesh Lab run has been performed on this fork.
  Upstream BitChat is validated on physical devices; these changes are not.

## Features

- **Dual transport.** Bluetooth LE mesh for offline messaging, Nostr relays for
  internet reach, with automatic fallback between them.
- **Wi-Fi Aware.** Higher-bandwidth local mesh on supported devices.
- **End-to-end encryption.** Noise XX (X25519 + ChaCha20-Poly1305) for private
  messages, with forward secrecy per session.
- **Decentralized mesh.** Automatic peer discovery and multi-hop relay, up to
  7 hops.
- **Media.** Images, audio, and files up to ~9.8 MB, fragmented and relayed over
  the mesh. Slow on BLE; much faster when both peers support Wi-Fi Aware.
- **Live voice.** Push-to-talk bursts (AAC-LC 16 kHz mono). Half-duplex and
  best-effort by design; the voice note sent on release is the reliable path.
  This is walkie-talkie, not a voice call.
- **Location channels.** Geohash-based rooms over Nostr.
- **Tor.** Embedded Arti client for private internet access.
- **Emergency wipe.** Triple-tap clears all local data.
- **Channel chats.** Topic-based group messaging with optional password
  protection (Argon2id + AES-256-GCM).

## Building

Requires JDK 21 and the Android SDK (compileSdk 37, build-tools 37.0.0).

```sh
git clone <this-repo>
cd signalless-android

# Single APK for all ABIs
ANDROID_HOME=$PATH_TO_ANDROID_SDK ./gradlew :app:packageDebug
```

On a memory-constrained machine:

```sh
ANDROID_HOME=$PATH_TO_ANDROID_SDK ./gradlew :app:packageDebug \
  --no-parallel --max-workers=2 \
  -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=768m" \
  -Dkotlin.daemon.jvmargs="-Xmx768m"
```

The repository's `gradle.properties` defaults to a 4 GB Gradle heap with
parallel execution, which is comfortable on a workstation and tight on a laptop.
The flags above cap the Gradle daemon and the separate Kotlin compiler daemon.

Output:

```
app/build/outputs/apk/debug/app-debug.apk
```

Use `:app:assembleDebug` instead if you want per-ABI split APKs; it produces one
APK per architecture plus a universal build.

### Tests

```sh
ANDROID_HOME=$PATH_TO_ANDROID_SDK ./gradlew :app:testDebugUnitTest
```

The first run downloads Robolectric's native runtime and can time out; rerun if
it fails on a download rather than a test.

### Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app requests Bluetooth, location, and notification permissions at runtime.
Location is required by Android for BLE scanning results and cannot be declined
if you want the mesh to work.

## Architecture

```
app/src/main/java/com/signalless/
├─ mesh/         transport core: BLE service, Wi-Fi Aware, relay/TTL/dedup
├─ noise/        Noise XX sessions (southernstorm, MIT)
├─ crypto/       identity keys and signing
├─ protocol/     binary wire format, compression, padding
├─ service/      foreground service, app lifecycle
├─ services/     message routing, conversation state
├─ nostr/        relay transport, NIP-13/17/44
├─ geohash/      location channels
├─ features/     file and voice transfer
└─ ui/           Compose screens and theme
```

`UnifiedMeshService` selects a transport per peer; `MessageRouter` prefers the
mesh and falls back to Nostr; `MeshForegroundService` keeps the mesh alive within
Android's background execution limits.

**BLE has no IP layer.** The protocol is a compact binary packet with TTL,
signing, and fragmentation. Wi-Fi Aware carries the same packets as raw bytes.
That is why WebRTC and other IP-dependent stacks do not fit this transport.

## Differences from upstream BitChat

- Application and display name, icons, and theming
- Application ID and namespace: `com.signalless.app`
- The Wear OS companion module has been removed
- Play Store metadata (`fastlane/`) has been removed
- Protocol internals are unchanged, so wire compatibility with BitChat clients
  is retained

## License

GNU General Public License v3.0. See [LICENSE](LICENSE.md) and [NOTICE](NOTICE).

Note: the upstream README described the project as public domain. That statement
predates BitChat's MIT to GPL-3.0 relicense and is stale; `LICENSE.md` is
authoritative.

Because this is GPL-3.0, distributing a build requires publishing the complete
corresponding source, preserving copyright notices, and stating that the work
was modified. Private use carries no such obligation. This is not legal advice.
