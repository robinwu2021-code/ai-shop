import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
import * as schema from '../lib/food-schema-sql.mjs';
const s=JSON.parse(fs.readFileSync(new URL('../../docs/technical/design/schema/food-system-design.json',import.meta.url)));
const migrations=new URL('../../backend-plugins/shop-industry-food/food-core/src/main/resources/db/industry/food/',import.meta.url);
test('V5 owns only adjustment working rows and preserves frozen V4 bytes',()=>{
 assert.equal(s.databaseInitialization.targetSchemaVersion,8);
 const sql=fs.readFileSync(new URL('V5__recipe_adjustment_table.sql',migrations),'utf8');
 assert.equal(sql,schema.renderRecipeAdjustmentV5(s));
 assert.deepEqual([...sql.matchAll(/CREATE TABLE `([^`]+)`/g)].map(m=>m[1]),['fnb_recipe_adjustment']);
 assert.match(sql,/`sort_no` INT NOT NULL/);
 assert.match(sql,/`option_identity_hash` VARCHAR\(64\)[^\n]* NOT NULL/);
 assert.match(sql,/\(`recipe_no`, `option_item_no`, `target_line_no`\)/);
 assert(!sql.includes('ALTER TABLE'));
 const v4=fs.readFileSync(new URL('V4__recipe_draft_tables.sql',migrations));
 assert.equal(createHash('sha256').update(v4).digest('hex'),'afe484f506d4e04ef376e0788b1bff508714039e3c1843aa0919d86391ba951a');
});
test('manual migration inventory includes V2 through V8 without rewriting actual initial database evidence',()=>{
 assert.equal(s.databaseInitialization.version,'1');
 assert.equal(s.databaseInitialization.businessTableCount,73);
 const names=s.databaseInitialization.additionalMigrations.map(p=>p.split('/').at(-1));
 assert.deepEqual(names,fs.readdirSync(migrations).filter(n=>/^V[2-8]__/.test(n)).sort());
 assert.equal(s.tables.find(t=>t.name==='fnb_recipe_adjustment').deployment,'MIGRATION_V5');
 assert.match(s.dtos.Recipe,/adjustments:RecipeAdjustmentVO\[\]/);
});
