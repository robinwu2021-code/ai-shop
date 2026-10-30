import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import {writeFrozenMigration} from './lib/beauty-schema-sql.mjs';
const design = JSON.parse(fs.readFileSync(new URL(
  '../docs/technical/design/schema/beauty-reception-identity-guard-schema.json', import.meta.url), 'utf8'));
if (design.version !== 6 || design.table !== 'bty_reception_operation'
  || design.constraint !== 'ck_bty_reception_operation_5') throw new Error('V6 owns only the additive identity guard');
if (!/^[a-zA-Z0-9_`'() >,]+$/.test(design.expression)) throw new Error('Unsupported CHECK syntax');
for (const match of design.expression.matchAll(/`([^`]+)`/g)) {
  if (!['identity_kind','identity_ref'].includes(match[1])) throw new Error('Unknown guard column');
}
const sql = '-- Beauty V6: reject half-null reception identity; V1-V5 remain frozen.\n'
  + '-- Existing invalid rows must block upgrade; no repair, baseline or data rewrite.\n\n'
  + 'ALTER TABLE `' + design.table + '`\n    ADD CONSTRAINT `' + design.constraint
  + '` CHECK (' + design.expression + ');\n';
const file = fileURLToPath(new URL('../backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/'
  + 'db/industry/beauty/V6__beauty_reception_identity_guard.sql', import.meta.url));
if (process.argv.includes('--check')) {
  if (fs.readFileSync(file, 'utf8') !== sql) throw new Error('Frozen V6 drift');
} else writeFrozenMigration(file, sql);
console.log('Beauty V6 identity guard verified');
