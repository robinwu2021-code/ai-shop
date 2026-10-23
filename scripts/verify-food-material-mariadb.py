#!/usr/bin/env python3
"""Verify original inventory migrations on disposable MariaDB, without rewriting frozen collation clauses."""
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
IMAGE = 'mariadb:11.4'


def docker(*args):
    return subprocess.check_output(['docker', *args], text=True, stderr=subprocess.STDOUT).strip()


def main():
    docker('image', 'inspect', IMAGE)
    name = 'food-material-' + uuid.uuid4().hex[:12]
    created = False
    try:
        docker('run', '--pull', 'never', '-d', '--name', name, '-p', '127.0.0.1::3306',
               '--tmpfs', '/var/lib/mysql', '-e', 'MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1', IMAGE)
        created = True
        deadline = time.monotonic() + 60
        while True:
            try:
                docker('exec', name, 'mariadb', '--protocol=TCP', '-h127.0.0.1', '-uroot',
                       '--execute', 'SELECT 1')
                break
            except subprocess.CalledProcessError:
                if time.monotonic() >= deadline:
                    raise RuntimeError('Disposable inventory MariaDB startup timed out')
                time.sleep(1)
        binding = docker('port', name, '3306/tcp')
        if not binding.startswith('127.0.0.1:') or '\n' in binding:
            raise RuntimeError('Expected one loopback-only test port')
        env = os.environ.copy()
        # The existing MySQL JDBC driver connects to the MariaDB server pinned by the disposable image.
        env.update(FOOD_TEST_DISPOSABLE='true', FOOD_TEST_JDBC_URL='jdbc:mysql://' + binding + '/mysql')
        with tempfile.NamedTemporaryFile(prefix='food-material-authority-', suffix='.log', delete=False) as log:
            result = subprocess.run(['mvn', '-o', '-f', 'backend/pom.xml', '-pl', 'shop-app', '-am',
                                     'test', '-Dtest=MaterialCatalogQueryPortTest',
                                     '-Dsurefire.failIfNoSpecifiedTests=false'], cwd=ROOT, env=env,
                                    stdout=log, stderr=subprocess.STDOUT)
        print('Inventory material authority log:', log.name, flush=True)
        if result.returncode != 0:
            print('\n'.join(Path(log.name).read_text().splitlines()[-45:]))
            raise RuntimeError('Original inventory material authority MariaDB test failed')
        report = ROOT / ('backend/shop-app/target/surefire-reports/'
                         'TEST-ai.neargo.shop.scenario.MaterialCatalogQueryPortTest.xml')
        suite = ET.parse(report).getroot()
        if int(suite.attrib['tests']) < 6 or any(int(suite.attrib[k]) for k in ['failures', 'errors', 'skipped']):
            raise RuntimeError('Expected executed original inventory material authority guards')
        print('Original inventory material authority MariaDB:', suite.attrib['tests'],
              'passed; no skipped tests', flush=True)
    finally:
        if created:
            docker('rm', '-f', name)


if __name__ == '__main__':
    main()
