"""Pure safety tests: no Docker, application, credentials or database access."""
import importlib.util
from pathlib import Path
import tempfile
import shlex
import unittest

spec = importlib.util.spec_from_file_location('query_fixture', Path(__file__).with_name('query_boundaries_mysql.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class FixtureSafetyTest(unittest.TestCase):
    def test_only_backend_sources_and_pom_are_admitted(self):
        for name in ['backend/pom.xml', 'backend/src/main/java/example/Test.java',
                     'backend/src/test/resources/fixture.json']:
            with self.subTest(name=name):
                self.assertTrue(fixture.eligible_source(name))
        for name in ['frontend/package.json', 'backend/mysql_gying_mcp.py', '../backend/src/Test.java',
                     'backend/src/../../.env', 'backend/target/test.xml', 'backend/node_modules/index.js',
                     'backend/src/.env', 'backend/src/.ENV.local', 'backend/src/service.KEY',
                     'backend/src/node_modules/file.js', 'backend/src/config.p12']:
            with self.subTest(name=name):
                self.assertFalse(fixture.eligible_source(name))

    def test_cleanup_requires_exact_full_id_and_both_labels(self):
        identifier = 'a' * 64
        run_id = '1' * 12
        valid = {'id': identifier, 'labels': {fixture.LABEL: fixture.KIND, fixture.RUN_LABEL: run_id}}
        fixture.own_container(valid, identifier, run_id)
        variants = [({}, identifier), (valid, 'a' * 12), (valid, 'b' * 64),
                    ({'id': identifier, 'labels': {fixture.LABEL: fixture.KIND}}, identifier),
                    ({'id': identifier, 'labels': {fixture.LABEL: 'production', fixture.RUN_LABEL: run_id}}, identifier)]
        for facts, candidate in variants:
            with self.subTest(facts=facts, candidate=candidate):
                with self.assertRaises(RuntimeError):
                    fixture.own_container(facts, candidate, run_id)
        with self.assertRaises(RuntimeError):
            fixture.own_container(valid, identifier, '2' * 12)

    def test_missing_and_skipped_sql_tests_cannot_be_counted_as_verified(self):
        with tempfile.TemporaryDirectory(prefix='gying-fixture-report-') as directory:
            root = Path(directory)
            with self.assertRaises(RuntimeError):
                fixture.summary(root)
            for name, count in fixture.REQUIRED_TESTS.items():
                (root / ('TEST-' + name + '.xml')).write_text(
                    f'<testsuite name="example.{name}" tests="{count}" failures="0" errors="0" skipped="0"/>', encoding='utf-8')
            result = fixture.summary(root)
            self.assertEqual(sum(fixture.REQUIRED_TESTS.values()), result['totals']['passed'])
            self.assertEqual(0, result['totals']['skipped'])
            file = root / 'TEST-QueryBoundaryMySqlTest.xml'
            file.write_text('<testsuite name="example.QueryBoundaryMySqlTest" tests="7" failures="0" errors="0" skipped="7"/>', encoding='utf-8')
            with self.assertRaises(RuntimeError):
                fixture.summary(root)

    def test_targeted_maven_command_runs_required_sql_tests_without_skipping(self):
        lines = fixture.maven_script(False).splitlines()
        self.assertEqual(1, len(lines))
        arguments = shlex.split(lines[0])
        self.assertEqual('mvn', arguments[0])
        self.assertIn('-o', arguments)
        self.assertIn('-Dtest=MonitoringServiceMySqlTest,QueryBoundaryMySqlTest', arguments)
        self.assertNotIn('-DskipTests', arguments)
        self.assertEqual('test', arguments[-1])

    def test_full_suite_requires_tests_then_separate_compile(self):
        lines = fixture.maven_script(True).splitlines()
        self.assertEqual(3, len(lines))
        test_args, compile_args = (shlex.split(line) for line in lines[:2])
        self.assertEqual('test', test_args[-1])
        self.assertFalse(any(arg.startswith('-Dtest=') for arg in test_args))
        self.assertNotIn('-DskipTests', test_args)
        self.assertIn('-DskipTests', compile_args)
        self.assertEqual('compile', compile_args[-1])
        self.assertIn('-o', compile_args)
        self.assertIn('/workspace/target/query-boundaries-compile.ok', lines[2])

    def test_before_all_failure_preserves_actual_surefire_counts(self):
        with tempfile.TemporaryDirectory(prefix='gying-fixture-report-') as directory:
            root = Path(directory)
            (root / 'TEST-MonitoringServiceMySqlTest.xml').write_text(
                '<testsuite name="example.MonitoringServiceMySqlTest" tests="4" failures="0" errors="0" skipped="0"/>', encoding='utf-8')
            (root / 'TEST-QueryBoundaryMySqlTest.xml').write_text(
                '<testsuite name="example.QueryBoundaryMySqlTest" tests="1" failures="0" errors="1" skipped="0"/>', encoding='utf-8')
            with self.assertRaises(RuntimeError) as raised:
                fixture.summary(root)
            report = getattr(raised.exception, 'report', None)
            self.assertIsNotNone(report)
            self.assertEqual(5, report['totals']['tests'])
            self.assertEqual(4, report['totals']['passed'])
            self.assertEqual(1, report['totals']['errors'])
            self.assertFalse(report['required_sql_verified'])

    def test_summary_does_not_hide_failed_or_errored_cases(self):
        with tempfile.TemporaryDirectory(prefix='gying-fixture-report-') as directory:
            root = Path(directory)
            for name, count in fixture.REQUIRED_TESTS.items():
                (root / ('TEST-' + name + '.xml')).write_text(
                    f'<testsuite name="example.{name}" tests="{count}" failures="1" errors="1" skipped="0"/>', encoding='utf-8')
            totals = fixture.summary(root)['totals']
            self.assertEqual(2, totals['failures'])
            self.assertEqual(2, totals['errors'])
            self.assertEqual(sum(fixture.REQUIRED_TESTS.values()) - 2 * len(fixture.REQUIRED_TESTS), totals['passed'])


if __name__ == '__main__':
    unittest.main()
