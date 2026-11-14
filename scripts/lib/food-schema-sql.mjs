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
 if(t.name==='fnb_recipe_adjustment'&&names.has('sort_no'))out.push('`sort_no` >= 0');
 if(t.name==='fnb_modifier_component')out.push('`combo_no` <> `member_no`');
 return out;
}
export function renderFoodSchema(s){
 validateFoodDesign(s);
 const tables=s.tables.filter(t=>t.name.startsWith('fnb_'));
 const spec=tables.find(t=>t.name==='fnb_option_group_item')?.fields.find(f=>f.name==='spec_value_no');
 const migrationVersion=value=>Number(String(value||'').match(/(?:^V|MIGRATION_V)(\d+)$/)?.[1]||1);
 const version=Math.max(spec?.type==='varchar(64)'?3:1,...tables.flatMap(t=>[migrationVersion(t.deployment),migrationVersion(t.introducedIn),...t.fields.map(f=>migrationVersion(f.introducedIn))]));
 let sql='-- Food-owned tables only; use the existing product/order database.\n-- Flyway location: db/industry/food; history: fnb_flyway_history; version: '+version+'.\n-- Frozen after application. Add a new migration for any subsequent change.\n-- No cross-domain physical foreign keys; services must enforce ownership and concurrency.\n\n';
 if(s.catalogEnhancement)sql='-- TARGET DESIGN ONLY; planned objects are not applied migrations.\n'+sql;
 for(const t of tables){
  const own=new Set(t.fields.map(f=>f.name));
  const fields=[...(t.usesBaseEntity===false?[]:s.commonFields.filter(f=>!own.has(f[0]))).map(([name,type,label])=>({name,type,label,nullable:false})),...t.fields];
  const lines=fields.map(f=>{
   if(!/^(bigint|int|tinyint|boolean|json|text|mediumtext|longtext|date|time|datetime(?:\(3\))?|varchar\(\d+\)|char\(\d+\)|varbinary\(\d+\)|decimal\(\d+,\d+\))$/.test(f.type))throw Error(`Unsupported type ${t.name}.${f.name}`);
   const binary=/^(var)?char/.test(f.type)&&(/_no$|_ref$|_key$|_hash$|^code$|^source_line_ref$/.test(f.name));
   const extra=f.name==='id'?' AUTO_INCREMENT':f.name==='version'?' DEFAULT 0':f.name==='deleted'?' DEFAULT 0':f.name==='tenant_no'?" DEFAULT 'MAIN'":t.name==='fnb_table_occupancy'&&f.name==='sharing_mode'?" DEFAULT 'EXCLUSIVE'":'';
   return '    '+id(f.name)+' '+f.type.toUpperCase()+(binary?' CHARACTER SET utf8mb4 COLLATE utf8mb4_bin':'')+(f.nullable?' NULL':' NOT NULL')+extra+' COMMENT '+literal(f.label);
  });
  lines.push('    PRIMARY KEY ('+(t.primaryKey||['id']).map(id).join(', ')+')');
  t.unique.forEach((key,i)=>lines.push('    UNIQUE KEY '+id('uk_food_'+(i+1))+' ('+key.map(id).join(', ')+')'));
  if(t.usesBaseEntity!==false)lines.push('    KEY `ix_food_audit` (`entity_no`, `created_at`, `id`)');
  if(t.usesBaseEntity!==false&&own.has('store_no'))lines.push('    KEY `ix_food_store` (`entity_no`, `store_no`, `updated_at`, `id`)');
  const parents=t.fields.filter(f=>f.ref?.startsWith('fnb_')&&!t.unique.some(key=>key[0]===f.name));
  if(t.usesBaseEntity!==false)parents.forEach((f,i)=>lines.push('    KEY '+id('ix_food_ref_'+(i+1))+' (`entity_no`, '+id(f.name)+', `id`)'));
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
 const laterTables=new Set(v1.tables.filter(t=>Number(t.introducedIn?.slice(1))>1).map(t=>t.name));
 v1.tables=v1.tables.filter(t=>!laterTables.has(t.name));
 for(const d of v1.domains)d.tables=d.tables.filter(name=>!laterTables.has(name));
 for(const row of v1.requirementAlignment)row.tables=row.tables.filter(name=>!laterTables.has(name));
 if(v1.catalogEnhancement){
  validateFoodDesign(s);
  const planned=new Set([...v1.catalogEnhancement.plannedTables,'fnb_queue_ticket','fnb_reservation_table','fnb_business_share_plan']);
  v1.tables=v1.tables.filter(t=>!planned.has(t.name));
  const fields={
   fnb_sales_restriction:['order_source','fulfillment'],
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
 for(const table of v1.tables){
  const added=new Set(table.fields.filter(f=>Number(f.introducedIn?.slice(1))>1 && !['sharing_mode','spec_value_no'].includes(f.name)).map(f=>f.name));
  table.fields=table.fields.filter(f=>!added.has(f.name));
  table.unique=table.unique.filter(key=>key.every(name=>!added.has(name)));
 }
 v1.tables.find(t=>t.name==='fnb_order_component').fields.find(f=>f.name==='choice_no').nullable=false;
 v1.tables.find(t=>t.name==='fnb_order_modifier').fields.find(f=>f.name==='source_no').type='varchar(32)';
 const publicationKind=v1.tables.find(t=>t.name==='fnb_config_revision').fields.find(f=>f.name==='config_kind');
 if(publicationKind.label==='MENU / BUNDLE / OPTION_GROUP / MODIFIER / STATION / RECIPE')
  publicationKind.label='MENU / BUNDLE / OPTION_GROUP / MODIFIER / STATION';
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

/** V4 owns exactly the two recipe draft tables; no conversion or execution state is invented. */
export function renderRecipeDraftV4(s){
 const ddl=renderFoodSchema(s);
 const blocks=['fnb_recipe','fnb_recipe_line'].map(name=>{
  const start=ddl.indexOf('CREATE TABLE `'+name+'`');
  if(start<0)throw Error('Missing recipe table '+name);
  return ddl.slice(start,ddl.indexOf(';',start)+1);
 });
 return '-- Recipe drafts only; preserve V1-V3. No stock execution or conversion authority.\n\n'+blocks.join('\n\n')+'\n';
}

/** V5 contains only mutable recipe adjustment rows; V1-V4 are frozen. */
export function renderRecipeAdjustmentV5(s){
 const ddl=renderFoodSchema(s),start=ddl.indexOf('CREATE TABLE `fnb_recipe_adjustment`');
 if(start<0)throw Error('Missing recipe adjustment table');
 return '-- Recipe adjustment drafts only; preserve V1-V4. No selection execution or inventory commands.\n\n'
  +ddl.slice(start,ddl.indexOf(';',start)+1)+'\n';
}

/** V6 applies composition-only working rows. V1-V5 remain immutable. */
export function renderMenuCompositionV6(s){
 const ddl=renderFoodSchema(s),start=ddl.indexOf('CREATE TABLE `fnb_menu_item_option_group`');
 if(start<0)throw Error('Missing menu item option table');
 return "-- Restaurant menu composition only; preserve all applied V1-V5 bytes.\n"
  +"ALTER TABLE fnb_menu_item ADD COLUMN option_mode VARCHAR(16) NOT NULL DEFAULT 'INHERIT' COMMENT 'INHERIT / REPLACE; empty REPLACE forbids options';\n"
  +"ALTER TABLE fnb_menu_item_sku ADD COLUMN recipe_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT 'Same-store same-SKU recipe variant';\n\n"
  +ddl.slice(start,ddl.indexOf(';',start)+1)+'\n';
}

/** V7 adds restaurant context scopes and channel planning, never external credentials or inventory balances. */
export function renderMenuContextV7(s){
 const ddl=renderFoodSchema(s),start=ddl.indexOf('CREATE TABLE `fnb_menu_channel_binding`');
 if(start<0)throw Error('Missing menu channel binding table');
 return "-- Menu contexts, verified channel activation and scoped local sales restrictions.\n"
  +"ALTER TABLE fnb_menu_context ADD COLUMN channel_binding_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL, ADD COLUMN channel_scope_key VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'LOCAL', ADD COLUMN price_policy_ref VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;\n"
  +"ALTER TABLE fnb_menu_context DROP INDEX uk_food_1, ADD UNIQUE KEY uk_food_1 (menu_no,order_source,fulfillment,channel_scope_key);\n"
  +"ALTER TABLE fnb_sales_restriction ADD COLUMN order_source VARCHAR(32) NULL, ADD COLUMN fulfillment VARCHAR(32) NULL;\n\n"
  +ddl.slice(start,ddl.indexOf(';',start)+1)+'\n';
}
