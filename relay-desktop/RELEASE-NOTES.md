# SignalLess Relay 0.3.0

This release adds a native desktop operator window to the Windows BLE relay.
The relay core is unchanged and `--no-ui` still runs the previous headless
daemon.

## Desktop operator window

- Native Compose Desktop window (no web dashboard, no listening ports) showing
  the phase, peer ID, uptime, live links, traffic counters, and recent events.
- Start, Stop, Restart, Copy ID, Open log, and Quit controls, plus Clear for the
  event list. Disabled controls explain themselves with tooltips.
- Keyboard shortcuts: `Ctrl+R` restart, `Ctrl+Shift+C` copy peer ID, `Ctrl+Q`
  quit.
- Window position and size are remembered across runs and checked against the
  current monitor layout.
- Actionable failure card with the technical message, `Try again`, and
  `Open log` when `--log-file` is set.
- The event list only follows new entries when you are already at the bottom.

## Behaviour and packaging

- The window is on by default; `--no-ui` keeps the exact headless behaviour for
  Task Scheduler and CI. A machine with no display falls back to headless with a
  log line.
- `--demo` opens a synthetic window with no identity, engine, or helper process;
  it is a developer-only layout aid.
- Packaged runtime explicitly includes `java.desktop`, `jdk.unsupported`, and
  `jdk.crypto.ec` (X25519 generation fails without the last one).
- jpackage launcher now uses `-Xmx512m` (Compose Desktop needs more heap than
  the headless relay) and keeps `--win-console` so diagnostics remain visible.
- CI smoke-tests the packaged `--help` and a timed
  `--no-ui --transport fake` start, and fails if the release zip exceeds
  125 MB.

## Validation notes

- Automated: `:relay-desktop:test` passes (97 tests at this release).
- Manual desktop validation: the window was verified at 600×760, 520×620, and
  700×800, including the failure card and helper cleanup on exit. Native
  Windows scaling, keyboard-only navigation, tooltip hover, interactive clicks,
  and the long RSS soak remain manual checks on a phone-equipped machine. See
  `UI-PLAN.md` Phase 6.
- Physical BLE behaviour is unchanged from the headless relay; the Windows
  extended-advertising notes in `README.md` still apply.

## Known limitations

- The window requires an interactive desktop; use `--no-ui` for services.
- The relay still depends on the separate `relay-ble-helper` process for the
  Windows peripheral role, and on extended advertising for the full
  advertisement payload.
- Linux has no supported BLE peripheral path; use `--transport fake` for
  protocol tests.
