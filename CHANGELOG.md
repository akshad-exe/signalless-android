# Changelog

All notable changes to this project are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-11-26

First SignalLess release. SignalLess is built on
[BitChat for Android](https://github.com/permissionlesstech/bitchat-android);
see [NOTICE](NOTICE) for attribution.

### Added

- Offline Bluetooth LE mesh messaging with multi-hop relay, plus Nostr relays
  when the internet is available.
- Wi-Fi Aware transport on supported devices.
- Noise XX end-to-end encryption for private messages.
- Images, audio and file transfer, and push-to-talk live voice.
- Geohash location channels, password-protected channels, embedded Tor and an
  emergency wipe.
- Desktop relay for Windows that bridges nearby mesh clusters.

### Changed

- Renamed to SignalLess, with its own application ID and icon.
- The app is offered in English and Indian languages.
- Portable protocol code moved into the `protocol-core` module.

### Fixed

- Queued messages survive an app restart.
- In-app updates and the release pipeline work after the rename.
- The relay binds a link to a peer only from a verified, direct announcement.

### Known limitations

- Not yet validated on a physical-device mesh lab.
- Voice is push-to-talk only; there are no voice calls.

[Unreleased]: https://github.com/akshad-exe/signalless-android/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/akshad-exe/signalless-android/releases/tag/v0.1.0
