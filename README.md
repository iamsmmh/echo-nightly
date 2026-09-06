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
| `domain/`          | Playback engine contract, `QueueManager` (deterministic shuffle/repeat), `PlaybackController` (offline-first resolution, persistence, crash resume), sleep timer + crossfade + ReplayGain (audio FX). |
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

## Stability mechanisms (Phase 1)

- **Playback watchdog** (`PlaybackWatchdog` on Android, `WatchdogPolicy` in
  `core/`): samples position/state every 5s; escalates *seek-resume → re-prepare
  → reload item → skip track* when playback freezes or buffers forever. Toggle
  in Settings → Player.
- **Crash-safe playback recovery**: the service continuously snapshots
  queue/position/play-state (`PlaybackRecoveryStore`); after a process kill or
  crash, the service restores and resumes automatically (setting *Resume after
  restart*). The KMP player persists its session through `KeyValuePlaybackPersister`.
- **Auto network retry**: stream re-resolution retries with jittered
  exponential backoff (`RetryPolicy.Playback`); `RecoveryPolicy` falls over to
  the next available server before skipping.
- **Cache validation**: Android's `CacheUtils` and the KMP `CacheValidator` wrap
  entries in timestamped, SHA-256-checked envelopes; stale/corrupt entries are
  purged instead of decoded (stream URLs go stale after 6h by default).
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

## Building

Prerequisites: JDK 17, Android SDK (for `app-android`/`wearApp`), Xcode 15+
(for `app-ios`), everything else via the Gradle wrapper.

```bash
./scripts/verify.sh            # static checks + tests + builds
./gradlew :app-android:assembleDebug   # phone APK
./gradlew :wearApp:assembleDebug       # watch APK
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
open app-ios/iosApp/iosApp.xcodeproj   # then Build in Xcode
```

CI: see `.github/workflows/` (`android.yml`, `multiplatform.yml`, `ios.yml`,
`pr.yml`, `codeql.yml`) — Android/Wear on ubuntu, iOS framework + Xcode build
on macOS, JVM unit tests for every shared module, CodeQL + Gitleaks scanning,
dependabot updates, signed nightlies/stable releases (when secrets exist) and
an unsigned-IPA fallback release pipeline.

## Extension compatibility

The `:common` artifact API is the contract for third-party extensions loaded
via `DexClassLoader`. **It is frozen**: new code in this repository never
changes signatures or serialization names in `common/` (enforced by
`scripts/verify.sh` step 4). Built-in extensions (Offline library, Subsonic)
use exactly the same API surface as external ones.

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for module responsibilities and data
flow, [`SECURITY.md`](SECURITY.md) for the disclosure policy, and
[`CHANGELOG.md`](CHANGELOG.md) for release notes.
