"""Root-only 166 release driver. Payload and state contain secrets: mode 700/600."""
import copy
import hashlib
import http.client
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import urllib.parse
import urllib.request


BASE = Path('/data0/app/ai-shop')
RID = 'mitake-sms-20261116'
RELEASE = BASE / 'releases' / RID
LOCK = BASE / '.deploy.lock'
JAR = BASE / 'shop-app.jar'
NAMES = [('ai-shop-api', 18082, 'ai-shop-api.env'), ('ai-shop-ops', 18081, 'ai-shop.env')]
ENV = {
    'SHOP_SMS_STUB': 'false', 'SHOP_SMS_PROVIDER': 'mitake',
    'SHOP_SMS_MITAKE_CONFIG_FILE': '/run/secrets/mitake.json',
    'SHOP_SMS_MITAKE_ENDPOINT': 'https://smsapi.mitake.com.tw/api/mtk/SmSend?CharsetURL=UTF-8',
    'SHOP_SMS_CHANNEL_READY_MARKETS': 'TW', 'SHOP_SMS_MOCK_PREFIXES': '',
    'SHOP_OTP_FIXED': '', 'SHOP_OTP_RATE_LIMIT': 'true',
}


def digest(path):
    h = hashlib.sha256()
    with open(str(path), 'rb') as f:
        for block in iter(lambda: f.read(1048576), b''):
            h.update(block)
    return h.hexdigest()


def docker(*args):
    return subprocess.check_output(['docker'] + list(args), stderr=subprocess.PIPE).decode()


def inspect(name):
    try:
        return json.loads(docker('inspect', name))[0]
    except subprocess.CalledProcessError:
        return None


def health(port):
    try:
        with urllib.request.urlopen('http://127.0.0.1:%s/actuator/health' % port, timeout=3) as r:
            return json.load(r).get('status') == 'UP'
    except Exception:
        return False


def wait(port):
    for _ in range(90):
        if health(port):
            return
        time.sleep(2)
    raise RuntimeError('Health check failed: ' + str(port))


def save(state):
    (RELEASE / 'state.private.json').write_text(json.dumps(state, indent=2))


def install_jar(source, expected_sha):
    temporary = BASE / ('.' + RID + '-jar.next')
    assert not temporary.exists(), 'Jar staging file already exists'
    metadata = JAR.stat()
    try:
        # Preserve existing mode and ACL/xattrs, then replace content before the atomic switch.
        shutil.copy2(str(JAR), str(temporary))
        os.chown(str(temporary), metadata.st_uid, metadata.st_gid)
        shutil.copyfile(str(source), str(temporary))
        assert digest(temporary) == expected_sha, 'Staged jar digest mismatch'
        os.replace(str(temporary), str(JAR))
    finally:
        if temporary.exists():
            temporary.unlink()


def unlock():
    if (LOCK / 'owner').exists() and (LOCK / 'owner').read_text() == RID:
        (LOCK / 'owner').unlink()
        LOCK.rmdir()


class DockerSocket(http.client.HTTPConnection):
    def __init__(self):
        super().__init__('localhost', timeout=30)

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect('/var/run/docker.sock')


def create(name, old):
    config = copy.deepcopy(old['Config'])
    config['Image'] = old['Image']
    env = dict(v.split('=', 1) for v in config['Env'] if '=' in v)
    env.update(ENV)
    config['Env'] = [k + '=' + v for k, v in env.items()]
    host = copy.deepcopy(old['HostConfig'])
    binds = host.get('Binds') or []
    assert all('/run/secrets/mitake.json' not in v for v in binds)
    binds.append(str(BASE / 'secrets' / (name + '-mitake.json')) + ':/run/secrets/mitake.json:ro')
    host['Binds'] = binds
    config['HostConfig'] = host
    # Docker allocates fresh dynamic addresses. Preserve user-defined network aliases/options.
    networks = old.get('NetworkSettings', {}).get('Networks', {})
    if host.get('NetworkMode') not in ('default', 'bridge', 'host', 'none'):
        config['NetworkingConfig'] = {'EndpointsConfig': {
            n: {k: v[k] for k in ('IPAMConfig', 'Links', 'Aliases', 'DriverOpts') if v.get(k) is not None}
            for n, v in networks.items()}}
    conn = DockerSocket()
    conn.request('POST', '/containers/create?name=' + urllib.parse.quote(name),
                 body=json.dumps(config), headers={'Content-Type': 'application/json'})
    response = conn.getresponse()
    response.read()
    status = response.status
    conn.close()
    if status != 201:
        raise RuntimeError('Container recreation failed: HTTP ' + str(status))


def prepare(payload):
    LOCK.mkdir(mode=0o700)
    (LOCK / 'owner').write_text(RID)
    try:
        assert not RELEASE.exists(), 'Release directory already exists'
        RELEASE.mkdir(mode=0o700)
        proof = json.loads((payload / 'build-proof.json').read_text())
        assert digest(JAR) == proof['baselineSha256'], 'Live jar changed since capture'
        assert digest(payload / 'candidate.jar') == proof['candidateSha256'], 'Candidate digest mismatch'
        state = {'releaseId': RID, 'baselineSha256': digest(JAR), 'candidateSha256': proof['candidateSha256'],
                 'containers': {}, 'envFiles': [], 'newSecrets': [], 'phase': 'prepared'}
        for name, port, envfile in NAMES:
            assert health(port), 'Pre-release health failed'
            assert inspect(name + '-mitake-before-20261116') is None, 'Backup container already exists'
            old = inspect(name)
            assert old and old['State']['Running']
            assert any(m['Source'] == str(JAR) and m['Destination'] == '/app/shop-app.jar' for m in old['Mounts'])
            state['containers'][name] = old
            if (BASE / envfile).exists():
                shutil.copy2(str(BASE / envfile), str(RELEASE / (envfile + '.before')))
                os.chmod(str(RELEASE / (envfile + '.before')), 0o600)
                state['envFiles'].append(envfile)
        shutil.copy2(str(JAR), str(RELEASE / 'before.jar'))
        os.chmod(str(RELEASE / 'before.jar'), 0o600)
        shutil.copyfile(str(payload / 'candidate.jar'), str(RELEASE / 'candidate.jar'))
        shutil.copyfile(str(payload / 'build-proof.json'), str(RELEASE / 'build-proof.json'))
        save(state)
        credentials = json.loads((payload / 'credentials.private.json').read_text())
        secret_dir = BASE / 'secrets'
        secret_dir.mkdir(mode=0o700, exist_ok=True)
        for name, _, _ in NAMES:
            secret = secret_dir / (name + '-mitake.json')
            assert not secret.exists(), 'Secret path already exists'
            uid = int(docker('exec', name, 'id', '-u').strip())
            secret.write_text(json.dumps({k: credentials[k] for k in ('username', 'password')}))
            os.chmod(str(secret), 0o600)
            os.chown(str(secret), uid, uid)
            state['newSecrets'].append(str(secret))
            save(state)
        (payload / 'credentials.private.json').unlink()
        print(json.dumps({'phase': 'prepared', 'backupReady': True, 'baselineSha256': state['baselineSha256']}))
    except Exception:
        if (RELEASE / 'state.private.json').exists():
            state = json.loads((RELEASE / 'state.private.json').read_text())
            for path in state.get('newSecrets', []):
                Path(path).unlink()
        unlock()
        raise


def rollback():
    acquired = False
    if not LOCK.exists():
        LOCK.mkdir(mode=0o700)
        (LOCK / 'owner').write_text(RID)
        acquired = True
    assert (LOCK / 'owner').exists() and (LOCK / 'owner').read_text() == RID, 'Another deployment owns the lock'
    state = json.loads((RELEASE / 'state.private.json').read_text())
    try:
        assert digest(JAR) in (state['baselineSha256'], state['candidateSha256']), 'Live jar belongs to a different release'
        for name, _, _ in NAMES:
            backup = inspect(name + '-mitake-before-20261116')
            current = inspect(name)
            expected = state.get('deployedContainers', {}).get(name)
            if current and expected:
                assert current['Id'] == expected, 'Current container belongs to a different release'
            if backup:
                assert backup['Id'] == state['containers'][name]['Id'], 'Backup container identity changed'
            else:
                assert current and current['Id'] == state['containers'][name]['Id'], 'Original container is unavailable'
    except Exception:
        if acquired:
            unlock()
        raise
    for name, _, _ in NAMES:
        if inspect(name + '-mitake-before-20261116'):
            current = inspect(name)
            if current:
                if current['State']['Running']:
                    docker('stop', '-t', '30', name)
                docker('rm', name)
        elif inspect(name) and inspect(name)['State']['Running']:
            docker('stop', '-t', '30', name)
    install_jar(RELEASE / 'before.jar', state['baselineSha256'])
    for filename in state['envFiles']:
        shutil.copyfile(str(RELEASE / (filename + '.before')), str(BASE / filename))
    for name, port, _ in NAMES:
        backup = name + '-mitake-before-20261116'
        if inspect(backup):
            docker('rename', backup, name)
        docker('start', name)
        wait(port)
    for path in state.get('newSecrets', []):
        Path(path).unlink()
    state['phase'] = 'rolled-back'
    save(state)
    unlock()
    print(json.dumps({'phase': 'rolled-back', 'bothServicesHealthy': True}))


def activate():
    assert (LOCK / 'owner').read_text() == RID
    state = json.loads((RELEASE / 'state.private.json').read_text())
    assert state['phase'] == 'prepared'
    assert digest(JAR) == state['baselineSha256'], 'Live jar changed before activation'
    try:
        for name, _, _ in NAMES:
            docker('stop', '-t', '30', name)
            docker('rename', name, name + '-mitake-before-20261116')
        install_jar(RELEASE / 'candidate.jar', state['candidateSha256'])
        assert digest(JAR) == state['candidateSha256']
        for filename in state['envFiles']:
            path = BASE / filename
            lines = [line for line in path.read_text().splitlines() if line.split('=', 1)[0].strip() not in ENV]
            path.write_text('\n'.join(lines + [k + '=' + v for k, v in ENV.items()]) + '\n')
        for name, port, _ in NAMES:
            create(name, state['containers'][name])
            state.setdefault('deployedContainers', {})[name] = inspect(name)['Id']
            save(state)
            docker('start', name)
            wait(port)
        state['phase'] = 'activated'
        save(state)
        print(json.dumps({'phase': 'activated', 'bothServicesHealthy': True, 'candidateSha256': state['candidateSha256']}))
    except Exception:
        rollback()
        raise


def finish():
    state = json.loads((RELEASE / 'state.private.json').read_text())
    assert state['phase'] == 'activated'
    assert digest(JAR) == state['candidateSha256']
    assert all(health(port) for _, port, _ in NAMES)
    state['phase'] = 'complete'
    save(state)
    unlock()
    print(json.dumps({'phase': 'complete', 'bothServicesHealthy': True, 'rollbackContainersRetained': True}))


if __name__ == '__main__':
    os.umask(0o077)
    assert os.geteuid() == 0, 'Run as root'
    action = sys.argv[1]
    if action == 'prepare':
        prepare(Path(sys.argv[2]))
    elif action == 'activate':
        activate()
    elif action == 'rollback':
        rollback()
    elif action == 'finish':
        finish()
    else:
        raise ValueError('Unknown action')
