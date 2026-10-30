import {execFileSync, spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const name = 'codex-schedule-jdbc-' + randomUUID();
const root = fileURLToPath(new URL('../', import.meta.url));
const docker = args => execFileSync('docker', args, {encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe']}).trim();
docker(['run', '--pull', 'never', '--detach', '--name', name, '--publish', '127.0.0.1::3306', '--tmpfs', '/var/lib/mysql', '--env', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', '--env', 'MYSQL_ROOT_HOST=%', 'mysql:8.4']);
try {
  let ready = false;
  for (let i = 0; i < 100; i++) {
    try {docker(['exec', name, 'mysql', '--protocol=TCP', '-h127.0.0.1', '-uroot', '-e', 'SELECT 1']); ready = true; break;}
    catch {await new Promise(resolve => setTimeout(resolve, 400));}
  }
  assert(ready, 'Disposable MySQL did not become ready');
  const address = docker(['port', name, '3306/tcp']);
  assert.match(address, /^127\.0\.0\.1:[0-9]+$/);
  const jdbc = `jdbc:mysql://${address}/mysql?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC`;
  const result = spawnSync('mvn', ['-o', '-f', root + 'backend/pom.xml', '-pl', 'shop-app', '-am', 'test',
    '-Dtest=ScheduleWindowsPersistenceIT', '-Dsurefire.failIfNoSpecifiedTests=false',
    `-Dschedule.test.admin-url=${jdbc}`], {cwd: root, env: process.env, stdio: 'inherit'});
  assert.equal(result.status, 0, 'Shared schedule actual migration and JDBC facts tests failed');
} finally {
  docker(['rm', '--force', name]);
}
