# Echo Nightly — Android + iOS Kotlin Multiplatform music player

Echo is an extension-based music player: a client for **your own sources**.
The app ships with no music and bundles no content — you either import local
audio files or connect it to your own server (e.g. any Subsonic / OpenSubsonic
compatible server such as Navidrome), and on Android you can additionally
install the Echo extension ecosystem's dynamic extensions.

This repository contains **two applications built from one shared Kotlin
Multiplatform core**:

| | Android app (`app/`) | iOS app (`iosApp/` + `composeApp/`) |
|---|---|---|
| UI | The mature native Android UI (Views/Fragments) | Compose Multiplatform UI |
| Audio | Media3 / ExoPlayer (`PlayerService`, media session, notification) | AVFoundation / AVPlayer + `AVAudioSession` |
| Shared | `:common` extension API + `:composeApp` shared core (domain, queue, downloads, extensions, settings, persistence) | same shared core |

## Architecture

```
            ┌───────────────────────────────────────────┐
            │        Shared Kotlin core (KMP)           │
            │  common/   extension API + models (KMP)   │
            │  composeApp/                               │
            │    commonMain: domain, queue, shuffle/     │
            │      repeat, downloads, settings, search,  │
            │      playlist logic, extension runtime,    │
            │      Compose Multiplatform UI              │
            │    androidMain: ExoPlayer engine,          │
            │      HttpURLConnection, MediaMetadataRetriever │
            │    iosMain: AVPlayer engine (AVAudioSession,│
            │      MPNowPlayingInfoCenter,               │
            │      MPRemoteCommandCenter), NSURLSession, │
            │      AVAsset metadata                      │
            └───────────────┬───────────────┬───────────┘
                            │               │
                    Android (app/)      iOS (iosApp/)
                    Media3 + Views      SwiftUI host + Compose
```

**What is shared** (one implementation, unit-tested in `commonTest`):
`Track`/`Album`/`Artist`/`Playlist` domain models (from `:common`),
`QueueManager` (deterministic queue-safe shuffle, repeat off/one/all),
`PlaybackController` (offline-first stream resolution, persistence,
resumption), the download system (queued/downloading/paused/completed/
failed/cancelled with progress, pause/resume/retry and file validation),
`ExtensionRuntime` (Echo's extension API with built-in extensions: **Offline
Library** and **Subsonic**), settings, playlists, favorites, history, search,
error types (`EchoError`) and logging (`EchoLogger`).

**What stays native:** the Android app keeps its proven View-based UI,
Media3 `PlayerService` (notification, lock screen, Android Auto, audio focus)
and its DEX extension loader; iOS implements playback with AVFoundation and
its SwiftUI host owns the app lifecycle. Dynamic (DEX) extensions are an
Android-only capability; on iOS the same extension API is served by the
built-in extensions.

## Features

- Streaming (first class — playback never requires downloading first)
- Offline playback (downloaded or imported files play without network)
- Queue with reorder, play-next/play-later, jump
- Deterministic shuffle (preserves the queue, never repeats current track,
  reshuffle keeps current track first) and repeat off/one/all — synchronized
  across UI, notification/lock screen on both platforms
- Downloads with progress, pause/resume (HTTP range), retry, duplicate
  detection, magic-byte file validation
- Local library import: Files app / document picker on iOS, offline extension
  (MediaStore) on Android
- Playlists, favorites, play history
- Lock screen / Control Center playback controls and metadata (iOS:
  `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter`; Android: media session)
- Background audio on both platforms (iOS background mode `audio`;
  Android foreground service)
- Audio interruptions (calls/Siri), route changes (headphone/Bluetooth
  disconnect) handled without crashes, playback resumption where supported
- Playback resilience: bounded automatic network retry (exponential backoff for
  transient failures), a stall watchdog that transparently re-prepares remote
  streams, and crash-safe session persistence/resumption (unit tested)
- Subsonic / OpenSubsonic streaming from your own server (token auth,
  bitrate/transcode settings, real ping validation)
- Dark/light theme following the system, responsive layouts (iPhone/iPad,
  portrait/landscape), accessibility labels on all player controls

## Requirements

- **Android**: Android Studio, JDK 17, Android SDK 36. Min SDK 24.
- **iOS**: macOS with Xcode 16+, JDK 17 (for the Kotlin framework),
  iOS 15.0+ deployment target, iPhone & iPad.

## Development

```bash
# shared unit tests (queue/shuffle/repeat/downloads/…)
./gradlew :composeApp:testDebugUnitTest     # via Android target
./gradlew :composeApp:iosSimulatorArm64Test # on macOS

# Android
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:assembleNightly      # nightly APK
./gradlew :app:assembleStable       # release APK

# iOS framework (builds from any host; running tests needs macOS)
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64

# iOS app (macOS)
open iosApp/iosApp.xcodeproj   # scheme "iosApp", pick a simulator, Cmd+R

# everything CI does, locally
./scripts/verify.sh
```

## Building the iOS app from the command line

```bash
SIMULATOR=$(xcrun simctl list devices available | grep -oE 'iPhone [^(]+' | head -1 | sed 's/ *$//')
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
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

## Roadmap (post-KMP migration)

The repository is a two-app Kotlin Multiplatform build (native Android Views
app + Compose Multiplatform iOS app over one shared `:composeApp` core). The
following are natural next steps, tracked separately:

1. **Unify the Android UI** onto the shared Compose Multiplatform UI (today
   Android uses the legacy View/Fragment UI in `app/`).
2. **Desktop target** — add a JVM/desktop target to `:composeApp` and a
   desktop CI workflow + installer artifacts.
3. **WearOS / watchOS / CarPlay / Chromecast surfaces** on top of the shared
   queue + playback state and the platform media-session integrations.
4. **Full audio-effects surface** (equalizer, ReplayGain, normalization,
   crossfade/gapless) across both platform engines.
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
