#!/usr/bin/env python3
"""Locate an Android build output and copy it to a stable release name.

Release builds are only signed when the signing secrets are configured. AGP
names the output differently in each case (``app-nightly.apk`` when signed,
``app-nightly-unsigned.apk`` otherwise), so hard-coding either name makes the
release workflows fail depending on repository configuration. This resolves the
artifact by variant and extension instead, and optionally copies it to a
requested destination.

    python3 scripts/android_artifact.py --variant nightly --type apk \
        --destination app-android/build/Echo-abc1234.apk

The resolved path is printed on stdout and, on GitHub Actions, exported as the
``ANDROID_ARTIFACT`` output/environment variable.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parent.parent


def candidates(module: str, variant: str, kind: str) -> list[Path]:
    outputs = ROOT / module / "build" / "outputs"
    roots = [outputs / ("apk" if kind == "apk" else "bundle") / variant]
    # Some AGP versions nest flavour directories; fall back to a full scan.
    found: list[Path] = []
    for root in roots:
        if root.is_dir():
            found.extend(sorted(root.rglob(f"*.{kind}")))
    if not found and outputs.is_dir():
        found = [
            path
            for path in sorted(outputs.rglob(f"*.{kind}"))
            if variant.lower() in str(path).lower()
        ]
    # Prefer signed output over the "-unsigned" variant when both exist.
    return sorted(found, key=lambda p: ("unsigned" in p.name, len(p.name)))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--module", default="app-android")
    parser.add_argument("--variant", required=True)
    parser.add_argument("--type", dest="kind", choices=("apk", "aab"), default="apk")
    parser.add_argument("--destination", default=None)
    parser.add_argument(
        "--optional",
        action="store_true",
        help="exit successfully when nothing was produced",
    )
    args = parser.parse_args(argv)

    matches = candidates(args.module, args.variant, args.kind)
    if not matches:
        message = (
            f"No .{args.kind} produced for variant '{args.variant}' in "
            f"{args.module}/build/outputs"
        )
        if args.optional:
            print(f"::notice::{message}")
            return 0
        print(f"::error::{message}", file=sys.stderr)
        return 1

    resolved = matches[0]
    if args.destination:
        destination = ROOT / args.destination
        destination.parent.mkdir(parents=True, exist_ok=True)
        if resolved.resolve() != destination.resolve():
            shutil.copy2(resolved, destination)
        resolved = destination

    relative = resolved.relative_to(ROOT)
    print(relative)
    for key in ("GITHUB_OUTPUT", "GITHUB_ENV"):
        path = os.environ.get(key)
        if path:
            with open(path, "a", encoding="utf-8") as handle:
                handle.write(f"ANDROID_ARTIFACT={relative}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
