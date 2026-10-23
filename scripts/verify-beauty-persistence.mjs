import {execFileSync, spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const name = 'codex-beauty-jdbc-' + randomUUID();
const root = fileURLToPath(new URL('../', import.meta.url));
const lowMemory = process.env.BEAUTY_TEST_LOW_MEMORY === '1';
const docker = args => execFileSync('docker', args, {encoding: 'utf8',
  timeout: lowMemory ? 10000 : undefined, stdio: ['pipe', 'pipe', 'pipe']}).trim();
if (lowMemory) {
  assert.equal(process.env.BEAUTY_TEST_DISK, '1', 'Low-memory calibration requires the disk datadir');
  assert(process.env.LIMA_HOME, 'Explicit test VM LIMA_HOME is required for the low-memory preflight');
  const memory = execFileSync('limactl', ['shell', '--instance',
    process.env.BEAUTY_TEST_LIMA_INSTANCE || 'colima-hxkey-tests', 'cat', '/proc/meminfo'],
    {encoding: 'utf8', timeout: 10000});
  const availableKiB = Number(memory.match(/^MemAvailable:\s+(\d+) kB$/m)?.[1]);
  assert(availableKiB >= 160 * 1024, `Test VM needs 160MiB available; observed ${availableKiB}KiB`);
  console.log('Beauty low-memory preflight KiB:', availableKiB, 'own UUID:', name, new Date().toISOString());
}
let operationFailed = false;
try {
  docker(['run', '--rm', '--pull', 'never', '--detach', '--name', name, ...(lowMemory ? ['--memory', '128m', '--memory-swap', '128m'] : []), '--publish', '127.0.0.1::3306', ...(process.env.BEAUTY_TEST_DISK === '1' ? [] : ['--tmpfs', '/var/lib/mysql']), '--env', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', '--env', 'MYSQL_ROOT_HOST=%', 'mysql:9.7.2',
    ...(lowMemory ? ['--datadir=/var/lib/beauty-test', '--innodb-buffer-pool-size=16M',
      '--innodb-log-buffer-size=4M', '--innodb-redo-log-capacity=16M', '--key-buffer-size=1M',
      '--max-connections=16', '--thread-cache-size=0', '--table-open-cache=64',
      '--table-definition-cache=400', '--mysqlx=OFF', '--performance-schema=OFF']
      : process.env.BEAUTY_TEST_DISK === '1' ? ['--datadir=/var/lib/beauty-test', '--innodb-buffer-pool-size=64M', '--performance-schema=OFF'] : [])]);
  let ready = false;
  for (let i = 0; i < 100; i++) {
    try {docker(['exec', name, 'mysql', '--protocol=TCP', '-h127.0.0.1', '-uroot', '-e', 'SELECT 1']); ready = true; break;}
    catch {await new Promise(resolve => setTimeout(resolve, 400));}
  }
  assert(ready, 'Disposable MySQL did not become ready');
  if (lowMemory) {
    console.log('Beauty low-memory ready:', name, new Date().toISOString());
    const host = JSON.parse(docker(['inspect', '--format', '{{json .HostConfig}}', name]));
    assert.equal(host.Memory, 128 * 1024 * 1024); assert.equal(host.MemorySwap, host.Memory);
    const expected = {innodb_buffer_pool_size: '16777216', innodb_log_buffer_size: '4194304',
      innodb_redo_log_capacity: '16777216', key_buffer_size: '1048576', max_connections: '16',
      thread_cache_size: '0', table_open_cache: '64', table_definition_cache: '400', performance_schema: 'OFF'};
    const variables = docker(['exec', name, 'mysql', '--protocol=TCP', '-h127.0.0.1', '-uroot',
      '--batch', '--raw', '--skip-column-names', '-e',
      `SHOW VARIABLES WHERE Variable_name IN (${Object.keys(expected).map(k => "'" + k + "'").join(',')})`]);
    const actual = Object.fromEntries(variables.split('\n').map(line => line.split('\t')));
    for (const [key, value] of Object.entries(expected)) assert.equal(actual[key], value, key);
    const mysqlx = docker(['exec', name, 'mysql', '-uroot', '--batch', '--skip-column-names', '-e',
      "SELECT COUNT(*) FROM INFORMATION_SCHEMA.PLUGINS WHERE PLUGIN_NAME='mysqlx' AND PLUGIN_STATUS='ACTIVE'"]);
    assert.equal(mysqlx, '0'); console.log('Beauty low-memory SHOW VARIABLES:\n' + variables);
    console.log('Beauty low-memory cap:', host.Memory, 'swap total:', host.MemorySwap, 'mysqlx active:', mysqlx);
  }
  const address = docker(['port', name, '3306/tcp']);
  assert.match(address, /^127\.0\.0\.1:[0-9]+$/);
  const jdbc = `jdbc:mysql://${address}/mysql?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC`;
  const selection = process.env.BEAUTY_TESTS || (process.argv.includes('--reception-fix-red')
    ? 'AppointmentBusinessIT#guestCreationReplaysOriginalOperationAfterClaim,BeautySchemaMigrationIT#identityPairRejectsHalfNullAndInvalidReferences+rejectsBacktickInsideCheckLiteral,BeautyCheckExpressionTest#quotedIdentifierNeverErasesLiteralContent'
    : process.argv.includes('--receipt-ownership-only')
    ? 'GuestClaimReceiptIT#receiptOwnershipIsAppliedBeforePagingAndRequiresCompleteScope'
    : process.argv.includes('--reception-with-appointments')
    ? 'GuestClaimReceiptIT,BeautySchemaMigrationIT,ResourceIntentMigrationIT,AppointmentBusinessIT'
    : process.argv.includes('--reception-only') ? 'GuestClaimReceiptIT,BeautySchemaMigrationIT,ResourceIntentMigrationIT'
    : process.argv.includes('--appointment-business-only') ? 'AppointmentBusinessIT'
    : process.argv.includes('--appointment-migrations-only') ? 'BeautySchemaMigrationIT,ResourceIntentMigrationIT'
    : process.argv.includes('--resource-intent-only') ? 'ResourceIntentPersistenceIT,ResourceIntentMigrationIT'
    : process.argv.includes('--publication-only') ? 'BeautyPolicyPublicationIT'
    : process.argv.includes('--public-catalog-only') ? 'PublicCatalogIT'
    : process.argv.includes('--source-only') ? 'SourceAllocationConcurrencyIT,ServiceSubmissionPersistenceIT'
    : process.argv.includes('--execution-only') ? 'AppointmentPersistenceIT,ServiceTaskPersistenceIT'
    : process.argv.includes('--catalog-only') ? 'BeautyCatalogIT'
    : 'AppointmentBusinessIT,ResourceIntentPersistenceIT,ResourceIntentMigrationIT,BeautySchemaMigrationIT,PolicyDefinitionPersistenceIT,BeautyPlatformFlywayIT,BeautyPolicyPublicationIT,BeautyCatalogIT,PublicCatalogIT,AppointmentPersistenceIT,ServiceTaskPersistenceIT,SourceAllocationConcurrencyIT,ServiceSubmissionPersistenceIT');
  const result = spawnSync('mvn', ['-o', '-Dmaven.repo.local=/tmp/codex-beauty-bm01-m2', '-f', root + 'backend-plugins/shop-industry-beauty/pom.xml', '-pl', 'beauty-core', '-am', 'test', `-Dtest=${selection}`, '-Dsurefire.failIfNoSpecifiedTests=false', `-Dbeauty.test.admin-url=${jdbc}`], {cwd: root, env: process.env, stdio: 'inherit'});
  assert.equal(result.status, 0, 'Beauty disposable JDBC integration tests failed');
  if (process.argv.includes('--include-server')) {
    const server = spawnSync('mvn', ['-o', '-Dmaven.repo.local=/tmp/codex-beauty-bm01-m2', '-f', root + 'backend-plugins/shop-industry-beauty/pom.xml',
      '-pl', 'beauty-server', '-am', 'test', '-Dtest=BeautyStandaloneEndToEndIT',
      '-Dsurefire.failIfNoSpecifiedTests=false', `-Dbeauty.test.admin-url=${jdbc}`],
      {cwd: root, env: process.env, stdio: 'inherit'});
    assert.equal(server.status, 0, 'Beauty standalone real TCP-to-database integration failed');
  }
  if (process.argv.includes('--include-host')) {
    const host = spawnSync('mvn', ['-o', '-Dmaven.repo.local=/tmp/codex-beauty-bm01-m2', '-f', root + 'backend/pom.xml', '-pl', 'shop-app', '-am',
      '-Pbeauty-embedded', 'test', '-Dtest=BeautyPolicyEndToEndIT', '-Dsurefire.failIfNoSpecifiedTests=false',
      `-Dbeauty.test.admin-url=${jdbc}`], {cwd: root, env: process.env, stdio: 'inherit'});
    assert.equal(host.status, 0, 'Beauty real HTTP-to-database integration failed');
  }
} catch (failure) {
  operationFailed = true;
  throw failure;
} finally {
  let cleanupFailure;
  if (lowMemory) {
    for (const args of [['exec', name, 'cat', '/sys/fs/cgroup/memory.peak', '/sys/fs/cgroup/memory.events'],
      ['inspect', '--format', '{{.State.Status}} {{.State.OOMKilled}}', name], ['logs', '--tail', '12', name]]) {
      try {console.log('Beauty own diagnostic', args[0], docker(args));}
      catch (failure) {console.log('Beauty own diagnostic unavailable', args[0], String(failure));}
    }
    console.log('Beauty own cleanup begin:', name, new Date().toISOString());
  }
  try {docker(['rm', '--force', '--volumes', name]);}
  catch (failure) {cleanupFailure = failure; console.log('Beauty own cleanup:', String(failure));}
  if (lowMemory) {
    console.log('Beauty own cleanup end:', name, new Date().toISOString());
    try {console.log('Beauty own terminal events:', docker(['events',
      '--since', new Date(Date.now() - 3600000).toISOString(), '--until', new Date().toISOString(),
      '--filter', 'container=' + name, '--filter', 'event=die', '--filter', 'event=kill',
      '--filter', 'event=oom', '--filter', 'event=destroy', '--format', '{{json .}}']));}
    catch (failure) {console.log('Beauty own terminal events unavailable:', String(failure));}
  }
  if (cleanupFailure && !operationFailed) throw cleanupFailure;
}
