# Playback and offline stabilization report — 2026-09-07

## Implemented

- **Chromecast:** `CastPlaybackController` now owns Cast session start/resume/failure/end handling, queue handoff, remote transport state, metadata replacement, position snapshots, and paused return-to-phone behavior. `ChromecastBridge` remains as a source-compatible type alias. The converter retains unresolved phone items so a Cast round trip does not replace extension state with a temporary receiver URL.
- **AirPlay and device routes:** the iOS host retains `AudioRouteMonitor` for its entire scene lifetime. It observes route changes and media-service resets, distinguishes old/new device events (including AirPods/Bluetooth changes), refreshes output state, and reactivates the playback category without overriding playback intent. The existing Kotlin AVPlayer engine continues to own pause/resume policy and observer cleanup.
- **Streaming:** `StreamRecoveryManager` provides observable `IDLE`, `BUFFERING`, `RECOVERING`, `FAILED`, and `RESTORED` states; cancellation of stale attempts; connectivity gating; and bounded exponential retry for timeout, DNS, socket, and HTTP 429/500/502/503 failures.
- **Downloads:** `DownloadIntegrityManager` verifies SHA-256 and size, validates resumability, fails closed on bad replacement media, records fresh sidecars after repair, and removes corrupt/partial files.
- **Audio processing:** `CrossfadeEngine` supplies exact-endpoint equal-power curves and seek-safe reset frames. `ReplayGainProcessor` performs bounded PCM/album analysis and uses the existing true-peak limiter for track/album gain.
- **Offline library:** `OfflineIndexManager` supports incremental updates, token-prefix intersection search, full cache rebuild, and orphan pruning. `LocalLibraryRepository` now uses it for search and keeps it synchronized on import/remove/load.

## Automated validation added

Common tests cover:

- retryable streaming status classification, recovery success, and network-loss gating;
- equal-power crossfade endpoints and clipping-safe ReplayGain;
- incremental offline search and orphan removal;
- corrupt download replacement and post-repair tamper detection.

The repository's 33 Python architecture/evidence/release helper tests and static checks pass locally. Gradle and Xcode tests could not be executed in this Linux workspace because no JDK or Xcode toolchain is installed; platform CI must therefore be treated as the build authority for this patch.

## Performance characteristics

| Path | Before | Current characteristic |
|---|---|---|
| Offline search | linear scan over every track for each query | token lookup plus set intersection; results retain library insertion order |
| Incremental import/remove | search always rescanned source records | postings update only for the changed record |
| Album loudness analysis | not available as a dedicated processor | single-pass O(samples), constant auxiliary memory |
| Integrity verification | existing completion-sidecar check | explicit size/SHA-256/repair workflow; SHA-256 remains streaming on platform implementations |
| Stream retries | controller-specific bounded retry | reusable serialized recovery with stale-job cancellation and connectivity gating |

The last device startup baseline already recorded in `docs/release/production-readiness.md` is median **2,808 ms**, p95 **2,852 ms**. This patch does not claim a new startup measurement because no Android device was available.

## Release gates and residual validation

The code changes do **not** substitute for receiver/device testing. Before promoting a release, CI and QA must still provide:

1. Android debug/release APK and stable AAB build/test artifacts.
2. iOS simulator/device compile, XCTest, archive, signing, and IPA export evidence.
3. Physical Chromecast tests covering receiver disconnect, Wi-Fi loss, app process death, queue edits, seeks, and return to phone.
4. Physical AirPlay/AirPods/Bluetooth route matrix tests, including calls and media-services reset.
5. Android Auto vehicle/DHU and Wear OS hardware reconnect tests.
6. Instrumented long-session LeakCanary/Instruments runs.
7. A measured critical-path coverage report. No 90% claim is made without that report.
8. Signed release workflow completion, SBOM/security gates, and crash-free soak evidence.

No production signing keys or fabricated release artifacts are committed to the repository.
