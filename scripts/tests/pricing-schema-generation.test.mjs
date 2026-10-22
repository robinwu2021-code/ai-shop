import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import assert from 'node:assert/strict';
import { test } from 'node:test';

test('pricing JSON validation survives H2 translation for both created and added constraints', () => {
    const dir = mkdtempSync(join(tmpdir(), 'pricing-json-schema-'));
    try {
        const output = join(dir, 'schema.sql');
        writeFileSync(join(dir, 'V1__pricing.sql'), `
CREATE TABLE pricing_fixture (
    id BIGINT NOT NULL,
    draft LONGTEXT NOT NULL,
    snapshot LONGTEXT NOT NULL,
    PRIMARY KEY (id),
    CHECK (JSON_VALID(draft))
);
ALTER TABLE pricing_fixture ADD CONSTRAINT ck_snapshot CHECK (JSON_VALID(snapshot));
`);
        execFileSync('python3', ['backend/scripts/gen-test-schema.py', output, dir]);
        const sql = readFileSync(output, 'utf8');
        assert.match(sql, /CHECK \(\(draft IS JSON\)\)/);
        assert.match(sql, /CONSTRAINT ck_snapshot CHECK \(\(snapshot IS JSON\)\)/);
        assert.doesNotMatch(sql, /JSON_VALID/);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});
