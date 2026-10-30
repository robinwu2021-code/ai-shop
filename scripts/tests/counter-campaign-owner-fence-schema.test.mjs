import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { render } from '../gen-counter-campaign-owner-fence-schema.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const source = JSON.parse(readFileSync(`${root}docs/technical/design/schema/counter-campaign-owner-fence-schema.json`));
const tables = ['mkt_campaign', 'pmt_activity', 'pmt_activity_version', 'pmt_activity_goods',
  'pmt_activity_rule', 'pmt_enrollment', 'pmt_enrollment_goods'];

test('render is deterministic and both committed artifacts are exact', () => {
  const first = render(source);
  assert.deepEqual(render(structuredClone(source)), first);
  assert.equal(readFileSync(`${root}${source.migration}`, 'utf8'), first.sql);
  assert.equal(readFileSync(`${root}${source.resource}`, 'utf8'), first.descriptor);
});

test('every predicate table has exactly the three before mutation triggers', () => {
  const { triggers } = JSON.parse(render(source).descriptor);
  assert.equal(triggers.length, 21);
  for (const table of tables) {
    assert.deepEqual(triggers.filter(t => t.table === table).map(t => t.event), ['INSERT', 'UPDATE', 'DELETE']);
    for (const trigger of triggers.filter(t => t.table === table)) {
      assert.equal(trigger.timing, 'BEFORE');
      assert.match(trigger.body, /ON DUPLICATE KEY UPDATE revision = revision \+ 1/);
      assert.doesNotMatch(trigger.body, /\b(?:UPPER|LOWER)\s*\(/);
    }
  }
});

test('fence identities bind ten effective columns while kind and parent checks stay binary', () => {
  const { sql, descriptor } = render(source);
  const expected = JSON.parse(descriptor);
  assert.match(sql, /VALUES \('MAIN', 'PLATFORM', 'PLATFORM', 0\)/);
  assert.deepEqual(expected.table.primaryKey, ['tenant_no', 'scope_kind', 'scope_no']);
  assert.equal(expected.table.charset, 'DATABASE_DEFAULT');
  assert.equal(expected.table.collation, 'OWNER_IDENTITY');
  assert.equal(expected.table.columns.filter(c => c.collation === 'utf8mb4_bin').length, 1);
  for (const name of ['tenant_no', 'scope_no']) {
    const column = expected.table.columns.find(c => c.name === name);
    assert.equal(column.charset, 'OWNER_IDENTITY');
    assert.equal(column.collation, 'OWNER_IDENTITY');
    assert.match(sql, new RegExp(`    ${name} VARCHAR\\(\\d+\\) NOT NULL`));
  }
  assert.match(sql, /scope_kind VARCHAR\(16\) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL/);
  assert.match(sql, /\) ENGINE=InnoDB;/);
  assert.doesNotMatch(sql, /DEFAULT CHARSET|DEFAULT CHARACTER SET/);
  assert.equal(expected.ownerIdentity.charset, 'utf8mb4');
  assert.deepEqual(expected.ownerIdentity.bindings, [
    ...tables.map(table => ({ table, column: 'tenant_no' })),
    ...['mkt_campaign', 'pmt_activity', 'pmt_enrollment'].map(table => ({ table, column: 'entity_no' }))
  ]);
  for (const trigger of expected.triggers) {
    if (trigger.event === 'UPDATE') assert.match(trigger.body, /BINARY NEW\.tenant_no <=> BINARY OLD\.tenant_no/);
    if (trigger.parent) {
      assert.match(trigger.body, /BINARY tenant_no = BINARY (NEW|OLD)\.tenant_no/);
      assert.match(trigger.body, /WHERE (activity_no|enrollment_no) = (NEW|OLD)\.(activity_no|enrollment_no)/);
      assert.match(trigger.body, /LOCK IN SHARE MODE/);
      assert.match(trigger.body, /SIGNAL SQLSTATE '45000'/);
    }
  }
  const platform = expected.triggers.find(t => t.table === 'pmt_activity' && t.event === 'INSERT');
  assert.match(platform.body, /'PLATFORM'/);
  assert.match(platform.body, /'MERCHANT'/);
});

test('descriptor rejects duplicate predicate groups and unknown ownership resolution', () => {
  const duplicate = structuredClone(source);
  duplicate.inputs.push(duplicate.inputs[0]);
  assert.throws(() => render(duplicate));
  const unknown = structuredClone(source);
  unknown.inputs[0].resolution = 'GUESS_FROM_PREFIX';
  assert.throws(() => render(unknown));
  const missingBinding = structuredClone(source);
  missingBinding.ownerIdentity.bindings.pop();
  assert.throws(() => render(missingBinding));
  const binaryFallback = structuredClone(source);
  binaryFallback.table.columns[0].collation = 'utf8mb4_bin';
  assert.throws(() => render(binaryFallback));
});
