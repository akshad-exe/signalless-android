# SignalLess relay BLE helper

This is the Windows-only BLE peripheral process for the SignalLess desktop
relay. It is **not** part of the Android build and must never be packaged into
the APK.

The Kotlin `relay-desktop` core owns protocol, identity, TTL, deduplication,
fragmentation, and relay policy. This helper owns only the WinRT pieces:

- adapter capability checks;
- one GATT service and one read/write/notify characteristic;
- advertising;
- link lifecycle and opaque link IDs;
- MTU / maximum notification size reporting;
- NDJSON over stdin/stdout and a bounded per-link notification queue.

The terminal mode still echoes one write for the hardware check. IPC mode does
not echo; the Kotlin core decides every transmission.

## Requirements

- Windows 11 with a working Bluetooth adapter.
- An adapter that reports both LE and peripheral-role support.
- .NET 8 SDK (the project targets `net8.0-windows10.0.19041.0`).
- A SignalLess Android build for the scan/connect/write test.

The helper runs in an interactive user session. Do not run it as a Windows
service in session 0; the Bluetooth APIs may be unavailable there.

## Build and run

From the repository root:

```powershell
dotnet build relay-ble-helper/RelayBleHelper.csproj -c Release
dotnet run --project relay-ble-helper/RelayBleHelper.csproj -- --help
dotnet run --project relay-ble-helper/RelayBleHelper.csproj
```

Diagnostics go to **stderr**. In terminal mode, standard output is unused. In IPC
mode, standard output is NDJSON only. A healthy terminal start looks like this:

```text
info adapter le=true peripheral=true maxAdvertisementDataLength=31
info advertising discoverable=true connectable=true serviceData=false status=Created
info advertising state=Started error=Success
info ready; press Ctrl+C to stop
```

The first advertising line reports the status immediately after the request; the
`state=Started` line is the asynchronous confirmation. Treat `state=Aborted` as
a failure and `state=StartedWithoutAllAdvertisementData` as degraded.

Then, after the phone scans and subscribes:

```text
info link up link=l1 mtu=517 maxNotification=514
info rx link=l1 bytes=42
info tx link=l1 bytes=42 echo=true
```

Link IDs are opaque and process-local. Logs never include Bluetooth addresses,
device names, peer IDs, packet bytes, keys, or nicknames.

## IPC mode

The Kotlin core starts the helper as:

```powershell
relay-ble-helper.exe --ipc --no-echo --max-links 7
```

The helper emits `ready`, `advertising`, `link_up`, `link_down`, `rx`,
`tx_result`, and `error` objects. It accepts `start`, `tx`, `stop`, and
`shutdown`. The full field table is in
[`relay-desktop/README.md`](../relay-desktop/README.md).

A representative exchange:

```json
{"v":1,"type":"ready","platform":"windows","maxLinks":7,"serviceDataSupported":true,"serviceDataEnabled":false,"api":"10.0.22631"}
{"v":1,"type":"link_up","link":"l1","mtu":517,"maxNotification":514}
{"v":1,"type":"rx","link":"l1","data":"AQIDBA=="}
{"v":1,"type":"tx_result","link":"l1","seq":1,"ok":true}
```

The helper exits cleanly when stdin closes or after `shutdown`. A bounded
per-link writer queue prevents a slow phone from blocking other links; a full
queue returns `tx_result` with `ok=false` and `error` set to `TX_QUEUE_FULL`.

## Advertising modes

The default is the shipping layout: **service UUID only**, connectable and
discoverable, with no service data.

```powershell
# Default: service UUID list only
dotnet run --project relay-ble-helper/RelayBleHelper.csproj

# Try the 8-byte service-data experiment
dotnet run --project relay-ble-helper/RelayBleHelper.csproj -- `
  --service-data 0011223344556677

# Test whether the friendly name is consuming the advertisement budget
dotnet run --project relay-ble-helper/RelayBleHelper.csproj -- --no-discoverable
```

The service-data flag is experimental. A 128-bit service-data AD structure and
a 128-bit service UUID list cannot both fit in the legacy 31-byte
advertisement. If enabling it removes the UUID from Android's
`ScanRecord.serviceUuids`, keep the flag off.

`StartedWithoutAllAdvertisementData` means Windows accepted the request but
dropped some data. It does **not** prove the UUID survived. The phone scan is
the authoritative check.

## Scan the advertisement layout

A second helper instance can print the relevant AD sections. It prints only the
128-bit service UUID list (`0x07`) and service data (`0x21`) sections; it does
not print addresses or names.

```powershell
dotnet run --project relay-ble-helper/RelayBleHelper.csproj -- `
  --scan --scan-seconds 20
```

Example:

```text
info scan started seconds=20 service=f47b5e2d-4a9e-4c5a-9b3f-8e1d2c3a4b5c
info ad type=0x07 bytes=16 hex=f47b5e2d4a9e4c5a9b3f8e1d2c3a4b5c
info scan finished matches=1
```

Scanning from the same adapter that is advertising is not guaranteed to see
its own advertisement. If it does not, use a second Windows machine or the
phone's scan result.

## Physical test checklist

Run this on the Windows machine, with the phone unlocked and awake.

- [ ] Adapter reports `le=true peripheral=true`.
- [ ] `GattServiceProvider.CreateAsync` succeeds and the helper logs `ready`.
- [ ] `AdvertisementStatus` is `Started` (not `Aborted` or
      `StartedWithoutAllAdvertisementData`).
- [ ] The phone sees the relay in its scan.
- [ ] Android's `ScanRecord.serviceUuids` contains
      `F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C`.
- [ ] The phone connects and subscribes; the helper emits `link_up` with an
      opaque link ID, MTU, and maximum notification size.
- [ ] One phone write arrives; the helper emits `rx` with the byte count only.
- [ ] A Kotlin `tx` command produces `tx_result ok=true` and the phone receives
      the bytes.
- [ ] Repeat with 2 and 4 phones. Record the practical link limit.
- [ ] Kill the helper while the core is running and confirm bounded restart.
- [ ] Confirm no MAC address, device name, payload, key, or nickname appears in
      the log or NDJSON.

This is a physical gate. CI can compile the helper but cannot validate the
adapter, advertisement bytes, CCCD behavior, MTU, notifications, or reconnects.

## Troubleshooting

| Log | Meaning |
|---|---|
| `NO_ADAPTER` | No default Bluetooth adapter was returned. |
| `LE_UNSUPPORTED` | The adapter has no Bluetooth LE support. |
| `PERIPHERAL_UNSUPPORTED` | The adapter cannot advertise as a peripheral. |
| `CENTRAL_UNSUPPORTED` | The adapter cannot scan for the `--scan` mode. |
| `GATT_CREATE_FAILED` | Service/characteristic creation failed, or the API is absent. |
| `ADVERTISING_FAILED` | `StartAdvertising` threw or the OS reported `Aborted`. |
| `ADVERTISING_DEGRADED` | The OS accepted advertising but dropped data. Check the UUID on the phone. |
| `LINK_LIMIT` | The local link cap was reached; Windows may still refuse the connection. |
| `TX_QUEUE_FULL` | The per-link writer queue is full; the phone is not draining notifications. |
| `PROTOCOL_ERROR` | A malformed NDJSON command; the process stays alive. |

If `GATT_CREATE_FAILED` reports `AccessDenied` or `DisabledByPolicy`, check the
adapter's peripheral-role support and whether the process is allowed to use the
Bluetooth APIs in its current session. A future MSIX package can declare the
`bluetooth` capability; this build is an unpackaged console app.

## CI

`.github/workflows/relay-desktop.yml` runs the Kotlin tests on Ubuntu, builds and
publishes the helper on `windows-latest`, and creates the Windows app-image zip.
It does not run the physical test.
