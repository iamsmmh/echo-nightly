# Security Policy

Echo is a local-first, extension-based music player. The core client stores no
user data in the cloud; it talks only to servers and extensions you explicitly
add (e.g. your own Subsonic/OpenSubsonic server, your imported local files, or
Android DEX extensions you install).

## Supported versions

Security fixes are applied to the latest release and, where reasonable, the
most recent nightly. Older release trains are not maintained.

| Version | Status |
|---------|--------|
| 3.0.x (current `main`) | ✅ Supported |
| Nightly | ✅ Supported (best effort) |
| < 3.0 | ❌ Not supported (community patches welcome) |

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

- Use [GitHub Private Vulnerability Reporting](https://github.com/iamsmmh/echo-nightly/security/advisories/new) — this is the preferred channel.
- Alternatively email the maintainers (see the repository's `README.md` /
  `FUNDING.yml`) with the subject line prefixed `[SECURITY]`.

Please include, if possible:

1. The affected version / build.
2. Steps to reproduce (minimal and complete).
3. Impact and, if you have one, a suggested fix.
4. Whether it is already publicly disclosed.

### What to expect

- We will acknowledge your report within **5 business days**.
- We will keep you informed as we triage and fix it.
- We aim to ship a fix or mitigation within **30 days** for confirmed issues in
  the app itself.
- We will credit you (unless you prefer to stay anonymous) once the issue is
  resolved.
- We ask that you give us a reasonable window to fix and release before public
  disclosure (default 90 days from acknowledgement).

## Scope

### In scope
- `app-android/`, `wearApp/` (the Android phone & Wear OS apps)
- `app-ios/` + `composeApp/` (iOS host app & shared Compose Multiplatform UI)
- `shared/`, `core/`, `domain/`, `data/`, `extensions/`, `player/` (business
  logic: playback, retries/recovery, persistence, extension runtime)
- `common/` — the public **extension API** consumed by third-party Echo
  extension APKs
- CI/CD pipelines under `.github/workflows/`

### Extension packages

Echo dynamically loads third-party extension APKs (DexClassLoader on Android).
Bugs that live purely inside a community extension (e.g. a scraping extension
mishandling tokens) belong to that extension's repository — but we will always
triage reports describing an attack surface that Echo itself enables (e.g.
deserialization of untrusted extension metadata, WebView flows, file paths
escaping the app sandbox).

## Hardening already in place

- **Signed extension metadata + integrity checks** — extension APKs are
  size/ZIP-header validated and SHA-256 fingerprinted before parsing;
  corrupted archives are skipped, not loaded.
- **Download integrity** — every finished download gets a `*.echo.sha256`
  sidecar; corrupted files fall back to streaming instead of being played.
- **Cache validation** — cached JSON is wrapped in a timestamped, hash-checked
  envelope; tampered or truncated cache entries are purged rather than decoded.
- **Log redaction** — `sanitizeUrl()` redacts token/password/salt query
  parameters before anything is logged or reported.
- **Dependency hygiene** — Dependabot (gradle + actions), CodeQL
  (`java-kotlin`), and Gitleaks secret scanning run on every PR; release
  artifacts ship with a CycloneDX SBOM.
- **Dependency graph** — enable the repository **Dependency graph**
  (Settings → Code security) so Dependabot alerts populate and the PR
  dependency-review job can block on new high-severity dependencies.
- **Signing material is never committed** — keystores, provisioning profiles
  and `google-services.json` are injected as CI secrets only (see `.gitignore`
  and `scripts/verify.sh`).

## Notes for extension developers

- The published `dev.brahmkshatriya.echo:common` API never exposes raw platform
  credentials; keep secrets inside your extension's private settings storage.
- Do not serialize untrusted input with `EchoFile` paths containing `..` —
  resolve and verify inside your sandbox directory first.

Questions about this policy can be raised in Discussions.
