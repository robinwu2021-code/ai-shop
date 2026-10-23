import {execFileSync, spawnSync} from 'node:child_process';
import {readFileSync, writeFileSync, readdirSync, mkdirSync, rmSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {createHash, randomBytes, randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';

const root = fileURLToPath(new URL('../', import.meta.url));
const name = 'codex-checkout-payment-' + randomUUID();
const temporary = '/tmp/' + name;
const database = 'checkout_pay_' + randomBytes(8).toString('hex');
const password = randomBytes(24).toString('base64url');
const pin = '5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b';
const migrationDirectory = root + 'backend/shop-app/src/main/resources/db/migration/';
const migration = readFileSync(migrationDirectory + 'V539__checkout_source_foundation.sql');
assert.equal(migration.length, 8444, 'V539 size changed after root pin');
assert.equal(createHash('sha256').update(migration).digest('hex'), pin, 'V539 hash changed after root pin');
const paymentMigration = readFileSync(migrationDirectory + 'V540__checkout_payment_operation.sql');
assert.equal(paymentMigration.length, 7477, 'V540 size changed after root pin');
assert.equal(createHash('sha256').update(paymentMigration).digest('hex'),
  'ca76b6512cf79c6f25fa71a4441f533e03cc609c6320611a0dcdc9634a10f745', 'V540 differs from root pin');
const memory = execFileSync('limactl', ['shell','--instance','colima-hxkey-tests','cat','/proc/meminfo'],
  {encoding:'utf8', env:{...process.env,LIMA_HOME:'/Users/robin/.colima/_lima'}});
const available = Number(memory.match(/^MemAvailable:\s+(\d+)\s+kB$/m)?.[1]);
assert(Number.isFinite(available) && available >= 512 * 1024, 'Colima MemAvailable must be at least 512 MiB');
console.log('Native preflight: MemAvailable=' + available + ' KiB; cached MySQL 9.7.2; disk 64M; one owned UUID.');
const docker = (args, input) => {
  try {
    return execFileSync('docker', ['--context','colima-hxkey-tests',...args],
      {encoding:'utf8', input, stdio:['pipe','pipe','pipe'], timeout:120000}).trim();
  } catch (error) {
    throw new Error('Owned Docker operation failed: ' + String(error.stderr || error.message)
      .replaceAll(password,'[REDACTED]'));
  }
};
const mysql = (sql, schema) => docker(['exec','-i','-e','MYSQL_PWD=' + password,name,
  'mysql','--protocol=TCP','-h127.0.0.1','-uroot',...(schema ? [schema] : [])], sql);
let owned = false;
try {
  mkdirSync(temporary + '/migrations', {recursive:true});
  for (const filename of readdirSync(migrationDirectory)) {
    const version = Number(filename.match(/^V(\d+)__/i)?.[1]);
    if (version > 0 && version <= 538) {
      writeFileSync(temporary + '/migrations/' + filename, readFileSync(migrationDirectory + filename));
    }
  }
  execFileSync('python3', [root + 'backend/scripts/gen-test-schema.py',
    temporary + '/schema-v538.sql', temporary + '/migrations'], {stdio:'pipe'});
  docker(['run','--rm','--pull','never','--detach','--name',name,'--publish','127.0.0.1::3306',
    '--env','MYSQL_ROOT_PASSWORD=' + password,'--env','MYSQL_ROOT_HOST=%','mysql:9.7.2',
    '--datadir=/var/lib/checkout-test','--innodb-buffer-pool-size=64M','--performance-schema=OFF']);
  owned = true;
  let ready = false;
  for (let i = 0; i < 150; i++) {
    try { mysql('SELECT 1'); ready = true; break; }
    catch { await new Promise(resolve => setTimeout(resolve,300)); }
  }
  assert(ready,'Owned MySQL did not become ready');
  const address = docker(['port',name,'3306/tcp']);
  assert.match(address,/^127\.0\.0\.1:[0-9]+$/);
  mysql('CREATE DATABASE ' + database + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
  // Current V538 table fixture, not a claim that all historical Flyway migrations replay on MySQL.
  const fixture = readFileSync(temporary + '/schema-v538.sql','utf8')
    .replace(/\b([A-Za-z_]\w*) IS JSON\b/g,'JSON_VALID($1)')
    .split(/\nINSERT (?:IGNORE )?INTO /)[0];
  mysql(fixture,database);
  mysql(migration.toString('utf8'),database);
  mysql(paymentMigration.toString('utf8'),database);
  console.log('Applied exact frozen V539 and V540 to isolated V538 structural fixture.');
  const url = 'jdbc:mysql://' + address + '/' + database
    + '?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC';
  const env = {...process.env,
    JAVA_HOME:'/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home',
    CHECKOUT_NATIVE_MYSQL_URL:url, CHECKOUT_NATIVE_MYSQL_USER:'root', CHECKOUT_NATIVE_MYSQL_PASSWORD:password,
    SPRING_DATASOURCE_URL:url, SPRING_DATASOURCE_USERNAME:'root', SPRING_DATASOURCE_PASSWORD:password,
    SPRING_DATASOURCE_DRIVER_CLASS_NAME:'com.mysql.cj.jdbc.Driver', SPRING_SQL_INIT_MODE:'never'};
  const selected = process.env.CHECKOUT_PAYMENT_TESTS || 'PaymentOperationNativeMysqlTest';
  // Native conditions must never turn a selected native test into a skipped or stale success.
  const nativeClasses = ['PaymentOperationNativeMysqlTest', 'PaymentOperationProcessRestartNativeTest',
    'OfflineReceiptFlowTest', 'CheckoutPaymentAuthenticationFlowTest', 'CheckoutCashPermissionFlowTest',
    'ReceiptEvidenceFlowTest', 'CheckoutNativeMysqlTest', 'CheckoutRestartNativeTest',
    'CheckoutPermissionFlowTest', 'CheckoutPricingFlowTest', 'CheckoutHttpFlowTest',
    'CheckoutManagedLifecycleFlowTest', 'CheckoutOwnerBoundaryFlowTest', 'CheckoutSourceFactsFlowTest']
    .filter(test => selected.split(',').some(selector => selector.split('#')[0] === test));
  const nativeReport = test => root + 'backend/shop-app/target/surefire-reports/'
    + 'TEST-ai.neargo.shop.scenario.' + test + '.xml';
  for (const test of nativeClasses) rmSync(nativeReport(test), {force:true});
  const args = ['-o','-Dmaven.repo.local=/tmp/codex-beauty-bm01-m2','-f',root + 'backend/pom.xml',
    '-pl','shop-app','-am','test','-Dtest=' + selected,'-Dsurefire.failIfNoSpecifiedTests=false',
    '-Dcheckout.test.native=true'];
  const result = spawnSync('mvn',args,{cwd:root,env,stdio:'inherit',timeout:600000});
  assert.equal(result.status,0,'Checkout payment native tests failed');
  for (const test of nativeClasses) {
    const suite = readFileSync(nativeReport(test), 'utf8').match(/<testsuite\b[^>]*>/)?.[0];
    assert(suite, test + ' must produce a fresh Surefire suite');
    const counts = Object.fromEntries([...suite.matchAll(/(tests|failures|errors|skipped)="(\d+)"/g)]
      .map(([, key, value]) => [key, Number(value)]));
    assert(counts.tests > 0, test + ' must execute at least one test');
    for (const field of ['failures', 'errors', 'skipped']) {
      assert.equal(counts[field], 0, test + ' must have zero ' + field);
    }
    console.log('Native execution verified: ' + test + '; tests=' + counts.tests + '; skips=0.');
  }
} finally {
  if (owned) {
    try { docker(['rm','--force','--volumes',name]); }
    catch (error) {
      console.error('Owned container cleanup failed: ' + name + '; ' + error.message);
      process.exitCode = 1;
    }
  }
  rmSync(temporary,{recursive:true,force:true});
}
