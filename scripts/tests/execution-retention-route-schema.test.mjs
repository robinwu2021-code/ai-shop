import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { render } from '../gen-execution-retention-route-schema.mjs';

// Source only until the unified phase: native immutable scope/intent and mutable CAS are separate.
test('route native schema retains complete immutable proof and bounded observation CAS', () => {
  const source = JSON.parse(readFileSync(new URL('../../docs/technical/design/schema/execution-retention-route-schema.json', import.meta.url)));
  const { sql, descriptor } = render(source);
  const schema = JSON.parse(descriptor);
  const table = source.tables[0];
  assert.equal(table.name, 'prd_execution_retention_route');
  for (const name of ['route_json', 'accepted_intent_json', 'component_roster_json', 'mirror_original_json',
    'source_evidence_json', 'reference_json', 'parent_original_json']) {
    assert.ok(table.immutable.includes(name));
    assert.ok(sql.includes(`JSON_VALID(${name})`));
  }
  assert.ok(!table.immutable.includes('observation_epoch'));
  assert.ok(!table.immutable.includes('observations_json'));
  assert.deepEqual(table.indexes.idx_esrr_due, ['next_retry_at', 'id']);
  assert.ok(sql.includes('9223372036854775807'));
  assert.equal(schema.triggers.length, 2);
  assert.ok(schema.triggers.some(t => t.event === 'DELETE' && t.body.includes('SIGNAL SQLSTATE')));
});
