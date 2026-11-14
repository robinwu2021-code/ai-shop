#!/usr/bin/env python3
"""Run actual ShopApplication with native MySQL9 in a brand-new disposable local schema.

Historical SQL compatibility uses the production Flyway adapter.
No test classes or generated schema are loaded; Flyway reads frozen resource bytes.
Credentials stay under a private runtime directory and are passed through the environment.
"""
import argparse
import base64
import configparser
import json
import hashlib
import shutil
import os
from pathlib import Path
import subprocess
import tempfile
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA_HOME = Path('/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home')


def freeze_main_outputs(entries, runtime):
    """Freeze current production outputs so later compiler writes cannot change a live process."""
    frozen = []
    manifest = {}
    for index, entry in enumerate(entries):
        source = Path(entry)
        if entry.endswith('/target/classes'):
            destination = runtime / 'main-snapshot' / f'{index:03d}-{source.parents[1].name}'
            shutil.copytree(source, destination)
            hashes = {}
            for file in sorted(source.rglob('*')):
                if file.is_file():
                    relative = file.relative_to(source)
                    original = hashlib.sha256(file.read_bytes()).hexdigest()
                    copied = hashlib.sha256((destination / relative).read_bytes()).hexdigest()
                    if original != copied:
                        raise RuntimeError('Production outputs changed during snapshot; wait for all builds to finish')
                    hashes[str(relative)] = original
            manifest[entry] = {'snapshot': str(destination), 'files': hashes}
            frozen.append(str(destination))
        else:
            frozen.append(entry)
    (runtime / 'main-snapshot-manifest.json').write_text(json.dumps(manifest, sort_keys=True))
    return frozen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=34211)
    parser.add_argument('--classpath-report', type=Path, default=ROOT / 'backend/shop-app/target/surefire-reports/TEST-ai.neargo.shop.portal.service.CustomerReportingMysqlTest.xml')
    parser.add_argument('--java-home', type=Path, default=JAVA_HOME)
    parser.add_argument('--self-test', action='store_true', help='Run native historical-adapter repeatability smoke before main')
    args = parser.parse_args()
    private_config = Path.home() / '.config/mysql9/client.cnf'
    cnf = configparser.ConfigParser(interpolation=None)
    cnf.read(private_config)
    client = cnf['client']
    mysql = ['/opt/homebrew/bin/mysql', '--defaults-file=' + str(private_config), '--host=127.0.0.1', '--port=3308', '--batch', '--skip-column-names']
    version = subprocess.run(mysql + ['-e', 'SELECT VERSION()'], capture_output=True, text=True, check=True).stdout.strip()
    if not version.startswith('9.'):
        raise RuntimeError('Native loopback MySQL9 is required')
    runtime = Path(tempfile.mkdtemp(prefix='ai-shop-full-local-', dir='/tmp'))
    runtime.chmod(0o700)
    schema = 'ai_shop_full_local_' + uuid.uuid4().hex[:8]
    subprocess.run(mysql + ['-e', f'CREATE DATABASE {schema} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci'], check=True)
    raw = ET.parse(args.classpath_report).find(".//property[@name='java.class.path']").attrib['value']
    entries = [entry for entry in raw.split(os.pathsep) if '/target/test-classes' not in entry]
    if not any(entry.endswith('/shop-app/target/classes') for entry in entries):
        raise RuntimeError('Classpath report must reference current main compiled outputs')
    missing = [entry for entry in entries if not Path(entry).exists()]
    # Maven includes target/classes for empty modules such as print-domain.
    if any(not entry.endswith('/target/classes') or any((Path(entry).parents[1] / 'src/main').rglob('*.*')) for entry in missing):
        raise RuntimeError('Classpath contains missing main outputs; compile using the coordinated reactor build first')
    entries = [entry for entry in entries if Path(entry).exists()]
    entries = freeze_main_outputs(entries, runtime)
    classpath = os.pathsep.join([str(runtime), *entries])
    java = args.java_home / 'bin/java'
    sources = sorted((ROOT / 'scripts/local-platform-runtime').glob('*.java'))
    subprocess.run([str(args.java_home / 'bin/javac'), '-proc:none', '-cp', classpath, '-d', str(runtime), *map(str, sources)], check=True)
    config = {
        'server.address': '127.0.0.1', 'server.port': args.port,
        'spring.profiles.active': 'api,ops', 'spring.security.user.password': base64.b64encode(os.urandom(32)).decode(),
        'spring.datasource.url': f'jdbc:mysql://127.0.0.1:3308/{schema}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8&rewriteBatchedStatements=true',
        'spring.datasource.username': client.get('user', 'root'), 'spring.datasource.password': client.get('password', ''),
        'shop.seed.enabled': True, 'shop.ops.auth.second-factor': 'off', 'shop.pay.stub': True,
        'shop.auth.token-store': 'db', 'shop.cors.extra-origins': 'http://127.0.0.1:34212,http://localhost:34212',
        'shop.secret.env': 'SANDBOX', 'shop.secret.master-keys': [{'alias': 'local-pay', 'namespace': 'PAY', 'material': base64.b64encode(os.urandom(32)).decode(), 'active': True}],
        'commerce.enabled': True, 'commerce.catalog.enabled': True,
        'commerce.access.active-key-id': 'local', 'commerce.access.keys.local': base64.b64encode(os.urandom(32)).decode(),
        'commerce.mall.enabled': True, 'commerce.mall.http.enabled': True,
        'commerce.mall.http.operator.enabled': True, 'commerce.mall.http.buyer.enabled': True,
        'commerce.mall.payment.enabled': True, 'commerce.mall.payment.platform-merchant-no': 'LOCAL_TEST_PLATFORM',
        'commerce.mall.payment.routes': 'OFFLINE:BANK_TRANSFER',
        'commerce.mall.offline.enabled': True, 'commerce.mall.offline.profile-no': 'LOCAL_TEST_COLLECTION_PROFILE',
        'shop.storage.root': str(runtime / 'storage'),
    }
    config_path = runtime / 'config.json'
    config_path.touch(mode=0o600)
    config_path.write_text(json.dumps(config))
    (runtime / 'classpath').write_text(classpath)
    (runtime / 'schema').write_text(schema)
    if args.self_test:
        env = dict(os.environ, LOCAL_DB_URL=config['spring.datasource.url'], LOCAL_DB_USER=config['spring.datasource.username'], LOCAL_DB_PASS=config['spring.datasource.password'])
        subprocess.run([str(java), '-cp', classpath, 'LocalMysqlHistoricalSmoke'], env=env, check=True)
    print(json.dumps({'runtime': str(runtime), 'schema': schema, 'mysql': version, 'port': args.port, 'main': 'ai.neargo.shop.ShopApplication', 'testClasses': False}), flush=True)
    os.environ['SPRING_APPLICATION_JSON'] = config_path.read_text()
    os.chdir(ROOT)
    os.execv(str(java), [str(java), '-Xmx1200m', '-cp', classpath, 'ai.neargo.shop.ShopApplication'])


if __name__ == '__main__':
    main()
