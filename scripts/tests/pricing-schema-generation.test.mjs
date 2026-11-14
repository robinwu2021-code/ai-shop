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

test('the unified offline-receipt migration replaces its old check without dropping other invariants', () => {
    const dir = mkdtempSync(join(tmpdir(), 'receipt-schema-'));
    try {
        const output = join(dir, 'schema.sql');
        writeFileSync(join(dir, 'V1__receipt.sql'), `
CREATE TABLE sv_recharge (
    id BIGINT PRIMARY KEY,
    status VARCHAR(32), collection_method VARCHAR(32), actual_amount_minor BIGINT,
    principal_minor BIGINT, receipt_ref VARCHAR(32), receipt_digest VARCHAR(64),
    CHECK (collection_method IN ('CASH','TRANSFER')),
    CHECK (principal_minor > 0)
);
`);
        const migration = readFileSync(
            'backend/shop-app/src/main/resources/db/migration/V538__stored_value_offline_collection.sql', 'utf8');
        writeFileSync(join(dir, 'V2__offline.sql'), migration);
        execFileSync('python3', ['backend/scripts/gen-test-schema.py', output, dir]);
        const sql = readFileSync(output, 'utf8');
        assert.match(sql, /ck_sv_collection_receipt CHECK/);
        assert.match(sql, /MERCHANT_OFFLINE/);
        assert.match(sql, /CHECK \(principal_minor > 0\)/);
        // H2 literal IN checks are emitted as nullable CASE expressions.
        assert.equal((sql.match(/CASE collection_method WHEN 'CASH' THEN TRUE WHEN 'TRANSFER' THEN TRUE WHEN 'MERCHANT_OFFLINE' THEN TRUE/g) ?? []).length, 1);
        assert.doesNotMatch(sql, /collection_method IN/);
        writeFileSync(join(dir, 'V2__offline.sql'), migration.replace('SELECT 1', 'DELETE FROM sv_recharge'));
        assert.throws(() => execFileSync('python3', ['backend/scripts/gen-test-schema.py', output, dir],
            { stdio: 'pipe' }));
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test('MODIFY accepts the optional COLUMN keyword and preserves nullability', () => {
    const dir = mkdtempSync(join(tmpdir(), 'modify-schema-'));
    try {
        const output = join(dir, 'schema.sql');
        writeFileSync(join(dir, 'V1__modify.sql'), `
CREATE TABLE modify_fixture (
    id BIGINT PRIMARY KEY,
    user_no VARCHAR(32) NOT NULL
);
ALTER TABLE modify_fixture MODIFY user_no VARCHAR(64) NULL;
ALTER TABLE modify_fixture ADD customer_no VARCHAR(64) NULL;
`);
        execFileSync('python3', ['backend/scripts/gen-test-schema.py', output, dir]);
        assert.match(readFileSync(output, 'utf8'), /user_no VARCHAR\(64\) NULL/);
        assert.match(readFileSync(output, 'utf8'), /customer_no VARCHAR\(64\) NULL/);
    } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('the unified member selection migration replaces only its identification-purpose check', () => {
    const dir = mkdtempSync(join(tmpdir(), 'selection-schema-'));
    try {
        const output = join(dir, 'schema.sql');
        writeFileSync(join(dir, 'V1__selection.sql'), `
CREATE TABLE mbr_identity_ticket (
    id BIGINT PRIMARY KEY,
    purpose VARCHAR(32), source_ticket_hash VARCHAR(64), customer_no VARCHAR(64),
    user_no VARCHAR(64), entity_no VARCHAR(64), store_no VARCHAR(64), actor_no VARCHAR(64),
    CHECK (purpose='CUSTOMER_REFERENCE' AND source_ticket_hash IS NOT NULL),
    CHECK (id > 0)
);
`);
        const migration = readFileSync(
            'backend/shop-app/src/main/resources/db/migration/V539__stored_value_customer_identity.sql', 'utf8');
        writeFileSync(join(dir, 'V2__selection.sql'), migration.slice(migration.indexOf('SET @sv_selection_constraint')));
        execFileSync('python3', ['backend/scripts/gen-test-schema.py', output, dir]);
        const sql = readFileSync(output, 'utf8');
        assert.match(sql, /ck_mbr_identification_purpose CHECK/);
        assert.match(sql, /CUSTOMER_SELECTION/);
        assert.match(sql, /CHECK \(id > 0\)/);
        assert.equal((sql.match(/CHECK.*purpose=/g) ?? []).length, 0);
    } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('ADD whitespace cannot turn unique keys and indexes into columns', () => {
    const dir = mkdtempSync(join(tmpdir(), 'constraint-schema-'));
    try {
        const output = join(dir, 'schema.sql');
        writeFileSync(join(dir, 'V1__constraint.sql'), `
CREATE TABLE constraint_fixture (
    id BIGINT PRIMARY KEY,
    code VARCHAR(32)
);
ALTER TABLE constraint_fixture ADD  UNIQUE KEY uk_code (code);
ALTER TABLE constraint_fixture ADD\n INDEX idx_code (code);
`);
        execFileSync('python3', ['backend/scripts/gen-test-schema.py', output, dir]);
        const sql = readFileSync(output, 'utf8');
        assert.match(sql, /CONSTRAINT uk_code UNIQUE \(code\)/);
        assert.doesNotMatch(sql, /INDEX idx_code/);
    } finally { rmSync(dir, { recursive: true, force: true }); }
});
