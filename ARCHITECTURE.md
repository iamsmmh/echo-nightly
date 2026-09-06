# Echo Nightly — Architecture

## Layering

```
 app-android ─┐                      ┌─ composeApp (Compose Multiplatform UI)
 wearApp     ─┤   player (engines)   │
 app-ios     ─┘          │           │
                domain ──┼── data ── extensions
                     core┴──shared   │
                       common (frozen extension API)
```

Rules enforced by `scripts/check.py` (module dependency validator):

- `common/` depends on nothing but Kotlin stdlib + kotlinx (serialization,
  coroutines). It is the **public extension contract**; signatures and
  `@Serializable` names are frozen. External extension APKs compiled against
  `dev.brahmkshatriya.echo:common:1.0.0` must keep loading unmodified.
- `shared/` → common. Pure platform abstractions (logger, errors, key-value
  stores, HTTP, file helpers, hashing, time/formatting) with android/ios/jvm
  actuals.
- `core/` → shared. **Pure decision policies** — retry backoff, playback
  watchdog escalation, failure recovery ladder. No platform or engine types,
  which is why they are exhaustively unit-testable.
- `domain/` → core, shared, common. The playback contract (`PlayerEngine`,
  `StreamResolver`), queue management, the controller state machine, audio FX
  (sleep timer, crossfade curve, ReplayGain math). Knows nothing about
  ExoPlayer/AVPlayer specifics.
- `data/` → domain pieces + shared. Settings, library repositories,
  downloads (state machine + integrity sidecars), validated caches.
- `extensions/` → data, shared, common. Extension runtime + built-ins
  (Subsonic, local library).
- `player/` → everything above. The only module that touches Media3
  (`AndroidAudioPlayer`) and AVFoundation (`IosAudioPlayer`).
- `composeApp/` → everything above (api). Shared UI + `AppGraph` composition
  root. iOS hosts it through the `ComposeApp` framework
  (`MainViewController` + `EchoIosBridge` exported in `ComposeApp.h`).
- `app-android/` is the legacy-first Android app: it keeps its View UI and
  media3 `PlayerService`, but consumes the shared modules for
  policies/validation/integrity so both platforms recover identically.

## Recovery data flow (Phase 1)

- `PlaybackWatchdog` (Android) / host loop (iOS) samples the engine and asks
  `WatchdogPolicy.decide(StallSample)` → `SeekResume | RePrepare | ReloadItem |
  SkipTrack`.
- Engine errors ask `RecoveryPolicy.decide(itemRetries, consecutiveFailures,
  serverCount, isNetworkError, hasNext)` → `RetrySameItem | TryNextServer |
  SkipToNext | StopWithError`.
- Transient network failures re-enter `withRetry(RetryPolicy.Playback)` inside
  `StreamableMediaSource` (KMP) and `PlayerEventListener` (Android).
- Crash resume: Android snapshots to `SharedPreferences`-backed
  `PlaybackRecoveryStore` on every play-state change; the service restores +
  resumes only when no UI controller claims the session within 2s. KMP
  persists `PlaybackState` via `KeyValuePlaybackPersister` and restores paused.
- Cache: entries carry `{timestamp, sha256}` envelopes (Android `CacheUtils`
  v2 envelope is read-compatible with legacy raw-JSON entries; KMP
  `CacheValidator` refuses stale/tampered payloads and purges them).
- Downloads: `<file>.echo.sha256` sidecar recorded by both `BaseTask`
  (Android) and `DownloadRepository` (KMP); verified before offline playback,
  mismatch ⇒ silent fallback to streaming (never deletes user data).

## UI parity

The Compose Multiplatform UI (composeApp) and the Android View UI intentionally
coexist: Android keeps its battle-tested Views + `PlayerService` (Android Auto,
notifications, effects), iOS uses Compose (`EchoApp` screens) over the KMP
`PlaybackController`. The shared domain guarantees identical queue/repeat/
shuffle semantics; the iOS `EchoIosBridge` gives the SwiftUI host stable
entry points for widgets, Live Activities (state feed in
`UserDefaults["echo.nowplaying"]`) and deep control.

## Extension loading (Android)

`FileRepository` sniffs each candidate APK (exists, non-empty, ZIP magic) →
`ExtensionParser.parseManifest` (PackageManager archive parse) → metadata is
SHA-256 fingerprinted; unchanged files reuse cached parse results
(`WeakHashMap`), changed/corrupt ones are re-parsed or skipped with a typed
error — a bad APK can no longer break the whole extension list.
The loader never mutates `:common` models, so extension binary compatibility
is preserved.
