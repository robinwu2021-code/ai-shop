import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import {writeFrozenMigration, renderBeautyAppointmentTables} from './lib/beauty-schema-sql.mjs';

const design = JSON.parse(fs.readFileSync(new URL(
  '../docs/technical/design/schema/beauty-appointment-business-schema.json', import.meta.url), 'utf8'));
const expected = ['bty_appointment_preparation', 'bty_appointment_operation', 'bty_appointment_arrangement', 'bty_appointment_current'];
if (JSON.stringify(design.tables.map(table => table.name)) !== JSON.stringify(expected)) {
  throw new Error('V3 owns exactly four appointment business tables');
}
const tables = renderBeautyAppointmentTables(design);
const sql = '-- Beauty appointment preparations, durable operations and immutable arrangement pointers.\n'
  + '-- Frozen V1 remains unchanged; permission and resource authority live in services.\n\n'
  + tables.join('\n\n') + '\n';
const file = fileURLToPath(new URL('../backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/'
  + 'db/industry/beauty/V3__beauty_appointment_business.sql', import.meta.url));
if (process.argv.includes('--check')) {
  if (fs.readFileSync(file, 'utf8') !== sql) throw new Error('Beauty V3 differs from reviewed design');
} else {
  writeFrozenMigration(file, sql);
}
console.log('Beauty V3 migration verified: four incremental private tables');
