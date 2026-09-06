import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from startup_benchmark import launch_time, summarize


class BenchmarkEvidenceTests(unittest.TestCase):
    def test_measurements_are_parsed_not_inferred(self):
        self.assertEqual(123, launch_time('Status: ok\nLaunchState: COLD\nTotalTime: 123\n'))

    def test_failed_or_missing_measurements_are_rejected(self):
        for output in ('Status: error\nTotalTime: 123', 'Status: ok', 'Status: ok\nTotalTime: -1'):
            with self.assertRaises(ValueError):
                launch_time(output)

    def test_nearest_rank_percentile_and_median(self):
        report = summarize([500, 100, 400, 300, 200])
        self.assertEqual(300, report['median_ms'])
        self.assertEqual(500, report['p95_ms'])

    def test_empty_samples_are_not_a_zero_millisecond_pass(self):
        with self.assertRaises(ValueError):
            summarize([])
        with self.assertRaises(ValueError):
            summarize([0, 10])
