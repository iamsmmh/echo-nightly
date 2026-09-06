# Echo Nightly — Android + iOS + Wear OS Kotlin Multiplatform music player

Echo is an extension-based music player: a client for **your own sources**.
The app ships with no music and bundles no content — you either import local
audio files or connect it to your own server (e.g. any Subsonic / OpenSubsonic
compatible server such as Navidrome), and on Android you can additionally
install the Echo extension ecosystem's dynamic extensions.

## Modules

One shared Kotlin Multiplatform core feeds three apps:

| Module             | What lives there                                                                 |
|--------------------|-----------------------------------------------------------------------------------|
| `common/`          | The public **extension API** (`dev.brahmkshatriya.echo:common`, published to Maven). Models, clients, streamables. Public signatures are frozen for third-party extension APKs. |
| `shared/`          | Platform abstractions: logging (`EchoLogger`, log redaction via `sanitizeUrl`), errors, `KeyValueStore`, HTTP client, files (`EchoFile` helpers), SHA-256, MD5, audio format sniffing, time formatting. |
| `core/`            | Pure recovery policies: `RetryPolicy` (exponential backoff + jitter), `WatchdogPolicy` (stall escalation), `RecoveryPolicy` (retry → next server → skip → stop). Fully unit tested. |
| `domain/`          | Playback engine contract, `QueueManager` (deterministic shuffle/repeat), `PlaybackController` (offline-first resolution, persistence, crash resume, bounded network retry + stall watchdog), sleep timer + crossfade + ReplayGain (audio FX). |
| `data/`            | Settings, playlists, favorites, history, downloads (state machine, resume via HTTP ranges, `*.echo.sha256` integrity sidecars), `CacheValidator` (timestamped + hash-checked cache envelopes). |
| `extensions/`      | Extension runtime, Subsonic/OpenSubsonic API client, local library client.        |
| `player/`          | Platform engines: `AndroidAudioPlayer` (Media3) and `IosAudioPlayer` (AVFoundation/AVPlayer + MPNowPlayingInfoCenter + MPRemoteCommandCenter + interruptions + route changes). |
| `composeApp/`      | Compose Multiplatform UI (Home/Search/Library/Queue/Player/Settings) + shared DI (`AppGraph`), hosted on iOS (and usable on Android). |
| `app-android/`     | The mature native Android app: Views UI, media3 `PlayerService`, Android Auto, notification, media session, dynamic DEX extension loader, **playback watchdog, crash-safe resume, auto network retry, Wear OS + Chromecast bridges**. |
| `wearApp/`         | Wear OS companion: play/pause/next/previous + now-playing card, synced over the Wearable Message API. |
| `app-ios/iosApp/`  | Xcode project (SwiftUI host, `EchoIosTests`, Live-Activity-ready state feed). |

```
                      ┌──────────────────────────────────────────┐
                      │              shared core (KMP)           │
                      │  common ─ extensions ─ shared ─ core     │
                      │  domain ─ data ─ player                  │
                      └───────┬──────────────┬─────────────┬─────┘
                              │              │             │
                       app-android/       composeApp/   (JVM/desktop
                       Media3 + Views +   Compose UI +  libraries via
                       Auto + Wear + Cast iOS host      gradle targets)
                                            ▲
                                     app-ios/iosApp (SwiftUI host)
```

## Features

- Streaming (first class — playback never requires downloading first)
- Offline playback (downloaded or imported files play without network)
- Queue with reorder, play-next/play-later, jump
- Deterministic shuffle (preserves the queue, never repeats current track,
  reshuffle keeps current track first) and repeat off/one/all — synchronized
  across UI, notification/lock screen on both platforms
- Downloads with progress, pause/resume (HTTP range), retry, duplicate
  detection, magic-byte file validation and SHA-256 integrity sidecars
- Local library import: Files app / document picker on iOS, offline extension
  (MediaStore) on Android
- Playlists, favorites, play history
- Lock screen / Control Center playback controls and metadata (iOS:
  `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter`; Android: media session)
- Background audio on both platforms (iOS background mode `audio`;
  Android foreground service)
- Audio interruptions (calls/Siri), route changes (headphone/Bluetooth
  disconnect) handled without crashes, playback resumption where supported
- Subsonic / OpenSubsonic streaming from your own server (token auth,
  bitrate/transcode settings, real ping validation)
- Dark/light theme following the system, responsive layouts (iPhone/iPad,
  portrait/landscape), accessibility labels on all player controls

## Stability mechanisms (Phase 1)

- **Playback watchdog**: the KMP `PlaybackController` re-prepares a remote
  stream that stalls (bounded, per-track recovery budget) and the Android
  `PlayerService` escalates *seek-resume → re-prepare → reload item → skip
  track* through `WatchdogPolicy` when playback freezes or buffers forever.
  Toggle in Settings → Player.
- **Crash-safe playback recovery**: the service continuously snapshots
  queue/position/play-state (`PlaybackRecoveryStore`); after a process kill or
  crash, the service restores and resumes automatically (setting *Resume after
  restart*). The KMP player persists its session through
  `KeyValuePlaybackPersister`.
- **Auto network retry**: stream re-resolution retries with jittered
  exponential backoff (`RetryPolicy.Playback` for Android, bounded retry on
  transient failures in the KMP controller); `RecoveryPolicy` falls over to
  the next available server before skipping.
- **Cache validation**: Android's `CacheUtils` and the KMP `CacheValidator`
  wrap entries in timestamped, SHA-256-checked envelopes; stale/corrupt
  entries are purged instead of decoded (stream URLs go stale after 6h by
  default).
- **Download/extension corruption detection**: finished downloads get a
  `*.echo.sha256` sidecar that is verified before playback (corrupted files
  fall back to streaming); extension APKs are size + ZIP-header sniffed before
  the parser runs, and WorkManager retries interrupted download sessions with
  backoff once connectivity returns.

## Audio features (Phase 5)

Dip-style crossfade (0–12s, Settings → Audio & Sleep), ReplayGain track/album
normalization with peak limiter, and a crash-safe sleep timer with a linear
15s fade-out — all implemented once in `domain/` (pure, unit tested) and
applied by the KMP player; the Android media3 app got the sleep-timer fade in
its existing sleep command. Gapless playback remains the platform default
(ExoPlayer / AVPlayer).

## Ecosystem (Phase 7)

- **Android Auto**: browse home/library/search/albums/playlists per extension
  with real paging, plus media-button focus handling.
- **Wear OS** (`wearApp/`): transport controls + now-playing card via the
  Wearable Message API (`/echo/command`, `/echo/state`).
- **Chromecast**: when a cast session is active (system media output picker),
  Echo mirrors the current track's direct stream URL to the receiver and
  forwards transport/seek controls; un-mirrors on session end
  (Settings → *Chromecast support*).
- **AirPlay / CarPlay audio (iOS)**: native `AVRoutePickerView` in the player
  row, `AVAudioSession` route-change handling; the now-playing snapshot is
  published to `UserDefaults` (`echo.nowplaying`) for widgets / Live
  Activities / a CarPlay template extension to consume.

## Requirements

- **Android**: Android Studio, JDK 17, Android SDK 36. Min SDK 24.
- **iOS**: macOS with Xcode 15+, JDK 17 (for the Kotlin framework),
  iOS 15.0+ deployment target, iPhone & iPad.

## Building

Prerequisites: JDK 17, Android SDK (for `app-android`/`wearApp`), Xcode 15+
(for `app-ios`), everything else via the Gradle wrapper.

```bash
./scripts/verify.sh            # static checks + tests + builds
./gradlew :app-android:assembleDebug   # phone APK
./gradlew :app-android:assembleNightly # nightly APK
./gradlew :app-android:assembleStable  # stable/release APK
./gradlew :wearApp:assembleDebug       # watch APK
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64  # macOS
open app-ios/iosApp/iosApp.xcodeproj   # then Build in Xcode
```

## Building the iOS app from the command line

```bash
SIMULATOR=$(xcrun simctl list devices available | grep -oE 'iPhone [^(]+' | head -1 | sed 's/ *$//')
xcodebuild -project app-ios/iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug \
  -destination "platform=iOS Simulator,name=$SIMULATOR" \
  -derivedDataPath build/DerivedData \
  CODE_SIGNING_ALLOWED=NO build
```

The Xcode project runs `:composeApp:embedAndSignAppleFrameworkForXcode` in a
"Compile Kotlin Framework" build phase, so Gradle must be runnable
(JDK 17 on PATH) — the framework is built automatically on every Xcode build.

## Unsigned IPA (testing / sideloading)

`.github/workflows/ios.yml` produces `Echo-iOS-unsigned.ipa` and
`Echo-iOS-app.zip` artifacts on every run, and `.github/workflows/release.yml`
on `v*` tags. The unsigned IPA is built with `CODE_SIGNING_ALLOWED=NO` and is
**not** App Store or TestFlight installable — re-sign it with a personal
Apple ID using Sideloadly/AltStore, or provide signing secrets (below).

## Code signing (optional)

The release workflow supports (all optional) GitHub secrets:

| Secret | Purpose |
|---|---|
| `IOS_CERTIFICATE_BASE64` | base64 of the distribution .p12 |
| `IOS_CERTIFICATE_PASSWORD` | .p12 password |
| `IOS_PROVISIONING_PROFILE_BASE64` | base64 of the .mobileprovision |
| `IOS_PROVISIONING_PROFILE_NAME` | profile name for `PROVISIONING_PROFILE_SPECIFIER` |
| `APPLE_TEAM_ID` | development team |
| `KEYCHAIN_PASSWORD` | temp keychain password used by CI |

Without them CI never fakes signing — it publishes the unsigned IPA.
Never commit certificates, profiles or passwords; `.gitignore` blocks them.

## Continuous Integration

- `.github/workflows/android.yml` — Android build + shared unit tests (ubuntu)
- `.github/workflows/ios.yml` — Kotlin iOS compilation (all targets), native
  unit tests, Xcode simulator build, XCTest launch tests, unsigned IPA
  packaging + validation (macOS)
- `.github/workflows/multiplatform.yml` — shared module matrix + framework
  API sanity checks
- `.github/workflows/release.yml` — manual/tagged releases: Android APK +
  iOS (un)signed IPA attached to a draft GitHub release
- `.github/workflows/android-aab.yml` — signed release Android App Bundle (AAB)
- `.github/workflows/release-tag.yml` — version automation: computes the next
  `3.0.<commit-count>` tag and pushes it to trigger the release pipeline
- `.github/workflows/codeql.yml` — CodeQL (Kotlin) static analysis
- `.github/workflows/security.yml` — dependency review on PRs, Gitleaks secret
  scan, scheduled O/S vulnerability scan
- `.github/workflows/sbom.yml` — CycloneDX SBOM generation per release
- `.github/dependabot.yml` — automated dependency update PRs (Gradle + Actions)

> **Note on desktop builds:** this repository currently targets Android + iOS
> from the shared KMP core and does **not** define a desktop (JVM) application
> target yet. No desktop CI workflow is added because there is no desktop
> target to build; see the roadmap below.

## Extension compatibility

The `:common` artifact API is the contract for third-party extensions loaded
via `DexClassLoader`. **It is frozen**: new code in this repository never
changes signatures or serialization names in `common/` (enforced by
`scripts/verify.sh` step 4). Built-in extensions (Offline library, Subsonic)
use exactly the same API surface as external ones.

## Privacy & security

See [`SECURITY.md`](SECURITY.md) for the vulnerability reporting policy. Summary:

- No analytics or tracking is bundled (Firebase is optional at build time and
  absent from CI builds).
- Credentials (e.g. Subsonic password) are stored only on-device via
  platform key-value stores and never logged (see `sanitizeUrl`).
- HTTPS is the default; plain HTTP is only allowed for local-network servers
  (iOS ATS `NSAllowsLocalNetworking`).
- Downloaded files are validated (magic bytes) before being marked complete.
- Supply chain: Dependabot (dependency scanning), CodeQL, Gitleaks secret
  scanning, O/S vulnerability scanning and CycloneDX SBOM generation all run
  from CI. Signing material and `google-services.json` are never committed.
- Enable native **GitHub secret scanning** for this repository
  (Settings → Code security) as the first line of defence for leaked tokens.
- Enable the repository **Dependency graph** (Settings → Code security) so
  Dependabot alerts populate and the PR dependency-review job can block on
  high-severity new dependencies.

## Roadmap (post-KMP migration)

The repository is a three-app Kotlin Multiplatform build (native Android Views
app, Compose Multiplatform iOS app over one shared `:composeApp` core, and a
Wear OS companion). The following are natural next steps, tracked separately:

1. **Unify the Android UI** onto the shared Compose Multiplatform UI (today
   Android uses the legacy View/Fragment UI in `app-android/`).
2. **Desktop target** — add a JVM/desktop target to `:composeApp` and a
   desktop CI workflow + installer artifacts.
3. **CarPlay template + iOS App Extension** — the now-playing data contract
   (`echo.nowplaying`) is in place; the ActivityKit/CarPlay appex needs
   provisioning + pbxproj target work.
4. **Full audio-effects surface** (equalizer, normalization presets) across
   both platform engines on top of the existing crossfade/ReplayGain.
5. **DEX extensions on Android remain** the primary third-party content path;
   built-in Local + Subsonic extensions cover iOS and are the reference for
   adding more built-ins.

## Legal

Echo is a client. It contains no music and no content sources. Users are
responsible for using sources they are entitled to. This fork follows the
upstream [Unabandon Public License](LICENSE.md); see `LICENSE.md` for
attribution requirements. Upstream project: [brahmkshatriya/echo](https://github.com/brahmkshatriya/echo).

## Contributing

1. Fork & branch from `main`
2. `./scripts/verify.sh --quick` must pass locally; CI must be green
3. Keep `commonMain` free of platform imports; put platform code behind the
   provided interfaces (`PlayerEngine`, `HttpClient`, `MusicStorage`,
   `MetadataReader`, `KeyValueStore`, `EchoLogger`)
4. No TODOs or placeholder implementations in production code paths

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for module responsibilities and data
flow, [`SECURITY.md`](SECURITY.md) for the disclosure policy, and
[`CHANGELOG.md`](CHANGELOG.md) for release notes.
