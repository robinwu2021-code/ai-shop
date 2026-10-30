import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { render } from '../gen-manual-service-execution-schema.mjs';
import { readSchema, AUDIT } from '../lib/ddl.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const truth = JSON.parse(readFileSync(`${root}docs/technical/design/schema/manual-service-execution-schema.json`));
const names = ['ord_service_execution_unit', 'ord_service_execution', 'ord_service_execution_action'];

test('pure renderer deterministically emits only exact V604 and the bundled owner descriptor', () => {
  const first = render(truth);
  assert.deepEqual(render(structuredClone(truth)), first);
  assert.equal(readFileSync(`${root}${truth.migration}`, 'utf8'), first.sql);
  assert.equal(readFileSync(`${root}${truth.resource}`, 'utf8'), first.descriptor);
  assert.equal(truth.platformVersion, 604);
  assert.deepEqual(truth.tables.map(table => table.name), names);
  assert.doesNotMatch(first.sql, /IF NOT EXISTS|ALTER TABLE|INSERT INTO|DROP TABLE/);
});

test('every permanent relation includes BaseEntity fields and immutable identity/delete triggers', () => {
  const { sql, descriptor } = render(truth);
  const contract = JSON.parse(descriptor);
  for (const table of contract.tables) {
    for (const field of AUDIT) assert.ok(table.columns.some(c => c.name === field), `${table.name}.${field}`);
    const update = contract.triggers.find(t => t.table === table.name && t.event === 'UPDATE');
    assert.ok(update);
    for (const field of table.immutable) assert.ok(update.body.includes(`NEW.${field}`), field);
    assert.ok(contract.triggers.find(t => t.table === table.name && t.event === 'DELETE'));
    assert.match(sql, new RegExp(`CREATE TABLE ${table.name} \\(`));
    assert.match(table.checks[`ck_${table.tag}_base`], /deleted=0/);
  }
});

test('unit and task uniqueness permanently ignore deleted/request keys and retry generations', () => {
  const unit = truth.tables[0];
  assert.deepEqual(unit.unique.uk_seu_source_unit, ['tenant_no', 'source_line_no', 'unit_ordinal']);
  assert.deepEqual(unit.unique.uk_seu_task, ['tenant_no', 'entity_no', 'caller', 'task_no']);
  for (const columns of Object.values(unit.unique)) {
    assert.ok(!columns.includes('deleted'));
    assert.ok(!columns.includes('attempt_no'));
    assert.ok(!columns.includes('binding_revision'));
  }
  assert.match(unit.checks.ck_seu_state, /BOUND.*RESERVED.*PERFORMED.*VOIDED/);
  assert.match(unit.checks.ck_seu_current, /state='RESERVED'.*current_execution_no IS NOT NULL/);
});

test('descriptor preserves actual inherited tenant collation and binary owner/source/hash equivalence', () => {
  for (const table of truth.tables) {
    assert.equal(table.columns.find(c => c.name === 'tenant_no').collation, 'OWNER_TENANT');
    for (const name of ['entity_no', 'store_no']) {
      assert.equal(table.columns.find(c => c.name === name).collation, 'utf8mb4_bin');
    }
    for (const c of table.columns.filter(c => c.name.endsWith('_hash'))) {
      assert.equal(c.collation, 'ascii_bin');
      assert.equal(c.type, 'char(64)');
    }
  }
  const source = truth.tables[0].columns.find(c => c.name === 'source_line_no');
  assert.equal(source.type, 'varchar(40)');
  assert.equal(source.collation, 'utf8mb4_bin');
});

test('every textual column describes inherited or explicit physical charset and collation', () => {
  const { sql, descriptor } = render(truth);
  const contract = JSON.parse(descriptor);
  const inheritedPayloads = new Set(['binding_json', 'proof_json']);
  const seenPayloads = new Set();
  for (const table of truth.tables) {
    const physical = contract.tables.find(t => t.name === table.name);
    const tableSql = sql.slice(sql.indexOf(`CREATE TABLE ${table.name} (`));
    for (const column of table.columns.filter(c => /^(?:varchar\(|char\(|(?:tiny|medium|long)?text$)/.test(c.type))) {
      const label = `${table.name}.${column.name}`;
      const emitted = physical.columns.find(c => c.name === column.name);
      assert.equal(emitted.charset, column.charset, `${label} descriptor charset`);
      assert.equal(emitted.collation, column.collation, `${label} descriptor collation`);
      assert.notEqual(emitted.collation, null, `${label} must describe its effective collation`);
      const declaration = tableSql.split('\n').find(line => line.trimStart().startsWith(`${column.name} `));
      assert.ok(declaration, `${label} emitted declaration`);
      if (column.collation === 'OWNER_TENANT') {
        assert.equal(column.charset, null, `${label} inherits the owner's utf8mb4 charset`);
        assert.doesNotMatch(declaration, /CHARACTER SET|COLLATE/, `${label} must inherit in DDL`);
      } else {
        assert.ok(['ascii', 'utf8mb4'].includes(column.charset), `${label} explicit charset`);
        assert.equal(column.collation, `${column.charset}_bin`, `${label} explicit binary equivalence`);
        assert.ok(declaration.includes(`CHARACTER SET ${column.charset}`), `${label} emitted charset`);
        assert.ok(declaration.includes(`COLLATE ${column.collation}`), `${label} emitted collation`);
      }
      if (inheritedPayloads.has(column.name)) {
        assert.equal(column.type, 'longtext', `${label} bounded object storage`);
        assert.equal(column.collation, 'OWNER_TENANT', `${label} LONGTEXT inherits owner metadata`);
        seenPayloads.add(column.name);
      }
    }
  }
  assert.deepEqual([...seenPayloads].sort(), [...inheritedPayloads].sort());
});

test('common DDL parser sees exact new columns and no cross-table leakage', () => {
  const tables = readSchema(root);
  for (const table of truth.tables) {
    assert.deepEqual(tables.get(table.name).cols.map(c => c.name), table.columns.map(c => c.name));
  }
  assert.equal(readdirSync(`${root}backend/shop-app/src/main/resources/db/migration`)
    .filter(name => /^V604__/.test(name)).length, 1);
});

test('renderer rejects extra tables, duplicate columns, invalid identities and missing permanent keys', () => {
  const mutations = [
    t => t.tables.push(structuredClone(t.tables[0])),
    t => t.tables[0].columns.push(structuredClone(t.tables[0].columns[0])),
    t => t.tables[0].immutable.push('invented_column'),
    t => delete t.tables[0].unique.uk_seu_task,
    t => t.tables[0].columns.find(c => c.name === 'tenant_no').collation = 'utf8mb4_bin',
    t => t.migration = 'backend/shop-app/src/main/resources/db/migration/V603__rewrite.sql'
  ];
  for (const mutate of mutations) {
    const changed = structuredClone(truth);
    mutate(changed);
    assert.throws(() => render(changed));
  }
});
