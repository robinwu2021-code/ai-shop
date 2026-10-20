import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import assert from 'node:assert/strict';

test('compile gate renders the verified ref under nounset before creating a worktree', () => {
    const source = readFileSync(new URL('../check-head-compiles.sh', import.meta.url), 'utf8');
    const line = source.split('\n').find(value => value.startsWith('echo "→ 从 '));
    assert.ok(line, 'the gate must identify its verified source');
    const result = spawnSync('bash', ['-uc', line], {
        env: { ...process.env, REF: 'HEAD', VERIFIED_SHA: 'abc123', WT: '/tmp/gate-test' },
        encoding: 'utf8',
    });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /HEAD（abc123）/);
});
