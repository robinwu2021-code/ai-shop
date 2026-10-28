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


// Phase A authors these cases; execution belongs to the unified test phase.
function migrationStatements(sql) {
    const loader = [
        'import json, runpy, sys',
        "module = runpy.run_path('backend/scripts/gen-test-schema.py')",
        "print(json.dumps(list(module['sql_statements'](sys.stdin.read()))))",
    ].join('\n');
    return JSON.parse(execFileSync('python3', ['-c', loader], { input: sql, encoding: 'utf8' }));
}

test('SQL statement boundaries preserve quoted semicolons, escapes and comment text', () => {
    const sql = String.raw`
-- a comment ; must not create a statement
CREATE TABLE probe (
    id BIGINT,
    note VARCHAR(80) DEFAULT 'it''s; -- still data',
    other VARCHAR(80) DEFAULT "double; # still data",
    escaped VARCHAR(80) DEFAULT 'backslash\';still data',
    \`semi;identifier\` BIGINT
) COMMENT='common service order; no Pay or money ledger';
INSERT INTO probe (note) VALUES ('line one;
-- line two remains data'); # another comment ;
SELECT/* a block ; and -- comment */1;
`;
    // Backticks are data in the SQL fixture, not JavaScript template delimiters.
    const statements = migrationStatements(sql.replaceAll('\\`', '`'));
    assert.equal(statements.length, 3);
    assert.match(statements[0], /'it''s; -- still data'/);
    assert.match(statements[0], /"double; # still data"/);
    assert.match(statements[0], /`semi;identifier`/);
    assert.match(statements[0], /common service order; no Pay or money ledger/);
    assert.match(statements[1], /line one;\n-- line two remains data/);
    assert.match(statements[2], /^SELECT\s+1$/);
});

test('custom delimiters keep every trigger branch in its own statement', () => {
    const statements = migrationStatements(`
CREATE TABLE probe (id BIGINT);
DELIMITER $$ -- client directive is not SQL
CREATE TRIGGER preserve_probe BEFORE UPDATE ON probe FOR EACH ROW
BEGIN
    IF NEW.id <> OLD.id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='immutable; -- reason';
    END IF;
END$$
DELIMITER ;
INSERT INTO probe VALUES (1);
`);
    assert.equal(statements.length, 3);
    assert.match(statements[1], /^CREATE TRIGGER preserve_probe/);
    assert.match(statements[1], /END IF;\s+END$/);
    assert.match(statements[1], /immutable; -- reason/);
    assert.equal(statements[2], 'INSERT INTO probe VALUES (1)');
});

test('real frozen manual execution migration retains all six compound triggers', () => {
    const migration = readFileSync(new URL(
        '../../backend/shop-app/src/main/resources/db/migration/V544__manual_service_execution_authority.sql',
        import.meta.url), 'utf8');
    const statements = migrationStatements(migration);
    assert.equal(statements.filter(statement => /^CREATE TRIGGER\b/i.test(statement)).length, 6);
    assert.equal(statements.some(statement => /^(?:IF|END|SIGNAL|DELIMITER)\b/i.test(statement)), false);
});

test('malformed quote, comment or custom-delimited body fails before fixture publication', () => {
    for (const sql of [
        "SELECT 'unfinished;",
        'SELECT 1; /* unfinished',
        'DELIMITER $$\nCREATE TRIGGER broken BEFORE UPDATE ON t FOR EACH ROW BEGIN SELECT 1;',
        'SELECT 1\nDELIMITER $$\n',
        '/*!50000 CREATE TABLE conditional (id BIGINT) */;',
    ]) {
        assert.throws(() => migrationStatements(sql));
    }
    assert.deepEqual(migrationStatements('SELECT 1--2; SELECT 3;'), ['SELECT 1--2', 'SELECT 3']);
});
