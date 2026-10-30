#!/usr/bin/env python3
"""Local MySQL9 restored test clone + actual FOOD/commerce boot jars. No production inputs."""
import argparse
import base64
import configparser
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-defaults', required=True, type=Path)
    parser.add_argument('--template', required=True, help='Existing local food_host_smoke[_tag] test schema; read only')
    parser.add_argument('--food-jar', required=True, type=Path)
    parser.add_argument('--commerce-jar', required=True, type=Path)
    parser.add_argument('--mysql-bin', type=Path, default=Path('/opt/homebrew/opt/mysql@9.7/bin'))
    args = parser.parse_args()
    if not re.fullmatch(r'food_host_smoke(?:_[a-z0-9_]{1,32})?', args.template):
        parser.error('Only an existing local food_host_smoke test template is allowed')
    config = configparser.ConfigParser(interpolation=None)
    config.read(args.mysql_defaults)
    db = config['client']
    if db.get('host') != '127.0.0.1' or db.get('port') != '3308':
        parser.error('Use the local native MySQL9 endpoint 127.0.0.1:3308')
    for jar in (args.food_jar, args.commerce_jar):
        if not jar.is_file():
            parser.error('Build both standalone boot jars before this verification')
    mysql = [str(args.mysql_bin / 'mysql'), '--defaults-file=' + str(args.mysql_defaults.resolve()), '-N']

    def sql(statement):
        return subprocess.check_output(mysql + ['-e', statement], text=True).strip()

    assert sql('SELECT VERSION()').startswith('9.'), 'Native MySQL9 required'
    schema = 'food_topology_it_' + uuid.uuid4().hex
    token, scope_key = secrets.token_hex(24), secrets.token_hex(32)
    logs = Path(tempfile.mkdtemp(prefix='food-standalone-'))
    processes = []
    created = False

    def stop(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()

    def launch(name, jar, extra):
        env = os.environ.copy()
        env.update(SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3308/' + schema + '?allowPublicKeyRetrieval=true&useSSL=false',
                   SPRING_DATASOURCE_USERNAME=db.get('user', 'root'), SPRING_DATASOURCE_PASSWORD=db.get('password', ''),
                   FOOD_DB_URL='jdbc:mysql://127.0.0.1:3308/' + schema + '?allowPublicKeyRetrieval=true&useSSL=false',
                   FOOD_DB_USER=db.get('user', 'root'), FOOD_DB_PASSWORD=db.get('password', ''),
                   SHOP_INTERNAL_TOKEN=token, SHOP_FOOD_SCOPE_KEY=scope_key)
        java = str(Path(env['JAVA_HOME']) / 'bin/java') if env.get('JAVA_HOME') else shutil.which('java')
        path = logs / (name + '.log')
        with path.open('w') as output:
            process = subprocess.Popen([java, '-Xmx768m', '-jar', str(jar.resolve()), '--server.port=0',
                                        '--server.address=127.0.0.1', '--spring.flyway.enabled=false',
                                        '--spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration',
                                        '--shop.seed.enabled=false', '--shop.job.enabled=false',
                                        '--shop.outbox.enabled=false', '--shop.food.local-stock.enabled=true',
                                        '--shop.food.migration.mode=validate', '--shop.commerce.migration.mode=validate',
                                        '--shop.services.internal-token=${SHOP_INTERNAL_TOKEN}',
                                        '--shop.food.scope-key=${SHOP_FOOD_SCOPE_KEY}'] + extra,
                                       env=env, stdout=output, stderr=subprocess.STDOUT)
        processes.append(process)
        deadline = time.monotonic() + 120
        while process.poll() is None and time.monotonic() < deadline:
            match = re.search(r'Tomcat started on port (\d+)', path.read_text())
            if match:
                return process, 'http://127.0.0.1:' + match[1]
            time.sleep(.2)
        raise RuntimeError(name + ' failed to start; inspect ' + str(path))

    def call(base, path, body=None, signed=False, stores=None, internal=True):
        data = None if body is None else json.dumps(body, separators=(',', ':')).encode()
        headers = {'Content-Type': 'application/json'}
        if internal:
            headers['X-Internal-Token'] = token
        if signed:
            digest = hashlib.sha256(b'POST\n' + path.encode() + b'\n' + data).hexdigest()
            claims = {'r': 'SYSTEM' if stores is None else 'STAFF', 'e': None if stores is None else entity,
                      's': [] if stores is None else stores, 'a': None if stores is None else 'IT',
                      'x': int(time.time()) + 60, 'd': digest}
            encode = lambda value: base64.urlsafe_b64encode(value).rstrip(b'=')
            payload = encode(json.dumps(claims, separators=(',', ':')).encode())
            original = b'v1.' + payload
            headers['X-Food-Scope'] = (original + b'.' + encode(hmac.new(scope_key.encode(), original, hashlib.sha256).digest())).decode()
        request = urllib.request.Request(base + path, data=data, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as failure:
            return failure.code, None

    def food_call(path, body):
        status, result = call(food, '/internal/food/stock-management/' + path, body, signed=True)
        assert status == 200, (path, status)
        return result

    entity, store = 'E' + uuid.uuid4().hex[:16], 'S' + uuid.uuid4().hex[:16]
    try:
        # Never execute a mutable target/*.jar: concurrent builds must not replace a live archive.
        for attribute in ('food_jar', 'commerce_jar'):
            source_jar = getattr(args, attribute)
            fixed_jar = logs / (attribute + '.jar')
            shutil.copy2(source_jar, fixed_jar)
            print(attribute + ' SHA256 ' + hashlib.sha256(fixed_jar.read_bytes()).hexdigest(), flush=True)
            setattr(args, attribute, fixed_jar)
        # Real mysqldump restore includes original data, history, keys and triggers. Never repair/baseline history.
        print('Restoring a disposable copy of the local test template', flush=True)
        dump = logs / 'template.sql'
        with dump.open('wb') as output:
            subprocess.run([str(args.mysql_bin / 'mysqldump'), '--defaults-file=' + str(args.mysql_defaults.resolve()),
                            '--single-transaction', '--no-tablespaces', '--set-gtid-purged=OFF', args.template],
                           stdout=output, check=True)
        sql('CREATE DATABASE `' + schema + '` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_520_ci')
        created = True
        with dump.open('rb') as source:
            subprocess.run(mysql + [schema], stdin=source, check=True)
        dump.unlink()
        print('Restore complete; starting real standalone jars (stateless internal APIs, Redis auto-configuration excluded)', flush=True)
        process, food = launch('food', args.food_jar, ['--shop.food.enabled=true', '--shop.commerce.enabled=false'])
        _, commerce = launch('commerce', args.commerce_jar, ['--shop.food.enabled=false', '--shop.commerce.enabled=true'])
        for base, health in ((food, '/actuator/health/readiness'), (commerce, '/actuator/health')):
            deadline = time.monotonic() + 30
            while call(base, health)[0] != 200:
                assert time.monotonic() < deadline, health
                time.sleep(.2)
        query = {'entityNo': entity, 'storeNo': store}
        path = '/internal/food/stock-management/sync/config'
        assert call(food, path, query)[0] == 401, 'Token alone must not authorize FOOD'
        foreign_status = call(food, path, query, signed=True, stores=['FOREIGN'])[0]
        assert foreign_status == 403, ('Foreign scope', foreign_status)
        assert food_call('sync/config', query)['status'] == 'OFF'
        create = {**query, 'actorNo': 'IT', 'key': 'create',
                  'input': {'itemKind': 'MATERIAL', 'weighed': 0, 'name': 'Standalone material', 'baseUnit': 'KG', 'trackingMode': 'TRACKED'}}
        item = food_call('items/create', create)
        assert food_call('items/create', create)['itemNo'] == item['itemNo'], 'Original idempotency key'
        initialized = food_call('balances/initialize', {**query, 'actorNo': 'IT', 'key': 'initial',
            'input': {'itemNo': item['itemNo'], 'trackingMode': 'TRACKED', 'status': 'ACTIVE', 'initialQty': 10,
                      'reasonCode': 'INITIAL', 'reasonDetail': 'Isolated physical fixture'}})
        # Decimal scale is preserved through the real internal JSON contract.
        adjusted = food_call('balances/adjust', {**query, 'actorNo': 'IT', 'itemNo': item['itemNo'], 'key': 'adjust',
            'input': {'delta': .123456, 'version': initialized['version'], 'reasonCode': 'COUNT', 'reasonDetail': 'Fixture count'}})
        assert str(adjusted['onHand']) == '10.123456'
        assert call(food, '/internal/food/stock-sync/dispatch', {}, signed=True)[0] == 400, 'Framework validation'
        envelope = {'eventNo': 'ORIGINAL', 'sourceSystem': 'PLATFORM', 'sourceEventNo': 'ORIGINAL',
                    'aggregateType': 'FOOD_STOCK_SYNC', 'aggregateId': 'SYNC',
                    'eventType': 'FOOD_STOCK_SYNC_REQUESTED', 'schemaVersion': 1,
                    'streamKey': 'FOOD_STOCK_SYNC:' + store, 'streamSequence': 1,
                    'payload': json.dumps({'syncNo': 'SYNC', 'entityNo': entity, 'storeNo': 'FOREIGN',
                                          'generation': 1, 'action': 'ACTIVATE', 'payloadHash': 'H'}),
                    'occurredAt': '2026-10-30T00:00:00Z'}
        assert call(food, '/internal/food/stock-sync/dispatch', {**query, 'envelope': envelope}, signed=True)[0] == 403
        authority_path = '/internal/commerce/catalog-authorities/resolve'
        authority = {**query, 'providerCode': 'MOCK', 'externalAccountRef': 'ABSENT', 'externalStoreRef': 'ABSENT'}
        assert call(commerce, authority_path, authority, internal=False)[0] == 401
        assert call(commerce, authority_path, authority)[0] == 404, 'Original missing identity, no fabricated mapping'
        print('Signed API and database checks passed; restarting FOOD OS process', flush=True)
        stop(process)
        _, food = launch('food-restarted', args.food_jar, ['--shop.food.enabled=true', '--shop.commerce.enabled=false'])
        assert food_call('items/create', create)['itemNo'] == item['itemNo'], 'Persisted receipt after OS restart'
        assert str(food_call('balances/get', {**query, 'itemNo': item['itemNo']})['onHand']) == '10.123456'
        print('PASS: actual FOOD/commerce jars, native clone, signed scope, stock mutation/idempotency, FOOD OS restart')
        print('Boundary: no production changes; no full host+commerce order/menu workflow claim')
    finally:
        for process in reversed(processes):
            stop(process)
        if created:
            sql('DROP DATABASE `' + schema + '`')
        print('Process logs:', logs)


if __name__ == '__main__':
    main()
