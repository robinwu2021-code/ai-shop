#!/usr/bin/env python3
"""Verify food catalog migrations and HTTP flows on disposable MariaDB; never use business credentials."""
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
    name = 'food-recipe-maria-' + uuid.uuid4().hex[:12]
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
                    raise RuntimeError('Disposable food MariaDB startup timed out')
                time.sleep(1)
        binding = docker('port', name, '3306/tcp')
        if not binding.startswith('127.0.0.1:') or '\n' in binding:
            raise RuntimeError('Expected one loopback-only test port')
        env = os.environ.copy()
        # The existing MySQL JDBC driver connects to the MariaDB server pinned by the disposable image.
        env.update(FOOD_TEST_DISPOSABLE='true', FOOD_TEST_JDBC_URL='jdbc:mysql://' + binding + '/mysql')
        with tempfile.NamedTemporaryFile(prefix='food-catalog-mariadb-', suffix='.log', delete=False) as log:
            result = subprocess.run(['mvn', '-o', '-f', 'backend-plugins/shop-industry-food/pom.xml', '-pl', 'food-server', '-am',
                                     'test', '-Dtest=MenuServiceTest,MenuCompositionServiceTest,MenuContextServiceTest,MenuChannelBindingServiceTest,StationServiceTest,SalesRestrictionServiceTest,CatalogPublicationServiceTest,CatalogServiceTest,MenuScheduleServiceTest,MenuSchedulePolicyTest,FoodRecipePrimaryDatabaseTest',
                                     '-Dsurefire.failIfNoSpecifiedTests=false'], cwd=ROOT, env=env,
                                    stdout=log, stderr=subprocess.STDOUT)
        print('Food catalog MariaDB log:', log.name, flush=True)
        if result.returncode != 0:
            print('\n'.join(Path(log.name).read_text().splitlines()[-45:]))
            raise RuntimeError('Food catalog MariaDB tests failed')
        for module, name, expected in [('food-core','MenuServiceTest',7),
                                        ('food-core','MenuCompositionServiceTest',4),
                                        ('food-core','MenuContextServiceTest',2),
                                        ('food-core','MenuChannelBindingServiceTest',5),
                                        ('food-core','StationServiceTest',4),
                                        ('food-core','SalesRestrictionServiceTest',5),
                                        ('food-core','CatalogPublicationServiceTest',10),
                                        ('food-core','CatalogServiceTest',10),
                                        ('food-core','MenuScheduleServiceTest',5),
                                        ('food-core','MenuSchedulePolicyTest',4),
                                        ('food-server','FoodRecipePrimaryDatabaseTest',1)]:
            report = ROOT / ('backend-plugins/shop-industry-food/'+module+'/target/surefire-reports/'
                             'TEST-ai.neargo.shop.food.scenario.'+name+'.xml')
            suite = ET.parse(report).getroot()
            assert int(suite.attrib['tests'])==expected
            assert not any(int(suite.attrib[k]) for k in ['failures','errors','skipped'])
            print(name+': '+str(expected)+' actual passed, no skips',flush=True)
    finally:
        if created:
            docker('rm', '-f', name)


if __name__ == '__main__':
    main()
