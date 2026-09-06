# Security Policy

## Supported versions

| Version | Supported          |
|---------|--------------------|
| 3.0.x (current `main`) | ✅ |
| < 3.0   | ❌ (community patches welcome) |

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

- Use [GitHub Private Vulnerability Reporting](https://github.com/iamsmmh/echo-nightly/security/advisories/new) — this is the preferred channel.
- Or email the maintainers through the contact listed on the repository profile.

We aim to acknowledge reports within **7 days** and ship a fix or mitigation within **30
days** for confirmed issues in the app itself.

## Scope

### In scope
- `app-android/`, `wearApp/` (the Android phone & Wear OS apps)
- `app-ios/` + `composeApp/` (iOS host app & shared Compose Multiplatform UI)
- `shared/`, `core/`, `domain/`, `data/`, `extensions/` (business logic: playback,
  retries/recovery, persistence, extension runtime)
- `common/` — the public **extension API** consumed by third-party Echo extension APKs
- CI/CD pipelines under `.github/workflows/`

### Extension packages
Echo dynamically loads third-party extension APKs (DexClassLoader on Android). Bugs that
live purely inside a community extension (e.g. a scraping extension mishandling tokens)
belong to that extension's repository — but we will always triage reports describing an
attack surface that Echo itself enables (e.g. deserialization of untrusted extension
metadata, WebView flows, file paths escaping the app sandbox).

## Hardening already in place
- **Signed extension metadata + integrity checks** — extension APKs are size/ZIP-header
  validated and SHA-256 fingerprinted before parsing; corrupted archives are skipped,
  not loaded.
- **Download integrity** — every finished download gets a `*.echo.sha256` sidecar;
  corrupted files fall back to streaming instead of being played.
- **Cache validation** — cached JSON is wrapped in a timestamped, hash-checked envelope;
  tampered or truncated cache entries are purged rather than decoded.
- **Log redaction** — `sanitizeUrl()` redacts token/password/salt query parameters before
  anything is logged or reported.
- **Dependency hygiene** — Dependabot (gradle + actions), CodeQL (`java-kotlin`), and
  Gitleaks secret scanning run on every PR; release artifacts ship with a CycloneDX SBOM.

## Notes for extension developers
- The published `dev.brahmkshatriya.echo:common` API never exposes raw platform
  credentials; keep secrets inside your extension's private settings storage.
- Do not serialize untrusted input with `EchoFile` paths containing `..` — resolve and
  verify inside your sandbox directory first.
