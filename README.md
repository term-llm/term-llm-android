# term-llm for Android (preview)

Native Agents / Chat / Listen / Music client. Chat uses the server-owned WebView;
device tools require both app/source consent and the page's own opt-in. Queue,
playback, offline downloads and per-source Web Push routes are native.

## License and release status

Original project-authored code is licensed under [MIT](LICENSE), Copyright (c)
2026 Sam Saffron. [Third-party components](PROVENANCE.md) retain their own terms.
This is a public-source release; the intended repository owner is
[SamSaffron/term-llm-android](https://github.com/SamSaffron/term-llm-android) after
its owner-operated transfer. See [source release notes](docs/RELEASE-PREPARATION.md)
and [security reporting](SECURITY.md).

The v3 OS-theme and keyboard-inset fixes are included in main (PR #1). This is
still a preview, not a claim of full Apple-client parity or guaranteed background
delivery. APK distribution, signing and hardware qualification are separate from
this source release; no APK release is performed by these build recipes.

## Build

Requirements: JDK 17, Android SDK platform 36 + build-tools 36.0.0. Use the checked-in
Gradle 8.13 wrapper (distribution and artifact checksums are pinned).

```sh
./gradlew --no-daemon testDebugUnitTest lintRelease assembleRelease
# Or a portable, account-free container:
docker build -t termllm-android-build .
docker run --rm --cpus=12 --memory=24g termllm-android-build
```

Release APKs are unsigned; sign with your own external keystore. Do not check it
in. Release enables R8 and resource shrinking, requires valid system-trusted HTTPS,
and has no default push relay or test activities/providers/CA. The debug build
allows HTTP only for loopback/emulator hosts and trusts a scoped local fixture CA.
No TLS validation bypass is used in any variant.

## Real emulator checks

Use only a disposable owned emulator: tests mutate queue and app settings. Never
run this harness against a production account/device. No accounts are needed for
the automated suite. The device script starts loopback fixtures and a fresh local
TLS CA, builds debug app/test APKs and verifies instrumentation's final OK result.

```sh
# Inside the container with /dev/kvm passed through, or your own SDK shell:
TEST_SECRETS_DIR=$(mktemp -d) testing/verify-device.sh
# Isolated package com.termllm.app.benchmark; never uninstalls main app:
testing/generate-profiles.sh
./gradlew :benchmark:connectedBenchmarkAndroidTest \
 -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

For a separate test node, enroll using the app's Add connection UI. Do not bake
credentials into URLs/images/scripts. Mount private config files read-only from
outside the tree. Notifications need contextual Android permission, an enabled
Agent alerts channel, source enrollment, and an HTTPS relay configured for that
source in Settings. The fixture relay is deliberately NOT a production service:
in-memory state, one container, bounded queues. Continuous Android SSE delivery is
subject to OS background/dataSync time limits; foreground return reconnects. This
is not FCM and must not be advertised as always-on guaranteed background delivery.

## Security / acceptance

See docs/HARDENING.md and docs/VERIFICATION.md. Hardware speaker/headset/Bluetooth,
carrier transitions, human TalkBack usability, and physical authenticator behavior
remain explicit hardware checks. Emulator benchmark numbers are not device claims.
Publication is owner-gated; build recipes do not create a remote or publish an APK.
See LICENSE and PROVENANCE.md before any redistribution.

### External QA certificate mounts

`testing/config.env.example` contains placeholders only. Set the certificate/key
paths to files OUTSIDE the checkout, then mount read-only. Do not put real values
in Docker build arguments or copy private config into the image. Example for a
loopback-only independent QA relay (not a production deployment):

```sh
: "${QA_CERT_FILE:?external public certificate path}"
: "${QA_KEY_FILE:?external private key path}"
docker run --rm --network=host \
 --mount "type=bind,source=$(realpath "$QA_CERT_FILE"),target=/run/secrets/relay.crt,readonly" \
 --mount "type=bind,source=$(realpath "$QA_KEY_FILE"),target=/run/secrets/relay.key,readonly" \
 termllm-android-build python3 testing/push_relay.py --bind 127.0.0.1 \
 --cert /run/secrets/relay.crt --key /run/secrets/relay.key
```

For the all-in-one disposable emulator harness, use a temporary secrets directory
or a writable tmpfs at runtime; `prepare-tls.sh` refuses paths inside the source
tree. The checked-in debug CA is public, not its private key. `.env`/private-key
files are excluded from both Git and Docker contexts by default.
