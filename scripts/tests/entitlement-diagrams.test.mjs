import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import assert from 'node:assert/strict';

// The real generator must include new pay tables, rather than only increasing its headline table count.
test('published catalog tables and command storage remain visible in generated ER documentation', () => {
    execFileSync(process.execPath, ['scripts/gen-erd.mjs'], { stdio: 'pipe' });
    const doc = readFileSync(new URL('../../docs/technical/reference/数据库-ER图.md', import.meta.url), 'utf8');
    for (const table of ['ent_template', 'ent_template_version', 'pay_ent_command_dedup']) {
        assert.ok(doc.includes(`| \`${table}\` |`), `${table} disappeared from domain diagrams`);
    }
    const graph = readFileSync(new URL('../../docs/technical/diagrams/db-ent.svg', import.meta.url), 'utf8');
    assert.match(graph, />template_no<\/text>/);
    assert.match(graph, />current_version_no<\/text>/);
});
