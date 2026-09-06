# Echo 1.0 readiness — engineering checkpoint

**Decision: NOT RELEASE-READY.** This is a partial implementation and validation
report, not a declaration that the requested Android/iOS/shared/Wear parity or
coverage targets have been achieved. Passing the platform workflow alone does
not qualify a production release.

Date: 2026-09-06 (Asia/Dhaka). Work is on `arena/01a0772e-echo-nightly`, based on
`6fe7d015f7872814759071de86eed2227b4c0639`. Changes were AI-assisted; tests and
remaining gaps are disclosed below, in accordance with `AI_POLICY.md`.

## Delivered changes

### Architecture and compatibility

- A tested, single module-graph checker rejects cycles, reverse dependencies,
  undeclared modules, platform imports in common code and UI dependencies in
  non-UI modules. The old duplicated hard-coded import graph was removed.
- Playback persistence moved from domain implementation code into data, behind
  the existing domain contract. Library reference/history models moved into
  domain without changing their package/serialization names.
- The published `common:1.0.0` extension contract was not changed. The legacy
  Android player and the KMP/iOS player intentionally continue to coexist.
- Android caches now reuse the shared checksum envelope while reading older
  raw/timestamped JSON. New cache files use hashed keys and atomic replacement;
  bounded eviction cannot loop forever on undeletable files. Present malformed
  integrity sidecars are rejected instead of silently falling back to legacy trust.
- Duplicate cancellation helpers were consolidated. Guava/coroutine bridges
  now complete on failures and propagate cancellation in both directions.
- This is **not** a completed UI/presentation separation: several composables
  still reach repositories through the existing `AppGraph` composition root.

### Playback and recovery

- Queue restoration, shuffle/current-item validation, playback intent,
  cancellation of stale stream resolutions, checkpoints and completion events
  have regression tests.
- iOS interruption, route-loss and audio-service-reset decisions are centralized
  in a tested policy. AVPlayer session activation, notification/remote-command
  cleanup, metadata and watchdog handling were hardened.
- The iOS artwork decoder, retained document-picker delegate and AirPlay picker
  bindings were fixed, as were Android Auto, WorkManager and Wear API errors.
- Automatic startup starts existing playback restoration and download health
  checks. This is not yet full download/sync/extension crash recovery.

### Search, recommendations and playlists

- `UniversalSearchService` aggregates enabled local/extension/Subsonic sources,
  ranks providers, merges tracks/albums/artists, retains provenance, handles
  cancellation/partial failure and uses revision-aware bounded caching.
- Search results and queues retain the originating provider for playback.
- Local recommendations cover recently/most played, forgotten favorites,
  genre-based moods, similar performers and album continuation. No external AI
  or listening-history upload is used.
- Listening aggregates are persisted independently of the capped history list.
- Validated, serializable smart rules and six built-ins use the existing playlist
  repository. Imported offline files are distinguished from download jobs.
- Home keeps local recommendations available when a remote feed fails.

### Downloads and lyrics

- The existing downloader has serialized scheduling, bounded concurrency,
  priorities, retries, generation guards, integrity checks and startup health
  repair. HTTP range/framing validation prevents incorrect append operations.
- Download requests refresh credentials/stream URLs rather than persisting an
  expiring request as the only way to retry.
- `core/lyrics` parses plain/LRC text and indexes line/word timing. A bounded,
  integrity-checked, provider/account-separated cache supports offline access.
- Lyrics use the existing extension API, including Subsonic/OpenSubsonic
  endpoints. Shared Compose UI supports following playback, line seeking,
  word highlighting, font scaling and saving user-provided lyrics.
- Portrait seek handling now converts a slider fraction into milliseconds.

### Protected credentials, themes and casting

- Shared settings use Android Keystore-backed EncryptedSharedPreferences and
  iOS device-only Keychain items. Secrets are not serialized into settings JSON.
- Credential generations, write/readback verification and a durable intent
  journal preserve recoverability across failed commits; cleanup is retried.
  New references are bound to the configured server/account.
- Settings are reactive; the leaking UI listener pattern was replaced with
  composition-scoped flow collection. Shared AMOLED and Android dynamic-color
  choices are available; the existing iOS palette remains the fallback.
- The Android Cast path uses Media3's forwarding player:
  one observable MediaSession player, asynchronous queue resolution, stale-work
  rejection and a paused return to local output. Source eligibility rejects
  local handles, DRM, live streams and app-only HTTP headers.
- This does **not** establish a unified Chromecast/AirPlay provider contract or
  hardware-tested Cast/AirPlay parity.

## Migration and upgrade notes

1. **Settings key is unchanged:** `echo.player.settings`. New fields have
   defaults. Legacy plaintext passwords are written to protected storage and
   read back before sanitized settings are durably committed.
2. `_credentialRef`, `_obsoleteCredentialRefs` and
   `echo.player.credentials.pending` contain opaque references, not passwords.
   Old generations remain available until the new settings commit succeeds;
   interrupted writes are reconciled on restart.
3. **Internal integration changes:** `SettingsRepository` requires an explicit
   `SecureStorage`; `KeyValueStore` implementations provide
   `putStringDurably`. All in-tree callers were updated. There is no implicit
   ephemeral secret store in production composition.
4. Android excludes `secure_echo_player.xml` from cloud/device-transfer backup.
   iOS uses `AfterFirstUnlockThisDeviceOnly`. Restoring settings onto a different
   device does not restore the protected credential; sign-in is required.
   Historical backups that already contained plaintext cannot be erased by
   this migration. Legacy Android extension credential stores are not covered.
5. Existing history seeds only the plays actually present in the old history
   document; it does not invent prior play counts. Existing user playlists are
   retained. Smart playlists have their own versioned key/schema.
6. Existing download entries remain readable. Corrupt/partial files are not
   accepted as completed media. Segmented downloads and durable iOS background
   resume data are not yet implemented.
7. The extension ABI was preserved; internal refactors are not permission to
   change the frozen `common` contract. No new release tag/version or signed
   distributable has been issued by this work.

## Build and test evidence

Evidence is published by `.github/workflows/production-readiness.yml` as GitHub
check annotations and build artifacts, not committed build logs. The workflow
requires architecture, shared JVM, Android/Wear compilation, Android unit and
emulator tests, three iOS compilations, native tests and app-hosted XCTest.
Missing test output, failures and skips do not count as a pass.

| Commit / run | Verified outcome |
|---|---|
| `acc867e` / [34041083865](https://github.com/iamsmmh/echo-nightly/actions/runs/34041083865) | Architecture passed; **145 JVM tests passed**. Three iOS Kotlin targets compiled. Native tests reached 122 passes but a JVM-only fixture did not compile. Android failed on legacy source/API errors. |
| `2cd419c` / [34041886688](https://github.com/iamsmmh/echo-nightly/actions/runs/34041886688) | **169 JVM tests passed**. Three iOS Kotlin targets compiled and the **host build/XCTest step passed**. Native run: 170 tests, one Keychain test failure in the standalone runner (`errSecNotAvailable`); that integration test is moved, not skipped, into app-hosted XCTest. Android failed on Cast APIs. |
| `d4aea3a` / [34043006014](https://github.com/iamsmmh/echo-nightly/actions/runs/34043006014) | Architecture, **173 JVM tests and 173 iOS native tests passed**. All three native targets compiled. Android found six legacy utility/radio compile errors; XCTest compilation found three `remove(key_:)` argument-label errors. Device tests were blocked by Android compilation. Corrections are in the next increment. |

At `0065a89` / [34047283442](https://github.com/iamsmmh/echo-nightly/actions/runs/34047283442),
Android/Wear/shared UI compilation and **8 Android unit tests passed**; **2 Android
emulator tests passed**, exercising the real Keystore and Cast queue routing.
JVM and native suites each passed **173 tests**. XCTest ran **5 tests: 4 passed,
1 failed**. The app-hosted test exposed a real Keychain CFBoolean-bridging error
(`errSecParam`, -50), rather than a permissions failure. A native CFDictionary
implementation corrects the representation in the next increment. The separate
startup probe also failed without sufficient diagnostics; it now installs the
exact APK and publishes its failures through the CI diagnostic wrapper.

At `7005b3a` / [34048028030](https://github.com/iamsmmh/echo-nightly/actions/runs/34048028030),
compilation and the 173 JVM / 173 native / 8 Android / 2 emulator tests passed.
The app-hosted run could not launch its simulator (0 XCTest passes), so the
Keychain correction is not yet runtime-verified. CI now boots a separate,
isolated simulator after the standalone tests. The startup probe correctly
refused a zero-time permission-controller result; subsequent probes pre-grant
runtime permissions and require both a cold launch and Echo's own activity.

At `ca1b4a6` / [34049189038](https://github.com/iamsmmh/echo-nightly/actions/runs/34049189038),
the **entire iOS lane passed**: all three targets compiled, 173 native tests
passed, and **all 5 app-hosted XCTest tests passed**, including Keychain
round-trip/update/delete/isolation on the iOS 26.2 simulator. Android/Wear/shared
UI compilation, 8 Android unit tests and 2 emulator tests also passed. The
startup probe still reported an unknown launch state without a first-frame
time; those are not accepted as measurements. A launcher lifecycle test and
crash/activity-log capture are added to distinguish a real startup problem from
measurement setup.

Local static/YAML checks passed. The Python verification suite has **25 executed passing tests**, including
benchmark-evidence validation. The first-frame probe is wired to the Android
emulator lane; a result is not asserted until it has actually run. Local Kotlin/Android builds are unavailable:
Gradle distribution access is blocked and the sandbox lacks the Android SDK/full
JDK. Remote CI is the source of platform compilation/test evidence.

**Coverage has not been measured.** Test counts are not coverage percentages.
The requested unit 90%, integration 85%, UI 80% and overall 85% thresholds remain
unproven. Native compilation and simulator tests do not prove Bluetooth,
headset, background audio, casting, power-use or physical-device behavior.

## Performance evidence

| Requirement | Current evidence |
|---|---|
| Android cold start <2 s | Not yet measured on a release build/device. A process-cold debug/emulator first-frame probe is being added; it is not hardware or fully-interactive certification. |
| iOS cold start <2 s | Not measured. |
| Search <150 ms | Not measured; aggregation can wait for remote providers. |
| Library open <300 ms | Not measured. |
| Queue restoration <1 s | Correctness tested; latency not measured. |

The Android probe installs the debug APK with runtime permissions pre-granted,
force-stops the app between samples, and rejects warm launches, zero timings and
permission-controller/other-app activity results. This measures first-frame
latency only, not onboarding or fully-interactive startup. Raw samples and
nearest-rank p95 are retained as `build/verification/android-startup.json` in the
CI artifact. No latency, battery, parity or coverage percentage is asserted
without evidence.

## Release blockers / unfinished requested scope

| Area | Remaining work |
|---|---|
| Architecture | Complete presentation-boundary enforcement and a broader dead-code/API audit. |
| iOS playback | Physical-device interruption/headset/Bluetooth/background validation, authenticated HTTP-header streaming, recovery UX for unavailable protected storage. |
| Casting | Unified provider contract; complete discovery/handoff/session-restore validation on Chromecast and AirPlay 2; real receiver acceptance/error tests. |
| Search | Measured latency, progressive fast-local results, more provider integration/load tests. |
| Recommendations/playlists | Broader integration/UI tests, accessible rule editing and deletion UX. |
| Lyrics | Automated Compose interaction/scroll tests and provider/device offline validation. |
| DSP | Requested shared presets and Media3 processors plus a real iOS AVAudioEngine DSP path. |
| Download V2 | Segmented/parallel chunks, bandwidth controls, durable iOS background resumption and broader repair integration tests. |
| Sync | Offline-first metadata/artwork/playlists/favorites/history sync, conflicts, checkpoints and recovery. |
| Marketplace | Cross-platform catalog, compatibility/trust/signature verification, publisher roots and update/install integration. |
| Security | Legacy Android extension credentials, TLS/cleartext policy, certificate pinning and blocking dependency/security verification. |
| Recovery | Full startup sync/extension/background-download recovery. |
| Telemetry | Explicit opt-in local buffering/export/upload policy; the default-false setting alone is not an implementation. |
| Performance | Release/device traces, cold-start/search/library/queue benchmarks, paging/caching profiles and regressions. |
| WearOS | Standalone offline playback/library/queue/download/artwork sync and battery validation; current app remains principally a remote control. |
| UI | Full tablet/foldable/landscape, animations, accessibility and UI regression coverage. |
| Release engineering | Blocking release/lint/detekt/security/SBOM/coverage gates, minified-build extension-ABI checks, signed APK/AAB/IPA/Wear artifacts, semantic version/changelog automation and upgrade qualification. |

A remaining legacy marker exists in Android `AudioFocusListener` for playback
started during an active call. It is not removed merely to make a text scan
pass. The requested zero-unfinished-marker release criterion is not met.

## File inventory

See [changed-files.txt](changed-files.txt) for the complete added/modified/deleted
file inventory relative to the session base. Major code locations are:

- `scripts/architecture.py`, `scripts/ci_run.py`, test/benchmark evidence scripts
  and `.github/workflows/production-readiness.yml`.
- `domain/.../audio`, `domain/.../domain/{search,recommendations,playlists}` and
  `domain/.../library/LibraryModels.kt`.
- `core/.../core/{recovery,lyrics,casting}` and their common tests.
- `data/.../{audio,download,library,lyrics}` and regression/migration tests.
- `shared/src/*/.../{platform,security}` and platform factories.
- `extensions/.../ExtensionRuntime.kt`, Subsonic API/client and provider tests.
- `player/.../{audio,library}` and shared Compose graph/screens/platform actuals.
- Android service/Auto/Cast/Wear bridges, native credential/device tests, and
  iOS host XCTest.
