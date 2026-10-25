import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {buildFoodModuleInventory} from '../lib/food-module-code-inventory.mjs';
const read=n=>JSON.parse(fs.readFileSync(new URL('../../docs/technical/design/schema/'+n,import.meta.url)));
const s=read('food-system-design.json'),plan=read('food-code-plan.json');
const inventory=()=>buildFoodModuleInventory(s,read('food-code-inventory.json'),plan,read('food-code-baselines.json'));
test('ALL155 records the actual read-only station SKU route endpoint and ownership',()=>{
 assert.equal(s.apis.length,165);
 const a=s.apis.find(a=>a.id==='FOOD-API-155');
 assert.equal(a.method,'GET');assert.equal(a.path,'/biz/x/food/stores/{storeNo}/sku-routes/{skuNo}');
 assert.equal(plan.apiOwners[a.id],'MOD-02');assert.equal(plan.methodOverrides[a.id].method,'getRoutes');
 const flow=read('food-method-plan.json').apiFlows[a.id];assert.deepEqual(flow.writeTables,[]);
 assert.equal(flow.transaction,'READ_ONLY');
});
test('provided migrations are not represented as historical locally applied tables',()=>{
 assert.equal(s.databaseInitialization.version,'1');assert.equal(s.databaseInitialization.businessTableCount,73);
 for(const version of [6,7])assert(s.databaseInitialization.additionalMigrations.some(p=>p.includes('/V'+version+'__')));
 const p=inventory().persistence;
 assert.equal(p.filter(t=>t.schemaStatus==='CLOUD_BASELINE_V15').length,83);
 assert.equal(p.filter(t=>t.schemaStatus===(s.deliveryVerification?.status==='DEPLOYED_EMBEDDED_VERIFIED'?'SOURCE_MIGRATION_DEPLOYED':'SOURCE_MIGRATION_PENDING_DEPLOYMENT')).length,28);
 assert.equal(p.filter(t=>t.schemaStatus==='PLANNED_NOT_CREATED').length,3);
});
test('menu sections and catalog preserve actual nullable authority and private read contracts',()=>{
 const api=id=>s.apis.find(a=>a.id===id);
 assert.match(api('FOOD-API-017').input,/SCHEDULE.*COMPOSITION.*CONTEXT/);
 assert.doesNotMatch(s.dtos.RouteInput,/routeVersion/);
 assert.match(s.dtos.CatalogSku,/priceMinor\?:int64/);
 assert.match(s.dtos.CatalogSku,/available\?:int32/);
 assert.match(s.dtos.CatalogSku,/requiresSelectionQuote:boolean/);
 assert.match(api('FOOD-API-023').guard,/账号|channelAccount|隐私/);
 assert.doesNotMatch(api('FOOD-API-114').input,/at\?/);
});
test('generators describe partial implementation without denying existing Java modules',()=>{
 const generator=fs.readFileSync(new URL('../gen-food-code-inventory.mjs',import.meta.url),'utf8');
 assert.doesNotMatch(generator,/尚未创建餐饮Java业务模块或实现Controller/);
});
