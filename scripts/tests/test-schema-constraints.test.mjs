import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
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


const repository = fileURLToPath(new URL('../../', import.meta.url));
const sha256 = value => createHash('sha256').update(value).digest('hex');

function nativePolicyFixture(change = () => {}) {
    const root = mkdtempSync(join(tmpdir(), 'native-projection-'));
    try {
        const real = JSON.parse(readFileSync(join(repository, 'backend/scripts/h2-fixture-policy.json'), 'utf8'));
        const entry = structuredClone(real.entries.find(value => value.migration.includes('/V544__')));
        for (const name of [entry.migration, entry.descriptor]) {
            const target = join(root, name);
            mkdirSync(dirname(target), { recursive: true });
            writeFileSync(target, readFileSync(join(repository, name)));
        }
        change(root, entry);
        const policy = join(root, 'backend/scripts/h2-fixture-policy.json');
        mkdirSync(dirname(policy), { recursive: true });
        writeFileSync(policy, JSON.stringify({ schemaVersion: 1, purpose: real.purpose, entries: [entry] }));
        const code = [
            'import json, pathlib, runpy, sys',
            "module = runpy.run_path('backend/scripts/gen-test-schema.py')",
            'root = pathlib.Path(sys.argv[1])',
            'migration = root / sys.argv[2]',
            "policies = module['load_projection_policy'](migration.parent, root=root)",
            "kept = module['project_migration'](migration, policies)",
            'print(json.dumps(kept))',
        ].join('\n');
        return JSON.parse(execFileSync('python3', ['-c', code, root, entry.migration], { encoding: 'utf8' }));
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
}

function changeNativeSource(root, entry, suffix) {
    rewriteNativeSource(root, entry, sql => sql + suffix);
}

function rewriteNativeSource(root, entry, rewrite) {
    const file = join(root, entry.migration);
    const sql = rewrite(readFileSync(file, 'utf8'));
    writeFileSync(file, sql);
    entry.sourceSha256 = sha256(sql);
    const descriptorFile = join(root, entry.descriptor);
    const descriptor = JSON.parse(readFileSync(descriptorFile, 'utf8'));
    descriptor.migrationSha256 = entry.sourceSha256;
    const bytes = JSON.stringify(descriptor);
    writeFileSync(descriptorFile, bytes);
    entry.descriptorSha256 = sha256(bytes);
}

test('native capability projection consumes only the exact descriptor-bound statement set', () => {
    assert.deepEqual(nativePolicyFixture(), []);
    assert.throws(() => nativePolicyFixture((root, entry) => {
        entry.sourceSha256 = '0'.repeat(64);
    }));
    assert.throws(() => nativePolicyFixture((root, entry) => {
        entry.descriptorSha256 = '0'.repeat(64);
    }));
    assert.throws(() => nativePolicyFixture((root, entry) => {
        entry.absentTables.push('unrelated_legacy_table');
    }));
    assert.throws(() => nativePolicyFixture((root, entry) => {
        entry.datasource = 'INVENTORY';
    }));
});

test('even matching refreshed hashes cannot authorize legacy mutations or extra native statements', () => {
    for (const suffix of [
        '\nALTER TABLE ord_order ADD COLUMN unreviewed INT;\n',
        '\nINSERT INTO ord_order (order_no) VALUES (\'wrong\');\n',
        '\nCREATE TABLE undeclared_native (id BIGINT);\n',
        '\nDELIMITER $$\nCREATE TRIGGER undeclared BEFORE DELETE ON ord_service_execution_unit '
            + 'FOR EACH ROW BEGIN SIGNAL SQLSTATE \'45000\' SET MESSAGE_TEXT=\'blocked\'; END$$\nDELIMITER ;\n',
    ]) {
        assert.throws(() => nativePolicyFixture((root, entry) => changeNativeSource(root, entry, suffix)));
    }
});

test('legacy object constraints are projected without changing function-looking literal bytes', () => {
    const dir = mkdtempSync(join(tmpdir(), 'json-object-projection-'));
    try {
        const out = join(dir, 'schema.sql');
        writeFileSync(join(dir, 'V1__object.sql'), `
CREATE TABLE object_probe (
    id BIGINT,
    payload JSON NULL,
    label VARCHAR(80) DEFAULT 'JSON',
    literal VARCHAR(80) DEFAULT 'JSON_EXTRACT(payload)',
    CONSTRAINT ck_object CHECK (payload IS NULL OR
        (JSON_VALID(payload) AND JSON_TYPE(payload)='OBJECT'))
);
`);
        execFileSync('python3', ['backend/scripts/gen-test-schema.py', out, dir]);
        const schema = readFileSync(out, 'utf8');
        assert.match(schema, /payload TEXT NULL/);
        assert.match(schema, /payload IS JSON OBJECT/);
        assert.match(schema, /DEFAULT 'JSON'/);
        assert.match(schema, /DEFAULT 'JSON_EXTRACT\(payload\)'/);
        writeFileSync(join(dir, 'V1__object.sql'), `CREATE TABLE unsupported (
    payload TEXT,
    CONSTRAINT ck CHECK (JSON_TYPE(payload)='ARRAY')
);`);
        assert.throws(() => execFileSync('python3', ['backend/scripts/gen-test-schema.py', out, dir]));
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test('primary H2 projection retains legacy tables and marketing fence but cannot claim native installation', () => {
    const dir = mkdtempSync(join(tmpdir(), 'primary-projection-'));
    try {
        const out = join(dir, 'schema.sql');
        execFileSync('python3', ['backend/scripts/gen-test-schema.py', out]);
        const schema = readFileSync(out, 'utf8');
        for (const table of ['ord_order', 'mkt_counter_campaign_fence', 'mkt_campaign', 'pmt_activity',
            'pmt_activity_version', 'pmt_activity_goods', 'pmt_activity_rule', 'pmt_enrollment',
            'pmt_enrollment_goods', 'ord_manual_service_admission', 'ord_manual_service_admission_action']) {
            assert.match(schema, new RegExp(`CREATE TABLE IF NOT EXISTS ${table}\\b`));
        }
        for (const table of ['ord_service_execution_unit', 'ord_service_execution',
            'ord_service_execution_action', 'prd_execution_retention']) {
            assert.doesNotMatch(schema, new RegExp(`CREATE TABLE IF NOT EXISTS ${table}\\b`));
        }
        assert.match(schema, /Native capability absent:/);
        assert.match(schema, /Native trigger projection:/);
        assert.doesNotMatch(schema, /INSERT(?: IGNORE)? INTO (?:flyway_schema_history|inv_flyway_history)/i);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});


test('inventory projection keeps actual legacy owners and excludes only descriptor-bound native claims', () => {
    const dir = mkdtempSync(join(tmpdir(), 'inventory-projection-'));
    try {
        const out = join(dir, 'schema.sql');
        execFileSync('python3', ['backend/scripts/gen-test-schema.py', out,
            join(repository, 'backend/shop-inventory/src/main/resources/db/inventory')]);
        const schema = readFileSync(out, 'utf8');
        for (const table of ['inv_owner', 'inv_reservation', 'inv_reservation_line', 'inv_stock_balance',
            'inv_outbound_order', 'inv_outbound_line', 'inv_ledger', 'inv_refund_restore', 'inv_return_receipt']) {
            assert.match(schema, new RegExp(`CREATE TABLE IF NOT EXISTS ${table}\\b`));
        }
        for (const table of ['inv_execution_retention_claim', 'inv_execution_retention']) {
            assert.doesNotMatch(schema, new RegExp(`CREATE TABLE IF NOT EXISTS ${table}\\b`));
        }
        assert.match(schema, /Native capability absent:/);
        assert.doesNotMatch(schema, /INSERT(?: IGNORE)? INTO (?:flyway_schema_history|inv_flyway_history)/i);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});


test('fixture output removes comment padding while preserving multiline literal trailing bytes', () => {
    const dir = mkdtempSync(join(tmpdir(), 'projection-whitespace-'));
    try {
        const out = join(dir, 'schema.sql');
        writeFileSync(join(dir, 'V1__literal.sql'),
            "CREATE TABLE padded (id INT, text_value TEXT);\n"
            + "INSERT INTO padded VALUES (1, 'first  \nsecond'); -- trailing comment\n");
        execFileSync('python3', ['backend/scripts/gen-test-schema.py', out, dir]);
        const sql = readFileSync(out, 'utf8');
        assert.match(sql, /'first  \nsecond'/);
        assert.doesNotMatch(sql, /;[ \t]+\n/);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});


test('missing bound migration or descriptor fails before an owner can be omitted', () => {
    for (const field of ['migration', 'descriptor']) {
        assert.throws(() => nativePolicyFixture((root, entry) => {
            rmSync(join(root, entry[field]));
        }), error => String(error.stderr).includes('missing artifact'));
    }
});

test('refreshed source hashes cannot hide a missing declared trigger', () => {
    assert.throws(() => nativePolicyFixture((root, entry) => {
        rewriteNativeSource(root, entry, sql => {
            const changed = sql.replace(/CREATE TRIGGER trg_seu_immutable\b[\s\S]*?END\$\$/, '');
            assert.notEqual(changed, sql, 'actual declared trigger must be removed from the fixture');
            return changed;
        });
    }), error => String(error.stderr).includes('incomplete trigger set'));
});

test('refreshed source hashes cannot hide changed declared trigger body bytes', () => {
    assert.throws(() => nativePolicyFixture((root, entry) => {
        rewriteNativeSource(root, entry, sql => {
            const changed = sql.replace('Manual execution immutable original', 'changed immutable original');
            assert.notEqual(changed, sql, 'actual bound trigger literal must change');
            return changed;
        });
    }), error => String(error.stderr).includes('changed trigger definition trg_seu_immutable'));
});
