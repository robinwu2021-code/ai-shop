#!/usr/bin/env python3
"""N1 real Dart -> host Tomcat -> standalone PAY on disposable loopback MySQL9.

Uses compiled test launchers. The fault proxy drops one real PAY commit response;
restart preserves both databases, credentials and the original claim request.
"""
import argparse
import configparser
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlsplit
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path('/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java')
MYSQL = Path('/opt/homebrew/opt/mysql@9.7/bin/mysql')


def classpath(report):
    reported = next(p.get('value') for p in ET.parse(report).getroot().find('properties')
                    if p.get('name') == 'java.class.path')
    entries = []
    for item in reported.split(os.pathsep):
        path = Path(item).resolve()
        classes = path.parent / 'classes'
        if (path.suffix == '.jar' and path.parent.name == 'target' and classes.is_dir()
                and (path.is_relative_to(ROOT) or path.is_relative_to(ROOT.parent / 'near-pay'))):
            path = classes
        if str(path) not in entries:
            entries.append(str(path))
    return os.pathsep.join(entries)


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app-root', type=Path, default=ROOT.parent / 'ai-shop-bapp')
    parser.add_argument('--near-pay-root', type=Path, default=ROOT.parent / 'near-pay')
    parser.add_argument('--mysql-config', type=Path, default=Path.home() / '.config/mysql9/client.cnf')
    args = parser.parse_args()
    host_cp = classpath(ROOT / 'backend/shop-app/target/surefire-reports/TEST-ai.neargo.shop.commercebridge.StoreEntityClaimCoordinatorMysqlTest.xml')
    pay_cp = classpath(args.near_pay_root / 'backend/pay/pay-svc/target/surefire-reports/TEST-ai.neargo.shop.pay.svc.StoreClaimReservationTest.xml')
    config = configparser.ConfigParser(interpolation=None)
    if not config.read(args.mysql_config) or 'client' not in config:
        raise RuntimeError('Local MySQL9 client configuration required')
    output = Path(tempfile.mkdtemp(prefix='store-claim-local-'))
    output.chmod(0o700)
    pay_schema = 'near_pay_claim_align_' + uuid.uuid4().hex[:16]
    created, processes, handles = [], {}, []
    pay_port, host_port = free_port(), free_port()
    while host_port == pay_port:
        host_port = free_port()
    token, credential, password = secrets.token_urlsafe(36), secrets.token_urlsafe(36), secrets.token_urlsafe(24)
    private_values = [token, credential, password, config['client'].get('password', '').strip("\"'")]
    ready = {name: output / (name + '-ready.json') for name in ('pay', 'host', 'app')}
    env = os.environ.copy()
    env.update({
        'JAVA_TOOL_OPTIONS': '-Duser.timezone=UTC',
        'COMMERCE_MYSQL_URL': 'jdbc:mysql://127.0.0.1:3308/?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC',
        'COMMERCE_MYSQL_USER': config['client'].get('user', 'root').strip("\"'"),
        'COMMERCE_MYSQL_PASSWORD': private_values[-1],
        'ALIGNMENT_MYSQL_USER': config['client'].get('user', 'root').strip("\"'"),
        'ALIGNMENT_MYSQL_PASSWORD': private_values[-1],
        'ALIGNMENT_PAY_JDBC_URL': f'jdbc:mysql://127.0.0.1:3308/{pay_schema}?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC',
        'ALIGNMENT_PAY_PORT': str(pay_port), 'ALIGNMENT_PAY_READY_FILE': str(ready['pay']),
        'ALIGNMENT_PAY_REPO_ROOT': str(args.near_pay_root.resolve()),
        'ALIGNMENT_INTERNAL_TOKEN': token, 'ALIGNMENT_PAY_INTERNAL_CREDENTIAL': credential,
        'N1_HOST_PORT': str(host_port), 'N1_HOST_READY_FILE': str(ready['host']),
        'N1_OWNER_PASSWORD': password, 'N1_INTERNAL_TOKEN': token, 'N1_PAY_CREDENTIAL': credential,
        'BAPP_STORE_CLAIM_READY': str(ready['app']),
    })
    env.pop('N1_HOST_SCHEMA', None)
    result = {'status': 'FAIL', 'remoteAccess': False, 'vendorAccess': False,
              'scope': 'real DB login, Tomcat, independent PAY, MySQL9, Dart production coordinator'}
    fault = {'armed': False, 'drops': 0, 'restarts': 0}

    def mysql(sql, schema=None):
        if schema is not None and schema not in created:
            raise RuntimeError('Refusing non-owned database')
        command = [str(MYSQL), '--defaults-file=' + str(args.mysql_config.resolve()),
                   '--protocol=TCP', '-h127.0.0.1', '-P3308', '--batch', '--skip-column-names']
        if schema:
            command.append(schema)
        response = subprocess.run(command, input=sql, text=True, capture_output=True)
        if response.returncode:
            raise RuntimeError('Local MySQL operation failed; credentials withheld')
        return response.stdout.strip()

    def start(name):
        ready[name].unlink(missing_ok=True)
        cp, root, main_class, flag = (
            (host_cp, ROOT, 'ai.neargo.shop.commercebridge.StoreEntityClaimLocalLauncher', '-Dstore.claim.local.acceptance=true')
            if name == 'host' else
            (pay_cp, args.near_pay_root, 'ai.neargo.shop.pay.svc.StoreClaimAiShopLocalLauncher', '-Dnearpay.store.claim.local.acceptance=true'))
        log = output / (name + '.log')
        stream = log.open('a')
        log.chmod(0o600)
        handles.append(stream)
        command = [str(JAVA), '-Xmx900m', '-XX:ActiveProcessorCount=2', '-Duser.timezone=UTC', flag]
        agent = next((p for p in cp.split(os.pathsep) if Path(p).name.startswith('mockito-core-') and p.endswith('.jar')), None)
        if agent:
            command.append('-javaagent:' + agent)
        command.extend(['-cp', cp, main_class])
        process = subprocess.Popen(command, cwd=root, env=env, stdout=stream, stderr=subprocess.STDOUT)
        processes[name] = process
        deadline, notice = time.monotonic() + 150, time.monotonic()
        while time.monotonic() < deadline:
            if process.poll() is not None:
                if name == 'host':
                    # The owned auth cache is named from this launcher's newly created schema.
                    candidates = set(re.findall(r"Cache '(commerce_test_[a-f0-9]{32})[si]' created", log.read_text()))
                    if len(candidates) == 1:
                        owned = candidates.pop()
                        if owned not in created:
                            created.append(owned)
                raise RuntimeError(name + ' exited; inspect ' + str(log))
            if ready[name].exists():
                if ready[name].stat().st_mode & 0o077:
                    raise RuntimeError('Readiness must be private')
                data = json.loads(ready[name].read_text())
                origin = urlsplit(data[name + 'Origin'])
                if origin.scheme != 'http' or origin.hostname != '127.0.0.1':
                    raise RuntimeError('Loopback HTTP required')
                print(name + ' ready on loopback', flush=True)
                return data
            if time.monotonic() - notice > 20:
                print('Waiting for ' + name, flush=True)
                notice = time.monotonic()
            time.sleep(.2)
        raise RuntimeError(name + ' readiness timed out; inspect ' + str(log))

    def stop(name):
        process = processes.get(name)
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)

    class Proxy(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            if self.path == '/__local/drop-pay-commit':
                self.rfile.read(int(self.headers.get('Content-Length', 0)))
                fault['armed'] = True
                return self.reply({'armed': True})
            if self.path == '/__local/restart':
                self.rfile.read(int(self.headers.get('Content-Length', 0)))
                try:
                    stop('host'); stop('pay')
                    start('pay'); start('host')
                    fault['restarts'] += 1
                    return self.reply({'restarted': True})
                except Exception as failure:
                    result['restartError'] = type(failure).__name__
                    return self.reply({'restarted': False}, 500)
            self.forward()

        def do_GET(self):
            self.forward()

        def reply(self, value, status=200):
            data = json.dumps(value).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def forward(self):
            if not self.path.startswith('/internal/pay/'):
                return self.reply({'error': 'local proxy only'}, 404)
            body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
            headers = {k: v for k, v in self.headers.items() if k.lower() not in ('host', 'connection')}
            connection = http.client.HTTPConnection('127.0.0.1', pay_port, timeout=30)
            try:
                connection.request(self.command, self.path, body, headers)
                response = connection.getresponse()
                data = response.read()
                if (fault['armed'] and self.command == 'POST' and '/store-claims/' in self.path
                        and self.path.endswith('/commit') and response.status == 200):
                    fault['armed'] = False
                    fault['drops'] += 1
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
                self.send_response(response.status)
                for key, value in response.getheaders():
                    if key.lower() not in ('transfer-encoding', 'connection', 'content-length'):
                        self.send_header(key, value)
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            finally:
                connection.close()

    proxy = ThreadingHTTPServer(('127.0.0.1', 0), Proxy)
    proxy.daemon_threads = True
    proxy_origin = 'http://127.0.0.1:' + str(proxy.server_port)
    env['N1_PAY_ORIGIN'] = proxy_origin
    threading.Thread(target=proxy.serve_forever, daemon=True).start()
    try:
        result['stage'] = 'mysql'
        if not mysql('SELECT VERSION();').startswith('9.'):
            raise RuntimeError('MySQL9 required')
        mysql('CREATE DATABASE ' + pay_schema + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;')
        created.append(pay_schema)
        result['stage'] = 'host-launch'
        host = start('host')
        if not re.fullmatch(r'commerce_test_[a-f0-9]{32}', host['schema']):
            raise RuntimeError('Unexpected host schema')
        created.append(host['schema'])
        env['N1_HOST_SCHEMA'] = host['schema']
        result['stage'] = 'pay-launch'
        start('pay')
        host['controlOrigin'] = proxy_origin
        ready['app'].write_text(json.dumps(host))
        ready['app'].chmod(0o600)
        result['stage'] = 'dart-http'
        with (output / 'flutter.log').open('w') as log:
            run = subprocess.run([str(args.app_root / 'tooling/flutterw'), 'test', '--no-pub',
                                  'test/integration/store_claim_aishop_runtime_test.dart', '--reporter=expanded'],
                                 cwd=args.app_root / 'apps/merchant_app', env=env,
                                 stdout=log, stderr=subprocess.STDOUT)
        if run.returncode:
            raise RuntimeError('Dart HTTP acceptance failed; inspect ' + str(output / 'flutter.log'))
        if fault['drops'] != 1 or fault['restarts'] != 1:
            raise RuntimeError('Actual commit-response loss and dual restart were not exercised')
        result['stage'] = 'projection-delivery'
        current_host = json.loads(ready['host'].read_text())
        control = urlsplit(current_host['syncControlOrigin'])
        if control.scheme != 'http' or control.hostname != '127.0.0.1':
            raise RuntimeError('Projection hook must use loopback')
        for store in ('S', 'S2'):
            connection = http.client.HTTPConnection(control.hostname, control.port, timeout=15)
            connection.request('POST', '/sync?storeNo=' + store, b'', {'X-Local-Control': token})
            response = connection.getresponse()
            response.read()
            connection.close()
            if response.status != 200:
                raise RuntimeError('Production projection consumer failed')
        projection_rows = mysql("SELECT external_store_no,external_entity_id,source_revision FROM pay_store_projection ORDER BY external_store_no;", pay_schema)
        rows = [line.split('\t') for line in projection_rows.splitlines()]
        if len(rows) != 2:
            raise RuntimeError('Both actual store projections must exist')
        before = {'S': host['initialProjection'], 'S2': host['secondaryInitialProjection']}
        for store, entity, revision in rows:
            if entity != host['targetEntityNo'] or int(revision) <= int(before[store]['revision']):
                raise RuntimeError('Projection owner/revision did not advance')
        internal_headers = {'Content-Type': 'application/json', 'X-Internal-Token': token,
                            'X-Internal-Caller': 'AI_SHOP_UME', 'X-Internal-Credential': credential}
        for original in before.values():
            connection = http.client.HTTPConnection('127.0.0.1', pay_port, timeout=15)
            connection.request('POST', '/internal/pay/v2/store-projections/sync', json.dumps(original), internal_headers)
            response = connection.getresponse()
            response.read()
            connection.close()
            # InternalServiceResponses intentionally returns an empty 409 body.
            if response.status != 409:
                raise RuntimeError('Old source replay must produce the ownership conflict, status=' + str(response.status))
        if projection_rows != mysql("SELECT external_store_no,external_entity_id,source_revision FROM pay_store_projection ORDER BY external_store_no;", pay_schema):
            raise RuntimeError('Old source replay changed committed projections')
        result['projectionDelivery'] = {'consumerHook': True, 'outboxWorker': False,
                                        'oldSourceRejected': True, 'projections': rows}
        host_rows = mysql("SELECT state,COUNT(*) FROM mch_store_entity_claim GROUP BY state;", host['schema'])
        pay_rows = mysql("SELECT state,COUNT(*) FROM pay_source_store_claim GROUP BY state;", pay_schema)
        if host_rows != 'COMPLETED\t2' or pay_rows != 'COMMITTED\t2':
            raise RuntimeError('Both original claims must have durable terminal receipts')
        result.update(status='PASS', stage='complete', fault=fault, hostStates=host_rows, payStates=pay_rows)
        print('PASS: real Dart/host/PAY claim and restart recovery; evidence:', output, flush=True)
    finally:
        stop('host'); stop('pay')
        proxy.shutdown(); proxy.server_close()
        for handle in handles:
            handle.close()
        for path in ready.values():
            path.unlink(missing_ok=True)
        for schema in reversed(created):
            mysql('DROP DATABASE IF EXISTS ' + schema + ';')
        for path in output.glob('*.log'):
            content = path.read_text(errors='replace')
            for private in private_values:
                if private:
                    content = content.replace(private, '[redacted]')
            content = re.sub(r'\b(?:btk|ctk|otk)_[A-Za-z0-9_-]+', '[redacted-token]', content)
            content = re.sub(r'Using generated security password: [^\r\n]+', 'Using generated security password: [redacted]', content)
            path.write_text(content)
            path.chmod(0o600)
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    main()
