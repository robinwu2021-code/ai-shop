#!/usr/bin/env python3
"""N2 real App/host/PAY/OPS-client journey on owned loopback MySQL9 databases."""
import argparse
import base64
import configparser
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import socket
import struct
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlsplit
import uuid
import zlib

ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location('n1_local', ROOT / 'scripts/verify-store-claim-local.py')
common = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(common)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app-root', type=Path, default=ROOT.parent / 'ai-shop-bapp')
    parser.add_argument('--near-pay-root', type=Path, default=ROOT.parent / 'near-pay')
    parser.add_argument('--mysql-config', type=Path, default=Path.home() / '.config/mysql9/client.cnf')
    args = parser.parse_args()
    host_cp = common.classpath(ROOT / 'backend/shop-app/target/surefire-reports/TEST-ai.neargo.shop.commercebridge.PayChannelAccountClaimHostMysqlTest.xml')
    pay_cp = common.classpath(args.near_pay_root / 'backend/pay/pay-svc/target/surefire-reports/TEST-ai.neargo.shop.pay.svc.ChannelAccountClaimIntegrationTest.xml')
    config = configparser.ConfigParser(interpolation=None)
    if not config.read(args.mysql_config) or 'client' not in config:
        raise RuntimeError('Private local MySQL9 configuration required')
    output = Path(tempfile.mkdtemp(prefix='channel-account-claim-local-'))
    output.chmod(0o700)
    ready = {name: output / (name + '-ready.json') for name in ('host', 'pay', 'app')}
    created, processes, handles = [], {}, []
    token, credential, control_key, password = (secrets.token_urlsafe(36) for _ in range(4))
    master = base64.b64encode(secrets.token_bytes(32)).decode()
    db_password = config['client'].get('password', '').strip("\"'")
    private_values = [token, credential, control_key, password, master, db_password]
    pay_port, host_port = common.free_port(), common.free_port()
    while host_port == pay_port:
        host_port = common.free_port()
    schema = 'near_pay_claim_n2_' + uuid.uuid4().hex[:16]
    env = os.environ.copy()
    env.update({
        'JAVA_TOOL_OPTIONS': '-Duser.timezone=UTC',
        'COMMERCE_MYSQL_URL': 'jdbc:mysql://127.0.0.1:3308/?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC',
        'COMMERCE_MYSQL_USER': config['client'].get('user', 'root').strip("\"'"),
        'COMMERCE_MYSQL_PASSWORD': db_password,
        'ALIGNMENT_MYSQL_USER': config['client'].get('user', 'root').strip("\"'"),
        'ALIGNMENT_MYSQL_PASSWORD': db_password,
        'ALIGNMENT_PAY_JDBC_URL': f'jdbc:mysql://127.0.0.1:3308/{schema}?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC',
        'ALIGNMENT_PAY_REPO_ROOT': str(args.near_pay_root.resolve()),
        'ALIGNMENT_PAY_PORT': str(pay_port), 'ALIGNMENT_PAY_READY_FILE': str(ready['pay']),
        'ALIGNMENT_INTERNAL_TOKEN': token, 'ALIGNMENT_PAY_INTERNAL_CREDENTIAL': credential,
        'ALIGNMENT_MASTER_KEY': master,
        'N2_HOST_PORT': str(host_port), 'N2_HOST_READY_FILE': str(ready['host']), 'N2_OWNER_PASSWORD': password,
        'N2_SOURCE_ENTITY_REF': 'M', 'N2_MEMBERS_JSON': json.dumps([{'type': 'USER', 'ref': 'U', 'role': 'MERCHANT_ADMIN'}]),
        'BAPP_CHANNEL_CLAIM_READY': str(ready['app']), 'N2_OPS_RUNTIME_READY': str(ready['app']),
    })
    env.pop('N2_HOST_SCHEMA', None); env.pop('N2_OPS_TOKEN', None)
    state = {'uploadDrop': False, 'holdReceipts': False, 'opsDrop': False,
             'uploadDrops': 0, 'opsDrops': 0, 'restarts': 0, 'opsRuns': 0}
    result = {'status': 'FAIL', 'remoteAccess': False, 'vendorAccess': False,
              'opsSessionKind': 'CONTROLLED_DB_SESSION_NOT_PASSWORD_LOGIN'}

    def mysql(sql, database=None):
        if database is not None and database not in created:
            raise RuntimeError('Refusing non-owned schema')
        cmd = [str(common.MYSQL), '--defaults-file=' + str(args.mysql_config.resolve()),
               '--protocol=TCP', '-h127.0.0.1', '-P3308', '--batch', '--skip-column-names']
        if database:
            cmd.append(database)
        response = subprocess.run(cmd, input=sql, text=True, capture_output=True)
        if response.returncode:
            raise RuntimeError('Owned MySQL operation failed; credentials withheld')
        return response.stdout.strip()

    def start(name):
        ready[name].unlink(missing_ok=True)
        cp, repository, main_class, flag = (
            (host_cp, ROOT, 'ai.neargo.shop.commercebridge.ChannelAccountClaimHostLocalLauncher', '-Dchannel.account.claim.host.local.acceptance=true')
            if name == 'host' else
            (pay_cp, args.near_pay_root, 'ai.neargo.shop.pay.svc.ChannelAccountClaimAiShopLocalLauncher', '-Dnearpay.channel.account.claim.local.acceptance=true'))
        log = output / (name + '.log'); stream = log.open('a'); log.chmod(0o600); handles.append(stream)
        cmd = [str(common.JAVA), '-Xmx900m', '-XX:ActiveProcessorCount=2', '-Duser.timezone=UTC', flag]
        agent = next((p for p in cp.split(os.pathsep) if Path(p).name.startswith('mockito-core-') and p.endswith('.jar')), None)
        if agent:
            cmd.append('-javaagent:' + agent)
        cmd.extend(['-cp', cp, main_class])
        process = subprocess.Popen(cmd, cwd=repository, env=env, stdout=stream, stderr=subprocess.STDOUT)
        processes[name] = process
        deadline, notice = time.monotonic() + 180, time.monotonic()
        while time.monotonic() < deadline:
            if process.poll() is not None:
                if name == 'host':
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
                    raise RuntimeError('Only loopback HTTP is allowed')
                for key, value in data.items():
                    if isinstance(value, str) and re.search('token|password|credential', key, re.I):
                        private_values.append(value)
                print(name + ' ready on loopback', flush=True)
                return data
            if time.monotonic() - notice > 20:
                print('Waiting for ' + name, flush=True); notice = time.monotonic()
            time.sleep(.2)
        raise RuntimeError(name + ' timed out; inspect ' + str(log))

    def stop(name):
        process = processes.get(name)
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=10)

    class Proxy(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def reply(self, body, status=200):
            data = json.dumps(body).encode(); self.send_response(status)
            self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(data)))
            self.end_headers(); self.wfile.write(data)

        def do_POST(self):
            if self.path.startswith('/__local/'):
                body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
                if self.headers.get('X-Local-Control') != control_key:
                    return self.reply({'error': 'local control denied'}, 403)
                if self.path == '/__local/drop-upload-and-hold-receipts':
                    state.update(uploadDrop=True, holdReceipts=True); return self.reply({'armed': True})
                if self.path == '/__local/release':
                    state['holdReceipts'] = False; return self.reply({'released': True})
                if self.path == '/__local/drop-ops-response':
                    state['opsDrop'] = True; return self.reply({'armed': True})
                if self.path == '/__local/restart':
                    try:
                        stop('pay'); stop('host'); fresh_host = start('host')
                        if fresh_host['opsToken'] != env['N2_OPS_TOKEN']:
                            raise RuntimeError('Original OPS DB session was replaced')
                        fresh_pay = start('pay')
                        if fresh_pay['merchantNo'] != app['merchantNo']:
                            raise RuntimeError('Original PAY merchant changed')
                        state['restarts'] += 1; return self.reply({'restarted': True})
                    except Exception as failure:
                        result['restartError'] = type(failure).__name__
                        return self.reply({'restarted': False}, 500)
                if self.path == '/__local/run-ops':
                    data = json.loads(body)
                    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', data.get('claimNo', '')):
                        return self.reply({'error': 'invalid owned claim'}, 400)
                    local_env = {**env, 'N2_OPS_CLAIM_NO': data['claimNo']}
                    with (output / 'ops.log').open('w') as log:
                        run = subprocess.run(['npm', 'test', '--', 'tests/integration/channel-account-claim-runtime.test.ts'],
                            cwd=args.near_pay_root / 'ops-web', env=local_env, stdout=log, stderr=subprocess.STDOUT)
                    if run.returncode or not (output / 'ops-result.json').exists():
                        return self.reply({'error': 'actual OPS client acceptance failed'}, 500)
                    state['opsRuns'] += 1
                    return self.reply(json.loads((output / 'ops-result.json').read_text()))
                return self.reply({'error': 'unknown local control'}, 404)
            self.forward()

        def do_GET(self):
            self.forward()

        def forward(self):
            if not self.path.startswith(('/biz/pay/', '/internal/pay/', '/pay-ops/')):
                return self.reply({'error': 'local PAY transport only'}, 404)
            if state['holdReceipts'] and self.command == 'GET' and '/channel-account-claims/' in self.path and '/commands/' in self.path:
                return self.reply({'code': 10505, 'message': 'local receipt transport unavailable', 'data': None}, 503)
            body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
            headers = {k: v for k, v in self.headers.items() if k.lower() not in ('host', 'connection')}
            upstream = http.client.HTTPConnection('127.0.0.1', pay_port, timeout=30)
            try:
                upstream.request(self.command, self.path, body, headers); response = upstream.getresponse(); data = response.read()
                upload = state['uploadDrop'] and self.command == 'POST' and self.path.startswith('/biz/pay/v2/channel-account-claims/') and self.path.endswith('/files') and response.status == 200
                ops = state['opsDrop'] and self.command == 'POST' and self.path.startswith('/pay-ops/v2/channel-account-claims/') and self.path.endswith('/resolve')
                if upload or ops:
                    state['uploadDrop' if upload else 'opsDrop'] = False
                    state['uploadDrops' if upload else 'opsDrops'] += 1
                    self.close_connection = True; self.connection.shutdown(socket.SHUT_RDWR); self.connection.close(); return
                self.send_response(response.status)
                for key, value in response.getheaders():
                    if key.lower() not in ('transfer-encoding', 'connection', 'content-length'):
                        self.send_header(key, value)
                self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
            finally:
                upstream.close()

    proxy = ThreadingHTTPServer(('127.0.0.1', 0), Proxy); proxy.daemon_threads = True
    proxy_origin = 'http://127.0.0.1:' + str(proxy.server_port)
    env['ALIGNMENT_PAY_ORIGIN'] = proxy_origin
    threading.Thread(target=proxy.serve_forever, daemon=True).start()
    # A generated one-pixel PNG is a fixture, never a merchant's private document.
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(b'\x00\xff\x00\x00\xff')) + chunk(b'IEND', b'')
    proof = output / 'proof.png'; proof.write_bytes(png); proof.chmod(0o600)
    app = {}
    try:
        result['stage'] = 'mysql'
        if not mysql('SELECT VERSION();').startswith('9.'):
            raise RuntimeError('MySQL9 required')
        mysql('CREATE DATABASE ' + schema + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'); created.append(schema)
        result['stage'] = 'host-launch'; host = start('host')
        if not re.fullmatch(r'commerce_test_[a-f0-9]{32}', host['schema']):
            raise RuntimeError('Unexpected host schema')
        created.append(host['schema']); env['N2_HOST_SCHEMA'] = host['schema']; env['N2_OPS_TOKEN'] = host['opsToken']
        env['ALIGNMENT_HOST_ORIGIN'] = host['hostOrigin']
        result['stage'] = 'pay-launch'; pay = start('pay')
        app = {**host, 'merchantNo': pay['merchantNo'], 'payOrigin': proxy_origin,
               'controlOrigin': proxy_origin, 'controlKey': control_key, 'proofPath': str(proof),
               'proofSha256': hashlib.sha256(png).hexdigest(), 'opsResultPath': str(output / 'ops-result.json')}
        ready['app'].write_text(json.dumps(app)); ready['app'].chmod(0o600)
        result['stage'] = 'dart-and-ops-http'
        with (output / 'flutter.log').open('w') as log:
            run = subprocess.run([str(args.app_root / 'tooling/flutterw'), 'test', '--no-pub',
                'test/integration/channel_claim_aishop_runtime_test.dart', '--reporter=expanded'],
                cwd=args.app_root / 'apps/merchant_app', env=env, stdout=log, stderr=subprocess.STDOUT)
        if run.returncode:
            raise RuntimeError('Actual N2 HTTP acceptance failed; inspect ' + str(output / 'flutter.log'))
        if (state['uploadDrops'], state['opsDrops'], state['restarts'], state['opsRuns']) != (1, 2, 1, 1):
            raise RuntimeError('Expected upload/OPS response loss and restart were not exercised')
        claims = mysql('SELECT state,COUNT(*) FROM pay_channel_account_claim GROUP BY state;', schema)
        accounts = mysql('SELECT status,verification_status,COUNT(*) FROM pay_merchant GROUP BY status,verification_status;', schema)
        if claims != 'LINKED\t1' or accounts != 'UNVERIFIED\tPENDING\t1':
            raise RuntimeError('Claim linkage must not verify or activate the actual account')
        host_entity = mysql("SELECT entity_no FROM mch_store WHERE store_no='S';", host['schema'])
        host_payment_rows = mysql("SELECT COUNT(*) FROM mch_payment_merchant WHERE entity_no='M';", host['schema'])
        host_files = mysql("SELECT COUNT(*) FROM mch_qualification WHERE entity_no='M';", host['schema'])
        sealed = mysql('SELECT COUNT(*) FROM pay_channel_account_claim_file WHERE pinned=1 AND OCTET_LENGTH(encrypted_content)=byte_length+16 AND OCTET_LENGTH(nonce)=12 AND key_blob_id>0;', schema)
        if (host_entity, host_payment_rows, host_files, sealed) != ('M', '0', '0', '1'):
            raise RuntimeError('Unexpected host shadow state or unsealed PAY evidence')
        outcomes = mysql("SELECT JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.outcome')),COUNT(*) FROM pay_channel_account_claim_command GROUP BY JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.outcome'));", schema)
        result.update(status='PASS', stage='complete', fault=state, claimStates=claims, accountStates=accounts,
                      hostEntity=host_entity, hostPaymentRows=0, hostQualificationRows=0, sealedFiles=1, commandOutcomes=outcomes)
        print('PASS: N2 actual App/host/PAY/OPS clients with private-proof and receipt recovery; evidence:', output, flush=True)
    finally:
        stop('pay'); stop('host'); proxy.shutdown(); proxy.server_close()
        for handle in handles:
            handle.close()
        for path in ready.values():
            path.unlink(missing_ok=True)
        for database in reversed(created):
            mysql('DROP DATABASE IF EXISTS ' + database + ';')
        for path in output.glob('*.log'):
            content = path.read_text(errors='replace')
            for private in private_values:
                if private:
                    content = content.replace(private, '[redacted]')
            content = re.sub(r'\b(?:btk|ctk|otk)_[A-Za-z0-9_-]+', '[redacted-token]', content)
            content = re.sub(r'Using generated security password: [^\r\n]+', 'Using generated security password: [redacted]', content)
            path.write_text(content); path.chmod(0o600)
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    main()
