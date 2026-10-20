import fs from 'node:fs';
import {createHash} from 'node:crypto';
import {validateFoodDesign} from './food-design-validation.mjs';
const id=n=>{if(!/^[a-z][a-z0-9_]*$/.test(n))throw Error('Invalid SQL identifier');return '`'+n+'`';};
const literal=s=>"'"+s.replaceAll("'","''").replaceAll('\\','\\\\')+"'";
function checks(t){
 const names=new Set(t.fields.map(f=>f.name)),out=[];
 for(const n of ['qty','quantity','capacity','party_size','persons','seats','units_per_pick'])if(names.has(n))out.push(`${id(n)} > 0`);
 for(const n of ['min_qty','max_qty','default_qty','reserved_qty','consumed_qty','making_qty','ready_qty','served_qty','void_qty','remaining_qty','opening_minor','counted_minor'])if(names.has(n))out.push(`${id(n)} >= 0`);
 if(names.has('min_qty')&&names.has('max_qty'))out.push('`min_qty` <= `max_qty`');
 if(names.has('default_qty')&&names.has('max_qty'))out.push('`default_qty` <= `max_qty`');
 if(t.name==='fnb_sales_restriction')out.push('`quota_qty` IS NULL OR (`quota_qty` > 0 AND `reserved_qty` + `consumed_qty` <= `quota_qty`)');
 if(t.name==='fnb_kitchen_task')out.push('`served_qty` <= `ready_qty`','`ready_qty` <= `qty`','`making_qty` <= `qty`','`void_qty` <= `qty`');
 if(t.name==='fnb_option_group_item')out.push("(`source_kind` = 'SPEC_VALUE' AND `spec_value_no` IS NOT NULL AND `modifier_no` IS NULL) OR (`source_kind` = 'MODIFIER' AND `modifier_no` IS NOT NULL AND `spec_value_no` IS NULL)");
 if(names.has('end_day_offset'))out.push('`end_day_offset` IN (0, 1)');
 if(names.has('weekday'))out.push('`weekday` BETWEEN 1 AND 7');
 if(names.has('cutover_minute'))out.push('`cutover_minute` BETWEEN 0 AND 1439');
 if(t.name==='fnb_modifier_component')out.push('`combo_no` <> `member_no`');
 return out;
}
export function renderFoodSchema(s){
 validateFoodDesign(s);
 const tables=s.tables.filter(t=>t.name.startsWith('fnb_'));
 let sql='-- Food-owned tables only; use the existing product/order database.\n-- Flyway location: db/industry/food; history: fnb_flyway_history; version: 1.\n-- Frozen after application. Add a new migration for any subsequent change.\n-- No cross-domain physical foreign keys; services must enforce ownership and concurrency.\n\n';
 for(const t of tables){
  const own=new Set(t.fields.map(f=>f.name));
  const fields=[...s.commonFields.filter(f=>!own.has(f[0])).map(([name,type,label])=>({name,type,label,nullable:false})),...t.fields];
  const lines=fields.map(f=>{
   if(!/^(bigint|int|tinyint|boolean|json|text|date|time|datetime\(3\)|varchar\(\d+\)|char\(\d+\)|varbinary\(\d+\)|decimal\(\d+,\d+\))$/.test(f.type))throw Error(`Unsupported type ${t.name}.${f.name}`);
   const binary=/^(var)?char/.test(f.type)&&(/_no$|_ref$|_key$|_hash$|^code$|^source_line_ref$/.test(f.name));
   const extra=f.name==='id'?' AUTO_INCREMENT':f.name==='version'?' DEFAULT 0':f.name==='deleted'?' DEFAULT 0':f.name==='tenant_no'?" DEFAULT 'MAIN'":'';
   return '    '+id(f.name)+' '+f.type.toUpperCase()+(binary?' CHARACTER SET utf8mb4 COLLATE utf8mb4_bin':'')+(f.nullable?' NULL':' NOT NULL')+extra+' COMMENT '+literal(f.label);
  });
  lines.push('    PRIMARY KEY (`id`)');
  t.unique.forEach((key,i)=>lines.push('    UNIQUE KEY '+id('uk_food_'+(i+1))+' ('+key.map(id).join(', ')+')'));
  lines.push('    KEY `ix_food_audit` (`entity_no`, `created_at`, `id`)');
  if(own.has('store_no'))lines.push('    KEY `ix_food_store` (`entity_no`, `store_no`, `updated_at`, `id`)');
  const parents=t.fields.filter(f=>f.ref?.startsWith('fnb_')&&!t.unique.some(key=>key[0]===f.name));
  parents.forEach((f,i)=>lines.push('    KEY '+id('ix_food_ref_'+(i+1))+' (`entity_no`, '+id(f.name)+', `id`)'));
  checks(t).forEach(c=>lines.push('    CHECK ('+c+')'));
  sql+='CREATE TABLE '+id(t.name)+' (\n'+lines.join(',\n')+'\n) ENGINE=InnoDB COMMENT='+literal(t.label)+';\n\n';
 }
 return sql;
}
export function writeFrozenMigration(path,sql,io=fs){
 if(io.existsSync(path)){
  if(io.readFileSync(path,'utf8')!==sql)throw Error('Frozen migration differs; create a new version instead of overwriting '+path);
 }else io.writeFileSync(path,sql);
}

// Captured from the already-applied V1 before changing source ownership.
const FOOD_V1_SHA256='215d76bf3a1c23aefc31bbbaa1eea25fd62c7bad3d7eb9123f5a84f0cc347aa9';
export function assertFrozenFoodV1(sql){
 if(createHash('sha256').update(sql).digest('hex')!==FOOD_V1_SHA256)
  throw Error('Frozen V1 SHA-256 differs; create a new migration instead');
}
