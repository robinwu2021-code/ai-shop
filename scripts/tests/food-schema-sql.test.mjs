import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
import path from 'node:path';
import os from 'node:os';
import {spawnSync} from 'node:child_process';
import {renderFoodSchema,writeFrozenMigration} from '../lib/food-schema-sql.mjs';
const s=JSON.parse(fs.readFileSync(new URL('../../docs/technical/design/schema/food-system-design.json',import.meta.url)));
test('creates exactly the 73 food-owned tables and never existing core/shared tables',()=>{
 const sql=renderFoodSchema(s);const names=[...sql.matchAll(/CREATE TABLE `([^`]+)`/g)].map(m=>m[1]);
 assert.equal(names.length,73);assert(names.every(n=>n.startsWith('fnb_')));
 assert(!/\b(?:ALTER|DROP|TRUNCATE|INSERT|UPDATE|DELETE|CREATE DATABASE)\b/i.test(sql.replace(/--[^\n]*|'(?:[^']|'')*'/g,'')));
 assert(!/CREATE TABLE.*(?:prd_|ord_|pay_|prn_|rpt_)/.test(sql));
});
test('generated physical constraints preserve the corrected relationship cardinalities',()=>{
 const sql=renderFoodSchema(s);
 assert(sql.includes('(`menu_no`, `store_no`)'));
 assert(sql.includes('(`goods_no`, `group_no`)'));
 assert(sql.includes('(`store_no`, `sku_no`, `station_no`, `task_role`)'));
 assert(sql.includes('(`ticket_no`, `task_part_key`, `station_no`, `task_role`)'));
});
test('schema includes atomic-guard prerequisites and basic quantity checks',()=>{
 const sql=renderFoodSchema(s);
 assert(sql.includes('`reserved_qty` >= 0'));
 assert(sql.includes('`reserved_qty` + `consumed_qty` <= `quota_qty`'));
 assert(sql.includes('`ready_qty` + `served_qty`')===false); // ready and served are cumulative; not independent totals.
 assert(sql.includes('`served_qty` <= `ready_qty`'));
});
test('frozen migration is idempotent for identical content and rejects overwrite',()=>{
 const path='/virtual/V1.sql';let value;
 const io={existsSync:()=>value!==undefined,readFileSync:()=>value,writeFileSync:(_,s)=>{value=s;}};
 writeFrozenMigration(path,'one',io);writeFrozenMigration(path,'one',io);
 assert.equal(value,'one');assert.throws(()=>writeFrozenMigration(path,'two',io),/frozen/i);assert.equal(value,'one');
});

test('movedMigrationPreservesBytes',()=>{
 const file=new URL('../../backend-plugins/shop-industry-food/food-core/src/main/resources/db/industry/food/V1__food_business_tables.sql',import.meta.url);
 assert(fs.existsSync(file),'frozen migration must live in food-core');
 assert.equal(createHash('sha256').update(fs.readFileSync(file)).digest('hex'),'215d76bf3a1c23aefc31bbbaa1eea25fd62c7bad3d7eb9123f5a84f0cc347aa9');
 assert(!fs.existsSync(new URL('../../backend/shop-app/src/main/resources/db/industry/food/V1__food_business_tables.sql',import.meta.url)));
 let copies=0;
 for(const module of ['food-api','food-core','food-web','food-channel','food-server']){
  const resources=new URL('../../backend-plugins/shop-industry-food/'+module+'/src/main/resources/',import.meta.url);
  if(fs.existsSync(resources))copies+=fs.readdirSync(resources,{recursive:true}).filter(name=>path.basename(name)==='V1__food_business_tables.sql').length;
 }
 assert.equal(copies,1,'one food-owned V1 source only');
 assert.equal(s.databaseInitialization.migration,'backend-plugins/shop-industry-food/food-core/src/main/resources/db/industry/food/V1__food_business_tables.sql');
});

test('generator cannot bless a changed V1 even when design and SQL change together',()=>{
 const fixture=fs.mkdtempSync(path.join(os.tmpdir(),'food-v1-freeze-'));
 const repo=new URL('../../',import.meta.url);
 try{
  for(const name of ['scripts/gen-food-schema.mjs','scripts/lib/food-schema-sql.mjs','scripts/lib/food-design-validation.mjs','docs/technical/design/schema/food-system-design.json',s.databaseInitialization.migration]){
   const destination=path.join(fixture,name);fs.mkdirSync(path.dirname(destination),{recursive:true});
   fs.copyFileSync(new URL(name,repo),destination);
  }
  const run=(args=['--check'])=>spawnSync(process.execPath,[path.join(fixture,'scripts/gen-food-schema.mjs'),...args],{encoding:'utf8'});
  const baseline=run();assert.equal(baseline.status,0,baseline.stderr);
  const changed=structuredClone(s);changed.tables.find(t=>t.name==='fnb_table').label+=' changed';
  fs.writeFileSync(path.join(fixture,'docs/technical/design/schema/food-system-design.json'),JSON.stringify(changed));
  const file=path.join(fixture,s.databaseInitialization.migration);
  fs.writeFileSync(file,renderFoodSchema(changed));
  const before=fs.readFileSync(file);
  for(const args of [[],['--check']]){
   const rejected=run(args);
   assert.notEqual(rejected.status,0,'changing both design and SQL must not authorize frozen V1');
   assert.match(rejected.stderr,/frozen|SHA-256/i);
   assert.deepEqual(fs.readFileSync(file),before,'generator must not modify the rejected file');
  }
 }finally{fs.rmSync(fixture,{recursive:true,force:true});}
});
