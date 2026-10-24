import {test} from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {spawnSync} from 'node:child_process';
import {validateSelection, inspectReport, verifiedClasspath, startBoundFaultProxy} from '../verify-beauty-bm04-wire.mjs';

const cases = {
  owners: 'CheckoutCashPermissionFlowTest#ownerWildcardResolvesActualAccountButDelegationIsNotCurrentCashAuthorization',
  embedded: 'BeautyBm04EmbeddedHttpIT#realTokensRevocationNamespaceAndDeletedParentAreEnforced',
  standalone: 'BeautyBm04StandaloneHttpIT#privateServerUsesRealHostOwnersAndHasNoLocalOwnerFallback',
  aliases: 'WorkOrderScopeIT#openAliasesShareCanonicalIntentAndPreserveOriginalProofReplay'
};

test('only exact class and method allowlists are accepted before any provisioning', () => {
  for (const [mode, valid] of Object.entries(cases)) {
    assert.deepEqual(validateSelection(mode, valid).selectors, [valid]);
    for (const value of [undefined, '', ',', valid + ',', 'UnknownIT', valid + 'Typo', valid + '+typo',
      'WorkOrderScopeIT#unknown', 'WorkOrderScopeIT#', ' WorkOrderScopeIT', 'WorkOrderScopeIT,WorkOrderScopeIT']) {
      assert.throws(() => validateSelection(mode, value));
    }
  }
  assert.throws(() => validateSelection('wrong', 'WorkOrderScopeIT'));
  assert.throws(() => validateSelection('embedded', 'WorkOrderScopeIT'));
});

test('invalid CLI selectors reject with an empty executable PATH before resource tools', () => {
  const child = spawnSync(process.execPath, ['scripts/verify-beauty-bm04-wire.mjs', '--mode=aliases'], {
    encoding: 'utf8', env: {PATH: '', BEAUTY_BM04_TESTS: 'WorkOrderScopeIT#typo'}
  });
  assert.notEqual(child.status, 0);
  assert.match(child.stderr, /invalid Task59 selector/);
  assert.doesNotMatch(child.stderr, /ENOENT|docker|limactl/);
});

test('fresh XML includes selected parameterized methods with zero skips', () => {
  const xml = '<testsuite tests="2" failures="0" errors="0" skipped="0">'
    + '<testcase classname="A" name="race(boolean)[1]"/><testcase classname="A" name="race(boolean)[2]"/></testsuite>';
  assert.equal(inspectReport(xml, ['race']).tests, 2);
  for (const invalid of [xml.replace('tests="2"', 'tests="0"'), xml.replace('skipped="0"', 'skipped="1"'),
    xml.replace('failures="0"', 'failures="1"'), xml.replaceAll('race(boolean)', 'wrong(boolean)'), '<testsuite/>']) {
    assert.throws(() => inspectReport(invalid, ['race']));
  }
});

test('resolved child classpaths enforce physical ownership before launch', () => {
  const host = verifiedClasspath('standalone', '/repo/backend/shop-app', '/cache/beauty-web-1.jar:/cache/beauty-channel-1.jar');
  assert.match(host, /shop-app\/target\/test-classes/);
  assert.throws(() => verifiedClasspath('standalone', '/repo/backend/shop-app', '/cache/beauty-core-1.jar'));
  assert.throws(() => verifiedClasspath('server', '/repo/beauty-server', '/cache/shop-core-1.jar'));
  assert.throws(() => verifiedClasspath('server', '/repo/beauty-server', ''));
  assert.match(verifiedClasspath('server', '/repo/beauty-server', '/cache/beauty-core-1.jar'), /beauty-core/);
});

test('fault proxy drops only after owner acceptance and never records private request or response bodies', async () => {
  let committed = 0;
  const owner = http.createServer(async (request, response) => {
    for await (const ignored of request) { /* Consume the exact body before committing. */ }
    committed++; response.end('{"privateProof":"owner-secret"}');
  });
  await new Promise(resolve => owner.listen(0, '127.0.0.1', resolve));
  const events = [], proxy = await startBoundFaultProxy('http://127.0.0.1:' + owner.address().port, 'platform', events);
  try {
    proxy.drop('/internal/prepare');
    await assert.rejects(fetch(proxy.url + '/internal/prepare', {method: 'POST', body: 'cr1_PRIVATE'}));
    assert.equal(committed, 1); assert.equal(events[0].dropped, true); assert.equal(events[0].status, 200);
    assert.equal((await fetch(proxy.url + '/internal/prepare', {method: 'POST', body: 'cr1_PRIVATE'})).status, 200);
    assert.equal(committed, 2); assert.equal(events[1].dropped, false);
    proxy.block(true);
    await assert.rejects(fetch(proxy.url + '/internal/prepare'));
    assert.equal(committed, 2);
    assert.doesNotMatch(JSON.stringify(events), /cr1_PRIVATE|owner-secret|privateProof/);
  } finally { await proxy.close(); owner.closeAllConnections(); await new Promise(resolve => owner.close(resolve)); }
});

test('deployment command stays deferred before resource resolution unless unified gate is released', () => {
  const child = spawnSync(process.execPath, ['scripts/verify-beauty-bm04-wire.mjs', '--mode=standalone'], {
    encoding: 'utf8', env: {PATH: '', BEAUTY_BM04_TESTS: 'BeautyBm04StandaloneHttpIT'}
  });
  assert.notEqual(child.status, 0); assert.match(child.stderr, /DEFERRED/);
  assert.doesNotMatch(child.stderr, /ENOENT|docker|limactl/);
});

test('owner mode rejects cross-mode and unknown methods before executable resolution', () => {
  for (const selected of ['BeautySourceCheckoutIT#typo', 'CheckoutCashPermissionFlowTest#', 'WorkOrderScopeIT',
    'BeautySourceCheckoutIT+CheckoutCashPermissionFlowTest', 'BeautySourceCheckoutIT,']) {
    const child = spawnSync(process.execPath, ['scripts/verify-beauty-bm04-wire.mjs', '--mode=owners'], {
      encoding: 'utf8', env: {PATH: '', BEAUTY_BM04_TESTS: selected}
    });
    assert.notEqual(child.status, 0); assert.match(child.stderr, /invalid Task59 selector/);
    assert.doesNotMatch(child.stderr, /ENOENT|docker|limactl/);
  }
});
