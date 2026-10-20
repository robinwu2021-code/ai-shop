import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateFiles, validateUpdate } from './points-ops.mjs';
const head = 'a'.repeat(40), base = 'b'.repeat(40);
test('accepts points files and rejects unrelated changes', () => {
  validateFiles(['ops-web/app/points/types-tab.tsx']);
  assert.throws(() => validateFiles(['backend/shop-core/unrelated.java']));
});
test('B/C remain forbidden even if accidentally added to the manifest', () => {
  for (const f of ['b-app/src/api/endpoints.ts', 'c-app/src/pages/orders/index.vue']) {
    assert.throws(() => validateFiles([f], [f]));
  }
});
test('requires one existing canonical target and exactly HEAD', () => {
  assert.equal(validateUpdate(`HEAD ${head} refs/heads/codex/ai-shop-codex ${base}`, head), base);
  assert.throws(() => validateUpdate(`HEAD ${head} refs/heads/main ${base}`, head));
  assert.throws(() => validateUpdate(`HEAD ${base} refs/heads/codex/ai-shop-codex ${base}`, head));
  assert.throws(() => validateUpdate(`HEAD ${head} refs/heads/codex/ai-shop-codex ${'0'.repeat(40)}`, head));
  assert.throws(() => validateUpdate(`HEAD ${head} refs/heads/codex/ai-shop-codex ${base}\nHEAD ${head} refs/heads/other ${base}`, head));
});
test('empty releases and removed/renamed unrelated paths cannot pass', () => {
  assert.throws(() => validateFiles([]));
  assert.throws(() => validateFiles(['ops-web/app/points/types-tab.tsx', 'b-app/deleted.vue']));
});
test('CLI executes validation, including when launched through a symlink path', async () => {
  const { spawnSync } = await import('node:child_process');
  const { mkdtempSync, symlinkSync, rmSync } = await import('node:fs');
  const { tmpdir } = await import('node:os');
  const { join } = await import('node:path');
  const { fileURLToPath } = await import('node:url');
  const dir = mkdtempSync(join(tmpdir(), 'points-cli-'));
  try {
    const link = join(dir, 'check.mjs');
    symlinkSync(fileURLToPath(new URL('./points-ops.mjs', import.meta.url)), link);
    const result = spawnSync(process.execPath, [link], { input: '', encoding: 'utf8' });
    assert.notEqual(result.status, 0, 'invalid input must fail; a no-op CLI must never pass');
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
