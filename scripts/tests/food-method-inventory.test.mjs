import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {buildFoodMethodInventory} from '../lib/food-method-inventory.mjs';
const read=n=>JSON.parse(fs.readFileSync(new URL('../../docs/technical/design/schema/'+n,import.meta.url)));
const s=read('food-system-design.json'),code=read('food-module-code-inventory.json'),plan=read('food-method-plan.json'),codePlan=read('food-code-plan.json');
const build=p=>buildFoodMethodInventory(s,code,p||plan,codePlan);
test('all165API have controller,service,mapper/port call chains',()=>{
 const v=build();assert.equal(v.endpoints.length,165);assert.equal(v.controllers.length,56);assert.equal(v.services.length,38);
 assert(v.endpoints.every(e=>e.controllerSignature&&e.serviceSignature&&e.transaction));
 assert(v.endpoints.every(e=>e.mapperCalls.length||e.portCalls.length));
});
test('missing use case or unknown table is rejected instead of inventing mappings',()=>{
 const p=structuredClone(plan);delete p.apiFlows['FOOD-API-031'];assert.throws(()=>build(p),/missing.*flow/i);
 const other=structuredClone(plan);other.apiFlows['FOOD-API-031'].writeTables.push('ord_order');assert.throws(()=>build(other),/unknown|non-food/i);
});
test('service never depends on HTTP annotations and paths are distinct from trusted scope',()=>{
 for(const e of build().endpoints){
  assert(!e.serviceSignature.includes('@'));if(!['FOOD-API-023','FOOD-API-026','FOOD-API-127','FOOD-API-128','FOOD-API-129','FOOD-API-130'].includes(e.apiId))assert(e.serviceParameters.some(p=>p.name==='entityNo'));
  assert.equal(new Set(e.serviceParameters.map(p=>p.name)).size,e.serviceParameters.length);
 }
 const c=build().endpoints.find(e=>e.apiId==='FOOD-API-031');assert(c.controllerSignature.includes('@RequestHeader("Idempotency-Key")'));
});
test('Mapper work never writes core tables and core operations use explicit Ports',()=>{
 const v=build();for(const m of v.mappers){assert(!/\b(?:ord_|prd_|pay_)/.test(m.table));assert(m.methods.every(x=>x.signature));}
 assert(v.endpoints.find(e=>e.apiId==='FOOD-API-042').portCalls.includes('OrderPlacementPort'));
 assert(v.endpoints.find(e=>e.apiId==='FOOD-API-058').portCalls.includes('PaymentQueryPort'));
});
test('urge does not mutate task quantities and export is a shared task contract',()=>{
 const v=build(),urge=v.endpoints.find(e=>e.apiId==='FOOD-API-050');assert(!urge.mapperCalls.some(c=>c.method==='advanceTaskQuantityIfVersion'));
 const exportFlow=v.endpoints.find(e=>e.apiId==='FOOD-API-078');assert(!exportFlow.mapperCalls.some(c=>c.table==='fnb_report_run'&&c.access==='WRITE'));
});
test('all targeted mapper custom methods have typed definitions and no HTTP types',()=>{
 const v=build();for(const m of v.mappers)for(const f of m.methods){assert(!f.signature.includes('HttpServlet'));assert(!f.signature.includes('Req req'));}
 const call=v.endpoints.find(e=>e.apiId==='FOOD-API-031').mapperCalls.find(c=>c.method==='selectForUpdateByStoreAndTableNo');assert(call);
});

test('create-table and open-check insert rows; public session resolves scope through token validation',()=>{
 const v=build(),get=id=>v.endpoints.find(e=>e.apiId===id);
 assert(get('FOOD-API-024').mapperCalls.some(c=>c.table==='fnb_table'&&c.method==='insert'));
 assert(get('FOOD-API-031').mapperCalls.some(c=>c.table==='fnb_table_occupancy'&&c.method==='insert'));
 assert(!get('FOOD-API-026').serviceParameters.some(p=>p.name==='entityNo'));
 assert(get('FOOD-API-026').mapperCalls.some(c=>c.method==='selectValidByTokenHash'));
 assert(!get('FOOD-API-135').mapperCalls.some(c=>c.table==='fnb_cash_payment_link'&&c.access==='WRITE'));
});
