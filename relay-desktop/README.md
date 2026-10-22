# SignalLess desktop relay

`relay-desktop` is an always-on Windows mesh peer for SignalLess. It advertises
the same BLE service as a phone, accepts phone connections, and forwards packets
with the same TTL, dedup, fragmentation, and identity rules as the Android app.

It is never packaged into the Android APK. The Windows BLE peripheral role lives
in the separate `relay-ble-helper` .NET process.

The desktop build also includes a native operator window (Compose Desktop) that
shows relay state, start/stop controls, live links, counters, and recent events.
The relay core is still headless infrastructure: `--no-ui` runs exactly as
before, and the window only observes and drives the same controller. The UI
design and phase history live in [`UI-PLAN.md`](UI-PLAN.md); that document
supersedes the earlier "no UI; the relay is a headless daemon" statement.

## What the relay does not implement

The relay is a network appliance, not a second phone UI. It deliberately has no
chat list, message view, web dashboard, Nostr, geohash, media, Tor, voice, or
file transfer surfaces. It does retain the portable `FileSharingManager` type
through `protocol-core` because the core module is shared with the app; no relay
CLI path invokes it.

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

## Running the relay

The relay runs on an always-on Windows machine within BLE range of the phones,
and the Android app is still the only phone interface.

1. Unzip the release.
2. Start `SignalLessRelay.exe` (or `run-relay.cmd` to keep the console open
   after an error). The Windows launcher opens a console for diagnostics and the
   operator window; keep both running.
3. Press **Start** if the relay did not start automatically. It advertises the
   SignalLess service and waits for phones to connect.
4. On each phone, open SignalLess and open the Network sheet. The relay appears
   in the **Relay** section (name, address, link direction, signal strength) as
   soon as the phone holds a BLE link to it.
5. Phones keep their normal scan/connect role. No relay setting is needed on the
   phone; the relay is just another mesh node.

### Operator window

The window is a native Compose Desktop application; it is not a web dashboard
and it opens no ports. It shows the relay state, the peer ID, live links, the
traffic counters, and the most recent events.

| Control | Action |
|---|---|
| **Start** | Begin advertising. Disabled while starting, advertising, or stopping. |
| **Stop** | Stop advertising. Disabled unless the relay is starting or advertising. |
| **Restart** | Stop and start again with the same peer ID. Disabled during a transition. |
| **Copy ID** | Copy the relay peer ID to the clipboard. |
| **Open log** | Open the log file. Disabled unless the relay was started with `--log-file`. |
| **Quit** | Stop the relay, release the helper process, and close the window. |
| **Clear** | Empty the visible event list. New events still arrive and the buffer stays capped at 200. |

Keyboard: `Ctrl+R` restarts, `Ctrl+Shift+C` copies the peer ID, `Ctrl+Q` quits.
Disabled controls show a tooltip explaining why they are unavailable. Window
position and size are remembered in the user preferences and restored on the
next run after a check against the current monitor layout.

`--demo` opens a window with synthetic state (no identity, engine, or helper)
for layout work and screenshots; it is a developer option and is not part of the
help text.

`--no-ui` runs the relay headless with the same controller and the same output
as earlier releases; use it for Task Scheduler, CI, and servers without a
display. If `--ui` is requested on a machine with no display, the relay logs
`no display detected, continuing headless` and keeps running.

By default the relay advertises the service UUID together with the machine name.
A 128-bit service UUID plus a Bluetooth name does not fit the legacy 31-byte
advertisement payload, so Windows abandons the legacy instance and advertises
with BLE 5 extended advertising instead (up to 254 bytes per packet). The status
therefore passes through `Aborted` before settling on `Started`; the helper waits
for the settled state and only fails if advertising never comes up.

Phones must scan with extended advertising enabled. The Android app does this
(`ScanSettings.setLegacy(false)`); a legacy-only scan never reports the relay.
Use `--no-discoverable` only for adapters that cannot start an extended
advertisement — some adapters accept that request but never transmit it.

For unattended startup, use Task Scheduler and pass `--log-file <path>`. The
console is only a diagnostic view; closing it stops the relay.

### What the phone shows

The Network sheet separates identity from connectivity:

- **People** lists chat peers. Infrastructure nodes are kept out of it.
- **Relay** lists live BLE links to nodes that advertise
  `PeerCapabilities.NONE`. Each row shows the nickname, the BLE address, the
  link direction (`outgoing` / `incoming`) and a signal meter. Rows exist only
  while the link is up, so the list empties when the relay stops or leaves
  range.

The relay still sends a signed `ANNOUNCE`, so phones learn its nickname and
public keys. The announcement says *who* the node is; the Relay section says
whether it is reachable *right now*. App builds before 2.0.4-lab did not
classify capabilities, so they also showed the relay under People.

### BLE roles: who connects to whom

BLE has two independent role pairs:

- **Advertiser / peripheral**: the relay advertises the SignalLess service;
  phones scan for it.
- **GATT client and server**: the side that opens the connection is the client;
  the side that hosts the service is the server. A phone runs *both* — it is a
  client when it dials a peer or the relay, and a server when another phone
  dials it.

The relay is server-only: it advertises and accepts, and never scans. The phone
is therefore always the GATT client on a relay link, and the Relay row says
`outgoing`. The `connected to our server` line in the app log describes a
different link — another phone dialling this phone's own GATT service. It is
normal mesh behavior and has nothing to do with a cloud server; there is no
cloud server in this path at all.

### How a message crosses the relay

```text
Phone A ──BLE──> relay ──BLE──> Phone B
```

Phone A sends a normal SignalLess packet addressed to Phone B. If the packet
reaches the relay, the relay reassembles any fragments, checks TTL and
deduplication, then re-fragments for the link to B and forwards it. If B is
temporarily absent, a unicast packet is kept in the bounded cache and delivered
when B reconnects. Broadcasts go to every other link.

The relay does not hold Noise session keys and does not decrypt message or media
payloads. It sees routing metadata (sender, recipient, type, TTL, size) and the
public signed announcement; it does not see message text, media, or contact
data.

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
--discoverable              Include the friendly name in advertising
                            (default; makes Windows use extended advertising)
--no-discoverable           Omit the friendly name; only works if the adapter
                            can start a legacy advertisement
--ui                        Open the desktop window (default)
--no-ui                     Run headless; required for Task Scheduler/CI
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

Run the window from the source tree:

```sh
./gradlew :relay-desktop:run --args="--transport fake"
./gradlew :relay-desktop:run --args="--demo"
```

Headless smoke test (same path CI uses):

```sh
./gradlew :relay-desktop:run --args="--no-ui --transport fake"
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
3. builds the relay distribution on that same Windows runner, so the Compose
   dependency pulls the Windows Skiko natives rather than the Linux ones;
4. creates a `jpackage` app image with a trimmed Java runtime and a console
   launcher (`--win-console`) so diagnostics are visible when started by hand.
   The runtime explicitly includes `java.desktop`, `jdk.unsupported`, and
   `jdk.crypto.ec`; without the last one X25519 key generation fails on first
   run. The launcher uses `-Xmx512m` because Compose Desktop needs more heap than
   the old headless relay;
5. smoke-tests the packaged `--help` and a timed `--no-ui --transport fake`
   start, copies the helper, README, and `run-relay.cmd` beside the app, and
   writes a Windows x64 zip;
6. fails the build if the zip exceeds the 125 MB budget.

A tagged `relay-v*` push attaches the zip to a GitHub Release and uses the notes
in [`RELEASE-NOTES.md`](RELEASE-NOTES.md). Relevant pull requests build the same
zip and upload it as a workflow artifact, so the packaging path is tested before
a release. The Android workflow does not build or embed the relay.

Current package version: **0.3.0**. The Phase 0 measurements were a 197.0 MB
app-image and a 93.1 MB zip with the Compose spike; the exact numbers for the
release build are printed by the workflow size check.

## Physical test checklist

Automated tests cannot prove WinRT behavior. Run this on Windows 11 with an
adapter that reports LE peripheral support and two phones on the same build:

1. Start the helper with `--ipc` and check the `ready` line.
2. Confirm the phone sees the relay service UUID and connects.
3. Confirm a signed `ANNOUNCE` appears in the Relay section of the Network sheet
   with the configured nickname, and that the relay does not appear under People
   as a chat peer.
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

### Validated configuration (October 2026)

| Component | Value |
|---|---|
| Adapter | MediaTek MT7921 (USB `VID_13D3&PID_3567`, driver 1.3.17.168) |
| Phone | vivo V2130, Android 14 |
| App | SignalLess 2.0.4-lab (versionCode 41) |
| Result | `advertising settled status=Started`; phone connected with MTU 517 and a 514-byte notification size; signed `ANNOUNCE` relayed; 0 transport errors |

The MT7921 aborts the legacy advertising instance every time and only settles on
the extended one. That is expected on this adapter and is not a failure.

## Troubleshooting

### The window does not open

If the machine has no interactive display, the relay prints
`no display detected, continuing headless` and runs without a window; that is
expected for Task Scheduler and CI. If the packaged app fails before that check,
make sure the bundled runtime contains `java.desktop`:

```powershell
Get-Content .\runtime\release | Select-String "java.desktop|jdk.unsupported|jdk.crypto.ec"
```

The app-image ships a launcher plus a `runtime` directory, so there is no
`runtime\bin\java.exe` to query. Without `jdk.crypto.ec`, X25519 key generation
fails on the first run with `X25519 KeyPairGenerator not available`.

### `ADVERTISING_FAILED: adapter status was Aborted after 3s`

Another process already owns the advertising instance. Windows aborts the
second request, so a second relay cannot start while the first one is running —
including a leftover helper from an earlier run. Check for one:

```powershell
Get-Process SignalLessRelayBleHelper -ErrorAction SilentlyContinue
```

Close that console (or `Stop-Process -Name SignalLessRelayBleHelper -Force`) and
start the relay again. A phone connection never causes this; only another
advertising instance does.

### The phone scans but never sees the relay

- The app must scan with extended advertising enabled. Builds from 2.0.3-lab
  onwards do (`ScanSettings.setLegacy(false)`); a legacy-only scan cannot see the
  35-byte advertisement.
- Check the helper output for `advertising settled status=Started`, and the
  `ready` line for `le=true peripheral=true`.
- If the adapter cannot start an extended advertisement, try `--no-discoverable`
  (21-byte legacy payload). Some adapters, including the MT7921, accept that
  request but never transmit it.

### nRF Connect reports `Error 62 (0x3E)`

Windows rotates its random resolvable private address periodically. nRF Connect
caches the old address, so the connect attempt targets a stale entry. The
SignalLess app scans fresh each time and connects to the current address; forget
the cached device in nRF Connect and rescan. This is not a hardware limit.

### The relay disappears from the Relay section

The section lists live links only. Android drops BLE links when the app is
backgrounded or the OS power-manages the radio; the relay keeps advertising and
the phone reconnects. Watch the relay console for `link up` and `link down`
lines to confirm the cycle.

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

- `relay-desktop/UI-PLAN.md` — UX, phases, controls, and budgets
- `relay-desktop/src/main/kotlin/com/signalless/relay/ui/RelayController.kt`
- `relay-desktop/src/main/kotlin/com/signalless/relay/ui/compose/RelayApp.kt`
- `protocol-core/src/main/kotlin/com/signalless/app/protocol/BinaryProtocol.kt`
- `protocol-core/src/main/kotlin/com/signalless/app/model/IdentityAnnouncement.kt`
- `protocol-core/src/main/kotlin/com/signalless/app/mesh/FragmentManager.kt`
- `app/src/main/java/com/signalless/android/mesh/MessageHandler.kt`
- `relay-ble-helper/README.md`
- Microsoft Learn: `GattServiceProvider`, `GattLocalCharacteristic`,
  `GattSubscribedClient.MaxNotificationSize`, `BluetoothAdapter.IsPeripheralRoleSupported`
- Android developers: `BluetoothGattServer`, `BluetoothLeAdvertiser`
