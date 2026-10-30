import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, realpathSync, rmSync} from 'node:fs';
import {resolve} from 'node:path';
import {spawnSync} from 'node:child_process';
import {phaseSelection, inspectReport, migrationPaths, aggregatePom, requireAssertionFailure} from '../verify-manual-service-admission.mjs';

test('four exact phases use only the finite current reactor suites', () => {
  for (const phase of ['red', 'green']) {
    const selected = phaseSelection(phase);
    assert(selected.includes('ManualStockDispositionIT'));
    assert(selected.includes('BoundInternalProtocolTest'));
    assert(!selected.includes('*'));
  }
  for(const phase of ['ablation','restored']) assert.equal(phaseSelection(phase),
    'ManualServiceFinancialIsolationIT#manualPaidIsExcludedBeforeSettlementPageLimit');
  for (const phase of ['r1-red','r1-green']) {
    const selected = phaseSelection(phase);
    assert(selected.includes('ManualServiceFinancialIsolationIT#importedCashRecoveryRejectsManualBeforePayOrResourceEffects+'));
    assert(selected.includes('importedStockObligationRejectsManualBeforeOriginalReleaseOrRestore'));
    assert(selected.includes('fundedRefundRecoveryStillDispatchesAndReleasesOriginalOutsideCoreLocks'));
    assert(selected.endsWith('RemoteManualServiceAdmissionPortTest#wrongDigestOrMalformedResultIsUnavailable'));
    assert.equal(selected.includes('manualOrderCannotReachPayOrRefund'), phase === 'r1-green');
    assert(!selected.includes('manualPaidIsExcludedBeforeSettlementPageLimit'));
  }
  for (const phase of ['r2-red','r2-green']) {
    assert.equal(phaseSelection(phase), 'ManualServiceFinancialIsolationIT#fullPointsRefundTerminalStillRecoversPreparedOriginalStock'
      + (phase === 'r2-green' ? '+importedStockObligationRejectsManualBeforeOriginalReleaseOrRestore' : ''));
    assert(!phaseSelection(phase).includes('Remote'));
  }
  for (const phase of ['', 'GREEN', 'unknown', undefined]) assert.throws(() => phaseSelection(phase));
  assert.equal(migrationPaths.length, 2);
});

test('bad CLI phase or extra flag rejects before resource tools resolve', () => {
  for (const args of [[], ['--phase=unknown'], ['--phase=green', '--all'], ['--mode=green']]) {
    const child = spawnSync(process.execPath, ['scripts/verify-manual-service-admission.mjs', ...args], {
      encoding:'utf8', env:{PATH:''}
    });
    assert.notEqual(child.status, 0); assert.match(child.stderr, /invalid Task64 phase/);
    assert.doesNotMatch(child.stderr, /ENOENT|docker|limactl/);
  }
});

test('XML acceptance requires exact methods, nonzero tests, no errors or skipped tests', () => {
  const xml = '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="selected"/></testsuite>';
  assert.equal(inspectReport(xml, ['selected']).tests, 1);
  for (const changed of [xml.replace('tests="1"','tests="0"'), xml.replace('skipped="0"','skipped="1"'),
    xml.replace('failures="0"','failures="1"'), xml.replace('errors="0"','errors="1"'),
    xml.replace('name="selected"','name="selectedWrong"')]) assert.throws(() => inspectReport(changed, ['selected']));
  assert.throws(() => inspectReport('<testsuite/>', []));
});

test('temporary reactor resolves only the exact current worktree modules', () => {
  const directory = mkdtempSync('/tmp/codex-manual-service-admission-pom-test-');
  try {
    const pom = aggregatePom(directory);
    const modules = [...pom.matchAll(/<module>([^<]+)<\/module>/g)].map(m => realpathSync(resolve(directory,m[1])));
    assert.deepEqual(modules, ['backend','backend-plugins/shop-industry-beauty'].map(p => realpathSync(p)));
  } finally { rmSync(directory, {recursive:true,force:true}); }
});

test('selected RED must be the exact testcase own assertion rather than a later failure or error', () => {
  const own = '<testcase name="selected"><failure message="boundary"/></testcase>';
  assert.doesNotThrow(() => requireAssertionFailure(own, 'selected'));
  for (const xml of ['<testcase name="selected"/>' + '<testcase name="other"><failure/></testcase>',
    '<testcase name="selected"></testcase><testcase name="other"><failure/></testcase>',
    '<testcase name="selected"><error/></testcase>', '<testcase name="selectedWrong"><failure/></testcase>']) {
    assert.throws(() => requireAssertionFailure(xml, 'selected'));
  }
});

test('setup errors remain diagnostic even in a RED phase',()=>{
  const xml='<testsuite tests="1" failures="0" errors="1" skipped="0"><testcase name="selected"><error/></testcase></testsuite>';
  assert.throws(()=>inspectReport(xml,['selected'],true));
});
