#!/usr/bin/env python3
"""Run pricing publication against an isolated InnoDB schema; never read business credentials."""
import os
import re
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
    token = uuid.uuid4().hex[:12]
    container_name = 'pricing-config-' + token
    schema = 'pricing_test_' + token
    created = False
    try:
        docker('run', '--pull', 'never', '-d', '--name', container_name,
               '--label', 'ai.neargo.pricing-test=' + token, '-p', '127.0.0.1::3306',
               '--tmpfs', '/var/lib/mysql', '-e', 'MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1', IMAGE,
               '--innodb-buffer-pool-size=32M', '--innodb-log-buffer-size=4M',
               '--innodb-log-file-size=16M', '--max-connections=32', '--performance-schema=OFF')
        created = True
        deadline = time.monotonic() + 60
        while True:
            try:
                docker('exec', container_name, 'mariadb', '--protocol=TCP', '-h127.0.0.1', '-uroot', '--execute', 'SELECT 1')
                break
            except subprocess.CalledProcessError:
                if time.monotonic() >= deadline:
                    raise RuntimeError('Disposable MariaDB readiness timed out')
                time.sleep(1)
        docker('exec', container_name, 'mariadb', '--protocol=TCP', '-h127.0.0.1', '-uroot', '--execute',
               'CREATE DATABASE ' + schema)
        binding = docker('port', container_name, '3306/tcp')
        if not binding.startswith('127.0.0.1:') or '\n' in binding:
            raise RuntimeError('Expected a single loopback-only JDBC port')
        env = os.environ.copy()
        env.update(PRICING_TEST_DISPOSABLE='true', PRICING_TEST_JDBC_URL='jdbc:mysql://' + binding + '/' + schema)
        method = os.environ.get('PRICING_TEST_METHOD')
        if method and not re.fullmatch(r'[a-zA-Z][a-zA-Z0-9]+', method):
            raise RuntimeError('Only one explicit Java test method may be selected')
        selection = os.environ.get('PRICING_TEST_SUITE', 'publication' if method else 'all')
        available = {'publication': [('PricingConfigServiceTest', 23)], 'seed': [('PricingSeedServiceTest', 8)],
                     'tax': [('PricingTaxSettingServiceTest', 18)], 'quote': [('PricingQuotePersistenceTest', 23)],
                     'product': [('ai.neargo.shop.product.GoodsPricingPolicyPersistenceTest', 9)],
                     'all': [('PricingConfigServiceTest', 23), ('PricingSeedServiceTest', 8),
                             ('PricingTaxSettingServiceTest', 18), ('PricingQuotePersistenceTest', 23),
                             ('ai.neargo.shop.product.GoodsPricingPolicyPersistenceTest', 9)]}
        if selection not in available or method and selection == 'all':
            raise RuntimeError('Select publication, seed, tax, quote, product, or all; a method requires one suite')
        suites = available[selection]
        pattern = ','.join(name + ('#' + method if method else '') for name, count in suites)
        with tempfile.NamedTemporaryFile(prefix='cko08-mariadb-', suffix='.log', delete=False) as log:
            result = subprocess.run(['mvn', '-o', '-f', 'backend/pom.xml', '-pl', 'shop-core', '-am', 'test',
                                     '-Dtest=' + pattern, '-Dsurefire.failIfNoSpecifiedTests=false'],
                                    cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        print('Real pricing MariaDB log:', log.name, flush=True)

        if result.returncode != 0:
            print('Disposable MariaDB OOMKilled:', docker('inspect', '--format', '{{.State.OOMKilled}}', container_name),
                  flush=True)
            print(docker('logs', '--tail', '15', container_name), flush=True)
            print('\n'.join(Path(log.name).read_text().splitlines()[-65:]), flush=True)
            raise RuntimeError('Real pricing publication tests failed')
        for name, minimum in suites:
            qualified = name if '.' in name else 'ai.neargo.shop.trade.' + name
            report = ROOT / ('backend/shop-core/target/surefire-reports/TEST-' + qualified + '.xml')
            suite = ET.parse(report).getroot()
            assert int(suite.attrib['tests']) >= (1 if method else minimum)
            assert all(int(suite.attrib[k]) == 0 for k in ('failures', 'errors', 'skipped'))
            print(name + ':', suite.attrib['tests'], 'actual passed, zero skips', flush=True)
    finally:
        if created:
            docker('rm', '-f', container_name)
            assert not docker('ps', '-a', '--filter', 'name=^/' + container_name + '$', '--format', '{{.Names}}')
            print('Disposable MariaDB removed:', container_name, flush=True)


if __name__ == '__main__':
    main()
