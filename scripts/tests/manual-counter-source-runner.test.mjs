import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, realpathSync, symlinkSync, existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, dirname} from 'node:path';
import {pathToFileURL} from 'node:url';
import {phaseSelection, inspectReport, requiredPinPaths, assertionRedPhase, historicalClasspath, stageSchemaVersion, materializationPlan, requiredM2cPinPaths,
  resourcePublicationPlan, validateOldPendingBatch, validateM2cGate, m2cGatePath,
  driverClasspathPlan, m2cSchemaPlan, m2cOwnershipPlan, validateSelectedEvents, task68DriverSources, exportedDependencyClasspath, ownedProcessSignalPlan, oldClasspathPlan, canonicalLocalFileIdentity, verifyEffectiveClasspath} from '../verify-manual-counter-source.mjs';

test('early member-owner RED and GREEN are one exact method; future slices stay unavailable', () => {
  for (const phase of ['red', 'green']) {
    assert.deepEqual(phaseSelection('m2a', phase, 'current-claims'), [
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPrivacyIT',
        ['memberOwnerUnavailableFailsClosed']]
    ]);
  }
  for (const slice of ['m2b', 'm2c', '*', undefined]) {
    assert.throws(() => phaseSelection(slice, 'green', 'current-claims'));
  }
  for (const phase of ['RED', '', 'all', undefined]) {
    assert.throws(() => phaseSelection('m2a', phase, 'current-claims'));
  }
});

test('counter purchase stage is exact A1 and requires the actual new migration pin', () => {
  let selected;
  assert.doesNotThrow(() => { selected = phaseSelection('m2a', 'red', 'counter-purchase'); });
  assert.deepEqual(selected, [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPurchaseIT',
    ['actualUserAndGuestBuyWithoutConsumerSession']]]);
  const required = requiredPinPaths(selected, ['fixture.sql'], 'counter-purchase');
  assert(required.includes('backend/shop-app/src/main/resources/db/migration/'
    + 'V602__shared_manual_order_customer_party.sql'));
  assert(required.includes('.superpowers/sdd/beauty-backend/task-66-evidence/pre-native/'
    + 'pre-v542-legacy-checkout.sql'));
  assert(!requiredPinPaths(phaseSelection('m2a', 'red', 'current-claims'), [], 'current-claims')
    .some(path => path.includes('V542')));
});

test('selected native tests require both actual source and compiled-class pins', () => {
  const selection = phaseSelection('m2a', 'red', 'current-claims');
  const required = requiredPinPaths(selection, ['fixture.sql']);
  assert(required.includes('backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterPrivacyIT.java'));
  assert(required.includes('backend/shop-app/target/test-classes/ai/neargo/shop/scenario/ManualCounterPrivacyIT.class'));
  assert(required.includes('fixture.sql'));
});

test('XML must prove the exact method reached its own assertion RED or clean GREEN', () => {
  const xml = '<testsuite tests="1" failures="0" errors="0" skipped="0">'
    + '<testcase name="memberOwnerUnavailableFailsClosed"/></testsuite>';
  assert.equal(inspectReport(xml, ['memberOwnerUnavailableFailsClosed']).tests, 1);
  const red = xml.replace('failures="0"', 'failures="1"').replace('<testcase name="memberOwnerUnavailableFailsClosed"/>',
    '<testcase name="memberOwnerUnavailableFailsClosed"><failure message="owner missing"/></testcase>');
  assert.equal(inspectReport(red, ['memberOwnerUnavailableFailsClosed'], true).failures, 1);
  for (const invalid of [xml.replace('tests="1"', 'tests="0"'), xml.replace('skipped="0"', 'skipped="1"'),
    xml.replace('errors="0"', 'errors="1"'), xml.replace('FailsClosed', 'Wrong'),
    xml.replace('/></testsuite>', '/><testcase name="unexpected"/></testsuite>')]) {
    assert.throws(() => inspectReport(invalid, ['memberOwnerUnavailableFailsClosed']));
  }
  assert.throws(() => inspectReport(xml, ['memberOwnerUnavailableFailsClosed'], true));
  assert.throws(() => inspectReport(red, ['memberOwnerUnavailableFailsClosed']));
});

test('invalid CLI rejects before any native tools or lock mutation', () => {
  for (const args of [[], ['--slice=m2a'], ['--slice=m2a', '--phase=green', '--all'],
    ['--slice=m2c', '--phase=green'], ['--slice=m2a', '--phase=UNKNOWN']]) {
    const result = spawnSync(process.execPath, ['scripts/verify-manual-counter-source.mjs', ...args],
      {encoding: 'utf8', env: {PATH: ''}});
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /invalid Task66 selection/);
    assert.doesNotMatch(result.stderr, /ENOENT|docker|limactl/);
  }
});


test('counter owner bridge stage runs one real controller method with actual counter DDL', () => {
  const selected = phaseSelection('m2a', 'red', 'counter-bridge');
  assert.deepEqual(selected, [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterIdentityIT',
    ['boundUserAndClaimedGuestUseActualMemberOwners']]]);
  assert.deepEqual(phaseSelection('m2a', 'green', 'counter-bridge'), selected);
  assert(requiredPinPaths(selected, [], 'counter-bridge').some(path => path.includes('V542')));
  assert(requiredPinPaths(selected, [], 'counter-bridge').some(path => path.includes('pre-v542-legacy-checkout')));
});


test('cross cashier business regression stays one exact method with counter DDL pins', () => {
  for (const phase of ['red', 'green']) {
    const selected = phaseSelection('m2a', phase, 'counter-cashier');
    assert.deepEqual(selected, [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPurchaseIT',
      ['differentAuthorizedCashierResumesOriginalOnce']]]);
    assert(requiredPinPaths(selected, [], 'counter-cashier').some(path => path.includes('V542')));
    assert(requiredPinPaths(selected, [], 'counter-cashier').some(path => path.includes('pre-v542-legacy')));
  }
});


test('remaining acceptance stage is exact29 methods and excludes accepted historical gates', () => {
  const selected = phaseSelection('m2a', 'green', 'counter-acceptance');
  assert.equal(selected.length, 6);
  assert.equal(selected.reduce((sum, [, , methods]) => sum + methods.length, 0), 29);
  assert.deepEqual(selected.map(([, cls, methods]) => [cls.split('.').at(-1), methods.length]), [
    ['ManualCounterPurchaseIT', 8], ['ManualCounterIdentityIT', 5], ['ManualCounterRecoveryIT', 9],
    ['ManualCounterPrivacyIT', 3], ['ManualServiceFinancialIsolationIT', 3], ['ManualServiceAdmissionIT', 1]
  ]);
  const methods = selected.flatMap(([, , names]) => names);
  for (const historical of ['actualUserAndGuestBuyWithoutConsumerSession',
    'boundUserAndClaimedGuestUseActualMemberOwners', 'memberOwnerUnavailableFailsClosed',
    'differentAuthorizedCashierResumesOriginalOnce']) assert(!methods.includes(historical));
  assert(requiredPinPaths(selected, [], 'counter-acceptance').some(path => path.includes('V542')));
  assert.throws(() => phaseSelection('m2a', 'red', 'counter-acceptance'));
});

test('recovery diagnostic is exactly two actual failing owner cases with DDL and source/class pins', () => {
  const selected = phaseSelection('m2a', 'green', 'counter-recovery-diagnostic');
  assert.deepEqual(selected, [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterRecoveryIT',
    ['lostReserveReplyObservesOriginalRows', 'frozenInventoryOwnerReleasesAfterCurrentSkuMappingChanges']]]);
  const pins = requiredPinPaths(selected, [], 'counter-recovery-diagnostic');
  assert(pins.some(path => path.includes('V542')));
  assert(pins.some(path => path.endsWith('/ManualCounterRecoveryIT.java')));
  assert(pins.some(path => path.endsWith('/ManualCounterRecoveryIT.class')));
  for (const phase of ['red', 'ablation', 'restored']) {
    assert.throws(() => phaseSelection('m2a', phase, 'counter-recovery-diagnostic'));
  }
});

test('recovery fixes select failed12 plus two owner compatibility controls without replaying accepted17', () => {
  const selected = phaseSelection('m2a', 'green', 'counter-recovery-fixes');
  assert.deepEqual(selected.map(([, cls, methods]) => [cls.split('.').at(-1), methods.length]), [
    ['ManualCounterRecoveryIT', 10], ['ManualCounterPrivacyIT', 3], ['ManualServiceFinancialIsolationIT', 1]
  ]);
  const methods = selected.flatMap(([, , values]) => values);
  assert.equal(methods.length, 14);
  assert(methods.includes('heldCancellationAndExpiryRecordCompleteProgress'));
  assert(methods.includes('heldCancellationWriteFailureRollsBackOwnerRows'));
  assert(!methods.includes('unknownReserveNeverBecomesCreated'));
  assert(requiredPinPaths(selected, [], 'counter-recovery-fixes').some(path => path.includes('V542')));
  for (const phase of ['red', 'ablation', 'restored']) {
    assert.throws(() => phaseSelection('m2a', phase, 'counter-recovery-fixes'));
  }
});

test('fixture corrections rerun exactly the three failed methods and preserve actual competition', () => {
  const selected = phaseSelection('m2a', 'green', 'counter-fixture-fixes');
  assert.deepEqual(selected, [
    ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterRecoveryIT', [
      'heldCancellationWriteFailureRollsBackOwnerRows', 'concurrentFinalizeBlocksExpiredCompensation']],
    ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPrivacyIT', ['nullBuyerNeverWidensList']]
  ]);
  assert(requiredPinPaths(selected, [], 'counter-fixture-fixes').some(path => path.includes('V542')));
  for (const phase of ['red', 'ablation', 'restored']) {
    assert.throws(() => phaseSelection('m2a', phase, 'counter-fixture-fixes'));
  }
});

test('authorization ablation/restored select one actual post-member authorization case', () => {
  assert.equal(assertionRedPhase('ablation'), true);
  assert.equal(assertionRedPhase('restored'), false);
  const expected = [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPurchaseIT',
    ['currentAccountRevocationStopsCounterAfterMemberIO']]];
  for (const phase of ['ablation', 'restored']) {
    const selected = phaseSelection('m2a', phase, 'counter-authorization-ablation');
    assert.deepEqual(selected, expected);
    assert(requiredPinPaths(selected, [], 'counter-authorization-ablation').some(path => path.includes('V542')));
  }
  for (const phase of ['red', 'green']) {
    assert.throws(() => phaseSelection('m2a', phase, 'counter-authorization-ablation'));
  }
});

test('counter read authorization regression is one exact owner method with frozen counter SQL pins', () => {
  for (const phase of ['red', 'green']) {
    const selected = phaseSelection('m2a', phase, 'counter-read-authorization');
    assert.deepEqual(selected, [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterIdentityIT',
      ['counterReadRequiresExactProofOrCurrentSession']]]);
    const pins = requiredPinPaths(selected, [], 'counter-read-authorization');
    assert(pins.some(path => path.includes('V542')));
    assert(pins.some(path => path.includes('pre-v542-legacy-checkout')));
    assert(pins.some(path => path.endsWith('/ManualCounterIdentityIT.java')));
    assert(pins.some(path => path.endsWith('/ManualCounterIdentityIT.class')));
  }
  for (const phase of ['ablation', 'restored']) {
    assert.throws(() => phaseSelection('m2a', phase, 'counter-read-authorization'));
  }
});


test('manual member reception stage is one exact V8 owner assertion with canonical source and class pins', () => {
  for (const phase of ['red', 'green']) {
    const selected = phaseSelection('m2b', phase, 'reception-member');
    assert.deepEqual(selected, [['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBindingIT',
      ['noConsumerReceptionUsesCanonicalMemberSelector']]]);
    const pins = requiredPinPaths(selected, [], 'reception-member');
    assert(pins.includes('backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualSourceHostFixture.java'));
    assert(pins.includes('backend/shop-app/target/test-classes/ai/neargo/shop/scenario/ManualSourceHostFixture.class'));
    assert(pins.some(path => path.endsWith('/V8__beauty_source_observation.sql')));
    assert(pins.some(path => path.endsWith('/V602__shared_manual_order_customer_party.sql')));
    assert(pins.some(path => path.endsWith('/ReceptionIdentity.java')));
    assert(pins.some(path => path.endsWith('/ReceptionIdentity.class')));
    assert(!pins.some(path => path.includes('V9__')));
  }
  for (const phase of ['ablation', 'restored']) {
    assert.throws(() => phaseSelection('m2b', phase, 'reception-member'));
  }
  for (const stage of ['m2b', 'upgrade', '*']) {
    assert.throws(() => phaseSelection('m2b', 'green', stage));
  }
});


test('manual existing source RED stays one exact real-owner V8 case and forbids broad phases', () => {
  const selected = phaseSelection('m2b', 'red', 'source-binding-existing');
  assert.deepEqual(selected, [['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBindingIT',
    ['manualUserAndGuestBindExistingOrdinaryLines']]]);
  const pins = requiredPinPaths(selected, [], 'source-binding-existing');
  for (const owner of ['ManualServiceAdmissionServiceImpl', 'CounterOrderPlacementServiceImpl',
    'StockDispositionServiceImpl', 'OriginalStockDispositionPort']) {
    assert(pins.some(path => path.endsWith('/' + owner + '.java')));
    assert(pins.some(path => path.endsWith('/' + owner + '.class')));
  }
  assert(pins.some(path => path.endsWith('/V8__beauty_source_observation.sql')));
  assert(!pins.some(path => path.includes('V9__')));
  for (const phase of ['ablation', 'restored']) {
    assert.throws(() => phaseSelection('m2b', phase, 'source-binding-existing'));
  }
});

test('released manual existing GREEN pins candidate V9 tools while retaining finite same-method scope', () => {
  const selected = phaseSelection('m2b', 'green', 'source-binding-existing');
  assert.deepEqual(selected, phaseSelection('m2b', 'red', 'source-binding-existing'));
  const pins = requiredPinPaths(selected, [], 'source-binding-existing', 9);
  assert(pins.some(path => path.endsWith('/V9__beauty_manual_source_observation.sql')));
  assert(pins.includes('docs/technical/design/schema/beauty-manual-source-observation-schema.json'));
  assert(pins.includes('scripts/gen-beauty-manual-source-observation-schema.mjs'));
  for (const phase of ['ablation', 'restored']) {
    assert.throws(() => phaseSelection('m2b', phase, 'source-binding-existing'));
  }
});

test('manual matrix is exact new sixteen same-runtime methods and excludes accepted first-binding replay', () => {
  const selected = phaseSelection('m2b', 'green', 'source-matrix');
  assert.equal(selected.length, 2);
  const methods = selected.flatMap(([, , methods]) => methods);
  assert.equal(methods.length, 16);
  assert(!methods.includes('manualUserAndGuestBindExistingOrdinaryLines'));
  assert(!methods.includes('noConsumerReceptionUsesCanonicalMemberSelector'));
  assert(methods.includes('twoWorkOrdersCannotAllocateLastUnit'));
  assert(methods.includes('lateObservationCannotReviveSuspendedSource'));
  assert(methods.includes('claimedMp155RequiresCurrentMemberOwner'));
  for (const phase of ['red', 'ablation', 'restored']) assert.throws(() => phaseSelection('m2b', phase, 'source-matrix'));
});

test('matrix fixture fixes select only the three failed cases and retain thirteen passed results', () => {
  const selected = phaseSelection('m2b', 'green', 'source-matrix-fixture-fixes');
  assert.deepEqual(selected, [
    ['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBindingIT', [
      'manualPrepareContinuesOriginalWithoutRawGuestCredential', 'claimedGuestContextM1UserKeepsVerifiedRelation']],
    ['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBillIT', ['mixedSourceCurrencyTotalsRemainSeparate']]
  ]);
  for (const phase of ['red', 'ablation', 'restored']) assert.throws(() => phaseSelection('m2b', phase, 'source-matrix-fixture-fixes'));
});

test('historical upgrade has one exact method and replaces only the three actual beauty runtime roots', () => {
  assert.deepEqual(phaseSelection('m2b', 'green', 'source-upgrade'), [
    ['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBindingIT',
      ['preM2UnknownManualPlaceholderAdoptsSameIdentityAfterV9']]
  ]);
  for (const phase of ['red', 'ablation', 'restored'])
    assert.throws(() => phaseSelection('m2b', phase, 'source-upgrade'));
  const root = new URL('../../', import.meta.url).pathname.replace(/\/$/, '');
  const prefix = root + '/backend-plugins/shop-industry-beauty/';
  const current = ['api', 'core', 'web'].map(name => prefix + 'beauty-' + name + '/target/classes');
  const snapshot = root + '/.superpowers/sdd/beauty-backend/task-67-evidence/pre-v9-runtime';
  assert.deepEqual(historicalClasspath([...current, root + '/backend/shop-base/target/classes', '/private/tmp/common.jar']), [
    ...current.map(path => snapshot + path.slice(root.length)), root + '/backend/shop-base/target/classes', '/private/tmp/common.jar'
  ]);
  assert.throws(() => historicalClasspath(current.slice(0, 2)));
  assert.throws(() => historicalClasspath([...current, prefix + 'beauty-core/target/test-classes']));
  assert.throws(() => historicalClasspath([...current, '/private/tmp/beauty-core-1.jar']));
  assert.throws(() => historicalClasspath([...current, current[0]]));
  assert.throws(() => historicalClasspath([...current, '']));
  assert.throws(() => historicalClasspath([...current, root]));
});

test('final baseline selects only unallocated funded evidence and same-pair stale ticket cases', () => {
  assert.deepEqual(phaseSelection('m2b', 'green', 'source-final-baseline'), [
    ['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBindingIT', [
      'fundedEvidenceRejectsManualAdoptionBeforeAndAfterAllocation',
      'lateAdmittedReplyCannotCompleteNewerUnavailableTicket']]
  ]);
  for (const phase of ['red', 'ablation', 'restored'])
    assert.throws(() => phaseSelection('m2b', phase, 'source-final-baseline'));
});

test('ticket ablation and restored run only the same-pair case with strict V9 pins', () => {
  const expected = [['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBindingIT',
    ['lateAdmittedReplyCannotCompleteNewerUnavailableTicket']]];
  for (const phase of ['ablation', 'restored']) {
    assert.deepEqual(phaseSelection('m2b', phase, 'source-ticket-ablation'), expected);
    const pins = requiredPinPaths(expected, [], 'source-ticket-ablation', 9);
    assert(pins.some(path => path.endsWith('/ServiceSourceObservation.java')));
    assert(pins.some(path => path.endsWith('/V9__beauty_manual_source_observation.sql')));
  }
  for (const phase of ['red', 'green'])
    assert.throws(() => phaseSelection('m2b', phase, 'source-ticket-ablation'));
});

test('review recovery RED uses current V9 and GREEN adds only literal drift startup coverage', () => {
  assert.equal(stageSchemaVersion('source-review-recovery', 'red'), 9);
  assert.equal(stageSchemaVersion('source-review-recovery', 'green'), 9);
  assert.equal(stageSchemaVersion('source-binding-existing', 'red'), 8);
  const red = [['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBindingIT',
    ['createdManualPrepareRecoveryRequiresCurrentSourceFacts']]];
  assert.deepEqual(phaseSelection('m2b', 'red', 'source-review-recovery'), red);
  assert.deepEqual(phaseSelection('m2b', 'green', 'source-review-recovery'), [
    ['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBindingIT', [
      ...red[0][2], 'startupRejectsLiveTriggerLiteralDrift']]
  ]);
  const pins = requiredPinPaths(red, [], 'source-review-recovery', 9);
  assert(pins.some(path => path.endsWith('/V9__beauty_manual_source_observation.sql')));
  assert(pins.some(path => path.endsWith('/BeautySchemaValidatorTest.java')));
  for (const phase of ['ablation', 'restored'])
    assert.throws(() => phaseSelection('m2b', phase, 'source-review-recovery'));
});


// Task68 source-authored cases: deliberately UNEXECUTED during phase A.
test('Task68 is exactly 53 finite methods with the old original isolated from the other sixteen', () => {
  const stages = ['counter-protocol', 'counter-owner-fence', 'counter-old-pending', 'counter-discounts'];
  const selections = stages.map(stage => phaseSelection('m2c', 'green', stage));
  assert.deepEqual(selections.map(rows => rows.reduce((n, row) => n + row[2].length, 0)), [18, 18, 1, 16]);
  const names = selections.flatMap(rows => rows.flatMap(([, cls, methods]) => methods.map(m => cls + '#' + m)));
  assert.equal(new Set(names).size, 53);
  assert.equal(selections[2][0][2][0], 'oldPendingCounterKeepsZeroEconomicsAfterPromotionRollout');
  for (const phase of ['red', 'ablation', 'restored', 'prepare'])
    assert.throws(() => phaseSelection('m2c', phase, 'counter-protocol'));
  for (const stage of ['*', 'source-upgrade', 'counter-all', 'materialize-goldens'])
    assert.throws(() => phaseSelection('m2c', 'green', stage));
});

test('Task68 preparation has no selection/test pass and uses only its own exact gate', () => {
  assert.equal(m2cGatePath('prepare'), '.superpowers/sdd/beauty-backend/task-68-materialization-root-gate.json');
  assert.equal(m2cGatePath('green'), '.superpowers/sdd/beauty-backend/task-68-native-root-gate.json');
  assert.throws(() => m2cGatePath('red'));
  for (const stage of ['materialize-runtime', 'materialize-goldens']) {
    const plan = materializationPlan('m2c', 'prepare', stage);
    assert.equal(plan.selection, null);
    assert(plan.derivedOutputs.length > 0);
    assert.throws(() => materializationPlan('m2c', 'green', stage));
  }
  assert.throws(() => materializationPlan('m2c', 'prepare', 'counter-protocol'));
  assert.throws(() => inspectReport('<testsuite tests="0" errors="0" skipped="0" failures="0"/>', []));
});

test('Task68 pins actual sources, classes, profiles, seven-owner schema and frozen descriptor', () => {
  const pins = requiredM2cPinPaths(phaseSelection('m2c', 'green', 'counter-discounts'), 'counter-discounts');
  for (const name of ['CounterCampaignContract', 'CounterEconomicCodec', 'CounterQuoteSnapshot',
    'CounterOrderPlan', 'CampaignPortRouter', 'ActivityPricingServiceImpl', 'ManualCounterDiscountFixture',
    'CounterCampaignFenceMapper', 'MemberCustomerIdentityPortImpl', 'MemberGuestRegistrationPortImpl',
    'MerchantOperatorAccessPortImpl', 'ServiceCatalogFactsPortImpl', 'StockPortImpl', 'CheckoutFlowFixture']) {
    assert(pins.some(p => p.endsWith('/' + name + '.java')), name + ' source');
    assert(pins.some(p => p.endsWith('/' + name + '.class')), name + ' class');
  }
  assert(pins.some(p => p.endsWith('/V603__counter_campaign_owner_fence.sql')));
  assert(pins.some(p => p.includes('target/classes/schema/counter-campaign-owner-fence-descriptor.json')));
  assert(!pins.some(p => p.includes('V9__beauty')));
  const schema = m2cSchemaPlan();
  assert.deepEqual(schema.topology, ['platform', 'inventory']);
  assert.equal(schema.stockAuthority, 'PLATFORM');
  assert.equal(schema.tables.length, 7); assert.equal(schema.bindings.length, 10);
  assert.deepEqual(schema.steps.map(s => s.key), ['platform', 'inventory', 'v539', 'v540', 'v541',
    'preV542', 'v542', 'invV14', 'require-seven-owner-tables', 'v543', 'capture-effective-schema']);
  assert(schema.properties.includes('-Dshop.inventory.enabled=false'));
  assert(schema.properties.includes('-Dshop.inventory.stock-authority=PLATFORM'));
});

const testPin = (path, sha = 'a'.repeat(64)) => ({path, bytes: 1, sha256: sha});
const testToolRows = [
  ['org.junit.platform:junit-platform-launcher:6.0.3', 'org/junit/platform/junit-platform-launcher/6.0.3/junit-platform-launcher-6.0.3.jar'],
  ['org.junit.platform:junit-platform-engine:6.0.3', 'org/junit/platform/junit-platform-engine/6.0.3/junit-platform-engine-6.0.3.jar'],
  ['org.junit.platform:junit-platform-commons:6.0.3', 'org/junit/platform/junit-platform-commons/6.0.3/junit-platform-commons-6.0.3.jar'],
  ['org.opentest4j:opentest4j:1.3.0', 'org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar'],
  ['org.apiguardian:apiguardian-api:1.1.2', 'org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar'],
  ['org.jspecify:jspecify:1.0.0', 'org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar']
];
const toolManifest = () => ({schemaVersion: 1, junitVersion: '6.0.3',
  poms: [testPin('/private/tmp/codex-beauty-bm01-m2/root.pom')],
  artifacts: testToolRows.map(([coordinate, path]) => ({coordinate,
    definitions: [], ...testPin('/private/tmp/codex-beauty-bm01-m2/' + path)}))});
const projectManifest = () => ({workspaceRoot: '/reviewed', entries: [
  {path: '/reviewed/backend/shop-app/target/test-classes', definitions: ['ai/neargo/shop/scenario/ManualCounterDiscountIT.class']},
  ...['api', 'params', 'engine'].map(name => ({path: '/private/tmp/codex-beauty-bm01-m2/org/junit/jupiter/junit-jupiter-'
    + name + '/6.0.3/junit-jupiter-' + name + '-6.0.3.jar', definitions: []}))
]});

test('approved launcher suffix is explicit and identical for driver javac and java', () => {
  const project = projectManifest(), tools = toolManifest();
  const plan = driverClasspathPlan(project, tools);
  assert.equal(plan.runnerToolSuffix[0], tools.artifacts[0].path);
  assert.deepEqual(plan.javacClasspath, plan.javaClasspath);
  assert.deepEqual(plan.javacClasspath, [...project.entries.map(e => e.path), ...tools.artifacts.map(e => e.path)]);
  project.entries.push({...tools.artifacts[1], definitions: []});
  assert.equal(driverClasspathPlan(project, tools).runnerToolSuffix.length, 5);
  const missing = toolManifest(); missing.artifacts.shift();
  assert.throws(() => driverClasspathPlan(project, missing));
  const extra = toolManifest(); extra.artifacts.push({...testPin('/tmp/unapproved.jar'), coordinate: 'extra'});
  assert.throws(() => driverClasspathPlan(project, extra));
  const conflict = projectManifest(); conflict.entries[1].path = conflict.entries[1].path.replaceAll('6.0.3', '5.0.0');
  assert.throws(() => driverClasspathPlan(conflict, toolManifest()));
  for (const path of ['', '*', '/reviewed', '/cache/ai/neargo/shop/shop-core/1/shop-core-1.jar']) {
    const bad = projectManifest(); bad.entries.push({path, definitions: []});
    assert.throws(() => driverClasspathPlan(bad, toolManifest()));
  }
  const duplicate = projectManifest(); duplicate.entries.push({path: '/other/classes',
    definitions: [...duplicate.entries[0].definitions]});
  assert.throws(() => driverClasspathPlan(duplicate, toolManifest()));
});

test('golden publication copies exactly six accepted files and rejects duplicate/missing names', () => {
  const names = ['ordinary-v1.json', 'm1-v1.json', 'm2a-user-v1.json', 'm2a-guest-v1.json',
    'm2a-pending-plan-v1.json', 'hashes.json'];
  const pins = names.map(name => ({name, ...testPin('/accepted/' + name)}));
  const plan = resourcePublicationPlan(pins, '/private/tmp/codex-counter-m2c-run');
  assert.equal(plan.classpathRoot, '/private/tmp/codex-counter-m2c-run/resources');
  assert(plan.copies.every(p => p.destination.endsWith('/counter-economics/' + p.source.split('/').at(-1))));
  assert.throws(() => resourcePublicationPlan(pins.slice(1), '/owned'));
  assert.throws(() => resourcePublicationPlan([...pins.slice(0, 5), pins[0]], '/owned'));
});

const oldBatch = now => ['USER', 'GUEST', 'BASE_CHANGE', 'CATALOG_CHANGE'].map((suffix, index) => ({
  suffix, kind: index === 0 || index === 2 ? 'USER' : 'GUEST',
  baselineCommit: '2c0708f752d747d88a9eda7478326a964d1984fa',
  inputPath: '/owned/pending/' + suffix + '/input.json', pendingPath: '/owned/pending/' + suffix + '/old-pending.json',
  originPath: '/owned/origins/' + suffix + '.properties', originMatched: true, reservationObserved: true,
  zeroEconomics: true, database: 'same-owned-native', quoteExpiryMs: now + 225000, confirmationExpiryMs: now + 225000
}));
test('four originals require exact identities, same database, actual origins and full 225 second budget', () => {
  assert.equal(validateOldPendingBatch(oldBatch(1000), 1000), 226000);
  for (const alter of [e => {e[0].quoteExpiryMs--;}, e => {e[1].kind = 'USER';},
    e => {e[2].originMatched = false;}, e => {e[3].database = 'other';},
    e => {e[0].baselineCommit = 'task67';}, e => {e[1].reservationObserved = false;}]) {
    const entries = oldBatch(1000); alter(entries);
    assert.throws(() => validateOldPendingBatch(entries, 1000));
  }
  assert.throws(() => validateOldPendingBatch(oldBatch(1000).slice(1), 1000));
});

test('real event verdict rejects producer absence, skipped/setup/container errors and duplicate leaves', () => {
  const selection = phaseSelection('m2c', 'green', 'counter-old-pending');
  const cls = selection[0][1], method = selection[0][2][0];
  const event = {className: cls, methodName: method, status: 'SUCCESSFUL', started: true};
  assert.throws(() => validateSelectedEvents(selection, {events: [], containers: []}));
  for (const status of ['ABORTED', 'SKIPPED', 'FAILED'])
    assert.throws(() => validateSelectedEvents(selection, {events: [{...event, status}], containers: []}));
  assert.throws(() => validateSelectedEvents(selection, {events: [event], containers: [{status: 'FAILED'}]}));
  assert.throws(() => validateSelectedEvents(selection, {events: [event, event], containers: []}));
});

test('cleanup ownership fixes uuid/name/label/temporary paths and retains materialization evidence', () => {
  const uuid = '12345678-1234-4234-8234-123456789abc';
  const plan = m2cOwnershipPlan(uuid);
  assert.equal(plan.name, 'codex-counter-m2c-' + uuid);
  assert.equal(plan.label, 'codex.task68.uuid=' + uuid);
  assert.equal(plan.temporary, '/private/tmp/codex-counter-m2c-' + uuid);
  assert.equal(plan.lock, '/tmp/codex-beauty-bm04-native.lock');
  assert.equal(plan.terminateMs, 5000);
  assert.throws(() => m2cOwnershipPlan('../other'));
});

test('literal Java drivers use real mappers/engines and compare old expected origins before delegation', () => {
  const sources = task68DriverSources();
  assert(sources.Task68GoldenMain.includes('getDeclaredField("legacyCheckoutJson")'));
  assert(sources.Task68GoldenMain.includes('CounterEconomicCompatibilityTest.generateBaseline'));
  assert(sources.Task68GoldenMain.includes('CounterEconomicCompatibilityTest.verifyBaseline'));
  assert(!sources.Task68GoldenMain.includes('new ObjectMapper('));
  assert(sources.Task68SelectedMain.includes('launcher.execute(plan'));
  assert(sources.Task68SelectedMain.includes('selectMethod'));
  assert(sources.Task68SelectedMain.includes('executionFinished'));
  assert(sources.Task68OldMain.indexOf('if (!matched)') < sources.Task68OldMain.indexOf('getMethod("main"'));
  assert(sources.Task68OldMain.includes('Class.forName(name, false,'));
});

test('gate rejects old task fallback and absent pins before any process or output consumption', () => {
  for (const gate of [{}, {slice: 'm2a'}, {schemaVersion: 1, approved: true, slice: 'm2c', phase: 'red'},
    {schemaVersion: 1, approved: true, slice: 'm2c', phase: 'prepare', stage: 'materialize-runtime',
      action: 'TEST', selection: []}]) assert.throws(() => validateM2cGate(gate, {}));
});


test('dependency exporter parses only unique exact shop-app header with current versus old output prefixes', () => {
  const header = '[INFO] --- dependency:3.7.0:build-classpath (default-cli) @ shop-app ---';
  const log = header + '\n[INFO] Dependencies classpath:\n[INFO] /cache/external.jar:/old/backend/shop-core/target/classes\n[INFO] BUILD SUCCESS';
  const old = exportedDependencyClasspath(log, '/old');
  assert.deepEqual(old.slice(0, 2), ['/old/backend/shop-app/target/test-classes', '/old/backend/shop-app/target/classes']);
  const current = exportedDependencyClasspath(log, '/current', true);
  assert.deepEqual(current.slice(0, 3), ['/current/backend/shop-app/target/test-classes',
    '/current/backend/shop-app/target/classes', '/current/backend/shop-core/target/test-classes']);
  for (const bad of [log.replace('@ shop-app', '@ shop-core'), log + '\n' + log,
    log.replace('/cache/external.jar:', ':'), log.replace('/cache/external.jar', '*'),
    log.replace('BUILD SUCCESS', 'BUILD FAILURE'), log.replace('Dependencies classpath:', 'different:')])
    assert.throws(() => exportedDependencyClasspath(bad, '/old'));
  assert.throws(() => exportedDependencyClasspath(log.replace('/cache/external.jar', '/old/backend/shop-app/target/classes'), '/old'));
});


function protocolGateFixture() {
  const workspaceRoot = '/reviewed', phase = 'green', stage = 'counter-protocol';
  const selection = phaseSelection('m2c', phase, stage), head = 'b'.repeat(40);
  const uuid = '12345678-1234-4234-8234-123456789abc';
  const tools = Object.fromEntries(['java', 'javac', 'maven', 'git', 'docker', 'ps'].map(name => [name, workspaceRoot + '/bin/' + name]));
  const goldenPins = ['ordinary-v1.json', 'm1-v1.json', 'm2a-user-v1.json', 'm2a-guest-v1.json',
    'm2a-pending-plan-v1.json', 'hashes.json'].map(name => ({name, ...testPin(workspaceRoot + '/accepted/goldens/' + name)}));
  const currentClasspathManifest = workspaceRoot + '/current-classpath.json', runnerToolManifest = workspaceRoot + '/runner-tool-dependencies.json';
  const runtimeManifest = workspaceRoot + '/accepted/runtime-manifest.json', goldenManifest = workspaceRoot + '/accepted/golden-manifest.json';
  const buildToolsManifest = workspaceRoot + '/build-tools.json';
  const archiveLeaves = Array.from({length: 8}, (_, index) => workspaceRoot + '/archive/leaf-' + index);
  const files = [...new Set([...requiredM2cPinPaths(selection, stage).map(p => workspaceRoot + '/' + p),
    ...Object.values(tools), ...goldenPins.map(p => p.path), currentClasspathManifest, runnerToolManifest,
    runtimeManifest, goldenManifest, buildToolsManifest, ...archiveLeaves])].map(path => testPin(path));
  const gate = {schemaVersion: 1, approved: true, slice: 'm2c', phase, stage, action: 'TEST', uuid, head,
    workspaceRoot, stockAuthority: 'PLATFORM', schemaTopology: [], selection,
    properties: m2cSchemaPlan().properties, inputRoots: [workspaceRoot], files, tools,
    currentClasspathManifest, runnerToolManifest, runtimeManifest, goldenManifest, goldenPins, buildToolsManifest,
    timeouts: {oldBuild: 1200, javac: 120, readiness: 90, action: 600, golden: 120, terminate: 5},
    commands: ['git-archive', 'old-offline-test-compile-dependency-classpath', 'current-driver-javac', 'old-overlay-javac',
      'owned-docker-mysql', 'golden-java', 'old-origin-then-producer-java', 'selected-junit-java'],
    derivedOutputs: [{path: 'reports', producer: 'Task68SelectedMain', kind: 'directory', mustBeAbsent: true},
      {path: 'resources', producer: 'accepted-byte-publication', kind: 'directory', mustBeAbsent: true}]};
  return {gate, inventory: {workspaceRoot, head, usedUuid: false, runRoot: workspaceRoot + '/evidence',
    files: Object.fromEntries(files.map(p => [p.path, p])), requiredLeaves: archiveLeaves,
    runtimeAccepted: true, goldensAccepted: true, archiveEntryCount: 8,
    archiveSourceCommit: '531d2a87b98f1d15e5a002a5100c4545ab6280b9',
    archiveDocCommit: '28ebe9f111ede8363194cd0e42d50005de465a39'}};
}

test('a complete accepted protocol gate allows derived directories without imaginary output SHA inputs', () => {
  const {gate, inventory} = protocolGateFixture();
  assert.equal(validateM2cGate(gate, inventory).selection, gate.selection);
  assert(gate.derivedOutputs.every(slot => !Object.hasOwn(slot, 'sha256')));
  for (const mutate of [g => {g.action = 'MATERIALIZE_GOLDENS';}, g => {g.selection = [];},
    g => {g.phase = 'prepare';}, g => {g.schemaTopology = ['platform'];}, g => {g.stockAuthority = 'INVENTORY';},
    g => {g.files.push(g.files[0]);}, g => {g.files[0] = {...g.files[0], path: '/escape.java'};},
    g => {g.derivedOutputs[0].path = '../foreign';}, g => {g.derivedOutputs[0].sha256 = 'a'.repeat(64);}]) {
    const fresh = protocolGateFixture(); mutate(fresh.gate);
    assert.throws(() => validateM2cGate(fresh.gate, fresh.inventory));
  }
});

test('all actual Java/source/class/SQL/resource/archive/launcher pins and fresh root acceptance are mandatory', () => {
  for (const needle of ['CounterEconomicCodec.java', 'CounterEconomicCodec.class',
    'ManualCounterDiscountFixture.java', 'ManualCounterDiscountIT.class', 'V603__counter_campaign_owner_fence.sql',
    'target/classes/schema/counter-campaign-owner-fence-descriptor.json', 'application-testcfg.yml', 'archive/leaf-7',
    'runner-tool-dependencies.json', 'runtime-manifest.json', 'golden-manifest.json']) {
    const {gate, inventory} = protocolGateFixture();
    gate.files = gate.files.filter(pin => !pin.path.includes(needle));
    assert.throws(() => validateM2cGate(gate, inventory), needle);
  }
  for (const change of [i => {i.usedUuid = true;}, i => {i.runtimeAccepted = false;},
    i => {i.goldensAccepted = false;}, i => {i.archiveEntryCount = 7;},
    i => {i.head = 'c'.repeat(40);}, i => {delete i.files[Object.keys(i.files)[0]];}]) {
    const fresh = protocolGateFixture(); change(fresh.inventory);
    assert.throws(() => validateM2cGate(fresh.gate, fresh.inventory));
  }
});

test('signals/deadline termination cannot kill a foreign group or a reused parent PID', () => {
  const uuid = '12345678-1234-4234-8234-123456789abc';
  const record = {uuid, pid: 200, startIdentity: 'Mon Oct 26 10:00:00 2026'};
  const members = [{uuid, pid: 200, pgid: 200, startIdentity: record.startIdentity},
    {uuid, pid: 201, pgid: 200, startIdentity: 'Mon Oct 26 10:00:01 2026'}];
  assert.deepEqual(ownedProcessSignalPlan(record, members, 'SIGTERM'), {pid: -200, signal: 'SIGTERM'});
  assert.deepEqual(ownedProcessSignalPlan(record, members, 'SIGKILL'), {pid: -200, signal: 'SIGKILL'});
  assert.equal(ownedProcessSignalPlan(record, [], 'SIGTERM'), null);
  for (const mutate of [m => {m[0].startIdentity = 'reused';}, m => {m[1].uuid = 'foreign';}, m => {m[1].pgid = 300;}]) {
    const copy = members.map(m => ({...m})); mutate(copy);
    assert.throws(() => ownedProcessSignalPlan(record, copy, 'SIGKILL'));
  }
});


test('genuine old classpaths keep the exact old app prefix and reject Task67/current/installed owners', () => {
  const oldRoot = '/reviewed/task-68-evidence/prepare-12345678/materials/old-source';
  const prefix = [oldRoot + '/backend/shop-app/target/test-classes', oldRoot + '/backend/shop-app/target/classes'];
  const paths = [...prefix, oldRoot + '/backend/shop-core/target/classes', '/private/tmp/codex-beauty-bm01-m2/external.jar'];
  assert.deepEqual(oldClasspathPlan(paths, oldRoot), paths);
  for (const path of ['/current/backend/shop-core/target/classes',
    '/reviewed/task-67-evidence/pre-v9-runtime/beauty-core/target/classes',
    '/private/tmp/codex-beauty-bm01-m2/ai/neargo/shop/shop-core/1/shop-core-1.jar', '', '*'])
    assert.throws(() => oldClasspathPlan([...prefix, path], oldRoot));
  assert.throws(() => oldClasspathPlan([prefix[1], prefix[0]], oldRoot));
  assert.throws(() => oldClasspathPlan([...prefix, prefix[0]], oldRoot));
});


// Fix1 I1/I2 regression source only. These cases are UNEXECUTED in phase A.
const closurePin = path => {
  const bytes = readFileSync(path);
  return {path: realpathSync(path), bytes: bytes.length,
    sha256: createHash('sha256').update(bytes).digest('hex')};
};
function closureFixture() {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'task68-closure-case-')));
  const prefix = join(root, 'current-driver-classes'), project = join(root, 'project-classes');
  const put = (base, name, bytes) => {
    const path = join(base, name); mkdirSync(dirname(path), {recursive: true}); writeFileSync(path, bytes); return path;
  };
  const files = [put(prefix, 'ai/neargo/shop/scenario/Task68SelectedMain.class', 'unexecuted class inventory'),
    put(project, 'ai/neargo/shop/Owner.class', 'unexecuted owner inventory'),
    put(project, 'application-test.yml', 'fixture: true')];
  return {root, prefix, project, put, paths: [prefix, project], pins: files.map(closurePin), files};
}

test('local file origin identity accepts equivalent URI spelling and rejects foreign scheme/authority/escape', () => {
  const fixture = closureFixture();
  try {
    const uri = pathToFileURL(fixture.prefix + '/').href, short = uri.replace('file:///', 'file:/');
    assert.equal(canonicalLocalFileIdentity(uri, fixture.root), realpathSync(fixture.prefix));
    assert.equal(canonicalLocalFileIdentity(short, fixture.root), canonicalLocalFileIdentity(uri, fixture.root));
    assert.notEqual(canonicalLocalFileIdentity(pathToFileURL(fixture.project).href, fixture.root),
      canonicalLocalFileIdentity(uri, fixture.root));
    for (const bad of ['https://example.com/a', 'file://foreign.example/a', 'file://localhost/a', 'file:relative', uri + '?query', uri + '#fragment'])
      assert.throws(() => canonicalLocalFileIdentity(bad, fixture.root));
    const outside = realpathSync(mkdtempSync(join(tmpdir(), 'task68-foreign-origin-')));
    try {
      const escape = join(fixture.root, 'escape'); symlinkSync(outside, escape);
      assert.throws(() => canonicalLocalFileIdentity(pathToFileURL(outside).href, fixture.root));
      assert.throws(() => canonicalLocalFileIdentity(pathToFileURL(escape).href, fixture.root));
    } finally { rmSync(outside, {recursive: true}); }
  } finally { rmSync(fixture.root, {recursive: true}); }
});

test('actual JDK-only old shim origin helper compares file:/ and file:/// without invoking the producer', () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'task68-jdk-identity-case-')));
  try {
    const accepted = join(root, 'accepted'), origin = join(accepted, 'owner classes'), other = join(accepted, 'other');
    const foreign = join(root, 'foreign'), classes = join(root, 'compiled'), empty = join(root, 'empty');
    for (const path of [origin, other, foreign, classes, empty]) mkdirSync(path, {recursive: true});
    symlinkSync(foreign, join(accepted, 'escape'));
    const shim = join(root, 'Task68OldMain.java'); writeFileSync(shim, task68DriverSources().Task68OldMain);
    const probe = join(root, 'OriginIdentityProbe.java');
    writeFileSync(probe, String.raw`import ai.neargo.shop.scenario.Task68OldMain;
import java.nio.file.Path;
import java.util.List;
public final class OriginIdentityProbe {
  interface Checked { void run() throws Exception; }
  static void check(boolean value) { if (!value) throw new AssertionError("identity mismatch"); }
  static void rejects(Checked action) throws Exception {
    boolean rejected = false;
    try { action.run(); } catch (Exception expected) { rejected = true; }
    check(rejected);
  }
  public static void main(String[] args) throws Exception {
    String root = args[0], uri = Path.of(args[1]).toUri().toString();
    String shortUri = "file:" + Path.of(args[1]).toUri().getRawPath();
    check(Task68OldMain.sameLocalOrigin(uri, shortUri, root));
    check(Task68OldMain.sameLocalOrigin(shortUri, uri, root));
    check(!Task68OldMain.sameLocalOrigin(uri, Path.of(args[2]).toUri().toString(), root));
    check(Task68OldMain.canonicalLocalOrigin(uri, root).equals(Path.of(args[1]).toRealPath().toString()));
    for (String bad : List.of("https://example.com/a", "file://foreign.example/a", "file://localhost/a",
        "file:relative", uri + "?q", uri + "#f"))
      rejects(() -> Task68OldMain.sameLocalOrigin(uri, bad, root));
    rejects(() -> Task68OldMain.sameLocalOrigin(uri, Path.of(args[3]).toUri().toString(), root));
    rejects(() -> Task68OldMain.sameLocalOrigin(uri, Path.of(root, "escape").toUri().toString(), root));
    System.out.println("identity-ok");
  }
}
`);
    const javaHome = process.env.JAVA_HOME ?? '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home';
    const compiled = spawnSync(join(javaHome, 'bin/javac'), ['--release', '21', '-proc:none', '-sourcepath', empty,
      '-d', classes, shim, probe], {encoding: 'utf8', timeout: 120000});
    assert.equal(compiled.status, 0, compiled.stderr);
    // Only this JDK-only probe main runs; Task68OldMain.main and all owner/producer paths remain uncalled.
    const result = spawnSync(join(javaHome, 'bin/java'), ['-cp', classes, 'OriginIdentityProbe',
      accepted, origin, other, foreign], {encoding: 'utf8', timeout: 10000});
    assert.equal(result.status, 0, result.stderr); assert.equal(result.stdout.trim(), 'identity-ok');
  } finally { rmSync(root, {recursive: true}); }
});

test('effective accepted classpath closure validates every prefix/base leaf and its bytes', () => {
  const f = closureFixture();
  try {
    const entries = verifyEffectiveClasspath(f.paths, f.pins);
    assert.deepEqual(entries.map(entry => entry.path), f.paths);
    assert.equal(entries[0].definitions[0], 'ai/neargo/shop/scenario/Task68SelectedMain.class');
    writeFileSync(f.files[1], 'different owner bytes');
    assert.throws(() => verifyEffectiveClasspath(f.paths, f.pins));
  } finally { rmSync(f.root, {recursive: true}); }
});

test('extra helper classes, service resources and config files are rejected without deleting them', () => {
  for (const name of ['ai/neargo/shop/ExtraHelper.class', 'META-INF/services/example.Provider', 'application-extra.yml']) {
    const f = closureFixture();
    try {
      const extra = f.put(f.prefix, name, 'unapproved leaf');
      assert.throws(() => verifyEffectiveClasspath(f.paths, f.pins));
      assert(existsSync(extra), 'validation must not delete unexpected files');
    } finally { rmSync(f.root, {recursive: true}); }
  }
});

test('missing accepted files and even unpinned symlink leaves/root aliases close the full runtime closure', () => {
  for (const kind of ['missing', 'leaf-symlink', 'root-symlink']) {
    const f = closureFixture();
    try {
      let paths = f.paths;
      if (kind === 'missing') rmSync(f.files[0]);
      else if (kind === 'leaf-symlink') symlinkSync(f.files[1], join(f.prefix, 'unexpected-link'));
      else { const link = join(f.root, 'prefix-link'); symlinkSync(f.prefix, link); paths = [link, f.project]; }
      assert.throws(() => verifyEffectiveClasspath(paths, f.pins));
    } finally { rmSync(f.root, {recursive: true}); }
  }
});

test('generated current/old prefixes share the full base class/resource uniqueness policy', () => {
  const f = closureFixture();
  try {
    const duplicate = f.put(f.prefix, 'ai/neargo/shop/Owner.class', 'duplicate accepted class');
    assert.throws(() => verifyEffectiveClasspath(f.paths, [...f.pins, closurePin(duplicate)]));
    rmSync(duplicate);
    const oldPrefix = join(f.root, 'old-producer-classes');
    const duplicateConfig = f.put(oldPrefix, 'application-test.yml', 'duplicate accepted config');
    assert.throws(() => verifyEffectiveClasspath([oldPrefix, f.project], [...f.pins, closurePin(duplicateConfig)]));
    const copiedConfig = f.put(f.prefix, 'application-test.yml', 'another accepted config');
    assert.throws(() => verifyEffectiveClasspath(f.paths, [...f.pins, closurePin(copiedConfig)]));
  } finally { rmSync(f.root, {recursive: true}); }
});

test('same exact closure check catches drift before a process and after a process boundary', () => {
  for (const phase of ['before', 'after']) {
    const f = closureFixture();
    try {
      verifyEffectiveClasspath(f.paths, f.pins);
      const drift = f.put(f.project, phase + '-boundary-resource.properties', 'drift');
      assert.throws(() => verifyEffectiveClasspath(f.paths, f.pins));
      assert(existsSync(drift));
    } finally { rmSync(f.root, {recursive: true}); }
  }
});

test('private golden resource root requires the exact produced six-pin inventory before/after launch', () => {
  const f = closureFixture();
  try {
    const names = ['ordinary-v1.json', 'm1-v1.json', 'm2a-user-v1.json', 'm2a-guest-v1.json',
      'm2a-pending-plan-v1.json', 'hashes.json'];
    const resources = join(f.root, 'resources');
    const copies = names.map(name => f.put(resources, 'counter-economics/' + name, 'byte-copy inventory only'));
    const derivedPins = copies.map(closurePin), pins = [...f.pins, ...derivedPins];
    verifyEffectiveClasspath([f.prefix, resources, f.project], pins);
    const extra = f.put(resources, 'META-INF/services/unapproved', 'extra service');
    assert.throws(() => verifyEffectiveClasspath([f.prefix, resources, f.project], pins));
    assert(existsSync(extra)); rmSync(extra); rmSync(copies[0]);
    assert.throws(() => verifyEffectiveClasspath([f.prefix, resources, f.project], pins));
  } finally { rmSync(f.root, {recursive: true}); }
});
