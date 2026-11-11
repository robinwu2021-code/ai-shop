"""Run as root on 166. Publish only V657 and the two static OPS sites.
Payload: manifest.json, V657.sql, ai-shop-web.tar.gz, pay-web.tar.gz.
Database migration is performed separately and verified before switching.
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile
from pathlib import Path


def sha(path):
    with open(path, 'rb') as stream:
        digest = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
        return digest.hexdigest()


def inspect(name):
    return json.loads(subprocess.check_output(['docker', 'inspect', name]))[0]


def docker(action, name):
    subprocess.run(['docker', action, name], check=True, stdout=subprocess.DEVNULL)


def healthy(port):
    try:
        with urllib.request.urlopen('http://127.0.0.1:%s/actuator/health' % port, timeout=3) as response:
            return json.load(response).get('status') == 'UP'
    except (OSError, ValueError):
        return False


def wait(port):
    for _ in range(100):
        if healthy(port):
            return
        time.sleep(2)
    raise RuntimeError('Health check failed on port ' + str(port))


def restore_backend(jar, backup, content_changed):
    names = ['ai-shop-ops', 'ai-shop-api']
    if content_changed:
        for name in names:
            try:
                docker('stop', name)
            except subprocess.CalledProcessError:
                pass
        assert all(not inspect(name)['State']['Running'] for name in names), 'Cannot restore jar while a reader is running'
        shutil.copyfile(backup, jar)
    errors = []
    for name, port in [('ai-shop-api', 18082), ('ai-shop-ops', 18081)]:
        try:
            if not inspect(name)['State']['Running']:
                docker('start', name)
            wait(port)
        except Exception as error:
            errors.append(type(error).__name__ + ':' + name)
    if errors:
        raise RuntimeError('Rollback needs attention: ' + ','.join(errors))


def link(path, target):
    temporary = path.with_name(path.name + '.v657-next')
    if temporary.is_symlink():
        temporary.unlink()
    temporary.symlink_to(target)
    os.replace(temporary, path)


def preserve_assets(old, new):
    if not old.exists():
        return
    for source in old.rglob('*'):
        if not source.is_file():
            continue
        destination = new / source.relative_to(old)
        if destination.exists():
            assert sha(source) == sha(destination), 'Conflicting immutable asset'
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)


def main():
    mode, payload_dir = sys.argv[1:]
    payload = Path(payload_dir)
    manifest = json.loads((payload / 'manifest.json').read_text())
    rid = manifest['releaseId']
    assert rid.startswith('ops-nav-v657-') and '/' not in rid
    base = Path('/data0/app/ai-shop')
    release = base / 'releases' / rid
    lock = base / '.deploy.lock'
    host_link = Path('/data/www/ai-shop-ops/current')
    pay_link = Path('/data/www/oms-root/oms')
    jar = base / 'shop-app.jar'
    names = ['ai-shop-api', 'ai-shop-ops', 'nearpay-prod', 'inventory-test']
    if mode == 'prepare':
        assert not release.exists()
        lock.mkdir(mode=0o700)
        try:
            (lock / 'owner').write_text(rid)
            assert host_link.is_symlink() and pay_link.is_symlink()
            assert sha(jar) == manifest['baseJarSha256']
            assert healthy(18082) and healthy(18081)
            release.mkdir(mode=0o700)
            state = {'containers': {}, 'links': {str(p): os.readlink(p) for p in [host_link, pay_link]}}
            for name in names:
                current = inspect(name)
                assert current['Id'] == manifest['containerIds'][name], 'Container drift: ' + name
                state['containers'][name] = current
            for name in ['ai-shop-api', 'ai-shop-ops']:
                running = Path('/proc') / str(state['containers'][name]['State']['Pid']) / 'root/app/shop-app.jar'
                assert sha(running) == manifest['baseJarSha256']
            state_file = release / 'private-state.json'
            state_file.write_text(json.dumps(state))
            state_file.chmod(0o600)
            shutil.copyfile(jar, release / 'before.jar')
            for file_name, expected in manifest['payloadSha256'].items():
                assert sha(payload / file_name) == expected, file_name
            entry = 'BOOT-INF/classes/db/migration/V657__ops_compact_navigation.sql'
            with zipfile.ZipFile(release / 'before.jar') as source, zipfile.ZipFile(release / 'next.jar', 'w') as target:
                assert entry not in source.namelist()
                for info in source.infolist():
                    target.writestr(info, source.read(info.filename))
                target.writestr(entry, (payload / 'V657.sql').read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
            with zipfile.ZipFile(release / 'before.jar') as old, zipfile.ZipFile(release / 'next.jar') as new:
                assert set(new.namelist()) - set(old.namelist()) == {entry}
                for name in old.namelist():
                    assert old.read(name) == new.read(name), 'Existing jar entry changed: ' + name
            for archive, directory in [('ai-shop-web.tar.gz', 'ai-shop-web'), ('pay-web.tar.gz', 'pay-web')]:
                target = release / directory
                target.mkdir()
                subprocess.run(['tar', '-xzf', str(payload / archive), '-C', str(target)], check=True)
            preserve_assets(host_link / 'ops-shop/_next/static', release / 'ai-shop-web/ops-shop/_next/static')
            preserve_assets(pay_link / '_next/static', release / 'pay-web/oms/_next/static')
            # Nginx must be able to traverse public sites; private backups stay under a 0700 directory.
            public = Path('/data/www/ops-navigation-releases') / rid
            public.parent.mkdir(parents=True, exist_ok=True)
            public.parent.chmod(0o755)
            public.mkdir(mode=0o755)
            public.chmod(0o755)
            for directory in ['ai-shop-web', 'pay-web']:
                shutil.move(str(release / directory), str(public / directory))
            for item in public.rglob('*'):
                if not item.is_symlink():
                    item.chmod(0o755 if item.is_dir() else 0o644)
            state['publicRoot'] = str(public)
            state['nextJarSha256'] = sha(release / 'next.jar')
            state_file.write_text(json.dumps(state))
            shutil.copyfile(payload / 'manifest.json', release / 'manifest.json')
            print(json.dumps({'prepared': True, 'nextJarSha256': state['nextJarSha256'], 'oldEntriesUnchanged': True}))
        except Exception:
            if (lock / 'owner').exists() and (lock / 'owner').read_text() == rid:
                (lock / 'owner').unlink()
                lock.rmdir()
            raise
        return
    assert (lock / 'owner').read_text() == rid, 'Deployment lock ownership changed'
    state = json.loads((release / 'private-state.json').read_text())
    if mode == 'backend':
        proof = json.loads((payload / 'production-result.json').read_text())
        assert proof['flyway']['version'] == '657' and proof['flyway']['success'] == 1
        assert proof['checks']['menuMappings']['matched'] == 152
        assert proof['flyway']['checksum'] == manifest['flywayChecksum']
        for name in names:
            assert inspect(name)['Id'] == state['containers'][name]['Id']
        assert sha(jar) == manifest['baseJarSha256']
        content_changed = False
        try:
            docker('stop', 'ai-shop-ops')
            docker('stop', 'ai-shop-api')
            # Copy into the existing inode: both Docker bind mounts keep this inode.
            content_changed = True
            shutil.copyfile(release / 'next.jar', jar)
            assert sha(jar) == state['nextJarSha256']
            docker('start', 'ai-shop-api')
            wait(18082)
            docker('start', 'ai-shop-ops')
            wait(18081)
        except Exception:
            restore_backend(jar, release / 'before.jar', content_changed)
            raise
        print(json.dumps({'backend': 'UP', 'jarSha256': sha(jar)}))
    elif mode == 'frontend':
        for path in [host_link, pay_link]:
            assert os.readlink(path) == state['links'][str(path)], 'Static release drift'
        public = Path(state['publicRoot'])
        try:
            link(host_link, public / 'ai-shop-web')
            link(pay_link, public / 'pay-web/oms')
        except Exception:
            for path in [host_link, pay_link]:
                link(path, state['links'][str(path)])
            raise
        print(json.dumps({'staticSitesSwitched': True}))
    elif mode == 'rollback':
        for path in [host_link, pay_link]:
            current = path.resolve()
            previous = Path(state['links'][str(path)])
            previous = previous if previous.is_absolute() else path.parent / previous
            assert current == previous.resolve() or str(current).startswith(state['publicRoot'] + '/')
            link(path, state['links'][str(path)])
        restore_backend(jar, release / 'before.jar', sha(jar) != manifest['baseJarSha256'])
        assert sha(jar) == manifest['baseJarSha256']
        (lock / 'owner').unlink()
        lock.rmdir()
        print(json.dumps({'rolledBack': True, 'migrationRetained': True, 'lockReleased': True}))
    elif mode == 'finish':
        assert sha(jar) == state['nextJarSha256']
        assert healthy(18082) and healthy(18081)
        for name in names:
            current, old = inspect(name), state['containers'][name]
            assert current['Id'] == old['Id'] and current['State']['Running']
            assert current['Config'] == old['Config'] and current['HostConfig'] == old['HostConfig']
            assert current['Mounts'] == old['Mounts']
        assert str(host_link.resolve()) == state['publicRoot'] + '/ai-shop-web'
        assert str(pay_link.resolve()) == state['publicRoot'] + '/pay-web/oms'
        (lock / 'owner').unlink()
        lock.rmdir()
        print(json.dumps({'healthy': True, 'containerConfigurationPreserved': True, 'paymentInventoryUnchanged': True, 'lockReleased': True}))
    else:
        raise ValueError('Unknown phase')


if __name__ == '__main__':
    main()
