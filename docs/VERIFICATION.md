# Verification and limits

## Current v3 source

PR #1 merged the exact reviewed v3 patch as `9a77729a438324326c319cb4b3023fd10ea2d96f` (tested fix commit `b29a51fde45e6217058f120acc479751d95c558e`). Fresh verification on an owned API35 Android15 Pixel6 x86_64 emulator with WebView124.0.6367.219: **197 JVM tests re-executed,0 failures/errors/skips**; debug/test APK builds, release lint and unsigned R8 release build passed. OS-theme + actual system-IME geometry regressions passed **2/2 starting light and2/2 starting dark**; related native holder/trust/isolation/navigation/WebMCP/token/profile tests **21/21**; three-button keyboard regression **1/1**. Runtime/build inputs in this source-hygiene commit are byte-identical to that tested fix. No redundant emulator/full build was run for license/docs/ignore/checkout-only edits.

The debug certificate was verified unchanged against preserved v3; source versionCode3/versionName`1.0-android-port.3`. No APK artifact is distributed by this source release. Prior independent review and v2-fails/v3-passes reproduction apply to the exact patch; the baseline-negative run was not repeated. No new physical-device/OEM, floating-IME, multi-window, landscape or complete62-test instrumentation claim. The geometry helper retains its reviewed fullscreen-coordinate assumption. Remote CI is manual-only and is not claimed green from local evidence.

## Historical v1 verification (not a current artifact certification)

Final app source regression: **197 JVM tests,60 instrumentation tests,0 failures,0 skips**. Separate self-instrumenting APK: **2/2** tests (genuinely different-UID controller rejected by the isolated R8 session + cold startup). Unminified isolated profile generator:1/1, 20565 unobfuscated rules. R8/resource shrinking and release lint passed; debug fixture CA absent from release; baseline.prof packaged.

Startup on API35 x86_64 emulator, SwiftShader, container10CPU/22GiB (test node2CPU/2GiB), CompilationMode.None,3 cold runs: [345.724395, 344.680033, 331.109231]ms; median **344.680ms**. This is an emulator measurement, not a claim about physical speed or a measured baseline-profile speedup. Perfetto traces remain in external evidence.

## Historical v1 APKs (not current release artifacts)

These artifacts were rebuilt from the sanitized Git projection itself, which also passed197 JVM tests, release lint/R8 and the complete60-test device suite. They replace earlier workspace artifact hashes.


Debug SHA256: `c7fb68b879a020a33cb31410fd929ee04d13395a81ce9c04370135d7705b90ff` (26024271 bytes).

Unsigned release SHA256: `bd4702be79f8a114fcdfa5f637def0546a440b9af6b9c5f291fbd33e36ccec3f` (2895821 bytes). Sign release with an external owner-controlled keystore; do not commit it.

## Real journeys

Live test-node model→native media_add: native dispatch counter increases by2 across before/after now-playing probes: one media_add handler and the after-probe, not two media side effects. Actual queue item and screenshots retained. A short normal-speed screenrecord shows the model result/native playback/Hide; its local QA WAV is intentionally silent, so no physical audibility is claimed.

HTTPS enrollment returns201; source collisions/ciphertext/presence, renewal including same-endpoint rotated credentials, scoped disable/master-off and auth-failure handling run through real sockets. Actual Android15 dataSync budget forced to5000ms; service disappears with unchanged PID, reopening foreground reconnects; original device_config/compat overrides restored. Airplane-mode loss recovers background delivery within the90-second stale-stream watchdog with no foreground return and unchanged PID. Test-node notify CLI still has stale historical fixture subscriptions, so a partial CLI exit is NOT reported as all-target success; the intended native alert/tap is independently captured. Production notify was never used.

Real read identity: wrong store leaves2 unseen items; exact store/session/sequence clears exactly1, through the native page bridge to the real test node. Real SIGKILL download resumes with Range+If-Range and no credential on the public fixture; final file1920044 bytes. Music/Harbour restore after a naturally stopped/saved activity. Force-stop/reinstall and unsaved live DOM are not claimed to preserve transient UI state.

Native R8 clean-install notification grant/deny/permanent-denial/channel recovery,200% font/dark/light/tablet/landscape/narrow-window/actual split-screen matrix, automated accessibility and real predictive Back were exercised. Camera/mic denial and pending-prompt navigation guards use real holder wiring with controlled permission answers; human physical capture/authenticator/TalkBack/speaker/headset/Bluetooth/carrier behavior remains unverified.

## Release lint disposition

{'Warning': 63, 'Hint': 5}; **0 errors/fatals**, not warning-free. Remaining warnings: newer dependency/Gradle suggestions (reviewed compatible pins retained rather than an unrelated AGP9 rewrite), KTX/autoboxing stylistic suggestions, ApplySharedPref on intentionally awaited IO commits, feature checks recognized through wrappers rather than lint's local analysis, exported MediaSessionService required for system clients (real foreign UID is rejected), application-context lifetime held by PushCenter (not an Activity), screen-size API migration suggestion, allocatable-space suggestion, intent-filter formatting and monochrome-icon variant lint (API33 has a dedicated monochrome resource). No permissive SSL trust manager, release debug CA or release HTTP fixture was added.

## Portable build / CI

Dockerfile actually built and account-free fresh-container emulator recipe passed59 tests at its integration checkpoint; subsequent final scoped changes passed the current60-test suite on the owned API35 emulator. The recipe selects a Pixel6 profile, mainKeys=no and gestural navigation, creates private TLS keys outside source, and checks instrumentation's final OK output rather than adb's exit code. SHA256 verification remains enabled; missing fresh-cache parent/BOM metadata and external-UID-test metadata were fetched from official HTTPS repositories and pinned, not ignored. GitHub Actions is **manual-only (`workflow_dispatch`)**, targeting `[self-hosted, linux, x64, android-kvm]`; push/PR events do not queue builds or consume hosted-runner credits. **No matching runner is registered or provisioned.** Before manually dispatching, the owner must supervise registration on existing trusted Linux x86_64 infrastructure, attach all four labels, provide a usable Docker daemon and accessible `/dev/kvm` (including device permissions inside Docker), and allow at least 4 CPUs/12 GiB for each configured build container plus host/emulator overhead and dependency-download access. Do not dispatch until that runner is available. No runner/service was installed as part of this handoff. These are locally verified container/emulator results, **not a remote CI-green claim**. The CI/docs-only follow-up leaves app/build/test inputs and delivered APKs unchanged; the accepted app verification remains at `5ce83670884e1c09bff2108cebfed6386a608cee`, without a redundant full-suite rerun.

## Public source release

The owner authorized public MIT source publication and transfer to the personal
account `SamSaffron` (not the `term-llm` organization). The source commit does not
itself change GitHub visibility or ownership. See RELEASE-PREPARATION.md and
PROVENANCE.md. Private QA evidence, signing material, recordings, builds and
reference/core repositories remain outside the source tree. Future APK releases
and their binary notices/signing/hardware checks are separate.

## Final SPA-history regression

A last live pushState check exposed native Back relying on page-load callbacks. Fixed by tracking actual current history locations, committing Back against the live WebView state, rebinding source listeners, avoiding stale AndroidView reloads, and deduplicating the native fallback stack. The expanded real-holder UI test covers full navigation, system/edge Back, SPA pushState/popstate, actual saved URL and a second source/profile. Three focused repetitions and the refreshed complete60-test suite passed. Web interactions use trusted input derived from real DOM element bounds; untrusted JS .click() would trigger Chromium history-skipping policy and is not a valid user-Back test. No production notify/remote publication occurred.
