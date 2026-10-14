#!/usr/bin/env python3
"""Fast behavioral checks for the test-run timing logger, without booting Java or Spring."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import signal
import time
import unittest

sys.dont_write_bytecode = True
SCRIPT = Path(__file__).with_name('run-backend-tests.py')


class TimingLogTest(unittest.TestCase):
    def load(self):
        self.assertTrue(SCRIPT.exists(), 'Per-case timing logger is missing')
        spec = importlib.util.spec_from_file_location('test_timing_logger', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def report(self, root, name, content):
        path = root / 'pay' / 'target' / 'surefire-reports' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def rows(self, path):
        return [json.loads(line) for line in path.read_text().splitlines() if line]

    def test_every_status_and_parameterized_case_is_logged_and_threshold_is_inclusive(self):
        logger = self.load()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            report = self.report(root, 'TEST-demo.xml', '''<testsuite name="Demo" time="10">
                <testcase classname="Demo" name="fast" time="0.2"/>
                <testcase classname="Demo" name="parameterized[1]" time="1"><failure/></testcase>
                <testcase classname="Demo" name="error" time="2"><error/></testcase>
                <testcase classname="Demo" name="skipped" time="0"><skipped/></testcase>
                </testsuite>''')
            result = logger.collect_reports([report], root / 'out', root, 1, None, 'RUN')
            rows = self.rows(root / 'out' / 'test-timings.log')
            self.assertEqual([r['status'] for r in rows], ['passed', 'failed', 'error', 'skipped'])
            self.assertEqual(len(self.rows(root / 'out' / 'slow-tests.log')), 2)
            self.assertEqual(result['test_count'], 4)
            suite = self.rows(root / 'out' / 'suite-timings.log')[0]
            self.assertAlmostEqual(suite['unattributed_seconds'], 6.8)
            self.assertEqual(rows[1]['test_name'], 'parameterized[1]')

    def test_old_reports_are_excluded_from_a_new_run(self):
        logger = self.load()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            old = self.report(root, 'TEST-old.xml', '<testsuite time="2"><testcase name="old" time="2"/></testsuite>')
            new = self.report(root, 'TEST-new.xml', '<testsuite time="1"><testcase name="new" time="1"/></testsuite>')
            os.utime(old, ns=(100, 100)); os.utime(new, ns=(300, 300))
            result = logger.collect_reports([old, new], root / 'out', root, 1, 200, 'RUN')
            self.assertEqual(result['test_count'], 1)
            self.assertEqual(self.rows(root / 'out' / 'test-timings.log')[0]['test_name'], 'new')

    def test_truncated_report_or_nonfinite_time_is_not_logged_as_zero(self):
        logger = self.load()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            broken = self.report(root, 'TEST-broken.xml', '<testsuite><testcase')
            invalid = self.report(root, 'TEST-invalid.xml', '<testsuite time="1"><testcase name="x" time="NaN"/></testsuite>')
            result = logger.collect_reports([broken, invalid], root / 'out', root, 1, None, 'RUN')
            self.assertEqual(len(result['report_errors']), 2)
            self.assertIsNone(self.rows(root / 'out' / 'test-timings.log')[0]['duration_seconds'])
            self.assertEqual(len(self.rows(root / 'out' / 'slow-tests.log')), 0)

    def test_zero_timeout_disables_deadline_but_keeps_elapsed_record(self):
        self.load()
        with tempfile.TemporaryDirectory() as temp:
            command = [sys.executable, str(SCRIPT), '--backend-root', temp, '--output', temp,
                       '--timeout', '0', '--', sys.executable, '-c', 'import time; time.sleep(0.15)']
            run = subprocess.run(command, capture_output=True, text=True, timeout=5)
            self.assertEqual(run.returncode, 0, run.stderr)
            summary = json.loads(next(Path(temp).glob('*/summary.json')).read_text())
            self.assertFalse(summary['timeout_enabled'])
            self.assertFalse(summary['timed_out'])
            self.assertGreaterEqual(summary['wall_seconds'], 0.15)

    def test_command_failure_exit_code_is_preserved(self):
        self.load()
        with tempfile.TemporaryDirectory() as temp:
            command = [sys.executable, str(SCRIPT), '--backend-root', temp, '--output', temp,
                       '--timeout', '2', '--', sys.executable, '-c', 'raise SystemExit(7)']
            run = subprocess.run(command, capture_output=True, text=True, timeout=5)
            self.assertEqual(run.returncode, 7, run.stderr)
            summary = json.loads(next(Path(temp).glob('*/summary.json')).read_text())
            self.assertEqual(summary['command_exit_code'], 7)
            self.assertFalse(summary['timed_out'])

    def test_timeout_also_stops_a_child_that_ignores_sigterm(self):
        self.load()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            marker = root / 'child-alive'
            pidfile = root / 'child-pid'
            child = ("import os,signal,time; from pathlib import Path; "
                     "signal.signal(signal.SIGTERM,signal.SIG_IGN); "
                     "Path(%r).write_text(str(os.getpid())); " % str(pidfile)
                     + "\nwhile True:\n Path(%r).open('a').write('x'); time.sleep(0.02)" % str(marker))
            parent = "import subprocess,sys,time; subprocess.Popen([sys.executable,'-c',%r]); time.sleep(10)" % child
            command = [sys.executable, str(SCRIPT), '--backend-root', temp, '--output', temp,
                       '--timeout', '0.5', '--', sys.executable, '-c', parent]
            try:
                run = subprocess.run(command, capture_output=True, text=True, timeout=6)
                self.assertEqual(run.returncode, 124, run.stderr)
                self.assertTrue(marker.exists(), 'owned child did not start')
                size = marker.stat().st_size
                time.sleep(0.15)
                self.assertEqual(marker.stat().st_size, size, 'owned child kept running after timeout')
            finally:
                if pidfile.exists():
                    try:
                        os.kill(int(pidfile.read_text()), signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    def test_cleanup_reaches_descendant_even_if_launcher_already_exited(self):
        logger = self.load()
        with tempfile.TemporaryDirectory() as temp:
            marker, pidfile = Path(temp) / 'alive', Path(temp) / 'pid'
            child = ("import os,signal,time; from pathlib import Path; "
                     "signal.signal(signal.SIGTERM,signal.SIG_IGN); "
                     "Path(%r).write_text(str(os.getpid())); " % str(pidfile)
                     + "\nwhile True:\n Path(%r).open('a').write('x'); time.sleep(0.02)" % str(marker))
            parent = "import subprocess,sys; subprocess.Popen([sys.executable,'-c',%r])" % child
            process = subprocess.Popen([sys.executable, '-c', parent], start_new_session=True,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                process.wait(timeout=2)
                deadline = time.monotonic() + 1
                while not marker.exists() and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(marker.exists(), 'owned descendant did not start')
                logger.stop_owned_process(process)
                size = marker.stat().st_size
                time.sleep(0.15)
                self.assertEqual(marker.stat().st_size, size, 'launcher exit leaked its descendant')
            finally:
                if pidfile.exists():
                    try:
                        os.kill(int(pidfile.read_text()), signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    def test_timeout_stops_owned_command_and_is_reported_without_a_fake_pass(self):
        self.load()
        with tempfile.TemporaryDirectory() as temp:
            command = [sys.executable, str(SCRIPT), '--backend-root', temp, '--output', temp,
                       '--timeout', '0.1', '--', sys.executable, '-c', 'import time; time.sleep(10)']
            run = subprocess.run(command, capture_output=True, text=True, timeout=5)
            self.assertEqual(run.returncode, 124, run.stderr)
            summary = json.loads(next(Path(temp).glob('*/summary.json')).read_text())
            self.assertTrue(summary['timed_out'])
            self.assertEqual(summary['test_count'], 0)
            self.assertEqual(summary['report_status'], 'partial')


if __name__ == '__main__':
    unittest.main()
