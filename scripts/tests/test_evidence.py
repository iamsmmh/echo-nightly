import contextlib
import io
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ci_run import diagnostic, escape
from test_results import report
from xcresult_evidence import report as xctest_report


class EvidenceTests(unittest.TestCase):
    def check_report(self, xml, device=False):
        with tempfile.TemporaryDirectory() as tmp:
            if xml is not None:
                path = Path(tmp) / ('app-android/build/outputs/androidTest-results/connected/TEST-device.xml'
                                    if device else 'core/build/test-results/jvmTest/TEST-core.xml')
                path.parent.mkdir(parents=True)
                path.write_text(xml)
            with contextlib.redirect_stdout(io.StringIO()):
                return report(tmp)

    def test_no_tests_is_not_a_pass(self):
        self.assertEqual(1, self.check_report(None))
        self.assertEqual(1, self.check_report('<testsuite tests="0"/>'))

    def test_real_tests_pass_on_jvm_and_device(self):
        xml = '<testsuite tests="1"><testcase name="works"/></testsuite>'
        self.assertEqual(0, self.check_report(xml))
        self.assertEqual(0, self.check_report(xml, device=True))

    def test_failure_elements_cannot_hide_behind_zero_counters(self):
        self.assertEqual(1, self.check_report('<testsuite tests="1" failures="0"><testcase><failure message="bad"/></testcase></testsuite>'))

    def test_malformed_xml_and_skips_fail_closed(self):
        self.assertEqual(1, self.check_report('<truncated'))
        self.assertEqual(1, self.check_report('<testsuite tests="1" skipped="1"><testcase><skipped/></testcase></testsuite>'))

    def test_kotlin_and_swift_diagnostics(self):
        self.assertEqual(('core/Foo.kt', '7', '3', 'Broken'), diagnostic('e: file:///repo/core/Foo.kt:7:3 Broken', '/repo'))
        self.assertEqual(('App.swift', '4', '1', 'missing type'), diagnostic('/repo/App.swift:4: error: missing type', '/repo'))
        self.assertIsNone(diagnostic('BUILD SUCCESSFUL', '/repo'))

    def test_annotations_escape_control_characters(self):
        self.assertEqual('100%25%0Aline', escape('100%\nline'))

    def test_xctest_requires_executed_tests_without_skips(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, xctest_report(dict(passedTests=5, failedTests=0, skippedTests=0)))
            self.assertEqual(1, xctest_report(dict(passedTests=0, failedTests=0, skippedTests=0)))
            self.assertEqual(1, xctest_report(dict(passedTests=5, failedTests=0, skippedTests=1)))
            self.assertEqual(1, xctest_report({}))
