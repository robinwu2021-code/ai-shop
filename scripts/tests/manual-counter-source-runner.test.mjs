import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {phaseSelection, inspectReport, requiredPinPaths, assertionRedPhase} from '../verify-manual-counter-source.mjs';

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
    ['--slice=m2b', '--phase=green'], ['--slice=m2a', '--phase=UNKNOWN']]) {
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
