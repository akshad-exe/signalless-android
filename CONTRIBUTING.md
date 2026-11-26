# Contributing

Thanks for helping. By taking part you agree to the [code of conduct](CODE_OF_CONDUCT.md).
Security problems go through [SECURITY.md](SECURITY.md), not public issues.

## Where help is most useful

- Testing on real phones and reporting what happens.
- The items under [Status](README.md#status).
- Issues labelled [`good first issue`](https://github.com/akshad-exe/signalless-android/labels/good%20first%20issue).

Open an issue before starting a large change.

## Set up

You need JDK 21 and the Android SDK (compileSdk 37). See [Building](README.md#building).

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew :relay-desktop:test
```

## Rules

- Kotlin official style, four-space indentation.
- `protocol-core` never imports `android.*`.
- Protocol and security changes stay fail-closed and compatible with other
  clients. Do not change existing wire formats; add golden-vector tests for new ones.
- Add tests for new behaviour. Avoid sleeps, public relays and live data in tests.
- Changes to discovery, routing, transports, encryption, identity, background
  power, messaging, transfers or packets need testing on two physical phones. Say
  in the pull request what you tested, or that you could not.
- Dependency changes must update the Gradle lock files and
  `gradle/verification-metadata.xml`.
- Never include real names, messages, keys, peer IDs, locations or raw device logs
  in code, tests, issues or screenshots.

## Pull requests

Branch from `main`, use a short imperative subject (or `fix(scope): ...`), keep one
topic per pull request, and describe the risk and how you tested it. Attach
screenshots for visible changes. Contributions are licensed under
[GPL-3.0](LICENSE.md).
