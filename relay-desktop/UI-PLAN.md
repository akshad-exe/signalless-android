# Relay desktop UI — implementation plan

| Field | Value |
|---|---|
| Status | Phase 4 complete (2026-10-22); Phases 5–6 not started |
| Date | 2026-10-22 |
| Branch | `feat/relay-desktop` |
| Scope | `relay-desktop` module + one CI workflow + README |
| Estimated effort | ~5 working days (Phases 0–6) |
| Supersedes | "No UI; the relay is a headless daemon" (see §2) |

---

## 1. Decision summary

**Build a native Compose Desktop window inside the existing `relay-desktop` module.**

Rejected alternatives:

| Option | Why not |
|---|---|
| Web dashboard served by the relay | Explicitly rejected by the product owner. It also adds an HTTP listener and a browser dependency to a daemon that should own no ports. |
| Tauri + React | Belongs to the abandoned `Projects/SignalLess` Rust app. A second toolchain (Node + Rust), a second binary, and WebView2 for one small window. |
| Swing | Zero dependencies, but ~2x the UI code for a visibly lower-quality result. Kept as the fallback if Phase 0 fails (Appendix C). |
| Terminal UI | Cannot show link cards, counters, and controls comfortably. |

**Why Compose now:** the shipping client (`app/`) is a Kotlin/Compose Material3 app derived from bitchat. The relay window can use the same toolkit and design language, so the operator UI matches the product instead of looking like a separate tool.

**Consequences we accept:**

- Compose Multiplatform 1.12.2 + Skiko artifacts (no KMP plugin needed — plain JVM target).
- Roughly +45–60 MB app-image and +25–30 MB zip (today's zip is 91 MB, dominated by the .NET helper).
- ~150–250 MB RSS for the relay process instead of ~60–90 MB.
- Lockfile and dependency-verification churn (both are strict in this repo, see §9.1).

---

## 2. Context and the decision being changed

The relay is currently a headless CLI daemon:

- `Main.kt` builds identity, transport, and `RelayEngine`, then blocks on `awaitCancellation()`.
- `relay-ble-helper` (.NET/WinRT) owns advertising and GATT; the JVM only speaks framed IPC.
- Packaging is `jpackage --type app-image` → zip, launched by `run-relay.cmd`.
- 66 relay unit tests pass; hardware validation has been done on the MT7921 + vivo V2130.

The original relay document (`bitchat-android/docs/relay-desktop-plan.md`, D: copy) says:

> **Never in `relay-desktop`:** Compose UI, Nostr, geohash, media capture, Tor, voice, file transfer...
> The relay is a headless daemon; `protocol-core` is its only shared code.

and:

> **Not included:** Compose Desktop chat UI. The desktop build is a daemon, not a client.

That decision was about a **chat client**. This plan adds an **operator console**: status, links, counters, and start/stop. It does not add messaging, pairing, contacts, or a client UI. The "headless" line is superseded only to the extent described here. The D: copy is read-only and remains historical; this document is the current decision record.

`--no-ui` preserves the old headless behaviour exactly, so existing deployments and Task Scheduler entries keep working.

---

## 3. Goals

- **G1** A real desktop window (one process, no browser, no local HTTP server) showing live relay state.
- **G2** Status: advertising/starting/stopped/failed, uptime, peer ID, nickname, transport, link count.
- **G3** Links: link ID, bound peer ID, MTU, notification size, age, bound/unbound state.
- **G4** Traffic: the counters an operator actually needs (received, forwarded, frames sent, stored, flushed, duplicates, TTL expired, errors).
- **G5** Events: a rolling log of link up/down, transport errors, and lifecycle transitions.
- **G6** Controls: start/stop advertising, restart the helper session, copy peer ID, open the log file, quit.
- **G7** Headless mode stays available (`--no-ui`, headless JVM, CI) and is unchanged.
- **G8** No protocol, advertising, or helper changes. The window reads the engine; it never touches packets.
- **G9** Windows ships first; the same source builds and runs on Linux for development and `--transport fake`.

## 4. Non-goals

- Chat, message rendering, conversations, contacts, pairing, QR.
- Remote access of any kind: no HTTP API, no WebSocket, no LAN binding.
- Editing `--peers`, `--ttl`, `--nickname`, `--discoverable` from the UI (v1 keeps CLI flags; see §14).
- Tray icon, notifications, auto-start, installer/MSI.
- Light theme (dark only in v1).
- Linux/macOS BLE transport. The only real transport is the Windows helper.
- Multi-window or tabs.

---

## 5. Budgets and constraints

| Item | Budget | Measurement |
|---|---|---|
| First window visible | ≤ 2.0 s after process start | manual stopwatch, cold start |
| Idle RSS | ≤ 300 MB | Task Manager / `Get-Process` |
| Idle CPU | ~0% (no animation loop) | Task Manager, 5 min idle |
| Zip size | ≤ 125 MB | CI check against `build/pkg/*.zip` |
| App-image growth | ≤ 60 MB | compare packaged folders |
| Link rendering | 500 ms tick, no dropped frames at 7 links | manual resize with demo state |
| Test suite | all relay tests green + new controller tests | `:relay-desktop:test` |
| Toolchain | JDK 21 toolchain, JVM target 17 | existing config |

Hard constraints:

- One process, one launcher (`SignalLessRelay.exe`), no second runtime.
- All engine calls happen off the UI thread.
- The window never blocks shutdown; closing it stops the helper.
- No new network listener.
- No dependency on the D: drive or the abandoned Rust project.

---

## 6. Architecture

### 6.1 Module layout

```
relay-desktop/src/main/kotlin/com/signalless/relay/
  Main.kt                          CLI, identity, log sink, headless/UI dispatch
  RelayConfig.kt                   + ui: Boolean
  RelayEngine.kt                   + isTransportReady(), lastTransportError()
  RelayLinkRegistry.kt             unchanged
  ui/
    RelayUiState.kt                immutable state + RelayLinkView + RelayEvent
    RelayController.kt             lifecycle, snapshots, commands, event ring buffer
    compose/
      RelayApp.kt                  application {}, Window, theme wrapper
      RelayScreen.kt               layout: header, links, traffic, events, footer
      RelayTheme.kt                colors, typography, shapes
      components/
        StatusHeader.kt
        LinkList.kt
        TrafficGrid.kt
        EventLog.kt
        RelayButton.kt             shared button styling (optional)
```

### 6.2 Data flow

```
helper process ──IPC frames──> HelperProcessTransport ──events──> RelayEngine
                                                                   │
                                              linksSnapshot()/stats()/notice
                                                                   ▼
                                                          RelayController
                                                        (single lifecycle
                                                         dispatcher, 500 ms
                                                         ticker, ring buffer)
                                                                   │
                                                            StateFlow<RelayUiState>
                                                                   ▼
                                                     Compose (collectAsState)
                                                                   │
                                                        buttons / shortcuts
                                                                   ▼
                                                          RelayController.start/stop/restart
```

Rules:

- The engine is the source of truth for links and counters.
- The controller is the source of truth for phase, uptime, first-seen timestamps, and events.
- Compose renders an immutable snapshot; it never calls the engine directly.

### 6.3 Lifecycle state machine

```
            start()                       Ready event
  STOPPED ──────────> STARTING ──────────────────────> ADVERTISING
     ▲                   │                                 │
     │                   │ start failure                   │ stop()
     │                   ▼                                 ▼
     └──── FAILED <──────┘                              STOPPING
                                                          │
                                                          ▼
                                                       STOPPED
```

- `restart()` = `stop()` then `start()` on the same identity, serialized; ignored while a transition is in flight.
- `shutdown()` = `stop()` + cancel the ticker + close the lifecycle dispatcher + shut the coroutine scope.
- Identity is loaded once in `Main` and reused across restarts; a restart does not create a new peer ID.
- On `--transport fake`, the same flow runs against `FakeRelayTransport`.

### 6.4 Concurrency

- `RelayController` owns a single-thread dispatcher (`newSingleThreadExecutor().asCoroutineDispatcher()`) for start/stop/restart, so two clicks cannot race.
- Engine work stays on `Dispatchers.IO` inside its own `SupervisorJob` scope per session.
- The ticker runs on a controller scope and updates `MutableStateFlow`; it is cheap (two snapshot copies every 500 ms).
- `notice` callbacks arrive on engine threads; the controller synchronizes the ring buffer and updates the flow.
- Compose collects with `collectAsState()` and never blocks the AWT event thread.

### 6.5 Engine additions (the only core change)

```kotlin
@Volatile private var transportReady: Boolean = false
@Volatile private var transportError: String? = null

fun isTransportReady(): Boolean = transportReady
fun lastTransportError(): String? = transportError
```

- `Ready` → `transportReady = true`, `transportError = null`.
- `Error` → `transportError = "${code}: ${message}"`.
- `start()` begins with both reset; `stop()` clears `transportReady`.
- No behaviour change for existing callers. Covered by new engine tests.

### 6.6 Controller surface

```kotlin
class RelayController(
    private val config: RelayConfig,
    private val identity: RelayIdentity,
    private val version: String,
    private val transportFactory: (CoroutineScope) -> RelayTransport,
) {
    val state: StateFlow<RelayUiState>

    fun start()        // no-op unless STOPPED or FAILED
    fun stop()         // no-op unless STARTING/ADVERTISING
    fun restart()      // single-flight stop + start
    fun shutdown()     // stop + release resources; idempotent
}
```

`transportFactory` is the test seam: tests inject `FakeRelayTransport` instances and drive `linkUp`/`linkDown`/`startFailure` directly.

### 6.7 State model

```kotlin
enum class RelayPhase { STOPPED, STARTING, ADVERTISING, STOPPING, FAILED }

data class RelayLinkView(
    val id: String,
    val peerId: String?,
    val mtu: Int,
    val notificationSize: Int,
    val firstSeenMs: Long,
)

data class RelayEvent(val atMs: Long, val kind: EventKind, val text: String)

data class RelayUiState(
    val phase: RelayPhase,
    val nickname: String,
    val peerId: String,
    val transport: String,      // "helper" / "fake"
    val version: String,
    val maxLinks: Int,
    val advertisingSinceMs: Long?,
    val links: List<RelayLinkView>,
    val stats: RelayStats,
    val events: List<RelayEvent>,   // newest last, max 200
    val error: String?,
    val nowMs: Long,                // drives uptime/age recomposition
)
```

- Link first-seen is tracked in the controller: a new link ID gets `now`, a removed link is dropped from the map.
- Events are appended for: session started, advertising ready, link up, link down, transport error, start failed, stopping, stopped, restart requested.
- `phase` becomes `ADVERTISING` when `engine.isTransportReady()` is true; if start returns but the radio has not reported `Ready`, the UI shows "Starting…" with a "waiting for the radio" hint (the helper can take a few seconds on the first extended advertisement).

### 6.8 Error handling

| Failure | Where it appears | Recovery |
|---|---|---|
| Helper missing / bad path | `FAILED` card with the message | Retry (Start) after fixing `--helper` |
| Helper start fails (`ADVERTISING_FAILED`) | `FAILED` or error banner if already running | Restart |
| Transport error mid-session | error banner + event, phase stays `ADVERTISING` | Restart |
| Helper process dies | `LinkDown` events, then an error banner if the transport reports one | Restart |
| UI exception | logged; window shows a generic failure card | Restart app |

No error path calls `exitProcess` while the UI is open; the window is the last thing to close.

---

## 7. UX specification

### 7.1 Window

- Size: 600 × 760 dp, resizable, minimum 520 × 620 dp; centered on first run.
- Title: `SignalLess Relay`.
- Standard native decorations (no custom chrome, no transparency).
- Dark theme only.
- Window bounds remembered between runs via `java.util.prefs.Preferences` (Phase 4).

### 7.2 Layout

```
┌ SignalLess Relay ────────────────────────────────────────────┐
│  ● Advertising            SignalLessRelay                    │
│  9f97 f332 1a2b 3c4d  [Copy]        helper · 0.3.0           │
│  up 00:42:11            2 / 7 links                          │
├ Links ───────────────────────────────────────────────────────┤
│  l11  4a7c 9e10 5b22 8d31   MTU 517   notify 514   up 03:12  │
│  l10  waiting for first packet   MTU 517   notify 514  00:41 │
├ Traffic ─────────────────────────────────────────────────────┤
│  Received 128   Forwarded 96   Frames 193   Stored 2         │
│  Flushed 1      Duplicates 4   TTL 0        Errors 0         │
├ Events ──────────────────────────────────────────────────────┤
│  14:02:11  link up l11 mtu=517                               │
│  14:02:07  link down l10 reason=session_closed               │
├──────────────────────────────────────────────────────────────┤
│  [Stop] [Restart] [Open log]                    [Quit]       │
└──────────────────────────────────────────────────────────────┘
```

- The window scrolls only inside the Links and Events regions; the header and footer stay fixed.
- Cards use 12 dp radius, 1 dp border, 16 dp padding, 12 dp gaps.

### 7.3 Header

| Element | Content | Source |
|---|---|---|
| Status dot + label | `Advertising` (green), `Starting…` (amber), `Stopped` (grey), `Needs attention` (red) | `phase` |
| Name | Nickname (`SignalLessRelay`) | config |
| Peer ID | 16 hex chars, grouped 4×4, monospace, selectable | identity |
| Copy button | Copies the raw 16-char ID | clipboard |
| Uptime | `up HH:MM:SS` since `Ready` | `advertisingSinceMs` |
| Link count | `n / max` | state |
| Transport + version | `helper · 0.3.0` | config, `VERSION` |

Status is never conveyed by colour alone: every state has a text label.

### 7.4 Links

Each row shows:

- Link ID (opaque, e.g. `l11`).
- Peer ID when the first packet binds it, otherwise a muted `waiting for first packet`.
- MTU and notification size.
- Age (`up MM:SS`, hours after 60 min).
- A `bound` / `unbound` badge.

Empty state: a dashed card — *"No phones connected yet. Start the relay and keep the phone within a few metres."*

Deliberate omission: **the desktop does not show RSSI or link direction.** The helper reports neither; RSSI is a phone-side scan value, and outgoing/incoming labels describe the phone's role. The relay is always the peripheral. MTU and notification size are the honest quality proxies.

### 7.5 Traffic

Eight fixed cells, `value` large + `label` muted:

| Cell | Source |
|---|---|
| Received | `stats.received` |
| Forwarded | `stats.forwarded` |
| Frames sent | `stats.framesSent` |
| Stored | `stats.stored` |
| Flushed | `stats.storeFlushed` |
| Duplicates | `stats.duplicates` |
| TTL expired | `stats.ttlExpired` |
| Errors | `stats.transportErrors + stats.sendFailed + stats.encodeFailed` |

No invented "dropped" total; each number maps to one counter so the UI cannot drift from the engine.

### 7.6 Events

- Last 200 entries, newest at the bottom.
- Format: `HH:mm:ss  message`, monospace, muted timestamp.
- Colours: info (default), warn (amber), error (red).
- Auto-scroll only if the view is already at the bottom (do not yank the view while the operator is reading).
- `Clear` button empties the view but keeps the ring buffer behaviour intact.
- Every entry also still goes to stdout/`--log-file`; the window does not replace logging.

### 7.7 Footer controls

| Control | Enabled when | Action |
|---|---|---|
| `Start` | `STOPPED` / `FAILED` | New transport + engine session |
| `Stop` | `STARTING` / `ADVERTISING` | Stop session, drop links |
| `Restart` | `STARTING` / `ADVERTISING` / `FAILED` / `STOPPED` | Stop then start (no-op if already stopping) |
| `Copy ID` | always | Clipboard gets the raw peer ID |
| `Open log` | `--log-file` provided | `Desktop.getDesktop().open(file)`; disabled with tooltip otherwise |
| `Quit` | always | `shutdown()` then exit |

Keyboard: `Ctrl+R` restart, `Ctrl+Shift+C` copy ID, `Ctrl+Q` quit. `Esc` does nothing (avoids accidental shutdown).

### 7.8 Theme tokens

| Token | Value |
|---|---|
| Background | `#0E0F10` |
| Surface | `#17181A` |
| Surface raised | `#1E2023` |
| Border | `#2A2D31` |
| Text | `#ECEDEE` |
| Muted text | `#9BA1A6` |
| Accent (advertising) | `#34D399` |
| Warning | `#FBBF24` |
| Error | `#F87171` |
| Card radius | 12 dp |
| Control height | 34 dp minimum |

Typography: system sans (Compose default) with Segoe UI fallback on Windows; IDs, counts, and the event log in a monospace family (`Cascadia Mono`, `Consolas`, fallback monospace). No bundled font in v1 (keeps the package small).

### 7.9 Accessibility

- Tab order: header copy → links → events → footer buttons.
- All interactive controls reachable and activatable by keyboard.
- Content descriptions on the status dot and icon-only buttons.
- Minimum contrast 4.5:1 for body text; status labels always include words.
- Window resizes without clipping; long peer IDs ellipsize, never push buttons off-screen.

---

## 8. CLI changes

Add to `RelayConfig`:

```kotlin
val ui: Boolean = true
```

Parsing:

- `--ui` → true (redundant with the default, kept for scripts).
- `--no-ui` → false.
- If `GraphicsEnvironment.isHeadless()` is true, force headless and print one line: `relay-desktop: no display detected, continuing headless`.

Help text:

```
--ui                        Open the desktop window (default)
--no-ui                     Run headless; required for Task Scheduler/CI
```

Default rationale: double-clicking `SignalLessRelay.exe` or `run-relay.cmd` should show the window. Unattended installs must opt out with `--no-ui`, which is documented in the README.

Developer flag (Phase 2): `--demo` opens the window with a synthetic state and never starts an engine. It is documented under developer options, not in the primary help.

`run-relay.cmd` keeps `--log-file` and passes `%*`; no other change.

---

## 9. Phases

Each phase is a separate commit (or small PR) and must leave the build green.

| Phase | Outcome | Effort | Gate |
|---|---|---|---|
| 0 | Compose builds, runs, packages on this toolchain | 0.5 d | Window opens from the packaged exe; start ≤ 2 s |
| 1 | Controller, state, engine observability, CLI flags | 1 d | New + existing tests green; headless unchanged |
| 2 | Read-only window (header, links, traffic, events) | 1 d | Renders every state at min/max size |
| 3 | Controls (start/stop/restart/copy/log/quit) | 0.75 d | No orphan helper; restart preserves identity |
| 4 | Polish, persistence, keyboard, soak | 0.75 d | Manual checklist; RSS ≤ 300 MB |
| 5 | jpackage, CI smoke, size guard, docs | 0.5 d | CI artifact works; zip ≤ 125 MB |
| 6 | Physical BLE validation with the phone | 0.5 d | All hardware steps pass |

### Phase 0 — dependency spike and build wiring (0.5 day)

Goal: prove Compose 1.12.2 works on this exact toolchain before writing UI code.

Tasks:

1. Add `compose-multiplatform = "1.12.2"` and the plugin alias to `gradle/libs.versions.toml`.
2. Apply the plugins and add `compose.desktop.currentOs` + `compose.material3` to `relay-desktop/build.gradle.kts`.
3. Add a throwaway `ui/compose/SmokeWindow.kt` and a temporary `--ui` branch that shows `SignalLess Relay`, `VERSION`, and a `Quit` button.
4. Update dependency locks and verification metadata (§9.1).
5. Run on `--transport fake`.
6. Package with `jpackage` including `--add-modules java.desktop,jdk.unsupported,jdk.crypto.ec` and launch the packaged exe.
7. Record: zip delta, app-image delta, cold start, idle RSS.

Gate (must all pass before Phase 1):

- `:relay-desktop:test` green.
- Window opens from Gradle and from the packaged app.
- No unresolved dependency or verification failure.
- Cold start ≤ 2.0 s.

If the plugin matrix fails after a 2-hour timebox, stop and switch to the Swing fallback (Appendix C). The controller work in Phase 1 is unaffected.

Deliverables: build files, lockfiles, verification metadata, smoke window, measurements in this document.

#### Phase 0 results (2026-10-22)

Status: complete. Toolchain: Compose Multiplatform 1.12.2, Kotlin 2.4.10, Gradle 9.6.1, JDK 21.0.10 (Temurin), jpackage app-image, Windows x64.

| Check | Result |
|---|---|
| `:relay-desktop:test` | 67 tests, 0 failures |
| Window from Gradle (`--ui`) | opens; RSS 218.1 MB |
| First window, final packaged exe | 1.86 s (includes identity creation) |
| Warm starts (3 runs) | 1.33 s / 1.34 s / 1.49 s |
| Idle RSS (packaged) | 194.9–202.8 MB |
| App-image | 197.0 MB total: runtime 70.9, app jars 36.9, helper 88.7 |
| Zip | 93.1 MB (`build/pkg-ui/SignalLessRelay-windows-x64-ui.zip`) |
| Like-for-like baseline | app-image 164.3 MB, zip 62.0 MB (same runtime modules + same helper) |
| Compose delta | +32.7 MB app-image, +31.1 MB zip |
| Budgets | cold start ≤2.0 s, RSS ≤300 MB, zip ≤125 MB, growth ≤60 MB — all pass |

Packaging notes:

- jpackage needs `jdk.crypto.ec` in `--add-modules`. Without it the first run fails with `X25519 KeyPairGenerator not available`; X25519 and Ed25519 are provided by the SunEC module. Final modules: `java.desktop, jdk.unsupported, jdk.crypto.ec` (resolved runtime also contains `java.base`, `java.datatransfer`, `java.xml`, `java.prefs`).
- The old package at `build/pkg/SignalLessRelay` (241.4 MB, zip 91 MB) used jpackage's default all-module runtime (148 MB), so it is not a like-for-like comparison. The baseline above was rebuilt from the pre-Compose jars with the same trimmed runtime and the same helper.
- Headless path checked in the packaged runtime with `--no-ui --transport fake`; generated an in-memory identity and logged `transport ready`. Persistent identity creation was checked via `--ui --identity-dir <temp> --reset-identity` (four 32-byte key files).
- `--ui` is opt-in in Phase 0; headless remains the default. Phase 1 flips the default to UI-on and keeps `--no-ui` as the escape hatch.
- `gradle/verification-metadata.xml` grew from 751 to 928 components. A comparison confirmed no existing component, artifact, or checksum was removed or changed.
- `ui/compose/SmokeWindow.kt` is throwaway and is replaced in Phase 2.

### Phase 1 — controller, state, and engine observability (1 day)

Goal: everything except pixels.

Tasks:

1. Add `RelayUiState`, `RelayLinkView`, `RelayEvent`, `RelayPhase`.
2. Implement `RelayController` with the single-thread lifecycle dispatcher, 500 ms ticker, event ring buffer (200), and `transportFactory` seam.
3. Add `isTransportReady()` / `lastTransportError()` to `RelayEngine`; reset on start/stop.
4. Add `ui: Boolean` to `RelayConfig` and `--ui` / `--no-ui` parsing; update `USAGE`.
5. Wire `Main`: load identity → install log sink → `if (config.ui) runUi(...) else runHeadless(...)`. In this phase `runUi` may still delegate to headless with a notice; Phase 2 replaces it.
6. Tests:
   - `RelayControllerTest` with fake transports:
     - start reaches `ADVERTISING` and publishes `Ready`
     - stop clears links and returns to `STOPPED`
     - restart reuses the peer ID and drops old links
     - start failure → `FAILED` with message; retry succeeds
     - double restart is a no-op while transitioning
     - ring buffer stays at 200
   - `RelayEngineTest`: ready flag and last error behaviour.
   - `RelayConfigTest`: defaults to UI on, `--no-ui` off, help text unaffected.
7. Keep headless behaviour bit-for-bit: same output lines, same exit codes.

Acceptance:

- New tests pass; all existing relay tests pass.
- `--no-ui --transport fake` behaves exactly as before.
- `--ui --transport fake` still headless in this phase (temporary, documented in the commit).

#### Phase 1 results (2026-10-22)

Status: complete.

- `RelayUiState.kt` adds `RelayPhase`, `RelayEventKind`, `RelayLinkView`, `RelayEvent`, and the immutable state snapshot.
- `RelayController.kt` owns a daemon single-thread lifecycle dispatcher, a 500 ms refresh, a 200-entry event ring buffer, and a `transportFactory` test seam. Start/stop/restart/shutdown are serialized; link events come from the engine's `notice` callback so short-lived links are not lost between ticks; the snapshot supplies link views and stable first-seen timestamps.
- `RelayEngine` exposes `isTransportReady()` / `lastTransportError()`; both reset on start, and stop clears them.
- `RelayConfig.ui` now defaults to true; `--no-ui` forces headless. `GraphicsEnvironment.isHeadless()` forces headless with one stderr notice.
- `Main` is split into `runUi` / `runHeadless`. For this phase `runUi` prints `relay-desktop: desktop window is not available yet; continuing headless` and calls `runHeadless`; Phase 2 replaces that branch with the Compose window.
- The throwaway Compose smoke window from Phase 0 was deleted to avoid dead code; Compose dependencies remain for Phase 2.

Verification:

| Check | Result |
|---|---|
| `:relay-desktop:test` | 80 tests, 0 failures (3 consecutive reruns) |
| `--no-ui --transport fake` | identical output lines and exit path |
| `--ui --transport fake` | one notice line, then the same headless output |
| `-Djava.awt.headless=true` (default UI) | `no display detected, continuing headless` |
| `--help` | shows `--ui` default and `--no-ui` for CI/Task Scheduler |

Notes:

- The controller test suite covers start→advertising, stop, restart identity/links, failure+retry, double restart, both ring-buffer limits, stable first-seen timestamps, transport-error banners, and idempotent shutdown.
- Link-down events keep the engine's reason text (`link down link=… reason=…`); Phase 2 can format this for display without changing the state model.
- `shutdown()` waits up to 3 seconds for the helper stop path, then releases the lifecycle dispatcher. Closing the window must call it before `exitApplication()` in Phase 2.

### Phase 2 — read-only window (1 day)

Goal: a real window showing live state, no controls except Quit.

Tasks:

1. `RelayApp.kt`: `application {}`, `Window`, `onCloseRequest = { controller.shutdown(); exitApplication() }`, `DisposableEffect` start/shutdown.
2. `RelayTheme.kt`: dark `MaterialTheme` from §7.8, shapes, typography.
3. `RelayScreen.kt`: header, links, traffic, events, footer.
4. Components: status pill, link row, stat cell, event row, empty states.
5. `--demo` developer flag: opens the window with a synthetic `RelayUiState` (2 links, non-zero counters, 8 events) and a 1 s timer that ages the links. Requires no engine and no helper; used for layout work and screenshots. Documented as a developer option, not in the main help.
6. Compose the real state from `RelayController`; render `Starting…` until `isTransportReady()`.
7. Manual pass with `--transport fake` (0 links) and with the helper + phone if available.

Acceptance:

- Window shows all sections at 600×760 and at the minimum size.
- Demo state renders 2 links, counters, and events; no clipping at 125% and 150% Windows scaling.
- Closing the window returns exit code 0 and leaves no helper process.
- `--demo` never starts the engine.

Results (2026-10-22):

- Implemented `RelayApp`, `RelayScreen`, theme, formatters, section cards, status header, link list, traffic grid, and event log under `ui/compose/`.
- The window is 600×760 dp, centred, with a 520×620 dp minimum computed from the current display density. A compact layout (smaller gaps, shorter cards, smaller type) activates below 700 dp of content height so both demo links and a few events remain visible at the minimum size.
- `--transport fake` renders `Advertising`, `0 / 7`, the dashed empty-links card, zero counters, and the three lifecycle events. `--demo` renders the synthetic state with 2 links, non-zero counters, a `DEMO` badge, and 8 events.
- Visual smoke tests: 600×760 at 100%, 125%, and 150% (`-Dsun.java2d.uiScale`); 520×620 at 100% and 150%. Both demo link rows fit with MTU/notify details; the event log scrolls.
- `--demo` requires a display and UI enabled; `--demo --no-ui` exits 2 without touching identity, engine, or helper. `--demo` remains out of `--help` as a developer option.
- Closing the demo window exits cleanly (exit code 0); the controller path calls `shutdown()` from both `onCloseRequest` and `Main.runUi`'s `finally`.
- `:relay-desktop:test` — 87 tests, 0 failures.
- Remaining manual checks: the helper + phone path and native Windows scaling (the `uiScale` property exercises Compose density but is not a substitute for the Phase 6 hardware pass).

### Phase 3 — controls (0.75 day)

Goal: run the relay from the window.

Tasks:

1. Wire Start / Stop / Restart to the controller; disable buttons per §7.3.
2. Error card with the failure message and a `Try again` action; keep the technical detail selectable/copyable.
3. Copy ID via Compose clipboard; `Open log` via `java.awt.Desktop`; `Quit`.
4. Lifecycle events: "relay starting", "advertising ready", "stopping", "stopped", "restart requested".
5. Single-flight guard and pending-state feedback (button spinner or disabled label).
6. Tests for transitions and command idempotence (extend `RelayControllerTest`).
7. Manual: stop → link drops; start → phone reconnects; restart 10×; close window while advertising.

Acceptance:

- No orphan `SignalLessRelayBleHelper.exe` after Quit or window close.
- Restart preserves the relay peer ID (phone sees the same relay).
- Starting while another advertiser owns the radio shows `ADVERTISING_FAILED` in the error card, and Retry works after the other process exits.

Results (2026-10-22):

- Start/Stop/Restart, Copy ID, Open log, and Quit are wired to `RelayController`. Button enablement lives in `RelayControlPolicy`; Start and Stop show `Starting…`/`Stopping…` while disabled.
- Restart is disabled during `STARTING` even though §7.7 lists it: the controller deliberately ignores restart during a transition, and a button that silently does nothing is worse than one that waits for the session to settle.
- `ErrorCard` renders the controller's failure message as selectable monospace text with `Try again` and, when `--log-file` is set, `Open log`. A missing helper produced `Needs attention`, the failure card, enabled Start/Restart, disabled Stop, and no start loop.
- Copy ID uses the AWT system clipboard because composition locals are not available at the application root. Open log uses `Desktop.open` and creates an empty log first when the first write has not happened yet.
- The empty-links card no longer enforces a 100 dp child inside a shorter card; its text now fits at 520×620. When the error card is visible at the minimum size, the empty links card shortens so at least one event row remains visible.
- Helper path: advertised with `--transport helper` for 10 s, then closing the window exited Java and left no `SignalLessRelayBleHelper.exe`.
- Controller tests cover lifecycle transitions, retry after failure, restart identity, double-command guards, and event-logger text including formatted link notices. `:relay-desktop:test` — 91 tests, 0 failures.
- Remaining manual checks: control clicks and clipboard/log-open contents (this session could not inject mouse or keyboard input; actions are direct controller references), stop/start with a phone, restart 10×, and native Windows scaling. Phone-dependent checks stay in Phase 6.

### Phase 4 — polish and resilience (0.75 day)

Tasks:

1. Window bounds persistence (`Preferences`), restore with a sanity check against screen bounds.
2. Auto-scroll behaviour for the event log; `Clear`; copy-all events (optional).
3. Animated error card and phase transitions (`AnimatedVisibility`, `animateContentSize`), no infinite animations.
4. Long-run checks: 30 min with 7 links, 1 000 events, repeated resize; RSS stable.
5. Keyboard shortcuts and focus order; tooltips for disabled controls.
6. Optional: one Compose UI test for "0 links empty state" and "error card renders message" using `compose.uiTest` (verify the artifact resolves before committing to it).
7. Trim event-list allocations if profiling shows churn (copy-on-write list is enough at 200 entries).

Acceptance:

- Checklist in §10.2 passes.
- RSS after 30 min ≤ 300 MB; no growth trend.
- All shortcuts work; nothing traps focus.

Results (2026-10-22):

- Window geometry is persisted as physical pixels in `Preferences` (Windows: `HKCU\Software\JavaSoft\Prefs\com\signalless\relay\ui\compose`) and restored through `WindowState`/`WindowPosition` using the current density, so a 100% saved rectangle follows the window at other scales. Saved geometry is sanitised against the monitor list; off-screen or undersized rectangles are discarded and oversized or edge rectangles are clamped. Verified: move to `200,120,700,800`, close, relaunch → restored `200,120,700,800`.
- The event list only follows new entries when it is already at the bottom; manual scrolling up keeps the position. `Clear` empties the view while the controller ring buffer keeps appending and stays bounded at 200. At 520×620 with the error card visible, the empty links card still shows its message and at least one event row remains visible.
- The error card enters and exits with `AnimatedContent`; the header and footer use `animateContentSize` for phase transitions. All animation specs are finite.
- Keyboard: `Ctrl+R` restarts (only when `RelayControlPolicy` allows it), `Ctrl+Shift+C` copies the peer ID, `Ctrl+Q` quits. Footer controls that are disabled show a tooltip with the reason; `Open log` is always present and disabled with a tooltip when `--log-file` is not set.
- `:relay-desktop:test` — 97 tests, 0 failures.
- Remaining manual checks: native 125/150/200% Windows scaling, keyboard-only navigation and tooltip hover (this session cannot inject mouse/keyboard input), interactive click behaviour, and the 30-minute RSS soak (Phase 6 step 8 covers it with phones).

### Phase 5 — packaging, CI, and docs (0.5 day)

Tasks:

1. `jpackage`: add `--add-modules java.desktop,jdk.unsupported`, raise `-Xmx` to 512m, bump `VERSION` and the jpackage app version to `0.3.0`.
2. Keep `--win-console` in v1 (see §14). Confirm the window and console coexist; document that logs still go to stdout.
3. CI:
   - packaged smoke: `SignalLessRelay.exe --help` and `--no-ui --transport fake` with a short timeout; assert startup lines.
   - zip size check against 125 MB.
   - keep `:relay-desktop:test` in the relay workflow.
4. Docs:
   - `relay-desktop/README.md`: "Desktop window" section, controls, `--ui`/`--no-ui`, screenshots (local only), troubleshooting (missing display, jlink).
   - Link this plan from the README.
   - Note in this plan that it supersedes the headless-only line.
5. Release notes for `relay-v0.3.0`.

Acceptance:

- CI artifact contains the window and the helper; unpacked exe opens the window on a clean Windows 10/11 profile.
- Zip ≤ 125 MB; app-image delta recorded.
- README commands copy-paste correctly.

### Phase 6 — hardware validation (0.5 day + device)

Prerequisites: phone connected (`adb devices`), helper built, v42 APK installed.

Checklist:

1. Start relay with helper; window shows `Starting…` → `Advertising`.
2. Phone discovers the relay and creates a link; row appears with peer ID, MTU 517, notify 514.
3. Send an encrypted message through the relay; `received`/`forwarded` increment; no errors.
4. `Stop` → phone link drops; `Start` → reconnects within 30 s.
5. `Restart` ×10 → same peer ID, no stale links, no helper leak.
6. Close the window while advertising → helper exits (check `Get-Process`).
7. Start a second advertiser (nRF Connect) then press Restart → `ADVERTISING_FAILED` is displayed legibly; Retry after closing nRF works.
8. 15-minute soak with 1–2 phones; counters stable; RSS ≤ 300 MB.
9. Screenshots for the README (kept local per AGENTS privacy rules).

Acceptance: all steps pass; findings appended to this document; no protocol regression.

---

## 10. Test plan

### 10.1 Automated

| Test | Covers |
|---|---|
| `RelayControllerTest.startPublishesAdvertising` | start → Ready → phase, uptime |
| `stopClearsLinksAndStopsTicker` | teardown |
| `restartKeepsIdentityDropsLinks` | restart semantics |
| `startFailureSurfacesMessageAndRetryWorks` | error path |
| `restartWhileTransitioningIsIgnored` | single-flight |
| `eventRingBufferIsBounded` | memory/backpressure |
| `linkFirstSeenIsStable` | age tracking |
| `RelayEngineTest.transportReadyTracksReadyAndStop` | engine flag |
| `RelayEngineTest.lastTransportErrorIsClearedOnStart` | engine flag |
| `RelayConfigTest.parsesUiFlags` | CLI |
| Formatter tests | uptime, count grouping, ID grouping |

Commands:

```powershell
.\gradlew.bat :relay-desktop:test
.\gradlew.bat :relay-desktop:run --args="--no-ui --transport fake"
```

### 10.2 Manual UI checklist

- [ ] Empty state (no links) at 600×760 and 520×620.
- [ ] 2 links, 7 links, long peer IDs, 999 999 counters.
- [ ] Starting, advertising, stopped, failed states.
- [ ] Error card with a long helper path.
- [ ] Event log: 0, 200, and 1 000 entries; auto-scroll respects manual scroll.
- [ ] 100%, 125%, 150%, 200% Windows scaling.
- [ ] Keyboard-only navigation.
- [ ] Copy ID, Open log, Quit, window close.
- [ ] Restart during linking, double-click Restart, Stop during Starting.

### 10.3 Packaging

- Unzip to a path with spaces and non-ASCII characters; launch.
- `--no-ui` headless from `cmd` and Task Scheduler.
- Verify `java.desktop` is present in the bundled runtime (`runtime\bin\java --list-modules`).
- Confirm no helper process remains after Quit.

---

## 11. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Compose 1.12.2 plugin breaks on Gradle 9.6.1 / Kotlin 2.4.10 | medium | high | Phase 0 timeboxed gate; fallback to 1.11.1, then Swing (Appendix C) |
| Strict lockfiles + dependency verification reject new artifacts | high | medium | Regenerate with `--write-locks --write-verification-metadata sha256`; review the diff |
| jlink runtime missing `java.desktop` | medium | high | Explicit `--add-modules`; packaged smoke test in CI |
| Package size growth | medium | low | 125 MB CI budget; no bundled fonts/icons in v1 |
| Orphan helper after window close | medium | high | `engine.stop()` in `finally`; shutdown hook; Phase 6 check |
| UI thread blocked by engine calls | medium | high | Single lifecycle dispatcher; no suspend calls from composables |
| Console window behind the UI | high | low | Keep `--win-console` in v1; revisit in a future version |
| Compose accessibility weaker than native | medium | low | Keyboard paths, labels, contrast; document |
| Linux build has no BLE transport | certain | low | Window + `--transport fake` only; document |
| Antivirus flags the packaged exe | low | medium | Existing issue; signed releases later |

---

## 12. Rollout and rollback

- **Rollout:** ship in `relay-v0.3.0`; UI on by default. Announce `--no-ui` for unattended installs.
- **Rollback A:** users run `--no-ui`; no code change needed.
- **Rollback B:** revert Phase 2+ commits and the build plugin; Phase 1's controller and engine observability are harmless and can stay.
- **Rollback C:** if Compose fails during Phase 0, switch the view to Swing; the controller, state model, tests, CLI, and packaging work all remain valid.

---

## 13. File change map

| File | Change | Phase |
|---|---|---|
| `gradle/libs.versions.toml` | Compose version + plugin alias | 0 |
| `relay-desktop/build.gradle.kts` | Compose plugins + desktop deps | 0 |
| `relay-desktop/gradle.lockfile` | regenerated | 0 |
| `settings-gradle.lockfile` | regenerated if plugin deps are locked | 0 |
| `gradle/verification-metadata.xml` | checksums for new artifacts | 0 |
| `relay-desktop/src/main/kotlin/.../ui/RelayUiState.kt` | new | 1 |
| `.../ui/RelayController.kt` | new | 1 |
| `.../RelayEngine.kt` | readiness/error accessors | 1 |
| `.../RelayConfig.kt` | `ui` flag + parsing + help | 1 |
| `.../Main.kt` | UI/headless dispatch | 1–2 |
| `.../ui/compose/RelayApp.kt` | new | 2 |
| `.../ui/compose/RelayTheme.kt` | new | 2 |
| `.../ui/compose/RelayScreen.kt` | new | 2 |
| `.../ui/compose/components/*` | new | 2–3 |
| `.../ui/compose/RelayDemo.kt` | developer `--demo` state | 2 |
| `relay-desktop/src/test/.../RelayControllerTest.kt` | new | 1 |
| `relay-desktop/src/test/.../RelayEngineTest.kt` | readiness cases | 1 |
| `relay-desktop/src/test/.../RelayConfigTest.kt` | UI flag cases | 1 |
| `.../ui/compose/WindowBounds.kt` | window geometry persistence | 4 |
| `.../src/test/.../ui/compose/WindowBoundsTest.kt` | sanitising cases | 4 |
| `.../ui/compose/components/ErrorCard.kt` | error card (added in 3) | 3–4 |
| `.github/workflows/relay-desktop.yml` | jpackage modules, smoke, size check | 5 |
| `relay-desktop/README.md` | window docs | 5 |
| `relay-desktop/run-relay.cmd` | optional `--ui` note; no behaviour change | 5 |
| `relay-desktop/UI-PLAN.md` | this document | 0 |

---

## 14. Open questions

1. **UI on by default?** Proposed yes; `--no-ui` for Task Scheduler. If the relay is usually deployed unattended, flip the default and add `--ui` to `run-relay.cmd`.
2. **Console window** — keep `--win-console` for v1 (logs visible) or drop it for a cleaner window and rely on the in-window log + `--log-file`? Recommendation: keep for v1.
3. **Editable nickname in the window?** Deferred. Doing it correctly needs a re-announce or a session restart; the CLI covers it.
4. **Tray icon** — useful for "running in background" but adds a second UI surface; deferred.
5. **Light theme** — deferred; the product is dark-first.
6. **Window icon** — use the existing Android launcher icon converted to `.ico` if the tooling is available; otherwise the default Java icon for v1.
7. **Linux packaging** — app-image first; `.deb` only when a Linux BLE transport exists.
8. **`--demo` in release builds** — keep it (small, no engine, clearly labelled) or compile it only in debug? Default: keep, documented as developer-only.

---

## 15. Definition of done

- [ ] Compose Desktop 1.12.2 builds and packages with the existing toolchain.
- [ ] Window shows phase, peer ID, uptime, links, traffic, and events.
- [ ] Start/Stop/Restart work; restart preserves identity; no orphan helper.
- [ ] Error states are legible and recoverable.
- [ ] `--no-ui` is byte-for-byte equivalent to today's headless behaviour.
- [ ] New controller/engine/config tests pass; 66 existing relay tests pass.
- [ ] Packaged exe works on a clean Windows profile; zip ≤ 125 MB.
- [ ] Cold start ≤ 2 s; idle RSS ≤ 300 MB.
- [ ] README documents the window, flags, and troubleshooting.
- [ ] Hardware validation recorded (phone + helper) with evidence kept local.
- [ ] This plan's status updated to "implemented" with any deviations.

---

## Appendix A — versions and dependency wiring

```toml
# gradle/libs.versions.toml
[versions]
compose-multiplatform = "1.12.2"

[plugins]
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "compose-multiplatform" }
```

```kotlin
// relay-desktop/build.gradle.kts
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.compose)        // Compose compiler, same version as Kotlin
    alias(libs.plugins.compose.multiplatform) // Compose runtime + desktop DSL
    application
}

dependencies {
    implementation(project(":protocol-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.gson)

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    testImplementation(libs.junit)
}
```

Compatibility notes:

- Compose Multiplatform 1.12.2 supports Windows 10+, Ubuntu 20.04+, macOS 13+ (64-bit only).
- Latest Compose Multiplatform tracks the latest Kotlin; the Compose compiler plugin must match Kotlin (2.4.10 here).
- Desktop requires JDK 11+ at runtime; packaging (jpackage) requires JDK 17+.

## Appendix B — lockfile and verification commands

Both are strict in this repo:

- `build.gradle.kts` applies `lockAllConfigurations()` with `LockMode.STRICT`.
- `gradle/verification-metadata.xml` verifies metadata (`verify-metadata=true`).

After adding dependencies:

```powershell
.\gradlew.bat help --write-locks --write-verification-metadata sha256
.\gradlew.bat :relay-desktop:dependencies --write-locks
```

Then review:

- `relay-desktop/gradle.lockfile` (and `settings-gradle.lockfile` if plugin classpath changed).
- `gradle/verification-metadata.xml` (new `org.jetbrains.compose`, `org.jetbrains.skiko`, `org.jetbrains.androidx.*` entries).

Do not hand-edit either file.

## Appendix C — Swing fallback (if Phase 0 fails)

Keep everything from Phase 1; replace only the view.

- `ui/swing/RelayFrame.kt`: `JFrame`, custom `RoundedPanel`, `StatusPill`, `LinkRow`, `StatCell`, `JTextArea` for events.
- `javax.swing.Timer(500)` on the EDT reads `controller.state.value`; commands run on the controller dispatcher.
- No new dependencies. jpackage still needs `--add-modules java.desktop` (adds ~12–15 MB).
- Code volume ~800–900 lines; visual fidelity medium.
- Estimated extra effort: +0.5–1 day over the Compose path, with lower runtime cost (~90–130 MB RSS, ~0.3 s start).

The controller, state model, CLI, tests, packaging, and docs are unchanged.

## Appendix D — commands

```powershell
# Unit tests
.\gradlew.bat :relay-desktop:test

# Run from source (UI)
.\gradlew.bat :relay-desktop:run --args="--transport helper --helper <path-to-helper.exe>"

# Run from source (headless)
.\gradlew.bat :relay-desktop:run --args="--no-ui --transport fake"

# Developer demo state (no engine)
.\gradlew.bat :relay-desktop:run --args="--demo"

# Package locally
.\gradlew.bat :relay-desktop:installDist

# Package with jpackage (CI equivalent)
jpackage --type app-image --name SignalLessRelay --app-version 0.3.0 `
  --input relay-dist/lib --main-jar relay-desktop.jar `
  --main-class com.signalless.relay.MainKt --win-console `
  --dest out/package --java-options "-Xmx512m" `
  --add-modules java.desktop,jdk.unsupported
```
