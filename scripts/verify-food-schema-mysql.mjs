// Tests only a disposable network-isolated MySQL instance, never application DB credentials.
import {execFileSync,spawnSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import {renderFoodSchema,assertFrozenFoodV1} from './lib/food-schema-sql.mjs';
const s=JSON.parse(readFileSync(new URL('../docs/technical/design/schema/food-system-design.json',import.meta.url)));
const migration=readFileSync(new URL('../'+s.databaseInitialization.migration,import.meta.url),'utf8');
assertFrozenFoodV1(migration);
assert(migration===renderFoodSchema(s),'Frozen SQL and design differ');
const name='codex-food-schema-'+randomUUID();
const docker=args=>execFileSync('docker',args,{encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();
const sql=(input,ok=true)=>{
 const r=spawnSync('docker',['exec','-i',name,'mysql','-uroot','food_schema_verify','--batch','--skip-column-names'],{input,encoding:'utf8',maxBuffer:8*1024*1024});
 if(ok)assert.equal(r.status,0,r.stderr);else assert.notEqual(r.status,0,'Expected database constraint rejection');
 return r;
};
const quote=x=>x===null?'NULL':typeof x==='number'?String(x):"'"+String(x).replaceAll("'","''")+"'";
function insert(table,overrides={}){
 const t=s.tables.find(t=>t.name===table),fields=[...s.commonFields.map(([name,type])=>({name,type})),...t.fields].filter(f=>f.name!=='id');
 const values=fields.map(f=>{
  if(Object.hasOwn(overrides,f.name))return overrides[f.name];
  if(f.nullable)return null;
  if(f.type==='json')return '{}';
  if(f.type.startsWith('datetime'))return '2026-10-20 00:00:00.000';
  if(f.type==='date')return '2026-10-20';
  if(f.type==='time')return '00:00:00';
  if(/int|decimal|boolean/.test(f.type))return ['making_qty','ready_qty','served_qty','void_qty','reserved_qty','consumed_qty'].includes(f.name)?0:1;
  return f.name+'_1';
 });
 return 'INSERT INTO `'+table+'` ('+fields.map(f=>'`'+f.name+'`').join(',')+') VALUES ('+values.map(quote).join(',')+');';
}
docker(['run','--pull','never','--detach','--name',name,'--network','none','--tmpfs','/var/lib/mysql','--env','MYSQL_ALLOW_EMPTY_PASSWORD=yes','--env','MYSQL_DATABASE=food_schema_verify','mysql:9.7.2']);
try{
 let ready=false;
 for(let i=0;i<90;i++){
  try{docker(['exec',name,'mysql','-uroot','food_schema_verify','-e','SELECT 1']);ready=true;break;}catch{await new Promise(r=>setTimeout(r,500));}
 }
 assert(ready,'Disposable MySQL did not become ready');
 sql(migration);
 assert.equal(sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();").stdout.trim(),'73');
 // Parent-to-many relations must accept multiple children and reject only exact duplicate relationships.
 for(const [table,rows] of [
  ['fnb_menu_store',[{menu_no:'M',store_no:'S1'},{menu_no:'M',store_no:'S2'}]],
  ['fnb_dish_option_group',[{goods_no:'G',group_no:'O1'},{goods_no:'G',group_no:'O2'}]],
  ['fnb_menu_item_sku',[{menu_item_no:'I',sku_no:'S1'},{menu_item_no:'I',sku_no:'S2'}]],
  ['fnb_sku_station',[{store_no:'S1',sku_no:'SKU',station_no:'K1'},{store_no:'S1',sku_no:'SKU',station_no:'K2'},{store_no:'S2',sku_no:'SKU',station_no:'K1'}]],
 ]){
  rows.forEach(row=>sql(insert(table,row)));
  const rejected=sql(insert(table,rows[0]),false);assert.match(rejected.stderr,/1062/);
 }
 sql(insert('fnb_kitchen_task',{task_no:'T1',component_no:null,task_part_key:'LINE-1'}));
 assert.match(sql(insert('fnb_kitchen_task',{task_no:'T2',component_no:null,task_part_key:'LINE-1'}),false).stderr,/1062/);
 sql(insert('fnb_order_line',{food_line_no:'L1',batch_no:'B',source_line_ref:'SOURCE'}));
 assert.match(sql(insert('fnb_order_line',{food_line_no:'L2',batch_no:'B',source_line_ref:'SOURCE'}),false).stderr,/1062/);
 sql(insert('fnb_sales_restriction',{restriction_no:'R1',quota_qty:5,reserved_qty:3,consumed_qty:2}));
 assert.match(sql(insert('fnb_sales_restriction',{restriction_no:'R2',quota_qty:5,reserved_qty:4,consumed_qty:2}),false).stderr,/3819|CONSTRAINT/i);
 assert.match(sql(insert('fnb_table',{capacity:-1}),false).stderr,/3819|CONSTRAINT/i);
 console.log('PASS MySQL 9.7.2: 73 tables created; four parent/child cardinalities, nullable-component dispatch dedup, source-line dedup, quota and capacity constraints verified.');
}finally{docker(['rm','--force',name]);}
