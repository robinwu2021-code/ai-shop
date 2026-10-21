#!/usr/bin/env python3
"""Run food JDBC tests on a fresh, loopback-only, tmpfs MySQL container; never use app credentials."""
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NAME = 'food-primary-' + uuid.uuid4().hex[:12]
IMAGE = 'mysql:9.7.2'


def docker(*args):
    return subprocess.check_output(['docker', *args], text=True, stderr=subprocess.STDOUT).strip()


def main():
    docker('image', 'inspect', IMAGE)
    created = False
    try:
        docker('run', '--pull', 'never', '-d', '--name', NAME, '-p', '127.0.0.1::3306',
               '--tmpfs', '/var/lib/mysql', '-e', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', IMAGE)
        created = True
        deadline = time.monotonic() + 60
        while True:
            try:
                # The entrypoint starts a temporary socket-only server while initializing.
                # JDBC needs the final TCP listener, not mysqladmin's socket ping.
                docker('exec', NAME, 'mysql', '--protocol=TCP', '-h127.0.0.1', '-uroot',
                       '--execute', 'SELECT 1')
                break
            except subprocess.CalledProcessError:
                if time.monotonic() >= deadline:
                    raise RuntimeError('Disposable MySQL startup timed out')
                time.sleep(1)
        binding = docker('port', NAME, '3306/tcp')
        if not binding.startswith('127.0.0.1:') or '\n' in binding:
            raise RuntimeError('Expected one loopback-only test port')
        env = os.environ.copy()
        env.update(FOOD_TEST_DISPOSABLE='true', FOOD_TEST_JDBC_URL='jdbc:mysql://' + binding + '/mysql')
        with tempfile.NamedTemporaryFile(prefix='food-primary-tests-', suffix='.log', delete=False) as log:
            result = subprocess.run(['mvn', '-o', '-f', 'backend-plugins/shop-industry-food/pom.xml',
                                     '-pl', 'food-server', '-am', 'test', '-Dtest=FoodPrimaryDatabaseTest,FoodIdempotencyTest,FoodOutboxWiringTest,StoreOperationServiceTest,StorePolicyPublicationTest,ServiceCalendarServiceTest,TableServiceTest,TableConfigurationTest,DiningSessionTest,CheckServiceTest,ReservationServiceTest,DishServiceTest,FoodDishPrimaryDatabaseTest,FoodServerPrimaryDatabaseTest',
                                     '-Dsurefire.failIfNoSpecifiedTests=false'], cwd=ROOT, env=env,
                                    stdout=log, stderr=subprocess.STDOUT)
        print('Test log:', log.name)
        report = ROOT / 'backend-plugins/shop-industry-food/food-core/target/surefire-reports/TEST-ai.neargo.shop.food.scenario.FoodPrimaryDatabaseTest.xml'
        if result.returncode != 0:
            print('\n'.join(Path(log.name).read_text().splitlines()[-45:]))
            raise RuntimeError('Food primary integration tests failed')
        suite = ET.parse(report).getroot()
        if int(suite.attrib['tests']) < 17 or any(int(suite.attrib[k]) for k in ['failures', 'errors', 'skipped']):
            raise RuntimeError('Expected all disposable integration tests to execute and pass')
        server_report = ROOT / 'backend-plugins/shop-industry-food/food-server/target/surefire-reports/TEST-ai.neargo.shop.food.scenario.FoodServerPrimaryDatabaseTest.xml'
        server = ET.parse(server_report).getroot()
        if int(server.attrib['tests']) != 1 or any(int(server.attrib[k]) for k in ['failures', 'errors', 'skipped']):
            raise RuntimeError('Standalone MySQL test did not execute and pass')
        dish_report = ROOT / 'backend-plugins/shop-industry-food/food-server/target/surefire-reports/TEST-ai.neargo.shop.food.scenario.FoodDishPrimaryDatabaseTest.xml'
        dish = ET.parse(dish_report).getroot()
        if int(dish.attrib['tests']) != 1 or any(int(dish.attrib[k]) for k in ['failures', 'errors', 'skipped']):
            raise RuntimeError('Standalone dish MySQL typed test did not execute and pass')
        guards = 0
        for name, minimum in [('FoodIdempotencyTest', 6), ('FoodOutboxWiringTest', 7), ('StoreOperationServiceTest', 5), ('StorePolicyPublicationTest', 10), ('ServiceCalendarServiceTest', 6), ('TableServiceTest', 14), ('TableConfigurationTest', 18), ('DiningSessionTest', 10), ('CheckServiceTest', 22), ('ReservationServiceTest', 3), ('DishServiceTest', 4)]:
            report = ROOT / ('backend-plugins/shop-industry-food/food-core/target/surefire-reports/'
                             + 'TEST-ai.neargo.shop.food.scenario.' + name + '.xml')
            guard = ET.parse(report).getroot()
            if int(guard.attrib['tests']) < minimum or any(int(guard.attrib[k]) for k in ['failures', 'errors', 'skipped']):
                raise RuntimeError('Expected executed write guards: ' + name)
            guards += int(guard.attrib['tests'])
        print('Food primary MySQL:', suite.attrib['tests'], '+ guards', guards,
              '+ standalone', int(server.attrib['tests']) + int(dish.attrib['tests']), 'passed; no skipped tests')
    finally:
        if created:
            docker('rm', '-f', NAME)


if __name__ == '__main__':
    main()
