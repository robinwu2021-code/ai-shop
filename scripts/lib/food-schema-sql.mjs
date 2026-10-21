import fs from 'node:fs';
import {createHash} from 'node:crypto';
import {validateFoodDesign} from './food-design-validation.mjs';
const id=n=>{if(!/^[a-z][a-z0-9_]*$/.test(n))throw Error('Invalid SQL identifier');return '`'+n+'`';};
const literal=s=>"'"+s.replaceAll("'","''").replaceAll('\\','\\\\')+"'";
function checks(t){
 const names=new Set(t.fields.map(f=>f.name)),out=[];
 for(const n of ['qty','quantity','capacity','party_size','persons','seats','units_per_pick'])if(names.has(n)&&!(t.name==='fnb_recipe_adjustment'&&n==='qty'))out.push(`${id(n)} > 0`);
 for(const n of ['min_qty','max_qty','default_qty','reserved_qty','consumed_qty','making_qty','ready_qty','served_qty','void_qty','remaining_qty','opening_minor','counted_minor'])if(names.has(n))out.push(`${id(n)} >= 0`);
 if(names.has('min_qty')&&names.has('max_qty'))out.push('`min_qty` <= `max_qty`');
 if(names.has('default_qty')&&names.has('max_qty'))out.push('`default_qty` <= `max_qty`');
 if(t.name==='fnb_sales_restriction')out.push('`quota_qty` IS NULL OR (`quota_qty` > 0 AND `reserved_qty` + `consumed_qty` <= `quota_qty`)');
 if(t.name==='fnb_kitchen_task')out.push('`served_qty` <= `ready_qty`','`ready_qty` <= `qty`','`making_qty` <= `qty`','`void_qty` <= `qty`');
 if(t.name==='fnb_option_group_item')out.push("(`source_kind` = 'SPEC_VALUE' AND `spec_value_no` IS NOT NULL AND `modifier_no` IS NULL) OR (`source_kind` = 'MODIFIER' AND `modifier_no` IS NOT NULL AND `spec_value_no` IS NULL)");
 if(names.has('end_day_offset'))out.push('`end_day_offset` IN (0, 1)');
 if(names.has('weekday'))out.push('`weekday` BETWEEN 1 AND 7');
 if(t.name==='fnb_table_occupancy'&&names.has('sharing_mode'))out.push("`sharing_mode` IN ('EXCLUSIVE', 'SHARED')");
 if(names.has('cutover_minute'))out.push('`cutover_minute` BETWEEN 0 AND 1439');
 if(t.name==='fnb_recipe')out.push('`yield_qty` > 0');
 if(t.name==='fnb_recipe_line')out.push('`loss_rate` >= 0 AND `loss_rate` < 1');
 if(t.name==='fnb_recipe_adjustment')out.push("(`action` = 'REMOVE' AND `qty` IS NULL) OR (`action` IN ('SET', 'ADD') AND `qty` IS NOT NULL AND `qty` >= 0)");
 if(t.name==='fnb_modifier_component')out.push('`combo_no` <> `member_no`');
 return out;
}
export function renderFoodSchema(s){
 validateFoodDesign(s);
 const tables=s.tables.filter(t=>t.name.startsWith('fnb_'));
 const spec=tables.find(t=>t.name==='fnb_option_group_item')?.fields.find(f=>f.name==='spec_value_no');
 const version=spec?.type==='varchar(64)'?3:tables.some(t=>t.name==='fnb_table_occupancy'&&t.fields.some(f=>f.name==='sharing_mode'))?2:1;
 let sql='-- Food-owned tables only; use the existing product/order database.\n-- Flyway location: db/industry/food; history: fnb_flyway_history; version: '+version+'.\n-- Frozen after application. Add a new migration for any subsequent change.\n-- No cross-domain physical foreign keys; services must enforce ownership and concurrency.\n\n';
 if(s.catalogEnhancement)sql='-- TARGET DESIGN ONLY; planned objects are not applied migrations.\n'+sql;
 for(const t of tables){
  const own=new Set(t.fields.map(f=>f.name));
  const fields=[...s.commonFields.filter(f=>!own.has(f[0])).map(([name,type,label])=>({name,type,label,nullable:false})),...t.fields];
  const lines=fields.map(f=>{
   if(!/^(bigint|int|tinyint|boolean|json|text|date|time|datetime\(3\)|varchar\(\d+\)|char\(\d+\)|varbinary\(\d+\)|decimal\(\d+,\d+\))$/.test(f.type))throw Error(`Unsupported type ${t.name}.${f.name}`);
   const binary=/^(var)?char/.test(f.type)&&(/_no$|_ref$|_key$|_hash$|^code$|^source_line_ref$/.test(f.name));
   const extra=f.name==='id'?' AUTO_INCREMENT':f.name==='version'?' DEFAULT 0':f.name==='deleted'?' DEFAULT 0':f.name==='tenant_no'?" DEFAULT 'MAIN'":t.name==='fnb_table_occupancy'&&f.name==='sharing_mode'?" DEFAULT 'EXCLUSIVE'":'';
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

/** Project reviewed post-V1 design additions; the pinned hash still protects every V1 byte. */
export function renderFoodV1(s){
 const v1=structuredClone(s);
 if(v1.catalogEnhancement){
  validateFoodDesign(s);
  const planned=new Set(v1.catalogEnhancement.plannedTables);
  v1.tables=v1.tables.filter(t=>!planned.has(t.name));
  const fields={
   fnb_menu_context:['channel_binding_no','channel_scope_key','price_policy_ref'],
   fnb_menu_item:['option_mode'],fnb_menu_item_sku:['recipe_no'],
   fnb_external_mapping:['channel_scope_key','mapping_revision_no'],
  };
  for(const [name,added] of Object.entries(fields)){
   const t=v1.tables.find(t=>t.name===name);t.fields=t.fields.filter(f=>!added.includes(f.name));
  }
  v1.tables.find(t=>t.name==='fnb_menu_context').unique=[['menu_no','order_source','fulfillment'],['context_no']];
  v1.tables.find(t=>t.name==='fnb_external_mapping').unique=[['mapping_no'],['provider','store_no','object_type','external_no']];
  v1.tables.find(t=>t.name==='fnb_modifier').fields.find(f=>f.name==='kind').label='类型 FLAVOR / ADDON / COMBO';
  for(const d of v1.domains)d.tables=v1.tables.filter(t=>t.domain===d.code).map(t=>t.name);
  for(const row of v1.requirementAlignment)row.tables=row.tables.filter(name=>!planned.has(name));
  delete v1.catalogEnhancement;
 }
 const spec=v1.tables.find(t=>t.name==='fnb_option_group_item')?.fields.find(f=>f.name==='spec_value_no');
 if(!spec||spec.type!=='varchar(64)')throw Error('V3 spec_value_no contract differs; create a reviewed migration instead');
 spec.type='varchar(32)';
 const table=v1.tables.find(t=>t.name==='fnb_table_occupancy');
 const field=table?.fields.find(f=>f.name==='sharing_mode');
 if(!field||field.type!=='varchar(16)'||field.nullable!==false)
  throw Error('V2 sharing_mode contract differs; create a reviewed migration instead');
 table.fields=table.fields.filter(f=>f.name!=='sharing_mode');
 return renderFoodSchema(v1);
}
