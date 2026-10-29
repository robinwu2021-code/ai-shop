// Task59 owns its selections, reports and disposable resources; deployment execution is separately gated.
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import {fileURLToPath} from 'node:url';
import {execFileSync, spawn} from 'node:child_process';
import {randomBytes, randomUUID, createHash} from 'node:crypto';
import {readFileSync, writeFileSync, mkdirSync, rmSync, existsSync, createWriteStream, copyFileSync, rmdirSync, readdirSync, renameSync} from 'node:fs';
import {join} from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const approved = {
  owners: {
    CheckoutCashPermissionFlowTest: ['ownerWildcardResolvesActualAccountButDelegationIsNotCurrentCashAuthorization',
      'receiveAndViewDoNotGrantCashAndGrantRevocationIsImmediateWithinOneSession',
      'userLoginResolvesLinkedAccountAndCannotBorrowAnotherStoreOrNamespace'],
    BeautySourceCheckoutIT: ['realGuestClaimThenCurrentConsumerPurchaseFeedsBindingAndBill',
      'lostQuoteResponseRecoversRealOriginalUnderCurrentManager',
      'closedSourceRetainsActualLateReceiptWithoutBindingOrExecution',
      'realOfflineCanonicalReceiptAndRefundBillRemainOwnerFacts']
  },
  embedded: {
    BeautyBm04EmbeddedHttpIT: ['realTokensRevocationNamespaceAndDeletedParentAreEnforced',
      'guestArrivalOpenPurchaseSourceAndNullableBillUseRealOwners']
  },
  standalone: {
    BeautyBm04StandaloneHttpIT: ['privateServerUsesRealHostOwnersAndHasNoLocalOwnerFallback',
      'lostPrepareAndRecoveryResponsesKeepOriginalOperationAndClearContexts']
  },
  aliases: {
    WorkOrderScopeIT: ['openAliasesShareCanonicalIntentAndPreserveOriginalProofReplay',
      'openAliasRaceAcceptsEitherWinnerAndLegacyRowsStayOriginal'],
    WalkInRecoveryIT: ['expectedRecoveryFailureEmitsAttributedWarningAndKeepsIntentRecoverable'],
    ReceptionBusinessIT: [],
    ServiceSubmissionIT: ['absentFindAfterQuoteFencesKnownBindingTicketAndEndsLease',
      'postCheckoutClaimedGuestAbsencePropagatesWithoutAvailabilityCompletion',
      'concurrentSameEndpointKeyReplaysOneCommittedSubmission',
      'concurrentChangedEndpointPayloadHasOneWinner',
      'concurrentDerivedActionsShareOnePermanentEndpointKey',
      'crossActionRaceRetainsEitherWinnersImmutableReceipt'],
    WorkOrderBillIT: ['ownerAuthorizationFailureIsNotPartialFinancialAvailability'],
    ServiceSourceObservationIT: []
  }
};

/** No class wildcard, guessed method, empty selector or cross-mode fallback may provision a resource. */
export function validateSelection(mode, selected) {
  assert(Object.hasOwn(approved, mode), 'invalid Task59 selector mode');
  assert(typeof selected === 'string' && selected.length > 0, 'invalid Task59 selector: empty');
  const selectors = selected.split(',');
  assert(new Set(selectors).size === selectors.length, 'invalid Task59 selector: duplicate');
  for (const value of selectors) {
    assert(/^[A-Za-z][A-Za-z0-9]*(?:#[A-Za-z][A-Za-z0-9]*)?$/.test(value), 'invalid Task59 selector shape');
    const [name, method] = value.split('#');
    assert(Object.hasOwn(approved[mode], name), 'invalid Task59 selector class');
    assert(!method || approved[mode][name].includes(method), 'invalid Task59 selector method');
  }
  return {mode, selectors};
}

/** Deployment evidence parsing refuses empty or skipped selections. */
export function inspectReport(xml, requested = []) {
  const suite = xml.match(/<testsuite\b[^>]*>/)?.[0]; assert(suite, 'missing Task59 XML suite');
  const counts = Object.fromEntries([...suite.matchAll(/(tests|failures|errors|skipped)="(\d+)"/g)]
    .map(([, key, value]) => [key, Number(value)]));
  assert(counts.tests > 0 && counts.failures === 0 && counts.errors === 0 && counts.skipped === 0,
    'Task59 XML must contain nonzero successful tests and zero skips');
  const found = [...xml.matchAll(/<testcase\b[^>]*?\bname="([^"]+)"/g)].map(match => match[1]);
  assert(requested.every(method => found.some(name => name === method || name.startsWith(method + '('))),
    'requested Task59 method missing from fresh XML');
  return counts;
}

/** Physical classpath validation precedes child process creation. */
export function verifiedClasspath(kind, module, resolved) {
  assert(['embedded', 'standalone', 'server'].includes(kind));
  assert(typeof resolved === 'string' && resolved.trim().length > 0, 'empty child classpath');
  const entries = resolved.trim().split(':');
  assert(entries.every(entry => entry.startsWith('/') && !entry.includes('\n')), 'invalid resolved child classpath');
  const forbidden = kind === 'standalone' ? ['beauty-core', 'beauty-server']
    : kind === 'server' ? ['shop-app', 'shop-core', 'shop-merchant', 'beauty-web'] : [];
  assert(!entries.some(entry => forbidden.some(name => entry.includes('/' + name + '-')
    || entry.includes('/' + name + '/'))), 'forbidden owner in child classpath');
  return [join(module, 'target/test-classes'), join(module, 'target/classes'), ...entries].join(':');
}

const pause = ms => new Promise(resolve => setTimeout(resolve, ms));

async function aliases(selection) {
  const evidence = join('/tmp/task59-evidence', 'aliases-' + randomUUID());
  mkdirSync(evidence, {recursive: true});
  const password = randomBytes(24).toString('base64url');
  const environment = {...process.env, MYSQL_ROOT_PASSWORD: password, MYSQL_PWD: password,
    BEAUTY_NATIVE_MYSQL_PASSWORD: password,
    JAVA_HOME: '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home'};
  const name = 'codex-beauty-bm04-' + randomUUID();
  const lock = '/tmp/codex-beauty-bm04-native.lock';
  let owned = false, locked = false;
  const docker = args => {
    try {
      return execFileSync('docker', ['--context', 'colima-hxkey-tests', ...args], {
        encoding: 'utf8', env: environment, stdio: ['ignore', 'pipe', 'pipe'], timeout: 45000
      }).trim();
    } catch (failure) {
      throw new Error('Task59 owned resource operation failed: '
        + String(failure.stderr ?? failure.message).replaceAll(password, '[REDACTED]'));
    }
  };
  try {
    mkdirSync(lock); locked = true;
    const memory = execFileSync('limactl', ['shell', '--instance', 'colima-hxkey-tests', 'cat', '/proc/meminfo'], {
      encoding: 'utf8', env: {...environment, LIMA_HOME: '/Users/robin/.colima/_lima'}, timeout: 15000
    });
    const available = Number(memory.match(/^MemAvailable:\s+(\d+)\s+kB$/m)?.[1]);
    assert(Number.isFinite(available) && available >= 524288, 'Task59 requires MemAvailable >= 524288 KiB');
    docker(['image', 'inspect', 'mysql:9.7.2']);
    console.log('Task59 aliases native: ' + name + '; MemAvailable=' + available + '; DISK64M; ' + evidence);
    owned = true;
    docker(['run', '--rm', '--pull', 'never', '--detach', '--name', name, '--publish', '127.0.0.1::3306',
      '--env', 'MYSQL_ROOT_PASSWORD', '--env', 'MYSQL_ROOT_HOST=%', 'mysql:9.7.2',
      '--datadir=/var/lib/beauty-test', '--innodb-buffer-pool-size=64M', '--performance-schema=OFF']);
    let ready = false;
    for (let attempt = 0; attempt < 150; attempt++) {
      try {
        docker(['exec', '--env', 'MYSQL_PWD', name, 'mysql', '--protocol=TCP', '-h127.0.0.1', '-uroot', '-e', 'SELECT 1']);
        ready = true; break;
      } catch { await pause(300); }
    }
    assert(ready, 'Task59 owned MySQL startup failed');
    const address = docker(['port', name, '3306/tcp']);
    assert.match(address, /^127\.0\.0\.1:[0-9]+$/);
    const module = join(root, 'backend-plugins/shop-industry-beauty/beauty-core');
    const reports = [...new Set(selection.selectors.map(value => value.split('#')[0]))].map(test => ({test,
      path: join(module, 'target/surefire-reports/TEST-ai.neargo.shop.beauty.scenario.' + test + '.xml')}));
    for (const report of reports) { rmSync(report.path, {force: true}); }
    const jdbc = 'jdbc:mysql://' + address + '/mysql?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC';
    const args = ['-o', '-Dmaven.repo.local=/tmp/codex-beauty-bm01-m2', '-f',
      join(root, 'backend-plugins/shop-industry-beauty/pom.xml'), '-pl', 'beauty-core', '-am', 'test',
      '-Dtest=' + selection.selectors.join(','), '-Dsurefire.failIfNoSpecifiedTests=false', '-Dbeauty.test.admin-url=' + jdbc];
    const log = createWriteStream(join(evidence, 'maven.log'));
    const child = spawn('mvn', args, {cwd: root, env: environment, stdio: ['ignore', 'pipe', 'pipe']});
    for (const stream of [child.stdout, child.stderr]) { stream.on('data', data => log.write(data)); }
    const timeout = setTimeout(() => child.kill('SIGTERM'), 600000);
    const status = await new Promise((resolve, reject) => {
      child.once('error', reject); child.once('exit', resolve);
    }).finally(() => clearTimeout(timeout));
    await new Promise(resolve => log.end(resolve));
    const summary = [];
    for (const report of reports) {
      assert(existsSync(report.path), 'missing fresh Task59 XML: ' + report.test);
      const xml = readFileSync(report.path, 'utf8');
      copyFileSync(report.path, join(evidence, report.test + '.xml'));
      const suite = xml.match(/<testsuite\b[^>]*>/)?.[0];
      assert(suite, 'missing Task59 suite');
      const counts = Object.fromEntries([...suite.matchAll(/(tests|failures|errors|skipped)="(\d+)"/g)]
        .map(([, key, value]) => [key, Number(value)]));
      const requested = selection.selectors.filter(value => value.startsWith(report.test + '#'))
        .map(value => value.split('#')[1]);
      const found = [...xml.matchAll(/<testcase\b[^>]*?\bname="([^"]+)"/g)].map(match => match[1]);
      assert(requested.every(method => found.some(name => name === method || name.startsWith(method + '('))),
        'requested Task59 method missing from fresh XML');
      summary.push({test: report.test, ...counts, sha256: createHash('sha256').update(xml).digest('hex')});
      console.log(report.test + ': ' + JSON.stringify(counts));
    }
    writeFileSync(join(evidence, 'summary.json'), JSON.stringify({status, summary}, null, 2));
    assert(summary.every(item => item.tests > 0 && item.skipped === 0), 'Task59 tests must run without skips');
    assert.equal(status, 0, 'Task59 native suite failed: ' + evidence);
    assert(summary.every(item => item.failures === 0 && item.errors === 0), 'Task59 failed XML');
  } finally {
    try {
      if (owned) { docker(['rm', '--force', '--volumes', name]); console.log('Task59 own cleanup: ' + name); }
    } finally { if (locked) { rmdirSync(lock); } }
  }
}

/** A bounded child command owns its log and timeout; arguments contain no credentials. */
async function runLogged(command, args, environment, logPath, timeoutMs = 600000) {
  const log = createWriteStream(logPath);
  const child = spawn(command, args, {cwd: root, env: environment, stdio: ['ignore', 'pipe', 'pipe']});
  for (const stream of [child.stdout, child.stderr]) { stream.on('data', data => log.write(data)); }
  let hard;
  const timer = setTimeout(() => { child.kill('SIGTERM'); hard = setTimeout(() => child.kill('SIGKILL'), 5000); }, timeoutMs);
  try {
    return await new Promise((resolve, reject) => { child.once('error', reject); child.once('exit', resolve); });
  } finally {
    clearTimeout(timer); clearTimeout(hard);
    await new Promise(resolve => log.end(resolve));
  }
}

/** The proxy forwards exact bytes and records only nonsecret route/status evidence. */
export async function startBoundFaultProxy(target, direction, events, changed = () => {}) {
  let drop = null, blocked = false;
  const server = http.createServer((request, response) => {
    const event = {direction, path: request.url, method: request.method, dropped: false};
    events.push(event);
    if (blocked) { event.blocked = true; changed(); request.resume(); response.destroy(); return; }
    const upstream = http.request(target + request.url, {
      method: request.method, headers: {...request.headers, host: new URL(target).host}
    }, reply => {
      const bytes = [];
      reply.on('data', data => bytes.push(data));
      reply.on('end', () => {
        event.status = reply.statusCode;
        if (drop === request.url) { drop = null; event.dropped = true; changed(); response.destroy(); return; }
        changed(); response.writeHead(reply.statusCode, reply.headers); response.end(Buffer.concat(bytes));
      });
    });
    upstream.setTimeout(45000, () => upstream.destroy());
    upstream.on('error', () => { event.transportFailure = true; changed(); response.destroy(); });
    request.on('error', () => upstream.destroy());
    request.pipe(upstream);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return {url: 'http://127.0.0.1:' + server.address().port,
    drop(path) { assert(path.startsWith('/internal/')); drop = path; },
    block(value) { blocked = value; },
    async close() { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
  };
}

async function deployment(selection) {
  if (selection.mode !== 'owners') {
    assert.equal(process.env.BEAUTY_BM04_DEPLOYMENT_RELEASE, 'unified',
      'Task59 actual deployment is DEFERRED until the unified deployment gate');
  }
  const evidence = join('/tmp/task59-evidence', selection.mode + '-' + randomUUID());
  mkdirSync(evidence, {recursive: true, mode: 0o700});
  const credentials = join(evidence, 'private'); mkdirSync(credentials, {mode: 0o700});
  const password = randomBytes(24).toString('base64url'), token = randomBytes(32).toString('base64url');
  const environment = {...process.env, JAVA_HOME: '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home',
    MYSQL_ROOT_PASSWORD: password, MYSQL_PWD: password, SPRING_DATASOURCE_PASSWORD: password,
    SHOP_SERVICES_INTERNAL_TOKEN: token, BEAUTY_BM04_FIXTURE_DIR: credentials, BEAUTY_BM04_SHAPE: selection.mode};
  const name = 'codex-beauty-bm04-' + randomUUID(), lock = '/tmp/codex-beauty-bm04-native.lock';
  const processes = [], proxies = [], events = []; let owned = false, locked = false, poll;
  const docker = (args, input) => {
    try { return execFileSync('docker', ['--context', 'colima-hxkey-tests', ...args],
      {input, env: environment, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'], timeout: 120000}).trim(); }
    catch (error) { throw new Error('Task59 owned resource failed: ' + String(error.stderr ?? error.message)
      .replaceAll(password, '[REDACTED]').replaceAll(token, '[REDACTED]')); }
  };
  const sql = (statement, database) => docker(['exec', '-i', '--env', 'MYSQL_PWD', name, 'mysql',
    '--default-character-set=utf8mb4', '-uroot', ...(database ? [database] : [])], statement);
  const maven = async (args, label) => {
    const result = await runLogged('mvn', ['-o', '-Dmaven.repo.local=/tmp/codex-beauty-bm01-m2', ...args],
      environment, join(evidence, label + '.log'));
    assert.equal(result, 0, 'Task59 build/setup failed: ' + label); return result;
  };
  const freePort = async () => {
    const socket = net.createServer(); await new Promise(resolve => socket.listen(0, '127.0.0.1', resolve));
    const port = socket.address().port; await new Promise(resolve => socket.close(resolve)); return port;
  };
  try {
    mkdirSync(lock); locked = true;
    const memory = execFileSync('limactl', ['shell', '--instance', 'colima-hxkey-tests', 'cat', '/proc/meminfo'],
      {encoding: 'utf8', env: {...environment, LIMA_HOME: '/Users/robin/.colima/_lima'}, timeout: 15000});
    assert(Number(memory.match(/^MemAvailable:\s+(\d+)\s+kB$/m)?.[1]) >= 524288,
      'Task59 requires MemAvailable >= 524288 KiB');
    docker(['image', 'inspect', 'mysql:9.7.2']);
    // Resolve/install current code before any child application starts; no stale local jar may satisfy the fixture.
    await maven(['-f', join(root, 'backend/pom.xml'), 'install', '-DskipTests'], 'install-backend');
    await maven(['-f', join(root, 'backend-plugins/shop-industry-beauty/pom.xml'), 'install', '-DskipTests'], 'install-beauty');
    const hostModule = join(root, 'backend/shop-app'), serverModule = join(root, 'backend-plugins/shop-industry-beauty/beauty-server');
    const hostProfile = '-Pbeauty-' + (selection.mode === 'owners' ? 'embedded' : selection.mode);
    let hostClasspath, serverClasspath;
    if (selection.mode !== 'owners') {
    await maven(['-f', join(root, 'backend/pom.xml'), '-pl', 'shop-app', '-am', hostProfile,
      'test-compile', '-DskipTests'], 'host-compile');
    await maven(['-f', join(hostModule, 'pom.xml'), hostProfile, 'dependency:build-classpath',
      '-Dmdep.includeScope=test', '-Dmdep.outputFile=' + join(evidence, 'host-dependencies.txt')], 'host-classpath');
    hostClasspath = verifiedClasspath(selection.mode, hostModule,
      readFileSync(join(evidence, 'host-dependencies.txt'), 'utf8'));
    if (selection.mode === 'standalone') {
      await maven(['-f', join(serverModule, 'pom.xml'), 'test-compile', '-DskipTests'], 'server-compile');
      await maven(['-f', join(serverModule, 'pom.xml'), 'dependency:build-classpath',
        '-Dmdep.includeScope=test', '-Dmdep.outputFile=' + join(evidence, 'server-dependencies.txt')], 'server-classpath');
      serverClasspath = verifiedClasspath('server', serverModule, readFileSync(join(evidence, 'server-dependencies.txt'), 'utf8'));
    }
    }
    const migrationDirectory = join(root, 'backend/shop-app/src/main/resources/db/migration');
    const migrations = join(evidence, 'schema-v598'); mkdirSync(migrations);
    for (const filename of readdirSync(migrationDirectory)) {
      const version = Number(filename.match(/^V(\d+)__/i)?.[1]);
      if (version > 0 && version <= 598) { copyFileSync(join(migrationDirectory, filename), join(migrations, filename)); }
    }
    execFileSync('python3', [join(root, 'backend/scripts/gen-test-schema.py'), join(evidence, 'schema-v598.sql'), migrations],
      {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']});
    const fixture = readFileSync(join(evidence, 'schema-v598.sql'), 'utf8')
      .replace(/\b([A-Za-z_]\w*) IS JSON\b/g, 'JSON_VALID($1)').split(/\nINSERT (?:IGNORE )?INTO /)[0];
    const frozen = [['V599__checkout_source_foundation.sql', 8444, '5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b'],
      ['V600__checkout_payment_operation.sql', 7477, 'ca76b6512cf79c6f25fa71a4441f533e03cc609c6320611a0dcdc9634a10f745']];
    for (const [file, size, hash] of frozen) {
      const bytes = readFileSync(join(migrationDirectory, file)); assert.equal(bytes.length, size);
      assert.equal(createHash('sha256').update(bytes).digest('hex'), hash);
    }
    owned = true;
    docker(['run', '--rm', '--pull', 'never', '--detach', '--name', name, '--publish', '127.0.0.1::3306',
      '--env', 'MYSQL_ROOT_PASSWORD', '--env', 'MYSQL_ROOT_HOST=%', 'mysql:9.7.2',
      '--datadir=/var/lib/beauty-test', '--innodb-buffer-pool-size=64M', '--performance-schema=OFF']);
    let ready = false;
    for (let attempt = 0; attempt < 150; attempt++) { try { sql('SELECT 1'); ready = true; break; } catch { await pause(300); } }
    assert(ready, 'Task59 owned MySQL startup failed');
    const address = docker(['port', name, '3306/tcp']); assert.match(address, /^127\.0\.0\.1:[0-9]+$/);
    const suffix = randomBytes(8).toString('hex'), platform = 'host_' + suffix, beauty = 'beauty_' + suffix;
    sql('CREATE DATABASE ' + platform + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE DATABASE '
      + beauty + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
    sql(fixture, platform);
    for (const [file] of frozen) { sql(readFileSync(join(migrationDirectory, file), 'utf8'), platform); }
    const infra = ['sys_idempotent', 'sys_outbox', 'sys_outbox_stream', 'sys_outbox_delivery', 'sys_outbox_operation', 'shedlock'];
    for (const table of infra) {
      const ddl = fixture.match(new RegExp('CREATE TABLE IF NOT EXISTS ' + table + '\\s*\\([\\s\\S]*?\\n\\);'))?.[0];
      assert(ddl, 'missing private infrastructure ' + table); sql(ddl, beauty);
    }
    const jdbc = database => 'jdbc:mysql://' + address + '/' + database
      + '?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC';
    environment.BEAUTY_BM04_HOST_JDBC = jdbc(platform);
    environment.BEAUTY_BM04_BEAUTY_JDBC = jdbc(selection.mode === 'embedded' ? platform : beauty);
    if (selection.mode === 'owners') {
      Object.assign(environment, {CHECKOUT_NATIVE_MYSQL_URL: jdbc(platform), CHECKOUT_NATIVE_MYSQL_USER: 'root',
        CHECKOUT_NATIVE_MYSQL_PASSWORD: password, SPRING_DATASOURCE_URL: jdbc(platform), SPRING_DATASOURCE_USERNAME: 'root',
        SPRING_DATASOURCE_DRIVER_CLASS_NAME: 'com.mysql.cj.jdbc.Driver', SPRING_SQL_INIT_MODE: 'never'});
      const classes = [...new Set(selection.selectors.map(value => value.split('#')[0]))];
      for (const name of classes) rmSync(join(hostModule, 'target/surefire-reports/TEST-ai.neargo.shop.scenario.' + name + '.xml'), {force: true});
      const status = await runLogged('mvn', ['-o', '-Dmaven.repo.local=/tmp/codex-beauty-bm01-m2', '-f', join(root, 'backend/pom.xml'),
        '-pl', 'shop-app', '-am', hostProfile, 'test', '-Dtest=' + selection.selectors.join(','),
        '-Dsurefire.failIfNoSpecifiedTests=false', '-Dcheckout.test.native=true', '-Dbeauty.source.test.native=true'],
        environment, join(evidence, 'owners.log'));
      const summary = [];
      for (const name of classes) {
        const source = join(hostModule, 'target/surefire-reports/TEST-ai.neargo.shop.scenario.' + name + '.xml');
        assert(existsSync(source), 'missing fresh owner XML'); copyFileSync(source, join(evidence, name + '.xml'));
        const xml = readFileSync(source, 'utf8');
        summary.push({name, ...inspectReport(xml, selection.selectors.filter(value => value.startsWith(name + '#'))
          .map(value => value.split('#')[1])), sha256: createHash('sha256').update(xml).digest('hex')});
      }
      writeFileSync(join(evidence, 'summary.json'), JSON.stringify({status, summary,
        schema: 'V598 structural fixture plus original V599/V600; not complete historical Flyway replay',
        scope: 'necessary native owner integration; no child application deployment launched'}, null, 2));
      assert.equal(status, 0, 'Task59 native owners failed');
      return;
    }
    const hostPort = await freePort(), serverPort = await freePort();
    const flush = () => {
      const temporary = join(credentials, 'proxy-events.tmp'); writeFileSync(temporary, JSON.stringify(events), {mode: 0o600});
      renameSync(temporary, join(credentials, 'proxy-events.json'));
    };
    if (selection.mode === 'standalone') {
      const platformProxy = await startBoundFaultProxy('http://127.0.0.1:' + hostPort, 'platform', events, flush);
      const beautyProxy = await startBoundFaultProxy('http://127.0.0.1:' + serverPort, 'beauty', events, flush);
      proxies.push(platformProxy, beautyProxy); flush();
      environment.SHOP_SERVICES_TARGETS_PLATFORM = platformProxy.url;
      environment.SHOP_SERVICES_TARGETS_BEAUTY = beautyProxy.url;
      poll = setInterval(() => {
        const path = join(credentials, 'proxy-command.json'); if (!existsSync(path)) return;
        const command = JSON.parse(readFileSync(path, 'utf8')); rmSync(path);
        const [direction, route] = command.argument.split(' '), proxy = direction === 'platform' ? platformProxy : beautyProxy;
        assert(['platform', 'beauty'].includes(direction));
        if (command.action === 'DROP') proxy.drop(route);
        else if (command.action === 'BLOCK') proxy.block(true);
        else if (command.action === 'UNBLOCK') proxy.block(false);
        else assert.fail('invalid owned fault command');
        const temporary = join(credentials, 'proxy-result.tmp');
        writeFileSync(temporary, JSON.stringify({id: command.id, completed: true}), {mode: 0o600});
        renameSync(temporary, join(credentials, 'proxy-command-result.json'));
      }, 20);
    }
    const start = async (label, classpath, main, port, database) => {
      const log = createWriteStream(join(evidence, label + '.log'));
      const args = ['-Xmx640m', '-cp', classpath, main, '--server.address=127.0.0.1', '--server.port=' + port,
        '--spring.datasource.url=' + jdbc(database), '--spring.datasource.username=root', '--spring.flyway.enabled=false',
        '--shop.beauty.enabled=true', '--shop.beauty.deployment=' + selection.mode, '--shop.beauty.migration.mode=migrate',
        '--shop.seed.enabled=false', '--shop.inventory.enabled=false', '--shop.auth.token-store=memory', '--shop.pay.deployment=standalone',
        '--shop.beauty.publication.archive-only=true', '--shop.catalog.publication.archive-only=true',
        '--shop.personnel.publication.archive-only=true', '--shop.resource.publication.archive-only=true',
        '--shop.schedule.publication.archive-only=true'];
      const child = spawn(join(environment.JAVA_HOME, 'bin/java'), args, {cwd: root, env: environment, stdio: ['ignore', 'pipe', 'pipe']});
      const done = new Promise(resolve => { child.once('error', resolve); child.once('exit', resolve); });
      processes.push({child, done, log});
      for (const stream of [child.stdout, child.stderr]) stream.on('data', data => log.write(data));
      for (let attempt = 0; attempt < 1200; attempt++) {
        assert(child.exitCode === null && child.pid, 'Task59 child startup failure: ' + label);
        if (existsSync(join(credentials, label + '.json'))) return;
        await pause(100);
      }
      assert.fail('Task59 child startup timeout: ' + label);
    };
    if (serverClasspath) await start('server', serverClasspath, 'ai.neargo.shop.beauty.fixture.BeautyBm04ServerFixture', serverPort, beauty);
    await start('host', hostClasspath, 'ai.neargo.shop.scenario.BeautyBm04HostFixture', hostPort, platform);
    const classes = [...new Set(selection.selectors.map(value => value.split('#')[0]))];
    for (const name of classes) rmSync(join(hostModule, 'target/surefire-reports/TEST-ai.neargo.shop.scenario.' + name + '.xml'), {force: true});
    const status = await runLogged('mvn', ['-o', '-Dmaven.repo.local=/tmp/codex-beauty-bm01-m2', '-f', join(root, 'backend/pom.xml'),
      '-pl', 'shop-app', '-am', hostProfile, 'test', '-Dtest=' + selection.selectors.join(','),
      '-Dsurefire.failIfNoSpecifiedTests=false', '-Dbeauty.bm04.deployment=true'], environment, join(evidence, 'acceptance.log'));
    const summary = [];
    for (const name of classes) {
      const source = join(hostModule, 'target/surefire-reports/TEST-ai.neargo.shop.scenario.' + name + '.xml');
      assert(existsSync(source), 'missing fresh deployment XML'); copyFileSync(source, join(evidence, name + '.xml'));
      const xml = readFileSync(source, 'utf8');
      summary.push({name, ...inspectReport(xml, selection.selectors.filter(value => value.startsWith(name + '#'))
        .map(value => value.split('#')[1])), sha256: createHash('sha256').update(xml).digest('hex')});
    }
    assert(processes.every(({child}) => child.exitCode === null), 'Task59 child exited during acceptance');
    writeFileSync(join(evidence, 'summary.json'), JSON.stringify({status, summary, schema:
      'V598 structural fixture plus exact V599/V600; actual beauty V1..8; not historical platform Flyway replay'}, null, 2));
    assert.equal(status, 0, 'Task59 deployment acceptance failed');
  } finally {
    clearInterval(poll);
    for (const {child, done, log} of processes.reverse()) {
      if (child.exitCode === null) { child.kill('SIGTERM'); await Promise.race([done, pause(10000)]);
        if (child.exitCode === null) child.kill('SIGKILL'); }
      await done; await new Promise(resolve => log.end(resolve));
    }
    for (const proxy of proxies) await proxy.close();
    writeFileSync(join(evidence, 'proxy-events.json'), JSON.stringify(events, null, 2));
    rmSync(credentials, {recursive: true, force: true});
    try { if (owned) docker(['rm', '--force', '--volumes', name]); }
    finally { if (locked) rmdirSync(lock); }
    console.log('Task59 owned cleanup complete; evidence: ' + evidence);
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  assert(process.argv.length === 3 && /^--mode=/.test(process.argv[2]), 'invalid Task59 selector arguments');
  const selection = validateSelection(process.argv[2].slice('--mode='.length), process.env.BEAUTY_BM04_TESTS);
  if (selection.mode === 'aliases') { await aliases(selection); }
  else { await deployment(selection); }
}
