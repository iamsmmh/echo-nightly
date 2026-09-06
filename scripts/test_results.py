#!/usr/bin/env python3
"""Report real JUnit results; fail when no tests ran or a suite failed."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def report(root):
    files = sorted(Path(root).glob('*/build/test-results/**/TEST-*.xml'))
    tests = failures = errors = skipped = 0
    for path in files:
        suite = ET.parse(path).getroot()
        tests += int(suite.get('tests', 0))
        failures += int(suite.get('failures', 0))
        errors += int(suite.get('errors', 0))
        skipped += int(suite.get('skipped', 0))
        for case in suite.findall('testcase'):
            for failure in list(case.findall('failure')) + list(case.findall('error')):
                message = f"{case.get('classname')}.{case.get('name')}: {failure.get('message', '')}"
                print('::error::' + message.replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D'))
    print(f'::notice::JUnit: {tests} tests, {failures} failures, {errors} errors, {skipped} skipped ({len(files)} suites)')
    return 0 if tests > skipped and failures == errors == 0 else 1


if __name__ == '__main__':
    sys.exit(report(sys.argv[1] if len(sys.argv) > 1 else '.'))
