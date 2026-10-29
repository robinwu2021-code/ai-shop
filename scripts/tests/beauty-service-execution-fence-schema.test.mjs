import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createHash } from 'node:crypto';
import { render } from '../gen-beauty-service-execution-fence-schema.mjs';
import { AUDIT } from '../lib/ddl.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const truth = JSON.parse(readFileSync(`${root}docs/technical/design/schema/beauty-service-execution-fence-schema.json`));

test('exact single V10 fence and owner descriptor deterministically render together', () => {
  const output = render(truth);
  assert.deepEqual(render(structuredClone(truth)), output);
  assert.equal(readFileSync(`${root}${truth.migration}`, 'utf8'), output.sql);
  assert.equal(readFileSync(`${root}${truth.resource}`, 'utf8'), output.descriptor);
  const contract = JSON.parse(output.descriptor);
  assert.equal(contract.platformVersion, 10);
  assert.equal(contract.tables.length, 1);
  assert.equal(contract.tables[0].name, 'bty_service_execution_fence');
  assert.equal(contract.migrationSha256, createHash('sha256').update(output.sql).digest('hex'));
  assert.doesNotMatch(output.sql, /IF NOT EXISTS|ALTER TABLE|INSERT INTO|DROP TABLE/);
});

test('permanent task and source ordinal ownership cannot recycle through history fields', () => {
  const table = truth.tables[0];
  assert.deepEqual(table.unique.uk_bsef_task, ['tenant_no', 'entity_no', 'task_no']);
  assert.deepEqual(table.unique.uk_bsef_unit, ['tenant_no', 'source_line_no', 'unit_ordinal']);
  assert.deepEqual(table.indexes.idx_bsef_source,
    ['tenant_no', 'entity_no', 'store_no', 'source_operation_no', 'source_no', 'execution_no']);
  for (const columns of Object.values(table.unique)) {
    for (const field of ['deleted', 'attempt_no', 'binding_revision', 'intent_state']) assert.ok(!columns.includes(field));
  }
});

test('BaseEntity identity and first genuine suspension survive all updates and deletes', () => {
  const { descriptor } = render(truth);
  const contract = JSON.parse(descriptor);
  const table = contract.tables[0];
  for (const field of AUDIT) assert.ok(table.columns.some(c => c.name === field));
  const update = contract.triggers.find(t => t.event === 'UPDATE');
  for (const field of table.immutable) assert.ok(update.body.includes(`NEW.${field}`));
  assert.ok(update.body.includes('OLD.suspension_intent_revision IS NOT NULL'));
  for (const field of table.columns.filter(c => c.name.startsWith('suspension_'))) {
    assert.ok(update.body.includes(`NEW.${field.name}`));
  }
  assert.ok(contract.triggers.find(t => t.event === 'DELETE').body.includes("SIGNAL SQLSTATE '45000'"));
});

test('typed JSON coordinates reject missing paths rather than SQL unknown success', () => {
  const table = truth.tables[0];
  assert.match(table.checks.ck_bsef_binding, /COALESCE.*JSON_TYPE.*schema.*STRING/);
  for (const [name, expression] of Object.entries(table.checks).filter(([n]) => n.startsWith('ck_bsef_json_'))) {
    assert.ok(expression.startsWith('COALESCE('), name);
    assert.ok(expression.endsWith(',0)'), name);
  }
  assert.match(table.checks.ck_bsef_unit, /quantity=1 AND uom='COUNT'/);
  assert.match(table.checks.ck_bsef_state, /PREPARED_OBSERVED.*REJECTED.*SUSPEND_REQUESTED/);
});

test('suspension proof fields are sticky while observed revision and hash independently remain nullable', () => {
  const table = truth.tables[0];
  assert.ok(table.columns.find(c => c.name === 'suspension_observed_revision').nullable);
  assert.ok(table.columns.find(c => c.name === 'suspension_observed_hash').nullable);
  assert.match(table.checks.ck_bsef_suspension, /suspension_observed_revision IS NULL OR/);
  assert.match(table.checks.ck_bsef_suspension, /suspension_observed_hash IS NULL OR/);
  assert.doesNotMatch(table.checks.ck_bsef_suspension,
    /SOURCE_UNKNOWN|RESOURCE_EXECUTION_UNAVAILABLE|EXECUTION_SCHEMA_UNAVAILABLE/);
});

test('every text column describes inherited or binary physical charset and collation', () => {
  const { sql, descriptor } = render(truth);
  const table = JSON.parse(descriptor).tables[0];
  for (const column of table.columns.filter(c => /^(?:varchar\(|char\(|longtext$)/.test(c.type))) {
    assert.notEqual(column.collation, null, column.name);
    const declaration = sql.split('\n').find(line => line.trimStart().startsWith(`${column.name} `));
    if (column.collation === 'OWNER_TENANT') {
      assert.equal(column.charset, null);
      assert.doesNotMatch(declaration, /CHARACTER SET|COLLATE/);
    } else {
      assert.equal(column.collation, `${column.charset}_bin`);
      assert.ok(declaration.includes(`CHARACTER SET ${column.charset} COLLATE ${column.collation}`));
    }
  }
  assert.match(sql, /DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin/);
});

test('renderer rejects wrong owner, extra table, duplicate column and missing permanent key', () => {
  for (const mutate of [t => t.owner = 'shop-core/primary', t => t.platformVersion = 11,
    t => t.tables.push(structuredClone(t.tables[0])),
    t => t.tables[0].columns.push(structuredClone(t.tables[0].columns[0])),
    t => delete t.tables[0].unique.uk_bsef_task]) {
    const changed = structuredClone(truth); mutate(changed);
    assert.throws(() => render(changed));
  }
});
