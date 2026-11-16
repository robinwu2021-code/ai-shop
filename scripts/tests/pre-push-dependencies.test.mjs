import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, realpathSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import assert from 'node:assert/strict';

const script = new URL('../link-head-dependencies.sh', import.meta.url).pathname;
test('clean HEAD resolves an OPS private package before running shared guards', (t) => {
  const temp = mkdtempSync(join(tmpdir(), 'head workspace dependencies '));
  t.after(() => rmSync(temp, { recursive: true, force: true }));
  const source = join(temp, 'source');
  const head = join(temp, 'head');
  for (const scope of ['', 'b-app', 'c-app', 'ops-web']) {
    mkdirSync(join(source, scope, 'node_modules'), { recursive: true });
    mkdirSync(join(head, scope), { recursive: true });
  }
  const pkg = join(source, 'ops-web/node_modules/opencc-js');
  mkdirSync(pkg);
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({ type: 'module', exports: { './cn2t': './cn2t.js' } }));
  writeFileSync(join(pkg, 'cn2t.js'), 'export const Converter = () => "private-dependency";');
  const linked = spawnSync('bash', [script, source, head], { encoding: 'utf8' });
  assert.equal(linked.status, 0, linked.stderr);
  const imported = spawnSync(process.execPath, ['--input-type=module', '-e', 'import { Converter } from "opencc-js/cn2t"; console.log(Converter());'], { cwd: join(head, 'ops-web'), encoding: 'utf8' });
  assert.equal(imported.status, 0, imported.stderr);
  assert.equal(imported.stdout.trim(), 'private-dependency');
  for (const scope of ['', 'b-app', 'c-app', 'ops-web']) {
    assert.equal(realpathSync(join(head, scope, 'node_modules')), realpathSync(join(source, scope, 'node_modules')));
  }
});
test('pre-push wires workspace dependencies before generated docs and shared guards', () => {
  const hook = readFileSync(new URL('../../.githooks/pre-push', import.meta.url), 'utf8');
  const wiring = hook.indexOf('gate_run bash scripts/link-head-dependencies.sh');
  assert.ok(wiring >= 0);
  assert.ok(wiring < hook.indexOf('gate_run node scripts/check-generated-docs.mjs'));
  assert.ok(wiring < hook.indexOf('gate_run node scripts/check-shared-guards.mjs'));
});
