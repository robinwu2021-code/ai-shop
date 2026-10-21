import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import {writeFrozenMigration, renderBeautyAppointmentTables} from './lib/beauty-schema-sql.mjs';

const design = JSON.parse(fs.readFileSync(new URL(
  '../docs/technical/design/schema/beauty-appointment-decision-schema.json', import.meta.url), 'utf8'));
const expected = ['bty_appointment_resource_decision'];
if (JSON.stringify(design.tables.map(table => table.name)) !== JSON.stringify(expected)) {
  throw new Error('V4 owns exactly one original decision metadata table');
}
const tables = renderBeautyAppointmentTables(design);
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
