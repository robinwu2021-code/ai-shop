import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {renderFoodV1, assertFrozenFoodV1, renderFoodSchema, renderRecipeDraftV4} from '../lib/food-schema-sql.mjs';
const s=JSON.parse(fs.readFileSync(new URL('../../docs/technical/design/schema/food-system-design.json',import.meta.url)));
test('recipe draft V4 contains only two newly owned tables and never a fake conversion version',()=>{
 const sql=fs.readFileSync(new URL('../../backend-plugins/shop-industry-food/food-core/src/main/resources/db/industry/food/V4__recipe_draft_tables.sql',import.meta.url),'utf8');
 assert.deepEqual([...sql.matchAll(/CREATE TABLE `([^`]+)`/g)].map(m=>m[1]),['fnb_recipe','fnb_recipe_line']);
 assert.match(sql,/`material_definition_hash` VARCHAR\(64\)/);
 assert.match(sql,/`conversion_ref` VARCHAR\(64\)[^\n]* NULL/);
 assert.match(sql,/UNIQUE KEY[^\n]*\(`store_no`, `sku_no`, `recipe_code`\)/);
 assert(!sql.includes('ALTER TABLE'));
 assert.equal(sql,renderRecipeDraftV4(s));
});
test('recipe draft source distinguishes migrated base lines from planned adjustments and execution',()=>{
 assert.equal(s.databaseInitialization.targetSchemaVersion,7);
 for(const name of ['fnb_recipe','fnb_recipe_line'])assert.equal(s.tables.find(t=>t.name===name).deployment,'MIGRATION_V4');
 assert.equal(s.tables.find(t=>t.name==='fnb_recipe_adjustment').deployment,'MIGRATION_V5');
 assert.equal(s.catalogEnhancement.contracts.inventory.implementation,'PENDING');
 assert.equal(s.catalogEnhancement.runtimeEnabled,false);
 const line=s.tables.find(t=>t.name==='fnb_recipe_line');
 assert(line.fields.some(f=>f.name==='material_definition_hash'&&f.type==='varchar(64)'));
 assert(line.fields.find(f=>f.name==='conversion_ref').nullable);
 assertFrozenFoodV1(renderFoodV1(s));
 assert.match(renderFoodSchema(s),/version: 7/);
});
