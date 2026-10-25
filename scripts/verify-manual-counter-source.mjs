import assert from 'node:assert/strict';
import {execFileSync, spawn} from 'node:child_process';
import {createHash, randomBytes} from 'node:crypto';
import {readFileSync, writeFileSync, mkdirSync, rmSync, existsSync, realpathSync, rmdirSync} from 'node:fs';
import {join, relative} from 'node:path';
import {fileURLToPath} from 'node:url';

const root = realpathSync(fileURLToPath(new URL('../', import.meta.url)));
const workspace = '.superpowers/sdd/beauty-backend';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const nativeGate = workspace + '/task-66-native-root-gate.json';
const legacyCheckoutFixture = workspace + '/task-66-evidence/pre-native/pre-v542-legacy-checkout.sql';
const counterMigration = 'backend/shop-app/src/main/resources/db/migration/'
  + 'V542__shared_manual_order_customer_party.sql';
const migrations = [
  'backend/shop-app/src/main/resources/db/migration/V539__checkout_source_foundation.sql',
  'backend/shop-app/src/main/resources/db/migration/V540__checkout_payment_operation.sql',
  'backend/shop-app/src/main/resources/db/migration/V541__manual_service_admission.sql',
  'backend/shop-inventory/src/main/resources/db/inventory/V14__original_stock_disposition.sql'
];

/** Root-controlled finite stages prevent a TDD owner run from silently expanding into all suites. */
export function phaseSelection(slice, phase, stage = 'm2a') {
  assert(slice === 'm2a' && ['red', 'green', 'ablation', 'restored'].includes(phase),
    'invalid Task66 selection');
  if (stage === 'current-claims' && ['red', 'green'].includes(phase)) {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPrivacyIT',
      ['memberOwnerUnavailableFailsClosed']]];
  }
  if (stage === 'counter-purchase' && ['red', 'green'].includes(phase)) {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPurchaseIT',
      ['actualUserAndGuestBuyWithoutConsumerSession']]];
  }
  if (stage === 'counter-bridge' && ['red', 'green'].includes(phase)) {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterIdentityIT',
      ['boundUserAndClaimedGuestUseActualMemberOwners']]];
  }
  if (stage === 'counter-read-authorization' && ['red', 'green'].includes(phase)) {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterIdentityIT',
      ['counterReadRequiresExactProofOrCurrentSession']]];
  }
  if (stage === 'counter-cashier' && ['red', 'green'].includes(phase)) {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPurchaseIT',
      ['differentAuthorizedCashierResumesOriginalOnce']]];
  }
  if (stage === 'counter-recovery-diagnostic' && phase === 'green') {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterRecoveryIT',
      ['lostReserveReplyObservesOriginalRows', 'frozenInventoryOwnerReleasesAfterCurrentSkuMappingChanges']]];
  }
  if (stage === 'counter-recovery-fixes' && phase === 'green') {
    return [
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterRecoveryIT', [
        'lostReserveReplyObservesOriginalRows', 'staleOriginalReleaseLostReplyRemainsCompensatingAndNeverReservesAgain',
        'finalizeAndCompensateHaveOneWinner', 'concurrentFinalizeBlocksExpiredCompensation',
        'createdRetryDoesNotLockParentAgainstReceiptProgress', 'legacyScannerCannotReleaseCounterIntent',
        'claimDoesNotChangePendingOriginal', 'frozenInventoryOwnerReleasesAfterCurrentSkuMappingChanges',
        'heldCancellationAndExpiryRecordCompleteProgress', 'heldCancellationWriteFailureRollsBackOwnerRows']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPrivacyIT', [
        'claimedOwnerReadsOriginalGuestOrder', 'unclaimedAndForeignUsersCannotRead', 'nullBuyerNeverWidensList']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualServiceFinancialIsolationIT', [
        'counterUserAndGuestRemainFinanciallyIneligible']]
    ];
  }
  if (stage === 'counter-fixture-fixes' && phase === 'green') {
    return [
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterRecoveryIT', [
        'heldCancellationWriteFailureRollsBackOwnerRows', 'concurrentFinalizeBlocksExpiredCompensation']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPrivacyIT', ['nullBuyerNeverWidensList']]
    ];
  }
  if (stage === 'counter-authorization-ablation' && ['ablation', 'restored'].includes(phase)) {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPurchaseIT',
      ['currentAccountRevocationStopsCounterAfterMemberIO']]];
  }
  if (stage === 'counter-acceptance' && phase === 'green') {
    return [
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPurchaseIT', [
        'currentAccountRevocationStopsCounterAfterMemberIO', 'counterAcceptanceRequiresOriginalCashierNamespace',
        'identityIsNotPurchaseAcceptance', 'counterCannotUseConsumerConfirmation', 'changedQuoteCannotCreate',
        'sameCounterKeyCreatesOneOriginal', 'legacyNamespaceBackfillPreservesHistoricalBytesAndOldUniqueness',
        'guestConstraintsAndCounterKeysRejectMalformedRows']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterIdentityIT', [
        'newPrepareAfterClaimRequiresCanonicalUser', 'claimBetweenQuoteAndAcceptanceKeepsGuestSnapshot',
        'claimAndCounterCreationPreserveOneOriginal', 'claimedGuestWithoutMembershipCanPrepareUserCounter',
        'claimedSelectorRejectsWrongTokenRegisteredAndInactiveAccount']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterRecoveryIT', [
        'lostReserveReplyObservesOriginalRows', 'staleOriginalReleaseLostReplyRemainsCompensatingAndNeverReservesAgain',
        'finalizeAndCompensateHaveOneWinner', 'concurrentFinalizeBlocksExpiredCompensation',
        'createdRetryDoesNotLockParentAgainstReceiptProgress', 'legacyScannerCannotReleaseCounterIntent',
        'unknownReserveNeverBecomesCreated', 'claimDoesNotChangePendingOriginal',
        'frozenInventoryOwnerReleasesAfterCurrentSkuMappingChanges']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPrivacyIT', [
        'claimedOwnerReadsOriginalGuestOrder', 'unclaimedAndForeignUsersCannotRead', 'nullBuyerNeverWidensList']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualServiceFinancialIsolationIT', [
        'counterUserAndGuestRemainFinanciallyIneligible', 'fullPointsRefundTerminalStillRecoversPreparedOriginalStock',
        'fundedRefundRecoveryStillDispatchesAndReleasesOriginalOutsideCoreLocks']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualServiceAdmissionIT', [
        'currentBuyerPurchaseAndCashierReceiptCreateOneManualSource']]
    ];
  }
  throw new Error('invalid Task66 selection: unreleased stage');
}

/** Only intentional RED and the reviewed authorization ablation require own assertion failure. */
export function assertionRedPhase(phase) {
  return phase === 'red' || phase === 'ablation';
}

/** A setup error, extra testcase or skipped method is never an accepted behavioral result. */
export function inspectReport(xml, methods, red = false) {
  const tag = xml.match(/<testsuite\b[^>]*>/)?.[0];
  assert(tag, 'missing Task66 XML');
  const counts = Object.fromEntries([...tag.matchAll(/(tests|failures|errors|skipped)="(\d+)"/g)]
    .map(([, key, value]) => [key, Number(value)]));
  assert.equal(counts.tests, methods.length, 'exact Task66 method count required');
  assert(counts.tests > 0 && counts.errors === 0 && counts.skipped === 0, 'Task66 needs unskipped assertion evidence');
  const cases = [...xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)];
  assert.equal(cases.length, methods.length, 'unexpected Task66 testcase');
  for (const method of methods) {
    const found = cases.filter(entry => entry[1].match(/\bname="([^"]+)"/)?.[1] === method);
    assert.equal(found.length, 1, 'missing exact Task66 method');
    if (red) assert(/<failure\b/.test(found[0][2] ?? ''), 'Task66 RED needs own assertion failure');
  }
  assert(red ? counts.failures === methods.length : counts.failures === 0, 'wrong Task66 outcome');
  return counts;
}

/** Relative aggregate paths resolve only the current canonical backend and beauty sources. */
export function aggregatePom(directory) {
  const modules = ['backend', 'backend-plugins/shop-industry-beauty'].map(path =>
    '<module>' + relative(realpathSync(directory), realpathSync(join(root, path))) + '</module>');
  return '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
    + '<groupId>local.task66</groupId><artifactId>counter-reactor</artifactId><version>1</version>'
    + '<packaging>pom</packaging><modules>' + modules.join('') + '</modules></project>';
}

/** Selected source and compiled test classes must both be reviewed, not only SQL and runner text. */
export function requiredPinPaths(selection, fixtures, stage = 'current-claims') {
  return [fileURLToPath(import.meta.url).slice(root.length + 1), ...fixtures, ...migrations,
    ...(['counter-purchase', 'counter-bridge', 'counter-cashier', 'counter-acceptance', 'counter-recovery-diagnostic', 'counter-recovery-fixes', 'counter-fixture-fixes', 'counter-authorization-ablation', 'counter-read-authorization'].includes(stage) ? [counterMigration, legacyCheckoutFixture] : []),
    ...selection.flatMap(([module, cls]) => [
      module + '/src/test/java/' + cls.replaceAll('.', '/') + '.java',
      module + '/target/test-classes/' + cls.replaceAll('.', '/') + '.class'
    ])];
}

/** Verify exact files before any lock, daemon, SQL or VM activity. */
function approved(slice, phase) {
  const gate = JSON.parse(readFileSync(join(root, nativeGate), 'utf8'));
  assert.equal(gate.approved, true, 'Task66 requires root native GO');
  assert((gate.allowedPhases ?? []).includes(phase), 'Task66 phase is not root approved');
  assert.equal(gate.slice, slice, 'Task66 slice differs from gate');
  assert.match(gate.uuid ?? '', /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/);
  const selection = phaseSelection(slice, phase, gate.stage);
  assert.deepEqual(gate.selection, selection, 'Task66 selected methods differ from gate');
  assert(Array.isArray(gate.files) && gate.files.length > 0, 'Task66 missing root pins');
  for (const pin of gate.files) {
    const bytes = readFileSync(join(root, pin.path));
    assert.equal(bytes.length, pin.bytes, 'Task66 bytes changed: ' + pin.path);
    assert.equal(hash(bytes), pin.sha256, 'Task66 hash changed: ' + pin.path);
  }
  const fixtures = ['platform-v538.sql', 'inventory-v13.sql'].map(file =>
    workspace + '/task-66-evidence/pre-native/' + file);
  for (const path of requiredPinPaths(selection, fixtures, gate.stage)) {
    assert(gate.files.some(pin => pin.path === path), 'Task66 missing actual SQL/runner pin: ' + path);
  }
  assert.equal(gate.executesV542, ['counter-purchase', 'counter-bridge', 'counter-cashier', 'counter-acceptance', 'counter-recovery-diagnostic', 'counter-recovery-fixes', 'counter-fixture-fixes', 'counter-authorization-ablation', 'counter-read-authorization'].includes(gate.stage), 'Task66 actual DDL set differs from stage');
  return {gate, selection, fixtures};
}

async function run(slice, phase) {
  const {gate, selection, fixtures} = approved(slice, phase);
  const uuid = gate.uuid;
  const evidence = join(root, workspace, 'task-66-evidence', phase + '-' + uuid);
  assert(!existsSync(evidence), 'Task66 UUID/phase evidence cannot be overwritten');
  mkdirSync(evidence, {recursive: true});
  writeFileSync(join(evidence, 'root-gate.json'), JSON.stringify(gate, null, 2));
  const password = randomBytes(24).toString('base64url');
  const environment = {...process.env, JAVA_HOME: '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home',
    MYSQL_ROOT_PASSWORD: password, MYSQL_PWD: password};
  const safe = value => String(value).replaceAll(password, '[REDACTED]');
  const name = 'codex-counter-' + uuid;
  const temporary = '/tmp/codex-counter-' + uuid;
  const lock = '/tmp/codex-beauty-bm04-native.lock';
  let locked = false, owned = false, activeMaven, interrupted = false;
  const onSignal = () => { interrupted = true; activeMaven?.kill('SIGTERM'); };
  process.on('SIGINT', onSignal); process.on('SIGTERM', onSignal);
  const docker = (args, timeout = 45000) => {
    try {
      return execFileSync('docker', ['--context', 'colima-hxkey-tests', ...args],
        {encoding: 'utf8', env: environment, timeout, stdio: ['ignore', 'pipe', 'pipe']}).trim();
    } catch (failure) { throw new Error(safe(failure.stderr ?? failure.message)); }
  };
  try {
    mkdirSync(lock); locked = true;
    const limits = args => execFileSync('limactl', ['shell', '--instance', 'colima-hxkey-tests', ...args],
      {encoding: 'utf8', env: {...environment, LIMA_HOME: '/Users/robin/.colima/_lima'}, timeout: 15000});
    const memory = Number(limits(['cat', '/proc/meminfo']).match(/^MemAvailable:\s+(\d+)\s+kB$/m)?.[1]);
    const disk = Number(limits(['df', '-Pk', '/']).trim().split('\n').at(-1).trim().split(/\s+/)[3]);
    assert(memory >= 524288 && disk >= 1048576, 'Task66 requires 512MiB memory and 1GiB free disk');
    docker(['image', 'inspect', 'mysql:9.7.2']);
    mkdirSync(join(temporary, 'reactor'), {recursive: true});
    const pom = aggregatePom(join(temporary, 'reactor'));
    writeFileSync(join(temporary, 'reactor/pom.xml'), pom);
    writeFileSync(join(evidence, 'aggregate-pom.xml'), pom);
    writeFileSync(join(evidence, 'resources.json'), JSON.stringify({uuid, name, memoryKiB: memory,
      diskKiB: disk, schemas: 2, reducedStructuralFixture: true, executesV542: gate.executesV542}, null, 2));
    for (const [module, cls] of selection) {
      rmSync(join(root, module, 'target/surefire-reports/TEST-' + cls + '.xml'), {force: true});
    }
    owned = true;
    docker(['run', '--rm', '--pull', 'never', '--detach', '--name', name,
      '--label', 'codex.task66.uuid=' + uuid, '--publish', '127.0.0.1::3306', '--memory', '768m',
      '--pids-limit', '256', '--cpus', '1', '--env', 'MYSQL_ROOT_PASSWORD', '--env', 'MYSQL_ROOT_HOST=%',
      'mysql:9.7.2', '--datadir=/var/lib/counter-test', '--innodb-buffer-pool-size=64M', '--performance-schema=OFF']);
    let ready = false;
    const readinessDeadline = Date.now() + 90000;
    while (Date.now() < readinessDeadline) {
      assert(!interrupted, 'Task66 interrupted');
      try {
        docker(['exec', '--env', 'MYSQL_PWD', name, 'mysql', '--protocol=TCP', '-h127.0.0.1', '-uroot', '-e', 'SELECT 1'], Math.min(3000, Math.max(1, readinessDeadline - Date.now())));
        ready = true; break;
      } catch { await pause(Math.min(300, Math.max(0, readinessDeadline - Date.now()))); }
    }
    assert(ready, 'Task66 readiness timed out');
    const address = docker(['port', name, '3306/tcp']);
    assert.match(address, /^127\.0\.0\.1:[0-9]+$/);
    const platform = 'counter_' + uuid.replaceAll('-', ''), inventory = 'counter_inv_' + uuid.replaceAll('-', '');
    const mysql = (input, schema) => {
      try {
        return execFileSync('docker', ['--context', 'colima-hxkey-tests', 'exec', '-i', '--env', 'MYSQL_PWD', name,
          'mysql', '--protocol=TCP', '-h127.0.0.1', '-uroot', ...(schema ? [schema] : [])],
        {encoding: 'utf8', input, env: environment, timeout: 45000, stdio: ['pipe', 'pipe', 'pipe']});
      } catch (failure) { throw new Error('Task66 SQL setup: ' + safe(failure.stderr ?? failure.message)); }
    };
    mysql('CREATE DATABASE ' + platform + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'
      + 'CREATE DATABASE ' + inventory + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;');
    mysql(readFileSync(join(root, fixtures[0]), 'utf8'), platform);
    mysql(readFileSync(join(root, fixtures[1]), 'utf8'), inventory);
    for (const path of migrations.slice(0, 3)) mysql(readFileSync(join(root, path), 'utf8'), platform);
    mysql(readFileSync(join(root, migrations[3]), 'utf8'), inventory);
    if (gate.executesV542) {
      mysql(readFileSync(join(root, legacyCheckoutFixture), 'utf8'), platform);
      mysql(readFileSync(join(root, counterMigration), 'utf8'), platform);
    }
    const jdbc = schema => 'jdbc:mysql://' + address + '/' + schema
      + '?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC';
    Object.assign(environment, {CHECKOUT_NATIVE_MYSQL_URL: jdbc(platform), CHECKOUT_NATIVE_MYSQL_USER: 'root',
      CHECKOUT_NATIVE_MYSQL_PASSWORD: password, MANUAL_INVENTORY_MYSQL_URL: jdbc(inventory),
      MANUAL_SERVICE_TEST_PASSWORD: password, SPRING_DATASOURCE_URL: jdbc(platform),
      SPRING_DATASOURCE_USERNAME: 'root', SPRING_DATASOURCE_PASSWORD: password,
      SPRING_DATASOURCE_DRIVER_CLASS_NAME: 'com.mysql.cj.jdbc.Driver', SPRING_SQL_INIT_MODE: 'never'});
    const selected = selection.map(([, cls, methods]) => cls.split('.').at(-1) + '#' + methods.join('+')).join(',');
    const command = ['-o', '-Dmaven.repo.local=/tmp/codex-beauty-bm01-m2', '-f', join(temporary, 'reactor/pom.xml'),
      '-pl', ':shop-app,:shop-inventory,:beauty-channel', '-am', 'test', '-Dtest=' + selected,
      '-Dsurefire.failIfNoSpecifiedTests=false', '-Dcheckout.test.native=true'];
    activeMaven = spawn('mvn', command, {cwd: root, env: environment, stdio: ['ignore', 'pipe', 'pipe']});
    const chunks = [];
    for (const stream of [activeMaven.stdout, activeMaven.stderr]) stream.on('data', bytes => chunks.push(bytes));
    let hard;
    const timer = setTimeout(() => { activeMaven?.kill('SIGTERM');
      hard = setTimeout(() => activeMaven?.kill('SIGKILL'), 5000); }, 600000);
    let status;
    try { status = await new Promise((resolve, reject) => {
      activeMaven.once('error', reject); activeMaven.once('exit', resolve);
    }); } finally {
      clearTimeout(timer); clearTimeout(hard); activeMaven = undefined;
      writeFileSync(join(evidence, phase + '.log'), safe(Buffer.concat(chunks)));
    }
    writeFileSync(join(evidence, 'command.json'), JSON.stringify({command: 'mvn', args: command, status}, null, 2));
    assert(!interrupted, 'Task66 interrupted');
    // Preserve every selected fresh XML before an earlier class's verdict can stop inspection.
    const reports = selection.map(([module, cls, methods]) => {
      const path = join(root, module, 'target/surefire-reports/TEST-' + cls + '.xml');
      assert(existsSync(path), 'Task66 missing fresh XML: ' + cls);
      const xml = safe(readFileSync(path, 'utf8'));
      writeFileSync(join(evidence, cls + '.xml'), xml);
      return {cls, methods, xml};
    });
    const summary = reports.map(({cls, methods, xml}) =>
      ({class: cls, ...inspectReport(xml, methods, assertionRedPhase(phase))}));
    assert(assertionRedPhase(phase) ? status !== 0 : status === 0, 'Task66 wrong Maven outcome');
    writeFileSync(join(evidence, 'summary.json'), JSON.stringify({phase, summary, status}, null, 2));
    console.log('Task66 ' + phase + ' evidence: ' + evidence);
  } catch (failure) {
    writeFileSync(join(evidence, 'attempt-failure.json'), JSON.stringify({phase, message: safe(failure.message)}, null, 2));
    throw failure;
  } finally {
    try {
      if (owned) {
        const present = docker(['ps', '-aq', '--filter', 'name=^/' + name + '$']);
        if (present) {
          assert.equal(docker(['inspect', '--format', '{{index .Config.Labels "codex.task66.uuid"}}', name]), uuid);
          docker(['rm', '--force', name]);
        }
        assert.equal(docker(['ps', '-aq', '--filter', 'name=^/' + name + '$']), '',
          'Task66 owned container must be absent after cleanup');
      }
    } finally {
      let temporaryAbsent = !existsSync(temporary);
      if (!temporaryAbsent) {
        assert.equal(realpathSync(temporary), '/private/tmp/codex-counter-' + uuid,
          'Task66 cleanup must target only this UUID temporary reactor');
        rmSync(temporary, {recursive: true, force: false});
        temporaryAbsent = !existsSync(temporary);
      }
      if (locked) rmdirSync(lock);
      writeFileSync(join(evidence, 'cleanup.json'), JSON.stringify({uuid, name,
        ownedContainerAbsent: !owned || docker(['ps', '-aq', '--filter', 'name=^/' + name + '$']) === '',
        ownedTemporaryAbsent: temporaryAbsent, ownedLockAbsent: !locked || !existsSync(lock)}));
      assert(temporaryAbsent && (!locked || !existsSync(lock)), 'Task66 owned cleanup incomplete');
      process.off('SIGINT', onSignal); process.off('SIGTERM', onSignal);
    }
  }
}

if (process.argv[1] && realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2);
    assert(args.length === 2 && args[0] === '--slice=m2a'
      && /^--phase=(red|green|ablation|restored)$/.test(args[1]), 'invalid Task66 selection');
    await run('m2a', args[1].slice('--phase='.length));
  } catch (failure) { console.error(failure.message); process.exitCode = 1; }
}
