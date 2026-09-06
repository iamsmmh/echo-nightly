import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ios_test_device import choose_template


class IosDeviceSelectionTests(unittest.TestCase):
    def test_latest_available_iphone_runtime_is_selected(self):
        old = 'com.apple.CoreSimulator.SimRuntime.iOS-17-5'
        new = 'com.apple.CoreSimulator.SimRuntime.iOS-18-5'
        unavailable = 'com.apple.CoreSimulator.SimRuntime.iOS-99-0'
        runtimes = [dict(identifier=old, version='17.5', isAvailable=True),
                    dict(identifier=new, version='18.5', isAvailable=True),
                    dict(identifier=unavailable, version='99.0', isAvailable=False)]
        devices = {old: [dict(name='iPhone 15', deviceTypeIdentifier='phone15')],
                   new: [dict(name='iPad', deviceTypeIdentifier='pad'), dict(name='iPhone 16', deviceTypeIdentifier='phone16')]}
        self.assertEqual((new, 'phone16'), choose_template(runtimes, devices))

    def test_missing_runtime_does_not_silently_reuse_an_arbitrary_device(self):
        with self.assertRaises(ValueError):
            choose_template([], {})
