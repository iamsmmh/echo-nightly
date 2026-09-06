#!/usr/bin/env python3
"""Summarize the app-hosted XCTest run without equating compilation with testing."""
import json
from pathlib import Path
import subprocess
import sys


def report(summary):
    passed, failed, skipped = (summary.get(key) for key in ('passedTests', 'failedTests', 'skippedTests'))
    if not all(isinstance(value, int) and value >= 0 for value in (passed, failed, skipped)):
        print('::error::XCTest summary is missing valid test counts')
        return 1
    print(f'::notice::XCTest: {passed} passed, {failed} failed, {skipped} skipped')
    for failure in summary.get('testFailures', []):
        message = json.dumps(failure, ensure_ascii=True)[:6000]
        print('::error::' + message.replace('%', '%25').replace('\n', '%0A'))
    return 0 if passed > 0 and failed == skipped == 0 else 1


def main(path):
    result = subprocess.run(['xcrun', 'xcresulttool', 'get', 'test-results', 'summary', '--path', path],
                            text=True, capture_output=True, check=True)
    summary = json.loads(result.stdout)
    output = Path('build/verification/xcresult-summary.json')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(summary, indent=2))
    return report(summary)


if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
