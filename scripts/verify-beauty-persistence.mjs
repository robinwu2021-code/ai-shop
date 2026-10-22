import {execFileSync, spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const name = 'codex-beauty-jdbc-' + randomUUID();
const root = fileURLToPath(new URL('../', import.meta.url));
const docker = args => execFileSync('docker', args, {encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe']}).trim();
docker(['run', '--pull', 'never', '--detach', '--name', name, '--publish', '127.0.0.1::3306', ...(process.env.BEAUTY_TEST_DISK === '1' ? [] : ['--tmpfs', '/var/lib/mysql']), '--env', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', '--env', 'MYSQL_ROOT_HOST=%', 'mysql:8.4',
    ...(process.env.BEAUTY_TEST_DISK === '1' ? ['--datadir=/var/lib/beauty-test', '--innodb-buffer-pool-size=64M', '--performance-schema=OFF'] : [])]);
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
} finally {
  docker(['rm', '--force', '--volumes', name]);
}
