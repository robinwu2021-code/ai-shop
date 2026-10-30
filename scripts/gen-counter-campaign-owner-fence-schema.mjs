#!/usr/bin/env node
// Shared owner contract renderer. --check compares bytes only; no database or runtime imports.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const truth = 'docs/technical/design/schema/counter-campaign-owner-fence-schema.json';
const expectedTables = ['mkt_campaign', 'pmt_activity', 'pmt_activity_version', 'pmt_activity_goods',
  'pmt_activity_rule', 'pmt_enrollment', 'pmt_enrollment_goods'];
const expectedBindings = [...expectedTables.map(table => ({ table, column: 'tenant_no' })),
  ...['mkt_campaign', 'pmt_activity', 'pmt_enrollment'].map(table => ({ table, column: 'entity_no' }))];
const resolutions = ['ENTITY', 'OWNER', 'ACTIVITY', 'ENROLLMENT', 'ENROLLMENT_GOODS'];
const signal = message => `        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '${message}';`;
const failIf = (predicate, message) => [`    IF ${predicate} THEN`, signal(message), '    END IF;'];
const bump = (tenant, kind, no) => [
  '    INSERT INTO mkt_counter_campaign_fence (tenant_no, scope_kind, scope_no, revision)',
  `    VALUES (${tenant}, ${kind}, ${no}, 1)`,
  '    ON DUPLICATE KEY UPDATE revision = revision + 1;'];
const binaryEqual = (a, b) => `BINARY ${a} <=> BINARY ${b}`;

function trigger(input, event) {
  const row = event === 'DELETE' ? 'OLD' : 'NEW';
  const parent = ['ACTIVITY', 'ENROLLMENT', 'ENROLLMENT_GOODS'].includes(input.resolution);
  const lines = ['BEGIN'];
  if (parent) lines.push('    DECLARE parent_entity VARCHAR(64) DEFAULT NULL;',
    '    DECLARE parent_owner VARCHAR(16) DEFAULT NULL;');
  if (event === 'UPDATE') {
    lines.push(...failIf(input.immutable.map(key => `NOT (${binaryEqual(`NEW.${key}`, `OLD.${key}`)})`)
      .join(' OR\n        '), 'Counter campaign immutable identity'));
  }
  lines.push(...failIf(`${row}.tenant_no IS NULL OR CHAR_LENGTH(TRIM(${row}.tenant_no)) = 0`,
    'Counter campaign tenant missing'));
  let kind = "'ENTITY'";
  let no = `${row}.entity_no`;
  if (input.resolution === 'OWNER') {
    lines.push(...failIf(`NOT (BINARY ${row}.owner IN ('MERCHANT', 'PLATFORM'))`,
      'Counter campaign owner invalid'));
    lines.push(...failIf(`BINARY ${row}.owner = 'PLATFORM' AND NOT (${binaryEqual(`${row}.entity_no`, "'PLATFORM'")})`,
      'Counter campaign platform identity invalid'));
    kind = `IF(BINARY ${row}.owner = 'PLATFORM', 'PLATFORM', 'ENTITY')`;
    no = `IF(BINARY ${row}.owner = 'PLATFORM', 'PLATFORM', ${row}.entity_no)`;
  } else if (parent) {
    if (input.resolution === 'ENROLLMENT_GOODS') {
      lines.push('    SELECT entity_no INTO parent_entity FROM pmt_enrollment',
        `    WHERE enrollment_no = ${row}.enrollment_no AND tenant_no = ${row}.tenant_no`,
        `      AND BINARY tenant_no = BINARY ${row}.tenant_no`,
        `      AND BINARY enrollment_no = BINARY ${row}.enrollment_no LOCK IN SHARE MODE;`);
    } else {
      lines.push('    SELECT entity_no, owner INTO parent_entity, parent_owner FROM pmt_activity',
        `    WHERE activity_no = ${row}.activity_no AND tenant_no = ${row}.tenant_no`,
        `      AND BINARY tenant_no = BINARY ${row}.tenant_no`,
        `      AND BINARY activity_no = BINARY ${row}.activity_no LOCK IN SHARE MODE;`);
    }
    lines.push(...failIf('parent_entity IS NULL', 'Counter campaign parent missing'));
    if (input.resolution === 'ENROLLMENT') {
      lines.push(...failIf(`NOT (${binaryEqual('parent_owner', "'PLATFORM'")}) OR ` +
        `NOT (${binaryEqual('parent_entity', "'PLATFORM'")})`, 'Counter enrollment parent invalid'));
    } else if (input.resolution === 'ACTIVITY') {
      lines.push(...failIf("NOT (BINARY parent_owner IN ('MERCHANT', 'PLATFORM'))",
        'Counter campaign parent owner invalid'));
      lines.push(...failIf("BINARY parent_owner = 'PLATFORM' AND " +
        `NOT (${binaryEqual('parent_entity', "'PLATFORM'")})`, 'Counter campaign parent identity invalid'));
      if (input.immutable.includes('entity_no')) {
        lines.push(...failIf(`NOT (${binaryEqual(`${row}.entity_no`, 'parent_entity')})`,
          'Counter campaign child owner mismatch'));
      }
      kind = "IF(BINARY parent_owner = 'PLATFORM', 'PLATFORM', 'ENTITY')";
      no = "IF(BINARY parent_owner = 'PLATFORM', 'PLATFORM', parent_entity)";
    } else no = 'parent_entity';
  }
  lines.push(...failIf(`${no} IS NULL OR CHAR_LENGTH(TRIM(${no})) = 0`, 'Counter campaign entity missing'));
  lines.push(...bump(`${row}.tenant_no`, kind, no), 'END');
  return { name: `trg_ccf_${input.table}_${event.toLowerCase()}`, table: input.table,
    event, timing: 'BEFORE', parent, body: lines.join('\n') };
}

/** Pure renderer used by static tests; the packaged descriptor owns runtime expectations. */
export function render(source) {
  if (source.schemaVersion !== 1 || source.platformVersion !== 603 ||
      JSON.stringify(source.inputs.map(i => i.table)) !== JSON.stringify(expectedTables) ||
      source.inputs.some(i => !resolutions.includes(i.resolution)) ||
      source.ownerIdentity?.charset !== 'utf8mb4' ||
      JSON.stringify(source.ownerIdentity?.bindings) !== JSON.stringify(expectedBindings) ||
      source.table.charset !== 'DATABASE_DEFAULT' || source.table.collation !== 'OWNER_IDENTITY' ||
      source.table.columns.some(c => ['tenant_no', 'scope_no'].includes(c.name) &&
        (c.charset !== 'OWNER_IDENTITY' || c.collation !== 'OWNER_IDENTITY'))) {
    throw new Error('Unexpected shared counter fence contract');
  }
  const table = source.table;
  const columns = table.columns.map(c => `    ${c.name} ${c.type.toUpperCase()}` +
    (c.charset && c.charset !== 'OWNER_IDENTITY' ? ` CHARACTER SET ${c.charset}` : '') +
    (c.collation && c.collation !== 'OWNER_IDENTITY' ? ` COLLATE ${c.collation}` : '') +
    (c.nullable ? ' NULL' : ' NOT NULL') +
    (c.default === null ? '' : ` DEFAULT ${c.default}`));
  columns.push(`    PRIMARY KEY (${table.primaryKey.join(', ')})`);
  for (const [name, check] of Object.entries(table.checks)) columns.push(`    CONSTRAINT ${name} CHECK (${check})`);
  const triggers = source.inputs.flatMap(i => ['INSERT', 'UPDATE', 'DELETE'].map(e => trigger(i, e)));
  const sql = '-- Generated by scripts/gen-counter-campaign-owner-fence-schema.mjs; do not edit.\n' +
    '-- Shared marketing coordination only. Do not delete durable fence rows.\n' +
    `CREATE TABLE ${table.name} (\n${columns.join(',\n')}\n` +
    `) ENGINE=${table.engine};\n\n` +
    `INSERT INTO ${table.name} (tenant_no, scope_kind, scope_no, revision)\n` +
    `VALUES ('${source.seed.tenant}', '${source.seed.kind}', '${source.seed.no}', 0);\n\nDELIMITER $$\n` +
    triggers.map(t => `CREATE TRIGGER ${t.name} ${t.timing} ${t.event} ON ${t.table}\n` +
      `FOR EACH ROW\n${t.body}$$\n`).join('\n') + 'DELIMITER ;\n';
  return { sql, descriptor: JSON.stringify({ schemaVersion: 1, owner: source.owner, table,
    seed: source.seed, ownerIdentity: source.ownerIdentity, triggers }, null, 2) + '\n' };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.some(a => a !== '--check')) throw new Error('Only --check is supported');
  const source = JSON.parse(readFileSync(resolve(root, truth), 'utf8'));
  const output = render(source);
  for (const [path, value] of [[source.migration, output.sql], [source.resource, output.descriptor]]) {
    const target = resolve(root, path);
    if (args.includes('--check')) {
      if (readFileSync(target, 'utf8') !== value) throw new Error(`Generated artifact drift: ${path}`);
    } else {
      mkdirSync(dirname(target), { recursive: true });
      writeFileSync(target, value);
    }
  }
}
