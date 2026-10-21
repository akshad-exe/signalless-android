# SignalLess desktop relay

`relay-desktop` is an always-on Windows mesh peer for SignalLess. It advertises
the same BLE service as a phone, accepts phone connections, and forwards packets
with the same TTL, dedup, fragmentation, and identity rules as the Android app.

It is never packaged into the Android APK. The Windows BLE peripheral role lives
in the separate `relay-ble-helper` .NET process.

## What the relay does not implement

The relay is a network appliance, not a second phone UI. It deliberately has no
Compose UI, Nostr, geohash, media, Tor, voice, or file transfer surfaces. It does
retain the portable `FileSharingManager` type through `protocol-core` because the
core module is shared with the app; no relay CLI path invokes it.

## Architecture

```
phones ── BLE ──> relay-ble-helper (WinRT GATT server, advertising)
                         │ NDJSON over stdin/stdout, opaque bytes only
                         ▼
                  relay-desktop (Kotlin/JVM)
                  ├─ RelayIdentity + signed ANNOUNCE
                  ├─ RelayEngine: admission, TTL, dedup
                  ├─ fragment reassembly and per-link fragmentation
                  ├─ bounded store-and-forward
                  └─ protocol-core: packet codec, identity model
```

The phone decides whether it needs a relay and initiates the connection. The
relay only advertises and accepts. It never scans for phones and never chooses a
phone to connect to.

### Why a separate helper

Kable's JVM backend (btleplug) is central-only and cannot host a GATT server.
Windows exposes the peripheral role through WinRT `GattServiceProvider`, so the
helper is a small C#/.NET console process. It is not part of the Gradle build and
must not be copied into an APK.

## Identity and ANNOUNCE

The relay creates two persistent key pairs on first run:

- X25519 Noise static key (the mesh identity).
- Ed25519 signing key (the announcement signature).

The peer ID is the first eight bytes of SHA-256 over the raw X25519 public key,
rendered as 16 lowercase hex characters. This is exactly how phones derive a peer
ID. The relay does not choose a vanity ID.

`IdentityAnnouncement` carries the nickname, Noise public key, Ed25519 public key,
and capabilities. The relay advertises `PeerCapabilities.NONE` because it is not
a phone feature endpoint. The packet is signed over the canonical signing bytes
(fixed TTL, no signature) and sent to every link on connect and periodically
afterwards. Phones therefore learn the relay through the existing People flow.

The identity files live under the per-user application data directory by
default and can be moved with `--identity-dir`. A partial or corrupt identity is
an error; use `--reset-identity` to explicitly rotate it. On POSIX the directory
is `0700` and files are `0600`. On Windows the per-user application data boundary
is used because the unpackaged build does not install an ACL.

## Relay rules

For each decoded packet from a link:

1. Reject packets claiming the relay's own sender ID.
2. Bind the link to the sender peer ID. This is how unicast routes are learned.
3. If an identity is configured, verify inbound `ANNOUNCE` signatures and
   drop invalid announcements.
4. Drop packets addressed to the relay itself.
5. Drop TTL 0 packets.
6. For `FRAGMENT`, deduplicate the fragment, reassemble the original packet, and
   then apply the normal packet rules once. Fragments are not forwarded
   individually.
7. Deduplicate complete packets by the portable packet ID.
8. Decrement TTL once.
9. Broadcast to every other link; unicast to every link currently bound to the
   recipient.
10. If a unicast recipient has no link, store the packet in a bounded cache and
    flush it when that peer binds.

Every outbound write is checked against the target link's reported maximum
notification size. A packet that does not fit is fragmented to that exact limit,
with every encoded fragment verified before it is queued.

### Store-and-forward limits

The default cache is 256 packets with a five-minute retention window. It is a
reconnect aid, not a durable message queue. A relay restart loses the cache.

### Link admission

The Kotlin engine accepts at most `--peers` links. The Windows helper also caps
its own local links (`--max-links`, 1-7) because a single Windows radio cannot
usefully host more. Links beyond the cap are logged and never used for routing.

## Scale: what "50 phones" means

Windows BLE peripheral mode is not a 50-concurrent-connection product. A single
adapter has a hard host limit of roughly seven links and a practical limit of
three or four active BLE connections. The relay is designed to extend a mesh of
about 50 phones in total: phones keep their own phone-to-phone links, and the
relay is one additional always-on peer that bridges nearby clusters.

If the requirement is 50 simultaneous direct connections to one relay, BLE is the
wrong transport. That needs a Wi-Fi TCP relay or a dedicated ESP32-class BLE
gateway. The current implementation does not claim 50 concurrent BLE links.

## Helper NDJSON protocol

The Kotlin core starts the helper with `--ipc`. Standard output is NDJSON only;
diagnostics go to standard error.

### Helper to Kotlin

| `type` | Fields | Meaning |
|---|---|---|
| `ready` | `platform`, `maxLinks`, `serviceDataSupported`, `serviceDataEnabled`, `api` | GATT service and advertising are up. |
| `advertising` | `state`, `serviceData`, `detail` | `started`, `degraded`, `aborted`, or `stopped`. |
| `link_up` | `link`, `mtu`, `maxNotification` | A phone subscribed; the link ID is opaque. |
| `link_down` | `link`, `reason` | The subscription ended. |
| `rx` | `link`, `data` (base64) | One characteristic write. |
| `tx_result` | `link`, `seq`, `ok`, optional `error` | Result of a `tx` command. |
| `error` | `code`, `message` | Protocol or helper failure. |

### Kotlin to helper

| `type` | Fields | Meaning |
|---|---|---|
| `start` | none | Compatibility no-op; advertising already started. |
| `tx` | `link`, `seq`, `data` (base64) | Queue one notification. |
| `stop` | none | Stop advertising; keep the process alive. |
| `shutdown` | none | Exit cleanly. |

The helper never parses `SignalLessPacket`, derives peer IDs, verifies signatures,
decides TTL or dedup, reads arbitrary files, opens a network listener, or logs
payload bytes, keys, MAC addresses, device names, or user nicknames. Link IDs are
opaque (`l1`, `l2`, ...) and the session key is a one-way hash held only in the
helper process.

## CLI

```text
relay-desktop [options]

--peers <n>                 Requested links (1-64; Windows clamps to 7)
--ttl <n>                   Relay TTL ceiling (1-255, default 7)
--transport <mode>          auto, helper, or fake
--helper <path>             BLE helper executable
--identity-dir <path>       Persistent identity directory
--reset-identity            Explicitly rotate the relay identity
--nickname <name>           Announcement nickname (max 15 characters)
--announce-interval <sec>   Refresh interval (1-3600)
--advertise-service-data    Experimental 8-byte peer-ID service data
--log-file <path>           Append logs to a file
-h, --help                  Help
-V, --version               Version
```

`auto` selects the helper only on Windows, or when `--helper` or
`SIGNALLESS_RELAY_HELPER` names one. It never silently falls back to the fake
transport. `fake` is for protocol tests only.

### Windows helper discovery

When `--helper` is omitted in `auto` or `helper` mode, the core looks next to the
packaged application, next to the Java runtime, and in the working directory for
`SignalLessRelayBleHelper.exe`. The release archive places it beside the app.

## Build and test

Kotlin core:

```sh
./gradlew :relay-desktop:test :relay-desktop:installDist
```

Windows helper (on Windows):

```powershell
dotnet build relay-ble-helper/RelayBleHelper.csproj -c Release --nologo
dotnet publish relay-ble-helper/RelayBleHelper.csproj `
  -c Release -r win-x64 --self-contained true `
  -p:DebugType=None -p:DebugSymbols=false -o out/helper
```

The helper targets `net8.0-windows10.0.19041.0`. Move to .NET 10 LTS after the
WinRT path has been verified on hardware; the target is intentionally pinned
until then.

## Packaging

The `Desktop relay` GitHub Actions workflow:

1. runs the Kotlin relay tests on Ubuntu;
2. builds and publishes the self-contained Windows helper on `windows-latest`;
3. creates a `jpackage` app image with a trimmed Java runtime;
4. copies the helper beside the app and writes a Windows x64 zip.

A tagged `v*` push attaches the zip to a GitHub Release. Relevant pull requests
build the same zip and upload it as a workflow artifact, so the packaging path is
tested before a release. The Android workflow does not build or embed the relay.

## Physical test checklist

Automated tests cannot prove WinRT behavior. Run this on Windows 11 with an
adapter that reports LE peripheral support and two phones on the same build:

1. Start the helper with `--ipc` and check the `ready` line.
2. Confirm the phone sees the relay service UUID and connects.
3. Confirm a signed `ANNOUNCE` appears in People with the configured nickname.
4. Send a broadcast from phone A and receive it on phone B through the relay.
5. Disconnect phone B, send a unicast from A, reconnect B, and confirm delivery.
6. Send a payload larger than the negotiated notification size and confirm
   fragment reassembly.
7. Verify the relay rejects a malformed announcement and still forwards normal
   traffic.
8. Confirm clean shutdown on `shutdown` and bounded restart after killing the
   helper process.

Record the adapter model, Windows build, phone builds, and pass/fail results
locally. Do not publish raw logs or device identifiers.

## Linux

The Kotlin core is portable and runs on Linux. The release Windows helper is
WinRT-only. A Linux BlueZ helper would need a D-Bus GATT server and advertising
implementation; it is a development path, not part of the current release.
`--transport helper --helper <path>` can run such a helper if one is supplied,
but no Linux peripheral path is validated here.

## Documentation research

Context7 resolved the .NET and Windows App SDK documentation namespaces and was
useful for build/package questions. It did not expose the WinRT GATT API surface
in enough detail, so Microsoft Learn was the authoritative reference for
`GattServiceProvider`, `GattLocalCharacteristic`, `MaxNotificationSize`, and
advertising status behavior. Android BLE behavior was checked against the local
app implementation and the Android `BluetoothGattServer` documentation.

## References

- `protocol-core/src/main/kotlin/com/signalless/app/protocol/BinaryProtocol.kt`
- `protocol-core/src/main/kotlin/com/signalless/app/model/IdentityAnnouncement.kt`
- `protocol-core/src/main/kotlin/com/signalless/app/mesh/FragmentManager.kt`
- `app/src/main/java/com/signalless/android/mesh/MessageHandler.kt`
- `relay-ble-helper/README.md`
- Microsoft Learn: `GattServiceProvider`, `GattLocalCharacteristic`,
  `GattSubscribedClient.MaxNotificationSize`, `BluetoothAdapter.IsPeripheralRoleSupported`
- Android developers: `BluetoothGattServer`, `BluetoothLeAdvertiser`
