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
        raise ValueError('Android launch did not succeed')
    match = re.search(r'^TotalTime:\s*(\d+)\s*$', output, re.M)
    if not match or int(match[1]) <= 0:
        raise ValueError('Android launch did not report a positive TotalTime')
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
    args = parser.parse_args()
    if not 3 <= args.samples <= 20 or not re.fullmatch(r'[\w.]+', args.package):
        parser.error('Use a valid package and 3–20 samples')
    component = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief', args.package).strip().splitlines()[-1]
    if not component.startswith(args.package + '/'):
        raise ValueError('No launcher activity was resolved')
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
