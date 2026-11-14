import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import {writeFrozenMigration} from './lib/beauty-schema-sql.mjs';

const design = JSON.parse(fs.readFileSync(new URL(
  '../docs/technical/design/schema/beauty-source-observation-schema.json', import.meta.url), 'utf8'));
if (design.version !== 8 || design.table.name !== 'bty_source_observation') {
  throw new Error('V8 owns exactly the source observation watermark');
}
const id = name => {
  if (!/^[a-z][a-z0-9_]{0,63}$/.test(name)) throw new Error('Invalid identifier');
  return '`' + name + '`';
};
const literal = value => "'" + value.replaceAll("'", "''") + "'";
const table = design.table;
const names = new Set(table.fields.map(field => field.name));
if (names.size !== table.fields.length) throw new Error('Duplicate field');
const parts = table.fields.map(field => {
  if (!/^(bigint|int|json|datetime\(3\)|(?:var)?char\([1-9][0-9]*\))$/.test(field.type)) {
    throw new Error('Invalid type');
  }
  let sql = id(field.name) + ' ' + field.type.toUpperCase()
    + (/^(?:var)?char/.test(field.type) ? ' COLLATE utf8mb4_bin' : '')
    + (field.nullable ? ' NULL' : ' NOT NULL')
    + (field.name === 'id' ? ' AUTO_INCREMENT' : '');
  if (Object.hasOwn(field, 'default')) {
    if (field.default !== 0 && field.default !== 'MAIN') throw new Error('Invalid default');
    sql += ' DEFAULT ' + (typeof field.default === 'string' ? literal(field.default) : field.default);
  }
  return sql + ' COMMENT ' + literal(field.description);
});
parts.push('PRIMARY KEY (`id`)');
for (const [keys, prefix] of [[table.unique, 'uk'], [table.indexes, 'idx']]) {
  keys.forEach((key, index) => {
    if (!key.length || new Set(key).size !== key.length || key.some(name => !names.has(name))) {
      throw new Error('Invalid key');
    }
    parts.push((prefix === 'uk' ? 'UNIQUE KEY ' : 'KEY ') + id(prefix + '_' + table.name + '_' + (index + 1))
      + ' (' + key.map(id).join(', ') + ')');
  });
}
table.checks.forEach((check, index) => {
  if (!/^[a-zA-Z0-9_`'() <>=,]+$/.test(check)) throw new Error('Invalid check');
  for (const match of check.matchAll(/`([^`]+)`/g)) {
    if (!names.has(match[1])) throw new Error('Unknown check column');
  }
  parts.push('CONSTRAINT ' + id('ck_' + table.name + '_' + (index + 1)) + ' CHECK (' + check + ')');
});
const sql = '-- Beauty source observation watermark. V1-V7 remain frozen.\n\nCREATE TABLE '
  + id(table.name) + ' (\n    ' + parts.join(',\n    ')
  + '\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;\n';
const file = fileURLToPath(new URL('../backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/'
  + 'db/industry/beauty/V8__beauty_source_observation.sql', import.meta.url));
if (process.argv.includes('--check')) {
  if (fs.readFileSync(file, 'utf8') !== sql) throw new Error('Frozen V8 drift');
} else {
  writeFrozenMigration(file, sql);
}
console.log('Beauty V8 migration verified: one nonmonetary source observation table');
