# Releasing

The signing key never leaves the maintainer's machine. CI builds unsigned
artifacts; signing and publishing happen locally.

1. Move the `Unreleased` notes in `CHANGELOG.md` under the new version. Set
   `signalless.versionName` and raise `signalless.versionCode` (defaults are in
   `app/build.gradle.kts`). Merge to `main`.
2. Tag that commit and push the tag: `git tag -a vX.Y.Z && git push origin vX.Y.Z`.
3. Wait for the `Release` workflow. It builds twice, compares the bytes and
   attests the result.
4. Download the `verified-unsigned-release` artifact:
   `gh run download <run-id> -n verified-unsigned-release -D <dir>`.
5. Sign: `tools/reproducible-builds/sign-release.sh <dir>` with the
   `SIGNALLESS_GITHUB_KEY*` variables set. The script rejects any key other than
   the one pinned in `gradle.properties`.
6. Optional, for a Play upload: `sign-play-bundle.sh <dir>` with the separate
   upload key. Then run `prepare-github-release.sh <dir>`.
7. Publish: `gh release create vX.Y.Z <dir>/* --notes-file <notes> --generate-notes`.
8. Check: `tools/reproducible-builds/verify-github-release.sh vX.Y.Z`.
