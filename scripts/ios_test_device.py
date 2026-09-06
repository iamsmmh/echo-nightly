#!/usr/bin/env python3
"""Prepare an isolated, fully booted simulator for the app-hosted CI tests."""
import json
import os
import subprocess


def choose_template(runtimes, devices):
    candidates = []
    for runtime in runtimes:
        identifier = runtime.get('identifier', '')
        if not runtime.get('isAvailable') or not identifier.startswith('com.apple.CoreSimulator.SimRuntime.iOS-'):
            continue
        for device in devices.get(identifier, []):
            if device.get('isAvailable', True) and device.get('name', '').startswith('iPhone') and device.get('deviceTypeIdentifier'):
                version = tuple(int(part) for part in runtime['version'].split('.'))
                candidates.append((version, identifier, device['deviceTypeIdentifier']))
    if not candidates:
        raise ValueError('No available iPhone runtime/device template was found')
    _, runtime, device_type = max(candidates)
    return runtime, device_type


def simctl(*args, capture=False):
    return subprocess.run(['xcrun', 'simctl', *args], check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise SystemExit('This simulator reset is restricted to an isolated GitHub Actions runner')
    runtimes = json.loads(simctl('list', 'runtimes', '--json', capture=True))['runtimes']
    devices = json.loads(simctl('list', 'devices', 'available', '--json', capture=True))['devices']
    runtime, device_type = choose_template(runtimes, devices)
    # Kotlin/Native standalone tests may leave a simulator booted without a
    # healthy UI session. Do not reuse that process for XCTest's application host.
    simctl('shutdown', 'all')
    name = 'Echo-CI-' + os.environ['GITHUB_RUN_ID']
    device = simctl('create', name, device_type, runtime, capture=True).strip()
    simctl('boot', device)
    simctl('bootstatus', device, '-b')
    with open(os.environ['GITHUB_ENV'], 'a') as output:
        output.write(f'SIMULATOR_ID={device}\n')
    print(f'::notice::App-hosted tests: fresh {device_type} on {runtime}, boot completed')


if __name__ == '__main__':
    main()
