#!/usr/bin/env python3
"""Real unified identity -> DB session -> host proxy -> standalone near-pay on loopback MySQL9.

Uses already compiled test launchers and runs only the targeted bapp runtime acceptance.
No Maven build, remote deployment, shared schema, OPS session or vendor channel access.
"""
import argparse
import base64
import configparser
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile
import time
from urllib.parse import urlsplit
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path('/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java')
MYSQL = Path('/opt/homebrew/opt/mysql@9.7/bin/mysql')


def classpath(report):
    properties = ET.parse(report).getroot().find('properties')
    return next(p.get('value') for p in properties if p.get('name') == 'java.class.path')


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def loopback_origin(origin):
    parsed = urlsplit(origin)
    if (parsed.scheme != 'http' or parsed.hostname != '127.0.0.1' or
            not parsed.port or parsed.username or parsed.password or
            parsed.path or parsed.query or parsed.fragment):
        raise RuntimeError('Only an explicit loopback HTTP origin is accepted')
    return origin


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app-root', type=Path, default=ROOT.parent / 'ai-shop-bapp')
    parser.add_argument('--near-pay-root', type=Path, default=ROOT.parent / 'near-pay')
    parser.add_argument('--mysql-config', type=Path, default=Path.home() / '.config/mysql9/client.cnf')
    args = parser.parse_args()
    reports = ROOT / 'backend/shop-app/target/surefire-reports'
    host_reports = [reports / ('TEST-ai.neargo.shop.commercebridge.' + name + '.xml')
                    for name in ('BizIdentityNativeMysqlTest', 'BizLoginStoreNativeMysqlTest')]
    host_report = next((report for report in host_reports if report.is_file()), None)
    if host_report is None:
        raise RuntimeError('Compile the current host native identity/login test launcher first')
    pay_report = args.near_pay_root / ('backend/pay/pay-svc/target/surefire-reports/'
                                     'TEST-ai.neargo.shop.pay.svc.BizOnboardingHttpAcceptanceTest.xml')
    host_cp, pay_cp = classpath(host_report), classpath(pay_report)
    config = configparser.ConfigParser(interpolation=None)
    if not config.read(args.mysql_config) or 'client' not in config:
        raise RuntimeError('A local MySQL9 client configuration is required')
    mysql_user = config['client'].get('user', 'root').strip("\"'")
    mysql_password = config['client'].get('password', '').strip("\"'")
    master_key = base64.b64encode(secrets.token_bytes(32)).decode()
    output = Path(tempfile.mkdtemp(prefix='bapp-unified-nearpay-'))
    output.chmod(0o700)
    ready_files = [output / name for name in ('host-ready.json', 'pay-ready.json', 'app-ready.json')]
    host_ready, pay_ready, app_ready = ready_files
    pay_schema = 'near_pay_align_' + uuid.uuid4().hex[:16]
    host_schema = None
    processes, created = [], []
    secrets_to_redact = [mysql_password, master_key]
    pay_port, host_port = free_port(), free_port()
    while host_port == pay_port:
        host_port = free_port()
    pay_origin = loopback_origin(f'http://127.0.0.1:{pay_port}')
    env = os.environ.copy()
    env.pop('ALIGNMENT_OPS_TOKEN', None)
    env.pop('ALIGNMENT_REVIEWER_TOKEN', None)
    env.update({
        'COMMERCE_MYSQL_URL': 'jdbc:mysql://127.0.0.1:3308/?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC',
        'COMMERCE_MYSQL_USER': mysql_user, 'COMMERCE_MYSQL_PASSWORD': mysql_password,
        'COMMERCE_CHAIN_READY_FILE': str(host_ready), 'COMMERCE_CHAIN_PORT': str(host_port),
        'COMMERCE_CHAIN_PAY_ORIGIN': pay_origin,
        'ALIGNMENT_PAY_JDBC_URL': f'jdbc:mysql://127.0.0.1:3308/{pay_schema}?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC',
        'ALIGNMENT_PAY_ORIGIN': pay_origin, 'ALIGNMENT_PAY_PORT': str(pay_port),
        'ALIGNMENT_PAY_READY_FILE': str(pay_ready),
        'ALIGNMENT_MYSQL_USER': mysql_user, 'ALIGNMENT_MYSQL_PASSWORD': mysql_password,
        'ALIGNMENT_MASTER_KEY': master_key,
        'BAPP_UNIFIED_NEARPAY_READY': str(app_ready),
        'NEARPAY_ALIGNMENT_READY': str(app_ready),
    })

    def mysql(sql, schema=None):
        if schema is not None and schema not in created:
            raise RuntimeError('Refusing a non-owned schema')
        command = [str(MYSQL), '--defaults-file=' + str(args.mysql_config.resolve()),
                   '--protocol=TCP', '-h127.0.0.1', '-P3308', '--batch', '--skip-column-names']
        if schema:
            command.append(schema)
        result = subprocess.run(command, input=sql, text=True, capture_output=True)
        if result.returncode:
            raise RuntimeError('Local MySQL operation failed; SQL and credentials withheld')
        return result.stdout.strip()

    def start(name, cp, main_class, ready, root, flag):
        command = [str(JAVA), '-Xmx900m', '-XX:ActiveProcessorCount=2',
                   '-Duser.timezone=UTC', flag]
        agent = next((path for path in cp.split(os.pathsep)
                      if Path(path).name.startswith('mockito-core-') and path.endswith('.jar')), None)
        if agent:
            command.append('-javaagent:' + agent)
        command.extend(['-cp', cp, main_class])
        log = output / (name + '.log')
        with log.open('w') as stream:
            log.chmod(0o600)
            process = subprocess.Popen(command, cwd=root, env=env,
                                       stdout=stream, stderr=subprocess.STDOUT)
        processes.append(process)
        deadline, last_notice = time.monotonic() + 120, time.monotonic()
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(name + ' exited before ready; inspect ' + str(log))
            if ready.exists():
                if ready.stat().st_mode & 0o077:
                    raise RuntimeError(name + ' readiness file must be private (0600)')
                try:
                    data = json.loads(ready.read_text())
                    for key, value in data.items():
                        if isinstance(value, str) and re.search(r'password|otp|token|ticket', key, re.I):
                            secrets_to_redact.append(value)
                    print(name + ' ready on loopback', flush=True)
                    return data
                except json.JSONDecodeError:
                    pass
            if time.monotonic() - last_notice > 20:
                print('Waiting for ' + name, flush=True)
                last_notice = time.monotonic()
            time.sleep(.2)
        raise RuntimeError(name + ' readiness timeout; inspect ' + str(log))

    result = {'status': 'FAIL', 'remoteAccess': False, 'vendorAccess': False,
              'scope': 'real unified login and DB session, two loopback JVMs, disposable MySQL9 schemas'}
    cleanup_errors = []
    try:
        result['stage'] = 'mysql9'
        version = mysql('SELECT VERSION();')
        if not version.startswith('9.'):
            raise RuntimeError('MySQL 9 is required')
        result['mysqlVersion'] = version
        mysql('CREATE DATABASE ' + pay_schema + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;')
        created.append(pay_schema)
        result['stage'] = 'host-launch'
        host = start('host', host_cp, 'ai.neargo.shop.commercebridge.CommerceChainLocalLauncher',
                     host_ready, ROOT, '-Dcommerce.chain.local.acceptance=true')
        host_origin = loopback_origin(host['hostOrigin'])
        if host_origin != f'http://127.0.0.1:{host_port}':
            raise RuntimeError('Host did not use the exact selected loopback port')
        host_schema = host['database']
        if not re.fullmatch(r'commerce_test_[0-9a-f]{32}', host_schema):
            raise RuntimeError('Host did not report a disposable commerce_test UUID schema')
        created.append(host_schema)
        if (host.get('ownerRef') != 'U' or host.get('linkedStaffRef') != 'SWITCH' or
                host.get('linkedStaffIdentity') != 'STAFF-SWITCH'):
            raise RuntimeError('Compile the updated unified identity host launcher first')
        env['ALIGNMENT_HOST_ORIGIN'] = host_origin
        result['stage'] = 'near-pay-launch'
        pay = start('near-pay', pay_cp, 'ai.neargo.shop.pay.svc.BizOnboardingAiShopLocalLauncher',
                    pay_ready, args.near_pay_root / 'backend/pay/pay-svc',
                    '-Dnearpay.aishop.local.acceptance=true')
        if loopback_origin(pay['payOrigin']) != pay_origin or pay.get('schema') != pay_schema:
            raise RuntimeError('Standalone pay did not use the exact owned origin/schema')
        merchant, other = pay['merchantNo'], pay['otherMerchant']
        if not all(isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_-]+', value)
                   for value in (merchant, other)) or merchant == other:
            raise RuntimeError('Unexpected local payment merchant identifiers')
        result['stage'] = 'seed-membership-remap'
        where = ("tenant_no='MAIN' AND merchant_no='" + merchant + "' AND source_system='AI_SHOP' "
                 "AND ((subject_type='USER' AND subject_ref='SAME') OR "
                 "(subject_type='MCH_ACCOUNT' AND subject_ref='MCH-ADMIN'))")
        before = mysql('SELECT membership_no,subject_type,subject_ref,role_code,source_system,merchant_no,status '
                       'FROM pay_merchant_membership WHERE ' + where + ' ORDER BY subject_type;', pay_schema)
        rows = [row.split('\t') for row in before.splitlines()]
        if (len(rows) != 2 or {(row[1], row[2]) for row in rows} !=
                {('USER', 'SAME'), ('MCH_ACCOUNT', 'MCH-ADMIN')} or
                any(row[3] != 'MERCHANT_ADMIN' or row[6] != 'ACTIVE' for row in rows)):
            raise RuntimeError('Expected exactly the two seeded active administrator memberships')
        mysql("UPDATE pay_merchant_membership SET subject_ref=CASE subject_type "
              "WHEN 'USER' THEN 'U' ELSE 'SWITCH' END WHERE " + where + ';', pay_schema)
        after = mysql('SELECT membership_no,subject_type,subject_ref,role_code,source_system,merchant_no,status '
                      "FROM pay_merchant_membership WHERE tenant_no='MAIN' AND merchant_no='" + merchant + "' "
                      "AND source_system='AI_SHOP' AND ((subject_type='USER' AND subject_ref='U') OR "
                      "(subject_type='MCH_ACCOUNT' AND subject_ref='SWITCH')) ORDER BY subject_type;", pay_schema)
        expected = [[*row[:2], 'U' if row[1] == 'USER' else 'SWITCH', *row[3:]] for row in rows]
        if [row.split('\t') for row in after.splitlines()] != expected:
            raise RuntimeError('Membership remap changed unexpected authorization facts')
        # The test still enters both identities through real credential/ticket HTTP. No sessions are seeded here.
        merged = dict(host, payMerchantNo=merchant, otherMerchant=other, payOrigin=pay_origin)
        fd = os.open(app_ready, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(merged, stream)
        result['stage'] = 'actual-dart-http'
        flutter_log = output / 'flutter-http.log'
        with flutter_log.open('w') as stream:
            flutter_log.chmod(0o600)
            run = subprocess.run([str(args.app_root / 'tooling/flutterw'), 'test', '--no-pub',
                                  'test/integration/unified_nearpay_runtime_test.dart', '--reporter=expanded'],
                                 cwd=args.app_root / 'apps/merchant_app', env=env,
                                 stdout=stream, stderr=subprocess.STDOUT)
        if run.returncode:
            raise RuntimeError('Unified near-pay Dart HTTP acceptance failed; inspect ' + str(flutter_log))
        result.update(stage='complete', status='PASS', dartHttpVerified=True, membershipRowsRemapped=2,
                      scenarios=['credential verification and identity ticket entry', 'typed DB session and cold restore',
                                 'USER and linked MCH proxy membership isolation', 'private 2MiB attachment readback',
                                 'draft cancellation and original command replay', 'unowned merchant rejection'],
                      hostClasspathReport=host_report.name, nearPayClasspathReport=pay_report.name)
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
        for process in reversed(processes):
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for schema in reversed(created):
            try:
                # The host shutdown hook normally removes its own schema; this is an exact-name fallback.
                mysql('DROP DATABASE IF EXISTS ' + schema + ';')
            except RuntimeError:
                cleanup_errors.append('owned schema cleanup failed')
        for ready in ready_files:
            ready.unlink(missing_ok=True)
        for log in output.glob('*.log'):
            content = log.read_text(errors='replace')
            for secret in sorted(set(secrets_to_redact), key=len, reverse=True):
                if secret:
                    content = content.replace(secret, '[redacted]')
            content = re.sub(r'\b(?:btk|ctk|otk)_[A-Za-z0-9_-]+', '[redacted-token]', content)
            content = re.sub(r'(?i)(Bearer\s+)[A-Za-z0-9._~-]+', r'\1[redacted-token]', content)
            log.write_text(content)
        result['cleanupComplete'] = not cleanup_errors
        if cleanup_errors:
            result['status'] = 'FAIL'
            result['cleanupErrors'] = cleanup_errors
        (output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        print('Local JVMs stopped, private ready files removed; evidence:', output, flush=True)
    if cleanup_errors:
        raise RuntimeError('Exact owned fixture schema cleanup failed; inspect result.json')
    print('PASS: real unified identity/session/proxy and standalone near-pay', flush=True)


if __name__ == '__main__':
    main()
