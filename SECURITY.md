# Security Policy

Echo is a local-first, extension-based music player. The core client stores no
user data in the cloud; it talks only to servers and extensions you explicitly
add (e.g. your own Subsonic/OpenSubsonic server, your imported local files, or
Android DEX extensions you install).

We take the security of the app and of the data you point it at seriously.

## Supported Versions

Security fixes are applied to the latest release and, where reasonable, the
most recent nightly. Older release trains are not maintained.

| Channel        | Status                  |
| -------------- | ----------------------- |
| Latest release | ✅ Supported            |
| Nightly        | ✅ Supported (best effort) |
| Older releases | ❌ Not supported        |

## Reporting a Vulnerability

Please **do not open a public issue** for security problems.

- Privately report via **GitHub Security Advisories** (recommended):
  go to *Security → Report a vulnerability* in this repository.
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
- We will credit you (unless you prefer to stay anonymous) once the issue is
  resolved.
- We ask that you give us a reasonable window to fix and release before public
  disclosure (default 90 days from acknowledgement).

## Security practices used in this repository

- **Dependency scanning** — Dependabot + scheduled vulnerability scans keep
  Gradle and GitHub Actions dependencies monitored.
- **Secret scanning** — enable GitHub **secret scanning** for this repository
  (Settings → Code security) so leaked tokens are detected automatically. A
  Gitleaks scan also runs in CI as defense-in-depth.
- **Static analysis** — CodeQL (Java/Kotlin) runs on pull requests and on a
  schedule.
- **SBOM** — a CycloneDX SBOM is generated on every release.
- **Signing material is never committed** — keystores, provisioning profiles
  and `google-services.json` are injected as CI secrets only (see `.gitignore`
  and `scripts/verify.sh`).
- **URL logging is sanitized** — credentials in URLs are redacted before they
  reach logs (`sanitizeUrl`).

## Scope / reporting notes

The Android DEX **extension system loads third-party code**. Only install
extensions from sources you trust. If you find an issue in an *extension* (not
the Echo core), report it to that extension's author.

Questions about this policy can be raised in Discussions.
