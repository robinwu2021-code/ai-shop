#!/usr/bin/env python3
"""Exercise production bapp login and P1 repository against an isolated loopback MySQL9 JVM."""
import argparse
import configparser
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path('/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app-root', type=Path, default=ROOT.parent / 'ai-shop-bapp')
    parser.add_argument('--mysql-config', type=Path, default=Path.home() / '.config/mysql9/client.cnf')
    args = parser.parse_args()
    report = ROOT / 'backend/shop-app/target/surefire-reports/TEST-ai.neargo.shop.commercebridge.BizLoginStoreNativeMysqlTest.xml'
    properties = ET.parse(report).getroot().find('properties')
    cp = next(p.get('value') for p in properties if p.get('name') == 'java.class.path')
    config = configparser.ConfigParser(interpolation=None)
    config.read(args.mysql_config)
    env = os.environ.copy()
    env.update(COMMERCE_MYSQL_URL='jdbc:mysql://127.0.0.1:3308/?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC',
               COMMERCE_MYSQL_USER=config['client'].get('user', 'root').strip("\"'"),
               COMMERCE_MYSQL_PASSWORD=config['client'].get('password', '').strip("\"'"))
    output = Path(tempfile.mkdtemp(prefix='bapp-chain-native-'))
    output.chmod(0o700)
    ready = output / 'ready.json'
    env.update(COMMERCE_CHAIN_READY_FILE=str(ready), BAPP_CHAIN_READY=str(ready))
    credentials = []
    with (output / 'host.log').open('w') as log:
        host = subprocess.Popen([str(JAVA), '-Xmx900m', '-XX:ActiveProcessorCount=2',
            '-Dcommerce.chain.local.acceptance=true', '-cp', cp,
            'ai.neargo.shop.commercebridge.CommerceChainLocalLauncher'], cwd=ROOT, env=env,
            stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 45
            while not ready.exists():
                if host.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('Local JVM did not become ready; inspect ' + str(output / 'host.log'))
                time.sleep(.2)
            private = json.loads(ready.read_text())
            credentials = [private['ownerPassword'], private['staffOtp']]
            with (output / 'flutter.log').open('w') as flutter_log:
                result = subprocess.run([str(args.app_root / 'tooling/flutterw'), 'test', '--no-pub',
                    'test/integration/chain_aishop_runtime_test.dart', '--reporter=expanded'],
                    cwd=args.app_root / 'apps/merchant_app', env=env,
                    stdout=flutter_log, stderr=subprocess.STDOUT)
            if result.returncode:
                raise RuntimeError('Actual Dart HTTP acceptance failed; inspect ' + str(output / 'flutter.log'))
            (output / 'result.json').write_text(json.dumps({
                'status': 'PASS', 'nativeMysql': '127.0.0.1:3308', 'dartHttpVerified': True,
                'scenarios': ['USR password login and cold restore', 'management without store selection',
                    'real brand creation/read', 'store join/switch/leave with impact checks and approvals',
                    'management space creation/switch',
                    'MCH OTP login and cold restore', 'typed delegated space authorization', 'logout'],
                'remoteAccess': False, 'vendorAccess': False,
            }, indent=2) + '\n')
            print('PASS: actual bapp login/P1 HTTP to isolated MySQL9; evidence:', output)
        finally:
            if host.poll() is None:
                host.terminate()
                host.wait(timeout=20)
            ready.unlink(missing_ok=True)
            for path in (output / 'host.log', output / 'flutter.log'):
                if not path.exists():
                    continue
                content = path.read_text()
                for secret in credentials:
                    content = content.replace(secret, '[redacted]')
                content = re.sub(r'\b(?:btk|ctk|otk)_[A-Za-z0-9_-]+', '[redacted-token]', content)
                path.write_text(content)


if __name__ == '__main__':
    main()
