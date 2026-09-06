#!/usr/bin/env python3
"""Publish real JVM/native/device JUnit evidence; missing, skipped and failed tests block."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def escape(value):
    return value.replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D')


def report(root):
    root = Path(root)
    files = sorted(set(root.glob('*/build/test-results/**/TEST-*.xml')) |
                   set(root.glob('*/build/outputs/androidTest-results/**/*.xml')))
    tests = failures = errors = skipped = malformed = suites = 0
    for path in files:
        try:
            document = ET.parse(path).getroot()
            for suite in document.iter('testsuite'):
                if suite.find('testsuite') is not None:
                    continue
                cases = suite.findall('testcase')
                suites += 1
                tests += int(suite.get('tests', len(cases)))
                failures += max(int(suite.get('failures', 0)), sum(len(case.findall('failure')) for case in cases))
                errors += max(int(suite.get('errors', 0)), sum(len(case.findall('error')) for case in cases))
                skipped += max(int(suite.get('skipped', 0)), sum(len(case.findall('skipped')) for case in cases))
                for case in cases:
                    for failure in list(case.findall('failure')) + list(case.findall('error')):
                        message = f"{case.get('classname')}.{case.get('name')}: {failure.get('message', failure.text or '')}"
                        print('::error::' + escape(message[:6000]))
        except (ET.ParseError, ValueError, OSError) as failure:
            malformed += 1
            print('::error::' + escape(f'Invalid test evidence: {path}: {failure}'))
    print(f'::notice::JUnit: {tests} tests, {failures} failures, {errors} errors, {skipped} skipped ({suites} suites)')
    if skipped:
        print('::error::Skipped tests do not satisfy the release test gate')
    if tests == 0:
        print('::error::No executed tests were found')
    return 0 if tests > 0 and failures == errors == skipped == malformed == 0 else 1


if __name__ == '__main__':
    sys.exit(report(sys.argv[1] if len(sys.argv) > 1 else '.'))
