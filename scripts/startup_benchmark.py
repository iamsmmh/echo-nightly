#!/usr/bin/env python3
"""Measure process-cold Android first-frame latency, not full UI readiness or device cold boot."""
import argparse
import json
import math
from pathlib import Path
import re
import statistics
import subprocess


def launch_time(output):
    if not re.search(r'^Status:\s*ok\s*$', output, re.M):
        raise ValueError('Android launch did not succeed: ' + output[:2000])
    match = re.search(r'^TotalTime:\s*(\d+)\s*$', output, re.M)
    if not match or int(match[1]) <= 0:
        raise ValueError('Android launch did not report a positive TotalTime: ' + output[:2000])
    return int(match[1])


def summarize(samples):
    if not samples or any(not isinstance(value, int) or value <= 0 for value in samples):
        raise ValueError('Positive measured samples are required')
    ordered = sorted(samples)
    return dict(samples_ms=samples, median_ms=statistics.median(samples),
                p95_ms=ordered[math.ceil(len(ordered) * .95) - 1], min_ms=ordered[0], max_ms=ordered[-1])


def adb(*args):
    return subprocess.run(['adb', *args], check=True, capture_output=True, text=True, timeout=120).stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--package', default='dev.brahmkshatriya.echo')
    parser.add_argument('--samples', type=int, default=5)
    parser.add_argument('--apk', type=Path, help='Exact debug APK to install before measuring')
    args = parser.parse_args()
    if not 3 <= args.samples <= 20 or not re.fullmatch(r'[\w.]+', args.package):
        parser.error('Use a valid package and 3–20 samples')
    # UTP may uninstall the target after instrumentation. Benchmark a known
    # build explicitly instead of depending on the test runner's cleanup policy.
    candidates = [args.apk] if args.apk else sorted(Path('app-android/build/outputs/apk/debug').glob('*.apk'))
    if len(candidates) != 1 or not candidates[0].is_file():
        raise ValueError('Provide exactly one built debug APK with --apk')
    adb('install', '-r', '-t', str(candidates[0]))
    resolved = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief',
                   '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', args.package)
    components = [line.strip() for line in resolved.splitlines() if line.strip().startswith(args.package + '/')]
    if len(components) != 1:
        raise ValueError('No unique launcher activity was resolved: ' + resolved[:2000])
    component = components[0]
    samples = []
    for _ in range(args.samples):
        adb('shell', 'am', 'force-stop', args.package)
        samples.append(launch_time(adb('shell', 'am', 'start', '-W', '-n', component)))
    result = dict(metric='process-cold-first-frame', platform='Android API 35 x86_64 emulator',
                  build_variant='debug', hardware_certification=False, **summarize(samples))
    output = Path('build/verification/android-startup.json')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2))
    print(f"::notice::Android debug/emulator startup: median {result['median_ms']} ms, p95 {result['p95_ms']} ms ({len(samples)} process-cold first-frame samples; not release/device certification)")


if __name__ == '__main__':
    main()
