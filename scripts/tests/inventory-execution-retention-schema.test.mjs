import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { render } from '../gen-inventory-execution-retention-schema.mjs';

const root = new URL('../../', import.meta.url);
const source = JSON.parse(readFileSync(new URL(
  'docs/technical/design/schema/inventory-execution-retention-schema.json', root)));

test('native Inventory authority renders both permanent owner identities and no quantity balance', () => {
  const result = render(source);
  assert.equal(readFileSync(new URL(source.migration, root), 'utf8'), result.sql);
  assert.equal(readFileSync(new URL(source.resource, root), 'utf8'), result.descriptor);
  assert.deepEqual(source.tables.map(t => t.name), ['inv_execution_retention_claim', 'inv_execution_retention']);
  for (const table of source.tables) {
    assert(!table.columns.some(c => ['version', 'tenant_no', 'deleted', 'protected_qty'].includes(c.name)));
    assert(table.immutable.includes('component_descriptor_hash'));
    assert(table.immutable.includes('reference_json'));
    assert(table.immutable.includes('parent_original_json'));
  }
  const descriptor = JSON.parse(result.descriptor);
  assert.equal(descriptor.triggers.length, 4);
  assert(descriptor.triggers.filter(t => t.event === 'UPDATE').every(t =>
    t.body.includes("OLD.state IN ('RELEASED_NO_EFFECT','SEALED_NO_EFFECT')")));
});

test('wrong version or missing permanent owner identity cannot silently render', () => {
  const changed = structuredClone(source);
  changed.inventoryVersion = 14;
  assert.throws(() => render(changed));
  const missing = structuredClone(source);
  missing.tables.pop();
  assert.throws(() => render(missing));
});


test('V17 binds the frozen native owner collation even when implicit defaults differ', () => {
  const rendered = render(source);
  assert.equal(source.ownerCollation, 'utf8mb4_uca1400_ai_ci');
  assert.equal((rendered.sql.match(/DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci/g) ?? []).length, 2);
  assert.match(rendered.sql, /owner_id VARCHAR\(32\) CHARACTER SET utf8mb4 COLLATE utf8mb4_uca1400_ai_ci/);
  assert(rendered.descriptor.includes('utf8mb4_uca1400_ai_ci'));
  const changed = structuredClone(source);
  changed.ownerCollation = 'utf8mb4_general_ci';
  assert.throws(() => render(changed));
});
