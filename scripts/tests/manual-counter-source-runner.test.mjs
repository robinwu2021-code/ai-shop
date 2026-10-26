import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {phaseSelection, inspectReport, requiredPinPaths, assertionRedPhase, historicalClasspath, stageSchemaVersion} from '../verify-manual-counter-source.mjs';

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
    + 'V542__shared_manual_order_customer_party.sql'));
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
    assert(pins.some(path => path.endsWith('/V542__shared_manual_order_customer_party.sql')));
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
