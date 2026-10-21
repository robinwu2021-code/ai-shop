import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {validateFoodDesign} from '../lib/food-design-validation.mjs';
import {renderFoodV1,assertFrozenFoodV1} from '../lib/food-schema-sql.mjs';
const source=()=>JSON.parse(fs.readFileSync(new URL('../../docs/technical/design/schema/food-system-design.json',import.meta.url)));
const added=['fnb_menu_channel_binding','fnb_menu_item_option_group','fnb_recipe','fnb_recipe_line','fnb_recipe_adjustment','fnb_production_consumption','fnb_production_consumption_line','fnb_catalog_sync_job','fnb_catalog_sync_item'];
test('enhancement schema traces nine target objects without pretending to have migrated them',()=>{
 const s=source();assert.equal(s.catalogEnhancement?.version,1);
 assert.equal(s.tables.filter(t=>t.name.startsWith('fnb_')).length,82);
 for(const name of added)assert.equal(s.tables.find(t=>t.name===name)?.deployment,['fnb_recipe','fnb_recipe_line'].includes(name)?'MIGRATION_V4':name==='fnb_recipe_adjustment'?'MIGRATION_V5':name==='fnb_menu_item_option_group'?'MIGRATION_V6':name==='fnb_menu_channel_binding'?'MIGRATION_V7':'PLANNED',name);
 assert.equal(s.databaseInitialization.targetSchemaVersion,7);
 assert.equal(s.catalogEnhancement.runtimeEnabled,false);
 assertFrozenFoodV1(renderFoodV1(s));
});
test('same source and fulfillment can distinguish provider accounts without nullable dedup',()=>{
 const s=source(),t=s.tables.find(t=>t.name==='fnb_menu_context');
 assert(t.fields.some(f=>f.name==='channel_scope_key'&&!f.nullable));
 assert(t.unique.some(k=>k.join(',')==='menu_no,order_source,fulfillment,channel_scope_key'));
 const bad=structuredClone(s);bad.tables.find(t=>t.name==='fnb_menu_context').unique=[['context_no'],['menu_no','order_source','fulfillment']];
 assert.throws(()=>validateFoodDesign(bad),/channel scope/i);
});
test('immutable mapping revisions retain account and historical catalog identity',()=>{
 const s=source(),t=s.tables.find(t=>t.name==='fnb_external_mapping');
 assert(t.unique.some(k=>k.join(',')==='provider,store_no,channel_scope_key,object_type,external_no,mapping_revision_no'));
});
test('recipe and consumption contracts do not silently lose material precision or authority',()=>{
 const s=source();assert(s.tables.find(t=>t.name==='fnb_recipe_line')?.fields.some(f=>f.name==='qty'&&f.type==='decimal(18,6)'));
 assert.equal(s.catalogEnhancement?.contracts?.inventory?.implementation,'PENDING');
 assert.equal(s.catalogEnhancement?.contracts?.pricing?.implementation,'PENDING');
 const bad=structuredClone(s);bad.catalogEnhancement={...bad.catalogEnhancement,runtimeEnabled:true};
 assert.throws(()=>validateFoodDesign(bad),/not.*ready|runtime.*closed/i);
});
test('menu item overrides and quote provenance are explicit API fields',()=>{
 const s=source();assert.match(s.dtos.MenuItemInput,/optionMode:string/);
 assert.match(s.dtos.ContextInput,/channelBindingNo\?:string/);
 assert.match(s.dtos.ContextInput,/pricePolicyRef\?:string/);
 assert.match(s.dtos.CatalogItem,/menuItemNo:string/);
 assert.match(s.dtos.CatalogSku,/priceMinor\?:int64/);
 assert.match(s.dtos.CatalogSku,/pricingStatus:string/);
});
test('no recipe or channel management endpoint is accidentally exposed as a consumer write',()=>{
 const s=source();const apis=s.apis.filter(a=>Number(a.id.slice(-3))>=142);
 assert.equal(apis.length,14);assert(apis.every(a=>a.path.startsWith('/biz/x/food/')));
 const draftIds=new Set(['FOOD-API-152','FOOD-API-148','FOOD-API-149','FOOD-API-150','FOOD-API-151']);
 const managed=new Set(['FOOD-API-142','FOOD-API-143','FOOD-API-144','FOOD-API-145','FOOD-API-155']);
 assert(apis.filter(a=>managed.has(a.id)).every(a=>a.implementation==='IMPLEMENTED_BACKEND_MANAGEMENT'));
 assert(apis.filter(a=>!draftIds.has(a.id)&&!managed.has(a.id)).every(a=>a.needs.includes('未实现')));
 assert(apis.filter(a=>draftIds.has(a.id)).every(a=>a.needs.startsWith('RECIPE-01A')||a.needs.startsWith('RECIPE-01C')));
 assert.equal(s.requirementAlignment.length,109);assert.equal(validateFoodDesign(s),true);
});
test('legacy catalog is an explicit projection and cannot reintroduce FLAVOR or implicit all SKUs',()=>{
 const s=source(),old=JSON.parse(fs.readFileSync(new URL('../../docs/technical/design/schema/food-catalog.schema.json',import.meta.url)));
 assert.equal(old.canonicalSource,'food-system-design.json');
 for(const name of ['fnb_modifier','fnb_option_group_item','fnb_menu_context','fnb_menu_item','fnb_menu_item_sku','fnb_recipe'])
  assert.deepEqual(old.tables.find(t=>t.name===name),s.tables.find(t=>t.name===name),name);
 assert(!old.rules.some(r=>r.includes('FLAVOR.sales_sku_no')||r.includes('无行表示该商品的全部')));
});
test('pricing and inventory planned contracts define typed inputs outputs and uncertain outcome recovery',()=>{
 const s=source();
 for(const name of ['pricing','inventory']){
  const c=s.catalogEnhancement.contracts[name];
  assert(s.dtos[c.inputDto]);assert(s.dtos[c.outputDto]);assert(c.errors.includes('UNKNOWN'));
 }
 assert.match(s.dtos.InventoryMaterialFacts,/quantityScale:int32/);
 assert.match(s.dtos.InventoryMaterialFacts,/valuationScale:int32/);
 assert.match(s.dtos.MaterialConsumptionReceipt,/actualQty\?:decimal/);
 assert.match(s.dtos.MaterialConsumptionReceipt,/costMinor\?:int64/);
 assert.match(s.dtos.ChannelQuoteReq,/menuItemNo:string/);
});
test('baseline projection cannot silently erase unrelated constraints or labels',()=>{
 for(const mutate of [s=>s.tables.find(t=>t.name==='fnb_menu_context').unique.push(['menu_no']),
  s=>s.tables.find(t=>t.name==='fnb_modifier').fields.find(f=>f.name==='kind').label='unreviewed change']){
  const s=source();mutate(s);assert.throws(()=>renderFoodV1(s),/reviewed|contract|scope/i);
 }
});
test('baseline projection rejects replacing unchanged context or mapping business identity uniqueness',()=>{
 for(const [name,identity] of [['fnb_menu_context','context_no'],['fnb_external_mapping','mapping_no']]){
  const s=source(),t=s.tables.find(t=>t.name===name);
  t.unique=t.unique.map(k=>k.length===1&&k[0]===identity?['entity_no']:k);
  assert.throws(()=>renderFoodV1(s),/reviewed|scope|identity/i,name);
 }
});
test('recipe lines keep independent identity even when the same material occurs twice',()=>{
 const s=source(),t=s.tables.find(t=>t.name==='fnb_recipe_line');
 assert(t.unique.some(k=>k.join(',')==='recipe_no,line_no'));
 assert(!t.unique.some(k=>k.join(',')==='recipe_no,item_ref'));
 const bad=structuredClone(s);bad.tables.find(row=>row.name===t.name).unique=[['line_no'],['recipe_no','item_ref']];
 assert.throws(()=>validateFoodDesign(bad),/recipe.*line|line.*identity/i);
});
