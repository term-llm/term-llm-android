# Public MIT source release and ownership

Original project-authored Android app code, tests, fixtures and build recipes are
MIT-licensed, Copyright (c) 2026 Sam Saffron. See [LICENSE](../LICENSE),
[PROVENANCE.md](../PROVENANCE.md), [SECURITY.md](../SECURITY.md), and the preserved
[Apache-2.0 text](../licenses/Apache-2.0.txt). Dependencies and external content
retain their original terms; this is not a relicensing of third-party components.

## Source state and verification

The v3 dark-mode and keyboard-inset fixes and their regression tests were merged
through PR #1, merge commit `9a77729a438324326c319cb4b3023fd10ea2d96f`.
The tested fix commit is `b29a51fde45e6217058f120acc479751d95c558e`; its runtime
inputs are unchanged by this source-license/docs/ignore/CI-checkout cleanup.
There is no longer an unmerged-v3 source gap. See [verification](VERIFICATION.md)
for fresh v3 test evidence and separately labelled historical v1 artifacts.

Tree/staged and complete fetched-history scans use fully redacted output. The
four existing exact-value AND exact-path public-vector exemptions remain narrow
and unchanged. Scans do not establish absence of every possible secret or
license issue. Private source references, signing keys, personal accounts,
recordings and external QA evidence are not part of this source distribution.

This is a **source-only** release, not an APK distribution. Applicable dependency
notices must accompany future binary distributions, but that separate work is not
a blocker to publishing this original source under MIT. No new asset-rights
claim is made: third-party rights remain with their respective owners.

## Authorized public release and personal-account transfer

The owner has explicitly authorized making the Android repository public and
transferring it from `sam-saffron-jarvis/term-llm-android` to the personal account
`SamSaffron`, preserving the name `term-llm-android`. The destination is **not**
the `term-llm` organization. Visibility/transfer are owner-operated GitHub actions,
not actions performed by a source commit or the build/test recipes. Until their
completion is verified, the old repository URL may remain authoritative.

After transfer, update existing clone remotes and verify the final URL, MIT
license and visibility. The Android CI workflow is manual-only/self-hosted; no
matching runner is registered by this source cleanup. Application ID, signing
identity, enrolled sources and the separately maintained core repository do not
change for a GitHub ownership transfer. No APK rollout or production service
change is part of this release.
