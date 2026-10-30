import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import {renderBeautySchema, writeFrozenMigration} from './lib/beauty-schema-sql.mjs';
const design = JSON.parse(fs.readFileSync(new URL('../docs/technical/design/schema/beauty-database-design.json', import.meta.url), 'utf8'));
const file = fileURLToPath(new URL('../backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V1__beauty_p1_tables.sql', import.meta.url));
const sql = renderBeautySchema(design);
if (process.argv.includes('--check')) {
  if (!fs.existsSync(file) || fs.readFileSync(file, 'utf8') !== sql) throw new Error('Beauty migration differs from its reviewed design');
} else {
  writeFrozenMigration(file, sql);
}
console.log('Beauty P1 migration verified: 16 private tables');
