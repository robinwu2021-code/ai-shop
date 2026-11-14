import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { render } from '../gen-execution-stock-retention-schema.mjs';
const root = new URL('../../', import.meta.url);
const source = JSON.parse(readFileSync(new URL('docs/technical/design/schema/execution-stock-retention-schema.json', root)));
test('permanent retention identity includes zero-delete and exact immutable association', () => {
  const rendered = render(source);
  assert.match(rendered.sql, /deleted=0/);
  assert.match(rendered.sql, /UNIQUE KEY uk_esr_original \(tenant_no, retention_no, original_row_id\)/);
  assert.match(rendered.sql, /BEFORE DELETE/);
  assert.match(rendered.sql, /NEW.reference_hash/);
  assert.match(rendered.sql, /RELEASED_NO_EFFECT/);
  assert.equal(rendered.sql, readFileSync(new URL(source.migration, root), 'utf8'));
  assert.equal(rendered.descriptor, readFileSync(new URL(source.resource, root), 'utf8'));
});
test('changing a permanent key is rejected by the authority renderer', () => {
  const changed = structuredClone(source);
  changed.tables[0].unique.uk_esr_original.push('deleted');
  assert.throws(() => render(changed));
});
