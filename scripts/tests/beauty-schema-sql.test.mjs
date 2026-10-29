import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
const design=JSON.parse(fs.readFileSync(new URL('../../docs/technical/design/schema/beauty-database-design.json',import.meta.url)));
const moduleUrl=new URL('../lib/beauty-schema-sql.mjs',import.meta.url);
async function implementation(){assert(fs.existsSync(moduleUrl),'beauty must implement the P1-owned migration renderer');return import(moduleUrl);}
const expected=['bty_store_policy','bty_store_policy_version','bty_service_definition','bty_service_definition_version','bty_service_requirement','bty_appointment','bty_appointment_item','bty_appointment_assignment','bty_work_order','bty_service_task','bty_service_step','bty_service_participation','bty_service_submission','bty_trade_binding','bty_action_log','bty_source_allocation_guard'];
test('renders only the sixteen P1 beauty tables, excluding shared and future data',async()=>{
 const {renderBeautySchema}=await implementation();const sql=renderBeautySchema(design);
 assert.deepEqual([...sql.matchAll(/CREATE TABLE `([^`]+)`/g)].map(m=>m[1]).sort(),expected.toSorted());
 assert(!/CREATE TABLE `(mch_|prd_|ord_|pmt_|bty_care_|bty_treatment_|bty_commission_)/.test(sql));
 assert(!/\b(?:DROP|TRUNCATE|ALTER|INSERT|UPDATE|DELETE)\s/i.test(sql));
});
test('prefixes business uniqueness with tenant and entity without using source revision as identity',async()=>{
 const {renderBeautySchema}=await implementation();const sql=renderBeautySchema(design);
 assert(sql.includes('(`tenant_no`, `entity_no`, `source_kind`, `source_no`, `source_line_ref`)'));
 assert(!/UNIQUE KEY[^\n]*`source_revision`/.test(sql));
 assert(sql.includes('(`tenant_no`, `entity_no`, `store_no`, `action`, `request_key_hash`)'));
});
test('rejects a malformed key or SQL identifier before generating migration',async()=>{
 const {renderBeautySchema}=await implementation();const broken=structuredClone(design);broken.tables.find(t=>t.name==='bty_service_task').unique.push(['missing_column']);
 assert.throws(()=>renderBeautySchema(broken),/unknown.*column/i);
 broken.tables[0].name='mch_personnel; DROP TABLE x';assert.throws(()=>renderBeautySchema(broken),/identifier/i);
});
test('keeps quantity and interval invariants in the actual DDL',async()=>{
 const {renderBeautySchema}=await implementation();const sql=renderBeautySchema(design);
 assert(sql.includes('`done_qty` + `voided_qty` <= `qty`'));
 assert(sql.includes('`allocated_qty` <= `source_qty`'));
 assert(sql.includes('`end_at` > `start_at`'));
});
test('rejects nullable source identity because it defeats the unique allocation guard',async()=>{
 const {renderBeautySchema}=await implementation();const broken=structuredClone(design);
 broken.tables.find(t=>t.name==='bty_source_allocation_guard').fields.find(f=>f.name==='source_line_ref').nullable=true;
 assert.throws(()=>renderBeautySchema(broken),/nullable.*source|source.*nullable/i);
});
test('write-once migration rejects drift and preserves existing bytes',async()=>{
 const {writeFrozenMigration}=await implementation();const dir=fs.mkdtempSync(path.join(os.tmpdir(),'beauty-frozen-'));const file=path.join(dir,'V1.sql');
 try{writeFrozenMigration(file,'first');writeFrozenMigration(file,'first');assert.throws(()=>writeFrozenMigration(file,'changed'),/new migration|frozen/i);assert.equal(fs.readFileSync(file,'utf8'),'first');}finally{fs.rmSync(dir,{recursive:true,force:true});}
});
test('shared appointment renderer retains the frozen V3 and V4 bytes', async () => {
 const {renderBeautyAppointmentTables}=await implementation();
 for (const [schema,migration,header] of [
  ['business','V3__beauty_appointment_business.sql','-- Beauty appointment preparations, durable operations and immutable arrangement pointers.'],
  ['decision','V4__beauty_appointment_resource_decision.sql','-- Immutable original source decision fence metadata; V1/V2/V3 remain frozen.']
 ]) {
  const input=JSON.parse(fs.readFileSync(new URL(`../../docs/technical/design/schema/beauty-appointment-${schema}-schema.json`,import.meta.url)));
  const actual=header+'\n-- Frozen V1 remains unchanged; permission and resource authority live in services.\n\n'
   +renderBeautyAppointmentTables(input).join('\n\n')+'\n';
  const frozen=fs.readFileSync(new URL(`../../backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/${migration}`,import.meta.url),'utf8');
  assert.equal(actual,frozen);
 }
});
test('shared appointment renderer rejects unsafe identifiers, missing key columns and check expressions', async () => {
 const {renderBeautyAppointmentTables}=await implementation();
 const input=JSON.parse(fs.readFileSync(new URL('../../docs/technical/design/schema/beauty-appointment-decision-schema.json',import.meta.url)));
 for (const [mutate,expected] of [
  [schema=>{schema.tables[0].name='unsafe;drop';},/identifier/i],
  [schema=>{schema.tables[0].unique.push(['missing']);},/unknown.*column/i],
  [schema=>{schema.tables[0].checks.push('version >= 0; DROP TABLE x');},/check/i]
 ]) {
  const broken=structuredClone(input);mutate(broken);assert.throws(()=>renderBeautyAppointmentTables(broken),expected);
 }
});
