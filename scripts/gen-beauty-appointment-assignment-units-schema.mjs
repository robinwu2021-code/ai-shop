import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import {writeFrozenMigration} from './lib/beauty-schema-sql.mjs';
const design = JSON.parse(fs.readFileSync(new URL(
  '../docs/technical/design/schema/beauty-appointment-assignment-units-schema.json', import.meta.url), 'utf8'));
const columns = ['tenant_no', 'entity_no', 'appointment_item_no', 'appointment_revision', 'requirement_no', 'resource_no'];
if (design.version !== 7 || design.table !== 'bty_appointment_assignment'
  || design.dropUniqueIndex !== 'uk_bty_appointment_assignment_2'
  || design.addIndex !== 'idx_bty_appointment_assignment_1'
  || design.retainedUniqueIndex !== 'uk_bty_appointment_assignment_1'
  || JSON.stringify(design.columns) !== JSON.stringify(columns)) {
  throw new Error('V7 only replaces the capacity-unit index; assignment business identity stays unique');
}
const sql = '-- Beauty V7: preserve each capacity demand unit as a distinct planned assignment.\n'
  + '-- V1-V6 and all existing rows remain unchanged; planned_assignment_no stays unique.\n\n'
  + 'ALTER TABLE `' + design.table + '`\n    DROP INDEX `' + design.dropUniqueIndex + '`,\n'
  + '    ADD INDEX `' + design.addIndex + '` (' + columns.map(c => '`' + c + '`').join(', ') + ');\n';
const file = fileURLToPath(new URL('../backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/'
  + 'db/industry/beauty/V7__beauty_appointment_assignment_units.sql', import.meta.url));
if (process.argv.includes('--check')) {
  if (fs.readFileSync(file, 'utf8') !== sql) throw new Error('Frozen V7 drift');
} else writeFrozenMigration(file, sql);
console.log('Beauty V7 assignment unit index verified');
