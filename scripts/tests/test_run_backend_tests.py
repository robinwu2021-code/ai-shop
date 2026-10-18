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


if __name__ == '__main__':
    unittest.main()
