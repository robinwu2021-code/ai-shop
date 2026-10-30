import {execFileSync, spawnSync} from 'node:child_process';
import {readFileSync, rmSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const root = fileURLToPath(new URL('../', import.meta.url));
const beautyReception = process.argv.includes('--beauty-reception');
const name = 'codex-member-guest-' + randomUUID();
const restartFile = '/tmp/' + name + '.properties';
const docker = (args, input) => execFileSync('docker', args,
  {encoding: 'utf8', input, stdio: ['pipe', 'pipe', 'pipe']}).trim();
try {
  docker(['run', '--rm', '--pull', 'never', '--detach', '--name', name, '--publish', '127.0.0.1::3306',
    ...(process.env.BEAUTY_TEST_DISK === '1' ? [] : ['--tmpfs', '/var/lib/mysql']), '--env', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', '--env', 'MYSQL_ROOT_HOST=%', 'mysql:9.7.2',
    ...(process.env.BEAUTY_TEST_DISK === '1' ? ['--datadir=/var/lib/beauty-test', '--innodb-buffer-pool-size=64M', '--performance-schema=OFF'] : [])]);
  let ready = false;
  for (let i = 0; i < 150; i++) {
    try { docker(['exec', name, 'mysql', '--protocol=TCP', '-h127.0.0.1', '-uroot', '-e', 'SELECT 1']);
      ready = true; break;
    } catch { await new Promise(resolve => setTimeout(resolve, 300)); }
  }
  assert(ready, 'Native MySQL did not become ready');
  const address = docker(['port', name, '3306/tcp']);
  assert.match(address, /^127\.0\.0\.1:[0-9]+$/);
  docker(['exec', name, 'mysql', '-uroot', '-e',
    'CREATE DATABASE guest_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci']);
  // Current platform fixture only, explicitly not historical platform migration replay.
  const fixture = readFileSync(root + 'backend/shop-app/src/test/resources/schema-test.sql', 'utf8')
    .replace(/\b([A-Za-z_]\w*) IS JSON\b/g, 'JSON_VALID($1)').split(/\nINSERT (?:IGNORE )?INTO /)[0];
  docker(['exec', '-i', name, 'mysql', '-uroot', 'guest_test'], fixture);
  if (!process.argv.includes('--red')) {
    docker(['exec', name, 'mysql', '-uroot', 'guest_test', '-e', 'DROP TABLE IF EXISTS mbr_guest_registration']);
    docker(['exec', '-i', name, 'mysql', '-uroot', 'guest_test'],
      readFileSync(root + 'backend/shop-app/src/main/resources/db/migration/V597__shared_guest_registration.sql', 'utf8'));
    docker(['exec', name, 'mysql', '-uroot', 'guest_test', '-e',
      'ALTER TABLE mbr_identity_ticket DROP CHECK ck_mbr_ticket_actor_namespace, DROP COLUMN actor_realm, DROP COLUMN actor_kind']);
    docker(['exec', '-i', name, 'mysql', '-uroot', 'guest_test'],
      readFileSync(root + 'backend/shop-app/src/main/resources/db/migration/V598__member_reference_actor_namespace.sql', 'utf8'));
  }
  const args = ['-o', '-Dmaven.repo.local=/tmp/codex-beauty-bm01-m2', '-f', root + 'backend/pom.xml',
    '-pl', 'shop-app', '-am', 'test', '-Dtest=' + (beautyReception ? process.env.BEAUTY_RECEPTION_TESTS || 'BeautyReceptionIdentityIT' : process.env.MEMBER_GUEST_TESTS || 'MemberGuestRegistrationIT'),
    '-Dsurefire.failIfNoSpecifiedTests=false', '-Dmember-guest.test.native=true',
    '-Dspring.datasource.url=jdbc:mysql://' + address + '/guest_test?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC',
    '-Dspring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver', '-Dspring.datasource.username=root',
    '-Dspring.datasource.password=', '-Dspring.sql.init.mode=never',
    '-Dmember-guest.test.restart-file=' + restartFile];
  if (beautyReception) args.push('-Pbeauty-embedded', '-Dbeauty.reception.test.native=true');
  const run = selected => spawnSync('mvn', selected, {cwd: root, env: process.env, stdio: 'inherit'});
  assert.equal(run(args).status, 0, 'Member guest native tests failed');
  if (!beautyReception && !process.argv.includes('--red') && !process.env.MEMBER_GUEST_TESTS) {
    const second = args.map(arg => arg.startsWith('-Dtest=')
      ? '-Dtest=MemberGuestRegistrationIT#restartJvmReplaysOriginalKeyFromEncryptedStorage' : arg);
    assert.equal(run(second).status, 0, 'Member guest fresh JVM replay failed');
  }
} finally {
  rmSync(restartFile, {force: true});
  try { docker(['rm', '--force', '--volumes', name]); } catch { /* Only this run's container may be removed. */ }
}
