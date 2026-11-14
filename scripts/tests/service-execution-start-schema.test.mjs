import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import test from 'node:test';
import { render } from '../gen-service-execution-start-schema.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const read = path => JSON.parse(readFileSync(resolve(root, path), 'utf8'));
const source = read('backend/shop-core/src/main/schema/service-execution-start-schema.json');
const base = read('backend/shop-core/src/main/resources/META-INF/shop/manual-service-execution-schema.json');

test('ACK renderer retains the frozen base and exact forward SQL bytes', () => {
  const original = JSON.stringify(base);
  const result = render(source, base);
  assert.equal(JSON.stringify(base), original);
  assert.equal(result.sql, readFileSync(resolve(root,
    'backend/shop-app/src/main/resources/db/migration/V668__service_execution_start_ack.sql'), 'utf8'));
  assert.equal(result.descriptor.postTriggers.length, 7);
  assert.equal(result.descriptor.platformVersion, 668);
});

test('a wrong base digest cannot mint an apparently installed successor', () => {
  const changed = structuredClone(source);
  changed.baseMigrationSha256 = '0'.repeat(64);
  assert.throws(() => render(changed, base), /base\/schema drift/);
});

test('completion successor retains ACK permanence and adds performed-unit guards', () => {
  const ack = render(source, base).descriptor;
  const completion = read('backend/shop-core/src/main/schema/service-execution-completion-schema.json');
  const result = render(completion, ack);
  assert.equal(result.descriptor.postTriggers.length, 9);
  assert.ok(result.descriptor.postTriggers.some(t => t.name === 'trg_sex_actual_immutable'));
  assert.ok(result.descriptor.postTriggers.some(t => t.name === 'trg_seu_performed_terminal'));
  assert.equal(result.descriptor.baseMigrationSha256, ack.migrationSha256);
  assert.equal(result.sql, readFileSync(resolve(root,
    'backend/shop-app/src/main/resources/db/migration/V669__service_execution_completion.sql'), 'utf8'));
});
