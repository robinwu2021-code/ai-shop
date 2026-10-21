import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import {writeFrozenMigration} from './lib/beauty-schema-sql.mjs';

const design = JSON.parse(fs.readFileSync(new URL(
  '../docs/technical/design/schema/beauty-appointment-decision-schema.json', import.meta.url), 'utf8'));
const expected = ['bty_appointment_resource_decision'];
if (JSON.stringify(design.tables.map(table => table.name)) !== JSON.stringify(expected)) {
  throw new Error('V4 owns exactly one original decision metadata table');
}
const identifier = name => {
  if (!/^[a-z][a-z0-9_]{0,63}$/.test(name)) throw new Error('Invalid identifier');
  return '`' + name + '`';
};
const literal = value => "'" + value.replaceAll("'", "''").replaceAll('\\', '\\\\') + "'";
const tables = design.tables.map(table => {
  const fields = [...design.common_fields.map(([name, type, description]) => ({name, type, description,
    nullable: ['created_by', 'updated_by'].includes(name)})), ...table.fields];
  const names = new Set(fields.map(field => field.name));
  if (names.size !== fields.length) throw new Error('Duplicate column');
  const parts = fields.map(field => {
    if (!/^(bigint|int|longtext|datetime\(3\)|(?:var)?char\([1-9][0-9]*\))$/.test(field.type)) {
      throw new Error('Unsupported type');
    }
    let result = identifier(field.name) + ' ' + field.type.toUpperCase();
    if (/^(?:var)?char|longtext/.test(field.type)) result += ' COLLATE utf8mb4_bin';
    result += field.nullable ? ' NULL' : ' NOT NULL';
    if (field.name === 'id') result += ' AUTO_INCREMENT';
    if (field.name === 'tenant_no') result += " DEFAULT 'MAIN'";
    if (['version', 'deleted'].includes(field.name)) result += ' DEFAULT 0';
    return result + ' COMMENT ' + literal(field.description);
  });
  parts.push('PRIMARY KEY (`id`)');
  table.unique.forEach((key, index) => {
    const columns = ['tenant_no', 'entity_no', ...key];
    if (columns.some(name => !names.has(name))) throw new Error('Unknown key column');
    parts.push('UNIQUE KEY ' + identifier('uk_' + table.name + '_' + (index + 1))
      + ' (' + columns.map(identifier).join(', ') + ')');
  });
  table.checks.forEach((check, index) => {
    if (!/^[a-zA-Z0-9_`() <>=,]+$/.test(check)) throw new Error('Unsupported check expression');
    parts.push('CONSTRAINT ' + identifier('ck_' + table.name + '_' + (index + 1)) + ' CHECK (' + check + ')');
  });
  return 'CREATE TABLE ' + identifier(table.name) + ' (\n    ' + parts.join(',\n    ')
    + '\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;';
});
const sql = '-- Immutable original source decision fence metadata; V1/V2/V3 remain frozen.\n'
  + '-- Frozen V1 remains unchanged; permission and resource authority live in services.\n\n'
  + tables.join('\n\n') + '\n';
const file = fileURLToPath(new URL('../backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/'
  + 'db/industry/beauty/V4__beauty_appointment_resource_decision.sql', import.meta.url));
if (process.argv.includes('--check')) {
  if (fs.readFileSync(file, 'utf8') !== sql) throw new Error('Beauty V4 differs from reviewed design');
} else {
  writeFrozenMigration(file, sql);
}
console.log('Beauty V4 migration verified: one incremental decision table');
