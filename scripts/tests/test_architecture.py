import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from architecture import ALLOWED, cycles, dependency_closure, inspect, validate_graph, without_comments


class ArchitectureTests(unittest.TestCase):
    def test_repository_architecture(self):
        self.assertEqual([], inspect(Path(__file__).resolve().parents[2]))

    def test_each_approved_edge_is_acyclic(self):
        self.assertEqual([], validate_graph(ALLOWED))

    def test_reverse_dependency_rejected(self):
        graph = {key: set(value) for key, value in ALLOWED.items()}
        graph['domain'].add('data')
        self.assertTrue(any('forbidden layer' in error for error in validate_graph(graph)))

    def test_circular_dependency_detected(self):
        self.assertEqual(['dependency cycle: a -> b -> a'], cycles({'a': {'b'}, 'b': {'a'}}))

    def test_self_dependency_detected(self):
        self.assertTrue(cycles({'a': {'a'}}))

    def test_unknown_module_requires_review(self):
        self.assertTrue(validate_graph({'parallel-system': set()}))

    def test_unknown_dependency_detected(self):
        self.assertTrue(validate_graph({'domain': {'missing'}}))

    def test_closure_terminates_even_on_cycles(self):
        self.assertEqual({'a', 'b'}, dependency_closure({'a': {'b'}, 'b': {'a'}}, 'a'))

    def test_comments_are_not_dependencies(self):
        self.assertEqual(' ', without_comments('// project(":data")'))
        self.assertIn('https://host', without_comments('val url = "https://host" // comment'))

    def test_source_imports_cannot_hide_behind_gradle_edges(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'settings.gradle.kts').write_text('include(":domain")\ninclude(":data")')
            for module, text in [('domain', 'package sample\nimport sample.DataRepository\nclass UseCase'),
                                 ('data', 'package sample\nclass DataRepository')]:
                source = root / module / 'src/commonMain/kotlin/sample/File.kt'
                source.parent.mkdir(parents=True)
                source.write_text(text)
                (root / module / 'build.gradle.kts').write_text('')
            self.assertTrue(any('outside dependencies' in error for error in inspect(root)))

    def test_common_platform_leak_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'settings.gradle.kts').write_text('include(":domain")')
            source = root / 'domain/src/commonMain/kotlin/test/File.kt'
            source.parent.mkdir(parents=True)
            source.write_text('package test\nimport platform.AVFoundation.AVPlayer\nclass Bad')
            (root / 'domain/build.gradle.kts').write_text('')
            self.assertTrue(any('platform import' in error for error in inspect(root)))
