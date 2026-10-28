#!/usr/bin/env node
// Pure schema rendering only: no database, application or test execution.
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { AUDIT } from './lib/ddl.mjs';
import { writeFrozenMigration } from './lib/beauty-schema-sql.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const truth = 'docs/technical/design/schema/execution-retention-route-schema.json';
const names = ['prd_execution_retention_route'];
const migration = 'backend/shop-app/src/main/resources/db/migration/V546__execution_retention_route.sql';
const resource = 'backend/shop-app/src/main/resources/META-INF/shop/execution-retention-route-schema.json';
const identifier = value => {
  if (typeof value !== 'string' || !/^[a-z][a-z0-9_]{0,63}$/.test(value)) throw new Error('Invalid identifier');
  return value;
};
const same = (left, right) => JSON.stringify(left) === JSON.stringify(right);
const permanent = {
  uk_esrr_route: ['tenant_no', 'route_no'],
  uk_esrr_retention: ['tenant_no', 'retention_no'],
  uk_esrr_execution: ['tenant_no', 'entity_no', 'execution_no', 'parent_original_hash'],
  uk_esrr_intent: ['tenant_no', 'accepted_intent_no']
};

function validate(source) {
  if (source.schemaVersion !== 1 || source.platformVersion !== 546 || source.owner !== 'shop-app/invbridge' ||
      source.migration !== migration || source.resource !== resource ||
      !same(source.tables.map(table => table.name), names) || source.ownerIdentity.length !== 2) {
    throw new Error('Unexpected B1 owner schema');
  }
  for (const table of source.tables) {
    identifier(table.name);
    identifier(table.tag);
    if (table.engine !== 'InnoDB' || !same(table.primaryKey, ['id'])) throw new Error('Invalid storage identity');
    const columns = new Set();
    for (const column of table.columns) {
      identifier(column.name);
      if (columns.has(column.name) || !/^(?:bigint|int|longtext|datetime\(3\)|(?:var)?char\([1-9][0-9]{0,3}\))$/
        .test(column.type)) throw new Error('Invalid column/type');
      columns.add(column.name);
      if (typeof column.nullable !== 'boolean' || typeof column.immutable !== 'boolean' ||
          ![null, 'ascii', 'utf8mb4'].includes(column.charset) ||
          ![null, 'OWNER_TENANT', 'ascii_bin', 'utf8mb4_bin'].includes(column.collation) ||
          ![null, '0', '1', "'MAIN'", "'BEAUTY'", "'COUNT'"].includes(column.default)) {
        throw new Error('Invalid column attributes');
      }
      if (column.name === 'tenant_no' && (column.collation !== 'OWNER_TENANT' || column.charset !== null)) {
        throw new Error('Tenant must retain actual inherited owner equivalence');
      }
    }
    if ([...AUDIT].some(name => !columns.has(name)) ||
        !same(table.immutable, table.columns.filter(c => c.immutable).map(c => c.name))) {
      throw new Error('BaseEntity or immutable identity drift');
    }
    for (const [name, fields] of Object.entries({ ...table.unique, ...table.indexes })) {
      identifier(name);
      if (!fields.length || new Set(fields).size !== fields.length || fields.some(c => !columns.has(c))) {
        throw new Error('Unknown or duplicate key column');
      }
    }
    for (const [name, check] of Object.entries(table.checks)) {
      identifier(name);
      if (typeof check !== 'string' || /;|--|\/\*|\b(?:SELECT|INSERT|UPDATE|DELETE|ALTER|DROP)\b/i.test(check)) {
        throw new Error('Invalid check expression');
      }
    }
    if (!table.checks[`ck_${table.tag}_base`]?.includes('deleted=0')) throw new Error('No deletion required');
  }
  for (const [name, columns] of Object.entries(permanent)) {
    if (!same(source.tables[0].unique[name], columns)) throw new Error('Permanent unit key drift');
  }
  for (const identity of source.ownerIdentity) {
    identifier(identity.table);
    identifier(identity.column);
    if (!['TENANT', 'BINARY'].includes(identity.role)) throw new Error('Unknown owner identity role');
  }
}

function triggers(table) {
  const immutable = table.immutable.map(column =>
    `NOT (BINARY NEW.${column} <=> BINARY OLD.${column})`).join(' OR\n        ');
  const lines = ['BEGIN', `    IF ${immutable} THEN`,
    "        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Execution stock retention immutable original';", '    END IF;'];
  lines.push("    IF OLD.state IN ('CONFLICT','RELEASE_PENDING','RELEASED_NO_EFFECT') AND NEW.state<>OLD.state THEN",
    "        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Retention route terminal state cannot reopen';", '    END IF;',
    '    IF NEW.revision<>OLD.revision+1 OR NEW.observation_epoch<>OLD.observation_epoch+1 OR',
    '        OLD.revision>=9223372036854775806 OR OLD.observation_epoch>=9223372036854775806 THEN',
    "        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Retention route observation CAS required';", '    END IF;');
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
      (column.collation && column.collation !== 'OWNER_TENANT' ? ` COLLATE ${column.collation}` : '') +
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
      `) ENGINE=InnoDB COMMENT='${table.comment.replaceAll("'", "''")}';`;
  });
  const guards = source.tables.flatMap(triggers);
  const sql = '-- Generated by scripts/gen-execution-retention-route-schema.mjs; do not edit.\n' +
    '-- Empty retention authority only; no balance or historical quantity is backfilled.\n\n' +
    definitions.join('\n\n') + '\n\nDELIMITER $$\n' + guards.map(t =>
      `CREATE TRIGGER ${t.name} ${t.timing} ${t.event} ON ${t.table}\nFOR EACH ROW\n${t.body}$$`)
      .join('\n\n') + '\nDELIMITER ;\n';
  const descriptor = JSON.stringify({ schemaVersion: 1, owner: source.owner, platformVersion: 546,
    migrationScript: 'V546__execution_retention_route.sql', flywayChecksum: flywayChecksum(sql),
    migrationSha256: createHash('sha256').update(sql).digest('hex'), ownerIdentity: source.ownerIdentity,
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
