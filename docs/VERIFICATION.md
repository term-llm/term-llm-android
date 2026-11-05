# Verification and limits

Final app source regression: **197 JVM tests,60 instrumentation tests,0 failures,0 skips**. Separate self-instrumenting APK: **2/2** tests (genuinely different-UID controller rejected by the isolated R8 session + cold startup). Unminified isolated profile generator:1/1, 20570 unobfuscated rules. R8/resource shrinking and release lint passed; debug fixture CA absent from release; baseline.prof packaged.

Startup on API35 x86_64 emulator, SwiftShader, container10CPU/22GiB (test node2CPU/2GiB), CompilationMode.None,3 cold runs: [356.216041, 341.567363, 338.135274]ms; median **341.567ms**. This is an emulator measurement, not a claim about physical speed or a measured baseline-profile speedup. Perfetto traces remain in external evidence.

## APKs

These artifacts were rebuilt from the sanitized Git projection itself, which also passed197 JVM tests, release lint/R8 and the complete60-test device suite. They replace earlier workspace artifact hashes.


Debug SHA256: `16a080fbfe2dd861937665d3987a5d419de9f81addab8c6d0d684dc38f9833ec` (25532158 bytes).

Unsigned release SHA256: `ac49cb35fbb23a44d62c9aeede13147811af9766cbfc43e95496ae8600cdd842` (2895821 bytes). Sign release with an external owner-controlled keystore; do not commit it.

## Real journeys

Live test-node model→native media_add: native dispatch counter increases by2 across before/after now-playing probes: one media_add handler and the after-probe, not two media side effects. Actual queue item and screenshots retained. A short normal-speed screenrecord shows the model result/native playback/Hide; its local QA WAV is intentionally silent, so no physical audibility is claimed.

HTTPS enrollment returns201; source collisions/ciphertext/presence, renewal including same-endpoint rotated credentials, scoped disable/master-off and auth-failure handling run through real sockets. Actual Android15 dataSync budget forced to5000ms; service disappears with unchanged PID, reopening foreground reconnects; original device_config/compat overrides restored. Airplane-mode loss recovers background delivery within the90-second stale-stream watchdog with no foreground return and unchanged PID. Test-node notify CLI still has stale historical fixture subscriptions, so a partial CLI exit is NOT reported as all-target success; the intended native alert/tap is independently captured. Production notify was never used.

Real read identity: wrong store leaves2 unseen items; exact store/session/sequence clears exactly1, through the native page bridge to the real test node. Real SIGKILL download resumes with Range+If-Range and no credential on the public fixture; final file1920044 bytes. Music/Harbour restore after a naturally stopped/saved activity. Force-stop/reinstall and unsaved live DOM are not claimed to preserve transient UI state.

Native R8 clean-install notification grant/deny/permanent-denial/channel recovery,200% font/dark/light/tablet/landscape/narrow-window/actual split-screen matrix, automated accessibility and real predictive Back were exercised. Camera/mic denial and pending-prompt navigation guards use real holder wiring with controlled permission answers; human physical capture/authenticator/TalkBack/speaker/headset/Bluetooth/carrier behavior remains unverified.

## Release lint disposition

{'Warning': 63, 'Hint': 5}; **0 errors/fatals**, not warning-free. Remaining warnings: newer dependency/Gradle suggestions (reviewed compatible pins retained rather than an unrelated AGP9 rewrite), KTX/autoboxing stylistic suggestions, ApplySharedPref on intentionally awaited IO commits, feature checks recognized through wrappers rather than lint's local analysis, exported MediaSessionService required for system clients (real foreign UID is rejected), application-context lifetime held by PushCenter (not an Activity), screen-size API migration suggestion, allocatable-space suggestion, intent-filter formatting and monochrome-icon variant lint (API33 has a dedicated monochrome resource). No permissive SSL trust manager, release debug CA or release HTTP fixture was added.

## Portable build / CI

Dockerfile actually built and account-free fresh-container emulator recipe passed59 tests at its integration checkpoint; subsequent final scoped changes passed the current60-test suite on the owned API35 emulator. The recipe selects a Pixel6 profile, mainKeys=no and gestural navigation, creates private TLS keys outside source, and checks instrumentation's final OK output rather than adb's exit code. SHA256 verification remains enabled; missing fresh-cache parent/BOM metadata and external-UID-test metadata were fetched from official HTTPS repositories and pinned, not ignored. CI config is supplied but no public service was registered.

## Publication gate

Only the sanitized projection is eligible for a new private repository. It has no inherited history. Run source and complete-history secret scans before handoff; do not include external evidence, private fixture credentials, records, recordings, emulator data, builds, iOS reference or core copies. Owner/parent reviews the scan/commit evidence and chooses/creates the private remote. No remote is configured by the implementation agent. Licensing remains private/no redistribution; see PROVENANCE.md and licenses/Apache-2.0.txt.
