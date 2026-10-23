import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import assert from 'node:assert/strict';
import { test } from 'node:test';

test('constant JSON permission appends remain idempotent in the generated H2 schema', () => {
    const dir = mkdtempSync(join(tmpdir(), 'print-permission-schema-'));
    try {
        const output = join(dir, 'schema.sql');
        writeFileSync(join(dir, 'V1__roles.sql'), `
CREATE TABLE mch_role (id BIGINT PRIMARY KEY, perms TEXT NOT NULL, role_code VARCHAR(32));
UPDATE mch_role SET perms=JSON_ARRAY_APPEND(perms,'$','biz:print:read') WHERE role_code='MANAGER' AND JSON_CONTAINS(perms,JSON_QUOTE('biz:print:read'))=0;
`);
        execFileSync('python3', ['backend/scripts/gen-test-schema.py', output, dir]);
        const sql = readFileSync(output, 'utf8');
        assert.doesNotMatch(sql, /JSON_ARRAY_APPEND|JSON_CONTAINS|JSON_QUOTE/);
        assert.match(sql, /LOCATE\('"biz:print:read"',perms\)=0/);
        assert.match(sql, /CASE WHEN TRIM\(perms\)='\[\]' THEN '' ELSE ',' END/);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});
