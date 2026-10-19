"""Progress collection must survive Maven deleting its temporary target/surefire directory."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('backend_test_runner', Path(__file__).parents[1] / 'run-backend-tests.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class ProgressDirectoryRaceTest(unittest.TestCase):
    def test_removed_maven_temporary_directory_does_not_abort_the_running_command(self):
        with tempfile.TemporaryDirectory() as directory:
            backend = Path(directory)
            transient = backend / 'module' / 'target' / 'surefire'
            reports = transient.parent / 'surefire-reports'
            transient.mkdir(parents=True)
            reports.mkdir()
            (reports / 'TEST-synthetic.xml').write_text('<testsuite><testcase name="completed" time="0.1"/></testsuite>')
            output = backend / 'logs'
            output.mkdir()
            (output / 'maven.log').write_text('[INFO] Running synthetic.Test\n')
            real_scandir = os.scandir

            def disappearing_directory(path):
                if Path(path) == transient:
                    raise FileNotFoundError(2, 'Maven removed its temporary directory', str(path))
                return real_scandir(path)

            real_glob = Path.glob

            def glob_with_maven_race(path, pattern):
                # Match the observed FileNotFoundError raised while recursively traversing
                # target/surefire. Stable surefire-reports is still available to read.
                if path == backend and pattern.startswith('**/'):
                    raise FileNotFoundError(2, 'Maven removed its temporary directory', str(transient))
                return real_glob(path, pattern)

            child = Mock()
            child.wait.side_effect = [subprocess.TimeoutExpired('synthetic', 0.001), 0]
            args = SimpleNamespace(timeout=0, progress_seconds=0.001)
            with patch('os.scandir', side_effect=disappearing_directory), patch.object(Path, 'glob', new=glob_with_maven_race), contextlib.redirect_stdout(io.StringIO()):
                result = runner.wait_with_progress(child, args, output, time.monotonic() - 0.01, 0, backend)
            self.assertEqual(0, result)
            self.assertIn('completed_cases=1', (output / 'progress.log').read_text())
            self.assertEqual(2, child.wait.call_count)


class ReportSnapshotTest(unittest.TestCase):
    def test_current_report_is_copied_and_old_report_is_excluded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reports = root / 'module/target/surefire-reports'
            reports.mkdir(parents=True)
            old = reports / 'TEST-old.xml'
            old.write_text('<testsuite name="old" time="1"><testcase name="old" time="1"/></testsuite>')
            cutoff = time.time_ns()
            fresh = reports / 'TEST-new.xml'
            fresh.write_text('<testsuite name="new" time="1"><testcase name="new" time="1"/></testsuite>')
            os.utime(old, ns=(cutoff - 10000000, cutoff - 10000000))
            os.utime(fresh, ns=(cutoff + 10000000, cutoff + 10000000))
            output = root / 'run'
            result = runner.collect_reports([old, fresh], output, root, 5, cutoff, 'run')
            self.assertEqual(1, result['test_count'])
            snapshot = output / 'reports/module/target/surefire-reports/TEST-new.xml'
            self.assertTrue(snapshot.exists())
            fresh.write_text('overwritten by next run')
            self.assertIn('testcase', snapshot.read_text())
            self.assertFalse((output / 'reports/module/target/surefire-reports/TEST-old.xml').exists())


if __name__ == '__main__':
    unittest.main()
