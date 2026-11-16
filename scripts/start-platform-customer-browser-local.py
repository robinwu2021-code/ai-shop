#!/usr/bin/env python3
"""Launch actual customer reads and operator login/RBAC against disposable local MySQL9.

Requires the focused Maven customer tests to have compiled the current source and
produced their classpath report. Credentials are read from an existing private client
file and never printed. Production configuration and databases are not used.
"""
import argparse
import configparser
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-config', type=Path, default=Path.home() / '.config/mysql9/client.cnf')
    parser.add_argument('--java-home', type=Path, default=Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home')))
    args = parser.parse_args()
    config = configparser.ConfigParser(interpolation=None)
    if not config.read(args.mysql_config) or 'client' not in config:
        raise RuntimeError('An existing private MySQL9 client configuration is required')
    report = root / 'backend/shop-app/target/surefire-reports/TEST-ai.neargo.shop.portal.service.CustomerReportingMysqlTest.xml'
    if not report.is_file():
        raise RuntimeError('Compile/run CustomerReportingMysqlTest before starting this test-only server')
    properties = ET.parse(report).getroot().find('properties')
    classpath = next(p.get('value') for p in properties if p.get('name') == 'java.class.path')
    source = root / 'backend/shop-app/src/test/java/ai/neargo/shop/commercebridge/PlatformCustomerBrowserLauncher.java'
    subprocess.run([str(args.java_home / 'bin/javac'), '-proc:none', '-cp', classpath,
                    '-d', str(root / 'backend/shop-app/target/test-classes'), str(source)], cwd=root, check=True)
    with tempfile.TemporaryDirectory(prefix='platform-customer-browser-') as tmp:
        seed = Path(tmp) / 'menu-seed.sql'
        with seed.open('w') as output:
            subprocess.run(['node', 'ops-web/scripts/gen-perm-seed.mjs'], cwd=root, stdout=output, check=True)
        env = os.environ.copy()
        env.update(COMMERCE_MYSQL_URL='jdbc:mysql://127.0.0.1:3308/?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&characterEncoding=UTF-8',
                   COMMERCE_MYSQL_USER=config['client'].get('user', 'root').strip('"\''),
                   COMMERCE_MYSQL_PASSWORD=config['client'].get('password', '').strip('"\''),
                   PLATFORM_MENU_SEED_FILE=str(seed))
        process = subprocess.Popen([str(args.java_home / 'bin/java'), '-Dplatform.customer.browser.local=true',
                                   '-cp', classpath, 'ai.neargo.shop.commercebridge.PlatformCustomerBrowserLauncher'], cwd=root, env=env)
        try:
            raise SystemExit(process.wait())
        except KeyboardInterrupt:
            process.terminate()
            process.wait()

if __name__ == '__main__': main()
