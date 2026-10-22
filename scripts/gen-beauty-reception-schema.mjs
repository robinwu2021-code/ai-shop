import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import {writeFrozenMigration} from './lib/beauty-schema-sql.mjs';
const design = JSON.parse(fs.readFileSync(new URL('../docs/technical/design/schema/beauty-reception-schema.json', import.meta.url), 'utf8'));
const expected = ['bty_reception_operation', 'bty_work_order_context', 'bty_guest_claim_receipt', 'bty_arrival_challenge'];
if (JSON.stringify(design.tables.map(t => t.name)) !== JSON.stringify(expected)) throw new Error('V5 owns exactly four reception tables');
const id = name => {
  if (!/^[a-z][a-z0-9_]{0,63}$/.test(name)) throw new Error('Invalid identifier');
  return '`' + name + '`';
};
const literal = value => "'" + value.replaceAll("'", "''") + "'";
const tables = design.tables.map(table => {
  const fields = [...design.common_fields.map(([name,type,description]) => ({name,type,description,nullable:['created_by','updated_by'].includes(name)})), ...table.fields];
  const names = new Set(fields.map(f => f.name));
  if (names.size !== fields.length) throw new Error('Duplicate field');
  const parts = fields.map(f => {
    if (!/^(bigint|int|json|datetime\(3\)|(?:var)?char\([1-9][0-9]*\))$/.test(f.type)) throw new Error('Invalid type');
    return id(f.name) + ' ' + f.type.toUpperCase() + (/^(?:var)?char/.test(f.type) ? ' COLLATE utf8mb4_bin' : '')
      + (f.nullable ? ' NULL' : ' NOT NULL') + (f.name === 'id' ? ' AUTO_INCREMENT' : '')
      + (f.name === 'tenant_no' ? " DEFAULT 'MAIN'" : '') + (['version','deleted'].includes(f.name) ? ' DEFAULT 0' : '')
      + ' COMMENT ' + literal(f.description);
  });
  parts.push('PRIMARY KEY (`id`)');
  for (const [keys,prefix] of [[table.unique,'uk'],[table.indexes,'idx']]) keys.forEach((key,i) => {
    if (!key.length || new Set(key).size !== key.length || key.some(k => !names.has(k))) throw new Error('Invalid key');
    parts.push((prefix === 'uk' ? 'UNIQUE KEY ' : 'KEY ') + id(prefix+'_'+table.name+'_'+(i+1)) + ' ('+key.map(id).join(', ')+')');
  });
  table.checks.forEach((check,i) => {
    if (!/^[a-zA-Z0-9_`'() <>=,]+$/.test(check)) throw new Error('Invalid check');
    for (const match of check.matchAll(/`([^`]+)`/g)) if (!names.has(match[1])) throw new Error('Unknown check column');
    parts.push('CONSTRAINT '+id('ck_'+table.name+'_'+(i+1))+' CHECK ('+check+')');
  });
  return 'CREATE TABLE '+id(table.name)+' (\n    '+parts.join(',\n    ')+'\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;';
});
const sql = '-- Beauty reception scope and immutable guest receipts. V1-V4 remain frozen.\n\n'+tables.join('\n\n')+'\n';
const file = fileURLToPath(new URL('../backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V5__beauty_reception_work_order.sql', import.meta.url));
if (process.argv.includes('--check')) { if (fs.readFileSync(file,'utf8') !== sql) throw new Error('Frozen V5 drift'); }
else writeFrozenMigration(file,sql);
console.log('Beauty V5 migration verified: four reception tables');
