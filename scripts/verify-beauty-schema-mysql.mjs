// Operates only on its own disposable network-isolated database; no application credentials.
import {execFileSync, spawnSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import {renderBeautySchema} from './lib/beauty-schema-sql.mjs';
const design = JSON.parse(readFileSync(new URL('../docs/technical/design/schema/beauty-database-design.json', import.meta.url)));
const migration = readFileSync(new URL('../backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V1__beauty_p1_tables.sql', import.meta.url), 'utf8');
assert.equal(migration, renderBeautySchema(design));
const name = 'codex-beauty-schema-' + randomUUID();
const docker = args => execFileSync('docker', args, {encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe']}).trim();
const sql = (input, ok = true) => {
  const result = spawnSync('docker', ['exec', '-i', name, 'mysql', '-uroot', 'beauty_verify', '--batch', '--skip-column-names'], {input, encoding: 'utf8'});
  if (ok) assert.equal(result.status, 0, result.stderr);
  else assert.notEqual(result.status, 0, 'Expected database constraint rejection');
  return result;
};
const quote = value => value === null ? 'NULL' : typeof value === 'number' ? String(value) : "'" + String(value).replaceAll("'", "''") + "'";
function insert(table, overrides = {}) {
  const definition = design.tables.find(t => t.name === table);
  const fields = [...design.common_fields.map(([name, type]) => ({name, type})), ...definition.fields].filter(f => f.name !== 'id');
  const values = fields.map(f => {
    if (Object.hasOwn(overrides, f.name)) return overrides[f.name];
    if (f.nullable) return null;
    if (f.type === 'json') return '{}';
    if (f.type.startsWith('datetime')) return f.name === 'end_at' ? '2026-10-20 11:00:00' : '2026-10-20 10:00:00';
    if (/int|decimal/.test(f.type)) return ['done_qty', 'voided_qty', 'deleted', 'version', 'allocated_qty'].includes(f.name) ? 0 : 1;
    return f.name + '_1';
  });
  return 'INSERT INTO `' + table + '` (' + fields.map(f => '`' + f.name + '`').join(',') + ') VALUES (' + values.map(quote).join(',') + ');';
}
const rejected = (statement, pattern) => assert.match(sql(statement, false).stderr, pattern);
docker(['run', '--pull', 'never', '--detach', '--name', name, '--network', 'none', '--tmpfs', '/var/lib/mysql', '--env', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', '--env', 'MYSQL_DATABASE=beauty_verify', 'mysql:8.4']);
try {
  let ready = false;
  for (let i = 0; i < 100; i++) {
    try {docker(['exec', name, 'mysql', '-uroot', 'beauty_verify', '-e', 'SELECT 1']); ready = true; break;}
    catch {await new Promise(resolve => setTimeout(resolve, 400));}
  }
  assert(ready, 'Disposable MySQL did not become ready');
  sql(migration);
  assert.equal(sql('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();').stdout.trim(), '16');
  // A source revision is state, not identity: two revisions cannot allocate twice.
  sql(insert('bty_source_allocation_guard', {guard_no: 'G1', source_qty: 5, allocated_qty: 3}));
  rejected(insert('bty_source_allocation_guard', {guard_no: 'G2', source_revision: 2}), /1062/);
  sql(insert('bty_source_allocation_guard', {guard_no: 'G2', source_line_ref: 'OTHER', source_qty: 5, allocated_qty: 0}));
  rejected("UPDATE bty_source_allocation_guard SET allocated_qty=6 WHERE guard_no='G1';", /3819|CONSTRAINT/i);
  // Same business identity in another entity is allowed.
  sql(insert('bty_source_allocation_guard', {entity_no: 'OTHER'}));
  rejected(insert('bty_source_allocation_guard', {guard_no: 'G3', source_line_ref: null}), /1048/);
  // Two walk-ins do not collide on a nullable appointment link.
  sql(insert('bty_work_order', {work_order_no: 'W1', appointment_no: null}));
  sql(insert('bty_work_order', {work_order_no: 'W2', appointment_no: null}));
  sql(insert('bty_work_order', {work_order_no: 'W3', appointment_no: 'A'}));
  rejected(insert('bty_work_order', {work_order_no: 'W4', appointment_no: 'A'}), /1062/);
  sql(insert('bty_service_submission', {submission_no: 'S1'}));
  rejected(insert('bty_service_submission', {submission_no: 'S2'}), /1062/);
  sql(insert('bty_service_task', {task_no: 'T1', qty: 5, done_qty: 3, voided_qty: 2}));
  rejected(insert('bty_service_task', {task_no: 'T2', qty: 5, done_qty: 4, voided_qty: 2}), /3819|CONSTRAINT/i);
  rejected(insert('bty_appointment', {end_at: '2026-10-20 09:00:00'}), /3819|CONSTRAINT/i);
  rejected(migration, /1050/); // No IF NOT EXISTS may hide drift.
  console.log('PASS MySQL 8.4: 16 tables; source identity, entity scope, quantity bounds, walk-in cardinality, request deduplication and interval checks.');
} finally {
  docker(['rm', '--force', name]);
}
