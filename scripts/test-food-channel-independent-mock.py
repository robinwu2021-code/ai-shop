#!/usr/bin/env python3
"""Existing integrated business workflow against a separate Mock JVM on disposable MySQL9 clones."""
import argparse
import configparser
import hashlib
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-defaults', required=True, type=Path)
    parser.add_argument('--template', required=True)
    parser.add_argument('--commerce-jar', required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r'food_host_smoke(?:_[a-z0-9_]{1,32})?', args.template):
        parser.error('Only a local food_host_smoke test template is allowed')
    config = configparser.ConfigParser(interpolation=None)
    config.read(args.mysql_defaults)
    db = config['client']
    if db.get('host') != '127.0.0.1' or db.get('port') != '3308':
        parser.error('Local native MySQL9 on 127.0.0.1:3308 required')
    root = Path(__file__).resolve().parent.parent
    bins = Path('/opt/homebrew/opt/mysql@9.7/bin')
    mysql = [str(bins / 'mysql'), '--defaults-file=' + str(args.mysql_defaults.resolve()), '-N']
    def sql(statement):
        return subprocess.check_output(mysql + ['-e', statement], text=True).strip()
    assert sql('SELECT VERSION()').startswith('9.')
    tag = 'mock_' + uuid.uuid4().hex[:20]
    schema = 'food_host_smoke_' + tag
    logs = Path(tempfile.mkdtemp(prefix='food-channel-mock-'))
    jar = logs / 'commerce.jar'
    shutil.copy2(args.commerce_jar, jar)
    print('Immutable Mock JAR SHA256 ' + hashlib.sha256(jar.read_bytes()).hexdigest(), flush=True)
    print('Logs: ' + str(logs), flush=True)
    created, process = [], None
    env = os.environ.copy()
    env.update(COMMERCE_MOCK_DIRECTORY=str(logs / 'mock-state'), COMMERCE_MOCK_TOKEN=secrets.token_hex(24),
               COMMERCE_MOCK_PORT='0', FOOD_HOST_SMOKE_DISPOSABLE='true',
               FOOD_HOST_SMOKE_JDBC_URL='jdbc:mysql://127.0.0.1:3308/' + schema,
               FOOD_HOST_SMOKE_DB_USER=db.get('user', 'root'), FOOD_HOST_SMOKE_DB_PASSWORD=db.get('password', ''))
    java = str(Path(env['JAVA_HOME']) / 'bin/java') if env.get('JAVA_HOME') else shutil.which('java')
    try:
        for suffix in ('', '_inv'):
            target = schema + suffix
            dump = logs / ('restore' + suffix + '.sql')
            with dump.open('wb') as output:
                subprocess.run([str(bins / 'mysqldump'), '--defaults-file=' + str(args.mysql_defaults.resolve()),
                                '--single-transaction', '--no-tablespaces', '--set-gtid-purged=OFF',
                                args.template + suffix], stdout=output, check=True)
            sql('CREATE DATABASE `' + target + '` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_520_ci')
            created.append(target)
            with dump.open('rb') as source:
                subprocess.run(mysql + [target], stdin=source, check=True)
            dump.unlink()
        with (logs / 'mock-stderr.log').open('w') as errors:
            process = subprocess.Popen([java, '-Dloader.main=ai.neargo.shop.commerce.channel.mock.CommerceMockApplication',
                                        '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher'],
                                       env=env, stdout=subprocess.PIPE, stderr=errors, text=True)
        # Startup deadline belongs to the subprocess command, not an unbounded readline.
        import select
        assert select.select([process.stdout], [], [], 30)[0], 'Mock startup timed out'
        line = process.stdout.readline().strip()
        assert line.startswith('Mock commerce listening on '), 'Mock failed; inspect stderr log'
        port = int(line.rsplit(' ', 1)[1])
        env.update(FOOD_CHANNEL_VERTICAL_MOCK_BASE_URL='http://127.0.0.1:' + str(port),
                   FOOD_CHANNEL_VERTICAL_MOCK_TOKEN=env['COMMERCE_MOCK_TOKEN'])
        tests = 'FoodChannelVerticalNativeIT#originalOwnersConserveTheInvoiceAndQueryAnUncertainAcceptanceWithoutSendingAgain'
        command = ['mvn', '-o', '-f', 'backend/pom.xml',
                   '-Pfood-embedded,food-verify,commerce-embedded,food-commerce-embedded,retail-commerce-embedded',
                   '-pl', 'shop-app', '-am', 'test', '-Dtest=' + tests,
                   '-Dsurefire.failIfNoSpecifiedTests=false']
        with (logs / 'vertical.log').open('w') as output:
            result = subprocess.run(command, cwd=root, env=env, stdout=output, stderr=subprocess.STDOUT)
        print('Existing business workflow exit=' + str(result.returncode), flush=True)
        if result.returncode:
            raise SystemExit(result.returncode)
    finally:
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            process.stdout.close()
        for target in reversed(created):
            sql('DROP DATABASE `' + target + '`')
        print('Owned MySQL clones removed; template unchanged', flush=True)


if __name__ == '__main__':
    main()
