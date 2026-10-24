import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {buildFoodModuleInventory} from '../lib/food-module-code-inventory.mjs';
const read=n=>JSON.parse(fs.readFileSync(new URL('../../docs/technical/design/schema/'+n,import.meta.url)));
const source=read('food-system-design.json'),inventory=read('food-code-inventory.json'),plan=read('food-code-plan.json'),baseline=read('food-code-baselines.json');
const build=(s=source,p=plan)=>buildFoodModuleInventory(s,inventory,p,baseline);
test('each of 165 APIs and91tables has one primary business module',()=>{
 const v=build();assert.equal(v.endpoints.length,165);assert.equal(v.persistence.length,91);
 assert.equal(new Set(v.modules.flatMap(m=>m.ownedApiIds)).size,165);
 assert.equal(new Set(v.modules.flatMap(m=>m.ownedTables)).size,91);
 assert.equal(v.persistence.filter(p=>p.schemaStatus==='CREATED_LOCAL').length,73);
});
test('reject absent or invalid primary owner instead of silently falling back',()=>{
 const p=structuredClone(plan);delete p.apiOwners['FOOD-API-001'];assert.throws(()=>build(source,p),/owner/i);
 p.apiOwners['FOOD-API-001']='MOD-99';assert.throws(()=>build(source,p),/owner/i);
});
test('no DTO class silently merges differing API input fields',()=>{
 const v=build(),biz=v.endpoints.find(e=>e.apiId==='FOOD-API-038'),mp=v.endpoints.find(e=>e.apiId==='FOOD-API-128');
 assert.notEqual(biz.requestClass,mp.requestClass);
 const names=v.dtoTypes.map(d=>d.className);assert.equal(new Set(names).size,names.length);
 assert.notEqual(v.endpoints.find(e=>e.apiId==='FOOD-API-069').requestClass,v.endpoints.find(e=>e.apiId==='FOOD-API-090').requestClass);
});
test('output schema is actual data contract, including pages and differing service-operation results',()=>{
 const v=build(),get=id=>v.endpoints.find(e=>e.apiId===id);
 assert.match(get('FOOD-API-096').returnType,/PageData<.*TableVO>/);
 assert.notEqual(get('FOOD-API-024').returnType,get('FOOD-API-105').returnType);
 assert.notEqual(get('FOOD-API-058').returnType,get('FOOD-API-059').returnType);
});
test('shared printing/reporting never get food-owned mapper/entity copies',()=>{
 const v=build(),get=id=>v.endpoints.find(e=>e.apiId===id);
 assert(!JSON.stringify(v).match(/ai\.neargo\.shop\.print\./));
 for(const id of ['FOOD-API-065','FOOD-API-066','FOOD-API-067']){
  const e=get(id);
  assert.equal(e.controllerClass,'ai.neargo.shop.food.api.biz.BizPrintJobController');
  assert(e.controllerFile.startsWith('backend-plugins/shop-industry-food/food-web/src/main/java/'));
  assert.equal(e.serviceClass,'ai.neargo.shop.printing.service.PrintService');
  assert.equal(e.serviceFile,'backend/shop-core/src/main/java/ai/neargo/shop/printing/service/PrintService.java');
  assert.equal(e.targetPublicRouteStatus,'PLANNED_SHARED_B_ENTRY_NOT_REGISTERED');
  assert.equal(e.targetDtoStatus,'PLANNED_TRANSPORT_CONTRACT');
  assert(e.actualImplementations[0].path.startsWith('/biz/x/food/print-jobs'));
  if(e.requestClass)assert.equal(v.dtoTypes.find(d=>d.className===e.requestClass).implementationStatus,'PLANNED_TRANSPORT_CONTRACT');
 }
 assert.match(get('FOOD-API-065').actualImplementations[0].delegate,/FoodPrintService.enqueue -> .*PrintPort.enqueue/);
 assert.equal(get('FOOD-API-066').actualImplementations[0].delegate,'ai.neargo.shop.spi.printing.PrintPort.get');
 assert.equal(get('FOOD-API-067').actualImplementations[0].delegate,'ai.neargo.shop.spi.printing.PrintPort.reprint');
 assert.equal(plan.portCatalog.PrintPort.interfaceClass,'ai.neargo.shop.spi.printing.PrintPort');
 assert.equal(plan.portCatalog.PrintPort.status,'EXISTS');
 for(const p of build().persistence.filter(p=>p.table.startsWith('prn_')||p.table.startsWith('rpt_'))){
  assert(!p.entityClass.startsWith('ai.neargo.shop.food.'));
  assert(!p.mapperClass.startsWith('ai.neargo.shop.food.'));
  assert.equal(p.schemaStatus,p.table.startsWith('prn_')?'SHARED_MIGRATION_AVAILABLE_NOT_APPLIED':'SHARED_NOT_CREATED');
  if(p.table.startsWith('prn_')){
   assert(p.entityClass.startsWith('ai.neargo.shop.printing.'));
   assert(p.entityFile.startsWith('backend/shop-core/src/main/java/'));
   assert(p.mapperFile.startsWith('backend/shop-core/src/main/java/'));
  }
 }
});
test('diff preserves every old API and table, with additions traced by stable IDs',()=>{
 const v=build();assert.equal(v.diffs.committed.filter(r=>r.status==='ADDED').length,74);
 assert(!v.diffs.committed.some(r=>r.status==='REMOVED'));
 assert(!v.diffs.previous.some(r=>r.status==='REMOVED'));
 assert.equal(v.tableDiffs.filter(r=>r.status==='ADDED').length,18);
});
test('shared class reuse has one owning module rather than duplicated source files',()=>{
 const v=build();const classes=v.modules.flatMap(m=>m.ownedControllerClasses);
 assert.equal(classes.length,56);assert.equal(new Set(classes).size,56);
 assert.equal(v.classOwners['ai.neargo.shop.food.service.DiningOrderService'],'MOD-03');
 assert.equal(v.classOwners['ai.neargo.shop.food.service.DiningAuditService'],'MOD-13');
 assert(v.modules.find(m=>m.moduleId==='MOD-04').referencedServiceClasses.includes('ai.neargo.shop.food.service.DiningOrderService'));
});

test('food controllers belong to food-web while platform mounts them and contracts stay transport neutral',()=>{
 const v=build();const external=v.endpoints.filter(e=>/^\/(biz|mp|ops)\//.test(e.path));
 assert.equal(external.length,164);
 for(const e of external){
  const expected=e.controllerClass.startsWith('ai.neargo.shop.food.')?'backend-plugins/shop-industry-food/food-web/src/main/java/':'backend/shop-app/src/main/java/';
  assert(e.controllerFile.startsWith(expected),e.apiId+' controller is outside its owning module');
 }
 assert.equal(external.filter(e=>e.controllerClass.startsWith('ai.neargo.shop.food.')).length,164);
 for(const e of v.endpoints.filter(e=>e.serviceClass.startsWith('ai.neargo.shop.food.'))){
  assert(e.serviceFile.startsWith('backend-plugins/shop-industry-food/food-api/src/main/java/'),e.apiId+' service interface must be shared');
  assert(e.serviceImplFile.startsWith('backend-plugins/shop-industry-food/food-core/src/main/java/'),e.apiId+' business implementation escaped core');
 }
 for(const d of v.dtoTypes.filter(d=>d.className.startsWith('ai.neargo.shop.food.'))){
  assert(d.sourceFile.startsWith('backend-plugins/shop-industry-food/food-api/src/main/java/'));
  assert.equal(d.owner,'food-api');
 }
 const sync=v.endpoints.find(e=>e.apiId==='FOOD-API-084');
 assert(sync.controllerFile.startsWith('backend-plugins/shop-industry-food/food-core/src/main/java/'));
});
