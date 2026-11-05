# Provenance and third-party components

This is a private Android implementation of the term-llm client contract, developed
against a private iOS reference and the term-llm protocol at core revision
`2496b5fd`. Neither reference tree nor core source is included. Protocol constants
are compatibility pins, not a claim that this snapshot contains upstream core.
Confirm derivative-work ownership/licensing with the owner before public reuse.

App code, Kotlin tests, relay fixtures and build/test recipes are included. No
personal accounts, model credentials, push identities, private CA keys, captured
conversations, databases, recordings, or reference repositories are included.
Generated Android Baseline/Startup Profiles contain code rules, not user data.
The checked-in public fixture CA is DEBUG ONLY; generate a fresh local CA/private
key with testing/prepare-tls.sh. Release resources do not include it.

Dependencies are declared in app/build.gradle.kts and benchmark/build.gradle.kts:
AndroidX/Media3/WorkManager, Kotlin, Gradle/AGP, OkHttp/Okio, Tink, JUnit and test
libraries retain their upstream Apache-2.0 or other applicable license notices.
Gradle wrapper is upstream Gradle (Apache-2.0). Consult resolved Maven POM license
metadata before distributing binary artifacts; this is not a relicensing notice.

Gradle artifact SHA-256 verification metadata was bootstrapped from HTTPS official
Google/Maven Central/Gradle repositories during the tested build. It pins the
observed artifacts, not an independent security audit. The Gradle 8.13 wrapper jar
was compared/replaced with the official v8.13.0 upstream jar; its SHA-256 is
81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f.

The upstream Apache-2.0 text for the Gradle wrapper/Apache components is in licenses/Apache-2.0.txt. No upstream Gradle NOTICE file exists at the pinned tag; no fabricated notice is supplied.

Frozen pairing-v2 JSON conformance vectors are the only cryptographic fixture copied into tests (not runtime resources). Their seeds/pairing secret are sequential public test bytes; derived MAC/public keys are non-operational. Four generic-api-key scanner hits were reviewed as these exact values. .gitleaks.toml exempts ONLY those exact bytes AND that exact test-resource path, not test directories or generic secrets.
