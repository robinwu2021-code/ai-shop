import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, realpathSync, rmSync} from 'node:fs';
import {resolve} from 'node:path';
import {spawnSync} from 'node:child_process';
import {phaseSelection, inspectReport, frozenMigrations, aggregatePom, requireAssertionFailure} from '../verify-personnel-operator.mjs';

test('four exact phases use only the finite current reactor suites', () => {
  for (const phase of ['red', 'green', 'ablation', 'restored']) {
    const selected = phaseSelection(phase);
    assert(selected.includes('PersonnelOperatorFactsIT'));
    assert(selected.includes('BoundInternalProtocolTest'));
    assert(!selected.includes('*'));
  }
  for (const phase of ['', 'GREEN', 'unknown', undefined]) assert.throws(() => phaseSelection(phase));
  assert.equal(frozenMigrations.length, 2);
});

test('bad CLI phase or extra flag rejects before resource tools resolve', () => {
  for (const args of [[], ['--phase=unknown'], ['--phase=green', '--all'], ['--mode=green']]) {
    const child = spawnSync(process.execPath, ['scripts/verify-personnel-operator.mjs', ...args], {
      encoding:'utf8', env:{PATH:''}
    });
    assert.notEqual(child.status, 0); assert.match(child.stderr, /invalid Task62 phase/);
    assert.doesNotMatch(child.stderr, /ENOENT|docker|limactl/);
  }
});

test('XML acceptance requires exact methods, nonzero tests, no errors or skipped tests', () => {
  const xml = '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="expiry"/></testsuite>';
  assert.equal(inspectReport(xml, ['expiry']).tests, 1);
  for (const changed of [xml.replace('tests="1"','tests="0"'), xml.replace('skipped="0"','skipped="1"'),
    xml.replace('failures="0"','failures="1"'), xml.replace('errors="0"','errors="1"'),
    xml.replace('name="expiry"','name="expiryWrong"')]) assert.throws(() => inspectReport(changed, ['expiry']));
  assert.throws(() => inspectReport('<testsuite/>', []));
});

test('temporary reactor resolves only the exact current worktree modules', () => {
  const directory = mkdtempSync('/tmp/codex-personnel-operator-pom-test-');
  try {
    const pom = aggregatePom(directory);
    const modules = [...pom.matchAll(/<module>([^<]+)<\/module>/g)].map(m => realpathSync(resolve(directory,m[1])));
    assert.deepEqual(modules, ['backend','backend-plugins/shop-industry-beauty'].map(p => realpathSync(p)));
  } finally { rmSync(directory, {recursive:true,force:true}); }
});

test('expiry RED must be the exact testcase own assertion rather than a later failure or error', () => {
  const own = '<testcase name="expiry"><failure message="boundary"/></testcase>';
  assert.doesNotThrow(() => requireAssertionFailure(own, 'expiry'));
  for (const xml of ['<testcase name="expiry"/>' + '<testcase name="other"><failure/></testcase>',
    '<testcase name="expiry"></testcase><testcase name="other"><failure/></testcase>',
    '<testcase name="expiry"><error/></testcase>', '<testcase name="expiryWrong"><failure/></testcase>']) {
    assert.throws(() => requireAssertionFailure(xml, 'expiry'));
  }
});
