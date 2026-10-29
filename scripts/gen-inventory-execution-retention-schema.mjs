#!/usr/bin/env node
// Pure schema rendering only: no database, application or test execution.
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { writeFrozenMigration } from './lib/beauty-schema-sql.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const truth = 'docs/technical/design/schema/inventory-execution-retention-schema.json';
const names = ['inv_execution_retention_claim', 'inv_execution_retention'];
const migration = 'backend/shop-inventory/src/main/resources/db/inventory/V17__execution_stock_retention.sql';
const resource = 'backend/shop-inventory/src/main/resources/META-INF/shop/inventory-execution-retention-schema.json';
const identifier = value => {
  if (typeof value !== 'string' || !/^[a-z][a-z0-9_]{0,63}$/.test(value)) throw new Error('Invalid identifier');
  return value;
};
const same = (left, right) => JSON.stringify(left) === JSON.stringify(right);
function validate(source) {
  if (source.schemaVersion !== 1 || source.inventoryVersion !== 17 ||
      source.owner !== 'shop-inventory/invDataSource' || source.ownerCollation !== 'utf8mb4_uca1400_ai_ci' ||
      source.migration !== migration ||
      source.resource !== resource || !same(source.tables.map(t => t.name), names)) {
    throw new Error('Unexpected native Inventory manifest');
  }
  for (const table of source.tables) {
    identifier(table.name);
    identifier(table.tag);
    if (table.engine !== 'InnoDB' || table.collation !== source.ownerCollation || !Array.isArray(table.columns)) throw new Error('Invalid storage owner');
    const columns = new Set();
    for (const column of table.columns) {
      identifier(column.name);
      if (columns.has(column.name) || !/^(?:bigint|int|longtext|datetime\(3\)|(?:var)?char\([1-9][0-9]{0,3}\))$/
        .test(column.type) || typeof column.nullable !== 'boolean' || typeof column.immutable !== 'boolean' ||
          ![null, 'ascii', 'utf8mb4'].includes(column.charset) ||
          ![null, source.ownerCollation, 'ascii_bin', 'utf8mb4_bin'].includes(column.collation) ||
          ![null, '0'].includes(column.default)) throw new Error('Invalid Inventory column');
      if (column.name === 'owner_id' && column.collation !== source.ownerCollation) {
        throw new Error('Native owner column collation mismatch');
      }
      columns.add(column.name);
    }
    if (!same(table.primaryKey, ['id']) ||
        !same(table.immutable, table.columns.filter(c => c.immutable).map(c => c.name)) ||
        !['component_no', 'reference_json', 'parent_original_json', 'component_descriptor_json',
          'request_hash', 'component_descriptor_hash'].every(c => table.immutable.includes(c))) {
      throw new Error('Invalid permanent identity');
    }
    for (const [name, fields] of Object.entries({ ...table.unique, ...table.indexes })) {
      identifier(name);
      if (!fields.length || fields.some(c => !columns.has(c)) || new Set(fields).size !== fields.length) {
        throw new Error('Invalid owner key');
      }
    }
    for (const [name, check] of Object.entries(table.checks)) {
      identifier(name);
      if (typeof check !== 'string' || /;|--|\/\*|\b(?:SELECT|INSERT|UPDATE|DELETE|ALTER|DROP)\b/i.test(check)) {
        throw new Error('Invalid Inventory check');
      }
    }
  }
}

function triggers(table) {
  const immutable = table.immutable.map(column =>
    `NOT (BINARY NEW.${column} <=> BINARY OLD.${column})`).join(' OR\n        ');
  const lines = ['BEGIN', `    IF ${immutable} THEN`,
    "        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Execution stock retention immutable original';", '    END IF;'];
  lines.push("    IF OLD.state IN ('RELEASED_NO_EFFECT','SEALED_NO_EFFECT') AND (NEW.state<>OLD.state OR",
    '        NOT (BINARY NEW.no_effect_seal_json <=> BINARY OLD.no_effect_seal_json) OR',
    '        NOT (BINARY NEW.no_effect_seal_no <=> BINARY OLD.no_effect_seal_no) OR',
    '        NOT (NEW.no_effect_seal_revision <=> OLD.no_effect_seal_revision) OR',
    '        NOT (BINARY NEW.no_effect_seal_hash <=> BINARY OLD.no_effect_seal_hash)) THEN',
    "        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Retention seal is permanent';", '    END IF;',
    '    IF OLD.issue_observation_hash IS NOT NULL AND (',
    '        NOT (BINARY NEW.issue_observation_json <=> BINARY OLD.issue_observation_json) OR',
    '        NOT (BINARY NEW.issue_observation_hash <=> BINARY OLD.issue_observation_hash)) THEN',
    "        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Retention issue observation is immutable';", '    END IF;');
  if (table.name === 'inv_execution_retention_claim') {
    lines.push('    IF OLD.owner_set_json IS NOT NULL AND (',
      '        NOT (BINARY NEW.owner_set_json <=> BINARY OLD.owner_set_json) OR',
      '        NOT (BINARY NEW.owner_set_hash <=> BINARY OLD.owner_set_hash) OR',
      '        NOT (NEW.owner_set_count <=> OLD.owner_set_count)) THEN',
      "        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Resolved owner roster is permanent';", '    END IF;');
    lines.push("    IF OLD.state='RETAINED' AND NEW.state='ACCEPTED' THEN",
      "        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Retention claim cannot regress';", '    END IF;');
  }
  lines.push('END');
  return [{ name: `trg_${table.tag}_immutable`, table: table.name, timing: 'BEFORE', event: 'UPDATE',
    body: lines.join('\n') },
  { name: `trg_${table.tag}_no_delete`, table: table.name, timing: 'BEFORE', event: 'DELETE',
    body: "BEGIN\n    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Execution stock retention history cannot be deleted';\nEND" }];
}

// Flyway's SQL checksum is CRC32 over UTF-8 logical lines, omitting line terminators and an initial BOM.
function flywayChecksum(sql) {
  let crc = 0xffffffff;
  for (const byte of Buffer.from(sql.replace(/^\uFEFF/, '').split(/\r\n|\n|\r/).join(''), 'utf8')) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) | 0;
}

/** Single-source renderer emits the frozen migration and exact runtime inspection descriptor together. */
export function render(source) {
  validate(source);
  const definitions = source.tables.map(table => {
    const parts = table.columns.map(column => `    ${column.name} ${column.type.toUpperCase()}` +
      (column.charset ? ` CHARACTER SET ${column.charset}` : '') +
      (column.collation ? ` COLLATE ${column.collation}` : '') +
      (column.nullable ? ' NULL' : ' NOT NULL') + (column.name === 'id' ? ' AUTO_INCREMENT' : '') +
      (column.default === null ? '' : ` DEFAULT ${column.default}`));
    parts.push(`    PRIMARY KEY (${table.primaryKey.join(', ')})`);
    for (const [name, columns] of Object.entries(table.unique)) {
      parts.push(`    UNIQUE KEY ${name} (${columns.join(', ')})`);
    }
    for (const [name, columns] of Object.entries(table.indexes)) {
      parts.push(`    KEY ${name} (${columns.join(', ')})`);
    }
    for (const [name, check] of Object.entries(table.checks)) {
      parts.push(`    CONSTRAINT ${name} CHECK (${check})`);
    }
    return `CREATE TABLE ${table.name} (\n${parts.join(',\n')}\n` +
      `) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${table.collation} COMMENT='${table.comment.replaceAll("'", "''")}';`;
  });
  const guards = source.tables.flatMap(triggers);
  const sql = '-- Generated by scripts/gen-inventory-execution-retention-schema.mjs; do not edit.\n' +
    '-- Empty retention authority only; no balance or historical quantity is backfilled.\n\n' +
    definitions.join('\n\n') + '\n\nDELIMITER $$\n' + guards.map(t =>
      `CREATE TRIGGER ${t.name} ${t.timing} ${t.event} ON ${t.table}\nFOR EACH ROW\n${t.body}$$`)
      .join('\n\n') + '\nDELIMITER ;\n';
  const descriptor = JSON.stringify({ schemaVersion: 1, owner: source.owner, inventoryVersion: 17,
    migrationScript: 'V17__execution_stock_retention.sql', flywayChecksum: flywayChecksum(sql),
    migrationSha256: createHash('sha256').update(sql).digest('hex'), ownerCollation: source.ownerCollation,
    ownerIdentity: source.ownerIdentity,
    tables: source.tables, triggers: guards }, null, 2) + '\n';
  return { sql, descriptor };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.some(arg => arg !== '--check')) throw new Error('Only --check is supported');
  const source = JSON.parse(readFileSync(resolve(root, truth), 'utf8'));
  const output = render(source);
  for (const [path, value] of [[migration, output.sql], [resource, output.descriptor]]) {
    const target = resolve(root, path);
    if (args.includes('--check')) {
      if (readFileSync(target, 'utf8') !== value) throw new Error(`Generated artifact drift: ${path}`);
    } else if (path === migration) {
      writeFrozenMigration(target, value);
    } else {
      mkdirSync(dirname(target), { recursive: true });
      writeFileSync(target, value);
    }
  }
}
