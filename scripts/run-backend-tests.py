#!/usr/bin/env python3
"""Run a bounded test command and record completed Surefire/Failsafe case timings without Java instrumentation."""
import argparse
from datetime import datetime, timezone
import json
import math
import os
import re
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent


def write_jsonl(path, rows):
    with path.open('w', encoding='utf-8') as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, allow_nan=False) + '\n')


def seconds(value, report, subject, errors):
    try:
        result = float(value)
        if not math.isfinite(result) or result < 0:
            raise ValueError('duration must be finite and nonnegative')
        return result
    except (ValueError, TypeError):
        errors.append({'report': str(report), 'subject': subject, 'error': 'missing or invalid duration'})
        return None


def collect_reports(reports, output, backend_root, slow_seconds, since_ns, run_id):
    """Collect only available completed cases; invalid/missing timing is unknown, never zero."""
    output.mkdir(parents=True, exist_ok=True)
    cases, suites, errors = [], [], []
    report_count = 0
    for report in sorted(set(reports)):
        try:
            stamp = report.stat().st_mtime_ns
            if since_ns is not None and stamp < since_ns:
                continue
            root = ET.parse(report).getroot()
        except (OSError, ET.ParseError) as error:
            errors.append({'report': str(report), 'error': str(error)})
            continue
        report_count += 1
        try:
            module = str(report.parent.parent.parent.relative_to(backend_root))
        except ValueError:
            module = str(report.parent.parent.parent)
        for suite in root.iter('testsuite'):
            suite_name = suite.get('name', '')
            duration = seconds(suite.get('time'), report, suite_name, errors)
            children = []
            for case in suite.findall('testcase'):
                name = case.get('name', '')
                elapsed = seconds(case.get('time'), report, name, errors)
                status = ('error' if case.find('error') is not None else
                          'failed' if case.find('failure') is not None else
                          'skipped' if case.find('skipped') is not None else 'passed')
                row = {'run_id': run_id, 'module': module, 'class_name': case.get('classname', suite_name),
                       'test_name': name, 'duration_seconds': elapsed, 'status': status,
                       'slow': elapsed is not None and elapsed >= slow_seconds,
                       'report': str(report), 'report_updated_ns': stamp}
                cases.append(row)
                children.append(row)
            valid = all(case['duration_seconds'] is not None for case in children)
            total = round(sum(case['duration_seconds'] for case in children), 6) if valid else None
            gap = (round(duration - total, 6) if duration is not None and total is not None
                   and duration >= total else None)
            suites.append({'run_id': run_id, 'module': module, 'class_name': suite_name,
                           'suite_seconds': duration, 'summed_case_seconds': total,
                           'unattributed_seconds': gap, 'test_count': len(children), 'report': str(report)})
    slow = sorted((row for row in cases if row['slow']), key=lambda row: row['duration_seconds'], reverse=True)
    write_jsonl(output / 'test-timings.log', cases)
    write_jsonl(output / 'slow-tests.log', slow)
    write_jsonl(output / 'suite-timings.log', suites)
    return {'run_id': run_id, 'test_count': len(cases), 'suite_count': len(suites), 'report_count': report_count,
            'slow_test_count': len(slow), 'slow_threshold_seconds': slow_seconds,
            'failed_test_count': sum(row['status'] == 'failed' for row in cases),
            'error_test_count': sum(row['status'] == 'error' for row in cases),
            'skipped_test_count': sum(row['status'] == 'skipped' for row in cases), 'report_errors': errors}


def stop_owned_process(process):
    """Stop only the command's session/process group, including its own Maven forked JVMs."""
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    deadline = time.monotonic() + 2
    # The launcher can exit before a forked JVM does. Waiting only for the launcher leaks descendants.
    while time.monotonic() < deadline:
        process.poll()
        try:
            os.killpg(process.pid, 0)
        except ProcessLookupError:
            process.wait()
            return
        time.sleep(0.02)
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()


def nonnegative(value):
    number = float(value)
    if not math.isfinite(number) or number < 0:
        raise argparse.ArgumentTypeError('must be finite and nonnegative')
    return number


def positive(value):
    number = nonnegative(value)
    if number == 0:
        raise argparse.ArgumentTypeError('must be positive')
    return number


def wait_with_progress(process, args, output, started, started_ns, backend_root):
    """Show observed progress only; incomplete suites do not supply a reliable total or percentage."""
    deadline = started + args.timeout if args.timeout else None
    next_progress = started + args.progress_seconds if args.progress_seconds else None
    while True:
        now = time.monotonic()
        wake = min([point for point in (deadline, next_progress) if point is not None], default=None)
        try:
            return process.wait(timeout=max(0, wake - now) if wake is not None else None)
        except subprocess.TimeoutExpired:
            now = time.monotonic()
            if deadline is not None and now >= deadline:
                raise
            completed = 0
            for report in backend_root.glob('**/target/surefire-reports/TEST-*.xml'):
                try:
                    if report.stat().st_mtime_ns >= started_ns:
                        completed += len(list(ET.parse(report).getroot().iter('testcase')))
                except (OSError, ET.ParseError):
                    pass
            with (output / 'maven.log').open('rb') as stream:
                stream.seek(max(0, (output / 'maven.log').stat().st_size - 65536))
                tail = stream.read().decode('utf-8', errors='replace')
            classes = re.findall(r'\[INFO\] Running ([A-Za-z0-9_.$]+)', tail)
            recent = classes[-1] if classes else 'build-or-context-startup'
            phases = re.findall(r'\[INFO\] --- ([A-Za-z0-9_.:-]+) .* @ ([A-Za-z0-9_.-]+) ---', tail)
            phase = '@'.join(phases[-1]) if phases else 'launch'
            message = 'TEST_PROGRESS elapsed=%.1fs completed_cases=%s phase=%s recent=%s' % (
                now - started, completed, phase, recent)
            print(message, flush=True)
            with (output / 'progress.log').open('a', encoding='utf-8') as stream:
                stream.write(message + '\n')
            next_progress = now + args.progress_seconds


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend-root', type=Path, default=ROOT / 'backend')
    parser.add_argument('--output', type=Path, help='Parent directory; each run creates a new child directory')
    parser.add_argument('--slow-seconds', type=nonnegative, default=1)
    parser.add_argument('--timeout', type=nonnegative, default=120, help='0 disables the automatic deadline')
    parser.add_argument('--progress-seconds', type=nonnegative, default=15,
                        help='Progress interval; 0 disables progress, without changing the deadline')
    parser.add_argument('--collect-only', action='store_true', help='Analyze existing reports without executing tests')
    parser.add_argument('--report', action='append', type=Path, help='Explicit report file; may be repeated')
    parser.add_argument('--since', type=nonnegative, help='Historical collection only: minimum report update epoch')
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if args.collect_only and command:
        parser.error('--collect-only cannot also execute a command')
    if not args.collect_only and not command:
        parser.error('provide an explicit test command after --, or use --collect-only')
    backend_root = args.backend_root.resolve()
    started_ns = time.time_ns()
    started = time.monotonic()
    run_id = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
    output = (args.output or backend_root / 'target' / 'test-timings').resolve() / run_id
    output.mkdir(parents=True)
    code, child_code, timed_out, interrupted = None, None, False, False
    launch_error = None
    if command:
        print('TEST_START log_dir=%s progress_seconds=%s timeout=%s' % (
            output, args.progress_seconds, args.timeout if args.timeout else 'disabled'), flush=True)
        with (output / 'maven.log').open('w', encoding='utf-8') as stream:
            try:
                process = subprocess.Popen(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                                           start_new_session=True)
                try:
                    child_code = wait_with_progress(process, args, output, started, started_ns, backend_root)
                    code = child_code if child_code >= 0 else 128 - child_code
                except subprocess.TimeoutExpired:
                    timed_out = True
                    stop_owned_process(process)
                    child_code, code = process.returncode, 124
                except KeyboardInterrupt:
                    interrupted = True
                    stop_owned_process(process)
                    child_code, code = process.returncode, 130
            except OSError as error:
                launch_error = str(error)
                code = 127
    reports = [path.resolve() for path in args.report] if args.report is not None else list(backend_root.glob('**/target/surefire-reports/TEST-*.xml'))
    if args.report is None:
        reports += list(backend_root.glob('**/target/failsafe-reports/TEST-*.xml'))
    since_ns = (int(args.since * 1_000_000_000) if args.since is not None else None) if args.collect_only else started_ns
    summary = collect_reports(reports, output, backend_root, args.slow_seconds, since_ns, run_id)
    summary.update({'source': 'existing_reports' if args.collect_only else 'current_command',
                    'started_at': datetime.fromtimestamp(started_ns / 1_000_000_000, timezone.utc).isoformat(),
                    'wall_seconds': round(time.monotonic() - started, 6), 'command_exit_code': code,
                    'child_exit_code': child_code, 'timed_out': timed_out, 'interrupted': interrupted,
                    'launch_error': launch_error, 'timeout_seconds': args.timeout if command else None,
                    'timeout_enabled': bool(command and args.timeout),
                    'progress_seconds': args.progress_seconds if command else None,
                    'report_status': 'partial' if timed_out or interrupted or launch_error or summary['report_errors']
                    or summary['test_count'] == 0 else 'complete_for_available_reports'})
    (output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2, allow_nan=False) + '\n',
                                       encoding='utf-8')
    print('TEST_TIMINGS tests=%s slow=%s threshold=%ss command_exit=%s report_status=%s log_dir=%s' % (
        summary['test_count'], summary['slow_test_count'], args.slow_seconds, code, summary['report_status'], output))
    return code if command else (2 if summary['report_errors'] else 0)


if __name__ == '__main__':
    sys.exit(main())
