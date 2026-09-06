#!/usr/bin/env python3
"""Run a build without hiding failures; publish compiler diagnostics as annotations.

Logs remain artifacts, never commits. This also makes failures inspectable via
GitHub's checks API when artifact/log downloads are unavailable.
"""
import os
from pathlib import Path
import re
import subprocess
import sys


def escape(value):
    return value.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def main(argv):
    if not argv:
        raise SystemExit('usage: ci_run.py COMMAND [ARG ...]')
    root = Path(__file__).resolve().parent.parent
    log = root / 'build' / 'verification' / 'build.log'
    log.parent.mkdir(parents=True, exist_ok=True)
    process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, bufsize=1)
    diagnostics = set()
    tail = []
    with log.open('a') as output:
        for line in process.stdout:
            print(line, end='', flush=True)
            output.write(line)
            tail = (tail + [line.rstrip()])[-25:]
            match = re.match(r'e: (?:file://)?(.+?):(\d+):(\d+) (.+)', line)
            if match and line not in diagnostics:
                diagnostics.add(line)
                file, row, column, message = match.groups()
                file = os.path.relpath(file, root)
                print(f'::error file={escape(file)},line={row},col={column}::{escape(message)}', flush=True)
    code = process.wait()
    if code and not diagnostics:
        print('::error::' + escape('Build failed: ' + ' '.join(argv) + '\n' + '\n'.join(tail)))
    return code


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
