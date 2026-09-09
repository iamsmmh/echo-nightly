# Changelog

## Unreleased — production stabilization

- Fixed Kotlin Multiplatform compilation: recovery helpers no longer call JVM-only wall-clock APIs from `commonMain`.
- Watchdog recovery now re-prepares, then re-resolves expired streams, then skips with a bounded consecutive-skip cap.
- Stream resolution walks alternate servers instead of stopping at the first failure.
- Settings import is parse → validate → migrate → apply; exported documents never include the Subsonic password.
- Android Auto no longer crashes on stale media IDs; Wear OS shows a disconnected-phone state.
- Added `android-build.yml` and `ios-build.yml` production builders; release artifacts use deterministic names.

- Added lifecycle-safe Cast session recovery, queue/metadata/position synchronization, and lossless return-to-phone queue conversion.
- Hardened iOS AirPlay, AirPods, Bluetooth, and speaker route monitoring across route and media-service resets.
- Added reusable stream recovery state/backoff policy, SHA-256 download repair, incremental offline indexing, equal-power crossfade scheduling, and clipping-safe ReplayGain analysis with common tests.
- Documented measured performance characteristics and the remaining physical-device/release gates in `docs/release/stabilization-2026-09-07.md`.

All notable changes to Echo Nightly are documented here.

## [3.0.0] — KMP restructure + stability/audio/ecosystem program

### Added
- **Module split**: `shared/`, `core/`, `domain/`, `data/`, `extensions/`,
  `player/`, `composeApp/` KMP modules (android + iosArm64 + iosSimulatorArm64
  + iosX64 + jvm targets); `app/` → `app-android/`, `iosApp/` → `app-ios/iosApp`.
- **Playback watchdog** with escalation ladder (seek → re-prepare → reload → skip),
  user toggle in Settings → Player (`core.WatchdogPolicy`, shared by platforms).
- **Crash-safe playback recovery**: queue/position snapshots survive process
  death; automatic resume (*Resume after restart* setting).
- **Automatic network retry** with jittered exponential backoff for stream
  resolution and failure recovery (`core.RetryPolicy`, `core.RecoveryPolicy`).
- **Cache validation**: timestamped + SHA-256-checked cache envelopes, TTL for
  stream URLs, corrupt entry purge (`CacheUtils` v2 / `data.CacheValidator`).
- **Download integrity**: SHA-256 sidecars recorded at completion and verified
  before offline playback; corrupted downloads fall back to streaming. WorkManager
  backoff + connectivity constraint so interrupted downloads resume themselves.
- **Extension hardening**: APK size/ZIP-magic validation before parsing; a
  corrupted extension can no longer crash the extension list; Android Auto
  browse no longer crashes when an extension disappears mid-session.
- **Audio FX (KMP player)**: dip-style crossfade (0–12s), ReplayGain
  track/album with peak limiter, crash-safe sleep timer with fade-out;
  Android sleep command gains the same fade.
- **Wear OS app** (`wearApp/`) with transport controls + now playing card.
- **Chromecast mirroring** on Android (default receiver; opt-out setting).
- **AirPlay route picker** on iOS + Live-Activity now-playing feed
  (`UserDefaults["echo.nowplaying"]`).
- **Android Auto** real feed paging (home/library/search/shelves), safe
  extension lookup, per-extension track cache keys.
- Pure-Kotlin `Sha256`/`Md5`, audio magic-byte sniffing, time formatting in
  `shared/` with FIPS/RFC test vectors.
- **Security/CI**: `SECURITY.md`, CodeQL (`java-kotlin`), Gitleaks secret
  scanning on PRs, Dependabot (gradle + actions), CycloneDX SBOM on releases,
  unsigned-release fallback when signing secrets are absent.

### Changed
- `common/` extension API **unchanged** (frozen; verified by `scripts/verify.sh`).
- iOS CI no longer depends on `xcpretty`; framework link OOM class fixed via
  explicit Kotlin/Native JVM heap; framework verification moved to macOS.
- Release workflows gate signing/Discord/Telegram on configured secrets.

### Fixed
- `PlayerEventListener.updateCurrentFlow` assertion that could crash the
  playback service (`throw Exception("This is possible")`).
- `PlayerRadio` could get stuck on “Loading” (and crash on unloaded items)
  when an extension errored.
- `FileRepository.listFiles()!!` NPE when the extension dir vanished.
- Android Auto `first{}`/`!!` crashes and a track-cache key collision.
- Time labels rendering garbage for unset/negative positions.
