# Echo

<img src="docs/assets/echo-logo.png" alt="Echo" width="128" height="128" />

Echo is an extension-based music player for **your own sources**. It ships with
no music and bundles no content — import local files, or connect a Subsonic /
OpenSubsonic server you operate (for example Navidrome). On Android you can
also install the Echo extension ecosystem’s dynamic extensions.

[![Android builder](https://github.com/iamsmmh/echo-nightly/actions/workflows/android-build.yml/badge.svg)](https://github.com/iamsmmh/echo-nightly/actions/workflows/android-build.yml)
[![iOS IPA builder](https://github.com/iamsmmh/echo-nightly/actions/workflows/ios-build.yml/badge.svg)](https://github.com/iamsmmh/echo-nightly/actions/workflows/ios-build.yml)
[![Release](https://github.com/iamsmmh/echo-nightly/actions/workflows/release.yml/badge.svg)](https://github.com/iamsmmh/echo-nightly/actions/workflows/release.yml)
[![Android CI](https://github.com/iamsmmh/echo-nightly/actions/workflows/android.yml/badge.svg)](https://github.com/iamsmmh/echo-nightly/actions/workflows/android.yml)
[![iOS CI](https://github.com/iamsmmh/echo-nightly/actions/workflows/ios.yml/badge.svg)](https://github.com/iamsmmh/echo-nightly/actions/workflows/ios.yml)

## Features

| Feature | Status |
|---|---|
| Streaming playback | **Stable** |
| Offline playback (downloads / local files) | **Stable** |
| Queue, shuffle, repeat off / one / all | **Stable** |
| Play next / play later, reorder, restore | **Stable** |
| Downloads with pause, resume, SHA-256 integrity | **Stable** |
| Playlists, favorites, history | **Stable** |
| Lyrics (plain + timestamped, optional) | **Stable** |
| Extensions (DEX on Android; built-in Local + Subsonic everywhere) | **Stable** |
| Subsonic / OpenSubsonic | **Stable** |
| Background playback, lock-screen / notification controls | **Stable** |
| Crash-safe queue restore, bounded retry, playback watchdog | **Stable** |
| ReplayGain + dip crossfade + sleep timer | **Stable** |
| Android Auto browse / search / play | **Stable** (vehicle QA still recommended) |
| Wear OS companion transport | **Stable** (needs a connected phone) |
| Chromecast session mirroring | **Platform-dependent** (Android) |
| AirPlay routing | **Platform-dependent** (iOS) |
| Equalizer | **Platform-dependent** (Android system EQ) |
| Live Activity / Dynamic Island | **Experimental** (now-playing feed only) |
| CarPlay UI templates | **Planned** (audio routing works; no CarPlay app) |
| Desktop | **Missing** |

## Supported Platforms

- **Android** 8.0+ (API 24), including Android Auto
- **Wear OS** companion (`wearApp/`)
- **iOS** 15+ (iPhone and iPad)

There is no desktop application target.

## Architecture

```
common          frozen public extension API
   ↓
shared          logging, errors, HTTP, files, hashing
   ↓
core            RetryPolicy, WatchdogPolicy, recovery
   ↓
domain          PlaybackController → QueueManager → recovery
   ↓
data            settings, library, downloads, cache
   ↓
player          Media3 / AVFoundation engines
extensions      Subsonic + local library runtime
composeApp      shared Compose UI (iOS host; reusable on Android)
app-android     production Android Views app + PlayerService
wearApp         Wear OS companion
app-ios         SwiftUI host
```

Playback is centralized:

```
PlaybackController
        ↓
QueueManager
        ↓
PlaybackRecovery / RetryPolicy / WatchdogPolicy
        ↓
Platform PlayerEngine (AndroidAudioPlayer / IosAudioPlayer)
```

Android’s shipped UI remains the battle-tested Views + `PlayerService` stack
(notifications, Android Auto, Cast, audio effects). iOS hosts `composeApp`.
Do not treat an unfinished Compose-on-Android migration as the release UI.

## Extensions

`:common` (`dev.brahmkshatriya.echo:common`) is the **frozen** contract for
third-party extension APKs. Invalid, incompatible, or crashing extensions are
isolated at the loader boundary and must not take down the app.

Built-in extensions:

- **Local / offline library** — Files on iOS, MediaStore on Android
- **Subsonic / OpenSubsonic** — token auth, search, browse, stream, download

Android additionally loads installed DEX extensions. Credentials never appear
in logs; URLs are sanitized before logging.

## Playback

- Stream URLs are treated as **expirable**. Failure invalidates the cached
  URL, re-resolves, then tries an alternate server before skipping.
- Shuffle keeps the current item and does not immediately replay it.
- Repeat One replays the current track; Repeat All wraps; Repeat Off stops.
- Queue, index, position, repeat, and shuffle persist across process death.
  Stale stream URLs are never treated as permanent playback truth.
- The watchdog recovers only when playback is expected, the engine reports
  playing or buffering, and position does not advance past the stall timeout.

## Downloads

Queued → downloading → paused → completed / failed / cancelled, with HTTP
Range resume, duplicate detection, filename sanitization, magic-byte checks,
SHA-256 sidecars, bounded exponential backoff, and leftover `.part` / `.tmp`
cleanup. A failed transfer is never marked complete.

## Android Auto

Root browsing, per-extension home / library / search, albums, playlists,
tracks, and paged shelves. Stale media IDs return empty results instead of
crashing the session.

## Wear OS

Play / pause / next / previous and now-playing, synced over the Wearable
Message API. If the phone is not connected the watch shows that state instead
of assuming a live session.

## Chromecast

**Android.** When a Cast session is active, Echo mirrors the current direct
stream and transport/seek controls. Ending the session restores local
playback without dropping the queue. Local and Cast output are not left
active together.

## iOS / AirPlay

AVFoundation playback, `AVAudioSession` interruptions and route changes,
`MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` (registered once and
removed on release), and native AirPlay routing.

CarPlay **audio** can ride the system now-playing path. A CarPlay template
application is **not** implemented and is not advertised as shipping.

The `echo.nowplaying` UserDefaults feed is the contract for a future Live
Activity. ActivityKit UI is not bundled.

## Build

Prerequisites: JDK 17, Android SDK 36 (for Android/Wear), Xcode 15+ (for iOS).

```bash
./scripts/verify.sh --quick
./gradlew :app-android:assembleDebug
./gradlew :app-android:assembleRelease
./gradlew :app-android:bundleRelease
./gradlew :wearApp:assembleDebug
./gradlew :shared:jvmTest :core:jvmTest :domain:jvmTest :data:jvmTest
```

### Android

```bash
./gradlew :app-android:assembleStable   # release-equivalent APK
./gradlew :app-android:bundleStable     # Play-style AAB
```

Version: `3.0.<git-commit-count>` (`versionName` / `versionCode` from Git).

### iOS

```bash
open app-ios/iosApp/iosApp.xcodeproj
```

The Xcode project runs `:composeApp:embedAndSignAppleFrameworkForXcode`.
Unsigned IPA packaging is done in CI (`CODE_SIGNING_ALLOWED=NO`) and is **not**
App Store / TestFlight installable — re-sign with Sideloadly/AltStore or
provide signing secrets.

## GitHub Actions

| Workflow | Purpose |
|---|---|
| `android-build.yml` | Tests + release APK + AAB (`Echo-Android.apk` / `.aab`) |
| `ios-build.yml` | KMP framework + unsigned IPA (`Echo-iOS-unsigned.ipa`) |
| `release.yml` | Tag `v*`: test, Android + unsigned IPA, GitHub Release |
| `android.yml` / `ios.yml` | Additional CI lanes |
| `codeql.yml` / `security.yml` | CodeQL, Gitleaks, dependency review |

Release artifacts use deterministic names:

- `Echo-Android.apk`
- `Echo-Android.aab`
- `Echo-iOS-unsigned.ipa`

The pipeline fails if tests fail or an artifact is missing. Signing material
is never committed; optional GitHub secrets are used only when present.

## Extension Development

Keep `:common` signatures unchanged. Extensions compile against
`dev.brahmkshatriya.echo:common`. A bad APK is skipped with a typed error.

## Project Structure

| Path | Role |
|---|---|
| `common/` | Public extension API |
| `shared/` | Platform abstractions |
| `core/` | Retry / watchdog / recovery policies |
| `domain/` | Queue + playback controller + audio FX math |
| `data/` | Settings, library, downloads, cache |
| `extensions/` | Runtime + Subsonic + local |
| `player/` | Platform engines |
| `composeApp/` | Shared Compose UI |
| `app-android/` | Android application |
| `wearApp/` | Wear OS application |
| `app-ios/iosApp/` | Xcode host |

## Privacy

- No analytics in default CI builds (Firebase is optional and local-only).
- Passwords, tokens, and private URL query parameters are not logged.
- HTTPS is the default; cleartext is limited to local-network servers.
- See [`SECURITY.md`](SECURITY.md).

## Contributing

1. Branch from `main`.
2. `./scripts/verify.sh --quick` must pass.
3. Keep `commonMain` free of Android/JVM-only APIs.
4. Do not break the `:common` extension contract.

See [`ARCHITECTURE.md`](ARCHITECTURE.md) and [`CHANGELOG.md`](CHANGELOG.md).

## License

Echo is a client. Users are responsible for sources they are entitled to use.

This repository follows the upstream [Unabandon Public License](LICENSE.md).
Upstream: [brahmkshatriya/echo](https://github.com/brahmkshatriya/echo).
