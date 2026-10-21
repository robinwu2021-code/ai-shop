import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import assert from 'node:assert/strict';
import { test } from 'node:test';

test('H2 fixture keeps table-scoped MySQL unique keys without schema name collisions', () => {
    const dir = mkdtempSync(join(tmpdir(), 'activity-schema-'));
    try {
        const out = join(dir, 'schema.sql');
        writeFileSync(join(dir, 'V1__tables.sql'), `
CREATE TABLE pay_commands (
    id BIGINT NOT NULL,
    source_no VARCHAR(64) NOT NULL,
    payload LONGTEXT NULL,
    start_at DATETIME(3) NULL,
    CONSTRAINT ck_grid CHECK (MICROSECOND(start_at)=0),
    CONSTRAINT ck_json_payload CHECK (payload IS NULL OR JSON_VALID(payload)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_command (source_no)
);
CREATE TABLE core_commands (
    id BIGINT NOT NULL,
    source_no VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_command (source_no)
);
ALTER TABLE core_commands ADD COLUMN snapshot JSON NULL;
ALTER TABLE core_commands ADD CONSTRAINT ck_nonnegative CHECK (id >= 0);
ALTER TABLE core_commands DROP CHECK ck_nonnegative;
ALTER TABLE core_commands ADD CONSTRAINT ck_nonnegative CHECK (id >= 1);
CREATE TABLE independent (
    id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_independent (id)
);
`);
        execFileSync('python3', ['backend/scripts/gen-test-schema.py', out, dir]);
        const schema = readFileSync(out, 'utf8');
        assert.match(schema, /CONSTRAINT pay_commands_uk_command UNIQUE \(source_no\)/);
        assert.match(schema, /CONSTRAINT core_commands_uk_command UNIQUE \(source_no\)/);
        assert.match(schema, /CONSTRAINT uk_independent UNIQUE \(id\)/);
        assert.match(schema, /CONSTRAINT ck_nonnegative CHECK \(id >= 1\)/);
        assert.equal([...schema.matchAll(/CONSTRAINT ck_nonnegative CHECK/g)].length,1);
        assert.doesNotMatch(schema, /CHECK \(id >= 0\)/);
        assert.match(schema, /snapshot TEXT NULL/);
        assert.match(schema, /payload IS NULL OR \(payload IS JSON\)/);
        assert.doesNotMatch(schema, /JSON_VALID\(/);
        assert.match(schema, /EXTRACT\(MICROSECOND FROM start_at\)=0/);
        assert.doesNotMatch(schema, /snapshot JSON/);
        const names = [...schema.matchAll(/CONSTRAINT (\w+) UNIQUE/g)].map(match => match[1]);
        assert.equal(new Set(names).size, 3);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});
