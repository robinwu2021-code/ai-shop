import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const read=name=>JSON.parse(fs.readFileSync(new URL('../../docs/technical/design/schema/'+name,import.meta.url)));
const s=read('food-system-design.json'),n=s.nonPaymentImplementation;
const routes=n.implementedApis;
test('nonpayment audit preserves all targets and records real registered routes as pending verification',()=>{
 assert.equal(s.apis.length,165);assert.equal(s.tables.length,119);
 assert.equal(routes.length,50);assert.equal(new Set(routes.map(a=>a.method+' '+a.path)).size,50);
 assert.equal(n.paymentExecutionExcluded,true);assert.equal(s.checkoutImplementation.historicalSlice,true);
 for(const a of routes){
  assert.equal(a.status,'CODED_PENDING_VERIFICATION');
  assert.equal(a.implementationStatus,'CODED_PENDING_VERIFICATION');
  assert.match(a.path,/^\/biz\/x\/food\//);assert.match(a.permission,/^biz:/);
  const source=fs.readFileSync(new URL('../../'+a.sourceFile,import.meta.url),'utf8');
  assert(source.includes('"'+a.path+'"'),a.path+' missing actual controller mapping');
  assert(source.includes('public '+a.responseType.split('.').at(-1))||source.includes(' '+a.handler+'('));
 }
 assert.deepEqual(n.postpayDelta.payPolicyValues,['PREPAY','POSTPAY']);
 assert.equal(n.postpayDelta.parentOrderStatus,'WAIT_PAY');
 assert.equal(n.postpayDelta.financialPaymentAcknowledged,false);
});
test('exact routes retain observable DTO differences instead of being promoted to complete target delivery',()=>{
 const get=id=>routes.find(a=>a.targetApiId===id);
 assert.equal(get('FOOD-API-045').contractStatus,'EXACT_PATH_WITH_DTO_DIFFERENCES');
 assert(get('FOOD-API-045').contractDifferences.some(d=>d.fields.includes('task')));
 assert(get('FOOD-API-052').contractDifferences.some(d=>d.fields.includes('approvalRef')));
 assert.equal(get('FOOD-API-065').mappingStatus,'PATH_CHANGED');
 assert.equal(get('FOOD-API-154').mappingStatus,'DIFFERENT_RESOURCE_KEY');
 assert.equal(n.targetReconciliation.find(a=>a.targetApiId==='FOOD-API-153').status,'EXACT_PATH');
 assert.equal(get('FOOD-API-153').contractStatus,'EXACT_PATH_WITH_DTO_DIFFERENCES');
});
test('queue targets and durable reception table have one explicit owner and original-check delegation',()=>{
 const code=read('food-code-plan.json'),flow=read('food-method-plan.json');
 for(let id=157;id<=165;id++){
  const key='FOOD-API-'+id;assert.equal(code.apiOwners[key],'MOD-01');
  assert.equal(code.methodOverrides[key].controller,'BizQueueController');
  assert.equal(code.methodOverrides[key].service,'QueueService');
  assert(flow.apiFlows[key].readTables.includes('fnb_queue_ticket'));
 }
 assert.equal(code.tableOwners.fnb_queue_ticket.moduleId,'MOD-01');
 assert.equal(flow.apiFlows['FOOD-API-164'].transaction,'LOCAL_ATOMIC');
 assert.deepEqual(flow.apiFlows['FOOD-API-164'].localServiceCalls,['CheckService.openQueuedCheck']);
 assert.match(flow.apiFlows['FOOD-API-164'].note,/同一food primary事务.*内部Java.*冲突整体回滚/);
 assert.equal(flow.apiFlows['FOOD-API-165'].transaction,'ORCHESTRATED');
 assert.match(flow.apiFlows['FOOD-API-165'].note,/旧SEATING.*OPEN_CHECK.*未知保持未知/);
 for(const id of ['FOOD-API-164','FOOD-API-165'])assert(!flow.apiFlows[id].writeTables.includes('fnb_check'));
});
test('rendered original API book includes actual nonpayment contracts, privacy-safe DTOs and honest boundaries',()=>{
 const text=fs.readFileSync(new URL('../../docs/technical/design/餐饮系统-API设计.md',import.meta.url),'utf8');
 assert.match(text,/## 十一、本批非支付实际API与目标对账/);
 assert.match(text,/50条实际公开biz路由/);assert.match(text,/CODED_PENDING_VERIFICATION/);
 assert(text.includes('ai.neargo.shop.food.dto.PrintDocumentReq'));
 assert(text.includes('ai.neargo.shop.food.dto.KitchenReturnContracts.HandleReq'));
 assert(text.includes('BLOCKED'));assert(text.includes('UNKNOWN'));assert(text.includes('父单WAIT_PAY'));
 assert(!text.includes('\\n## 十一、'));
});

test('final queue and initial print documentation follows reviewed atomicity and logical document identity',()=>{
 assert.equal(n.queueSeating.newSeatTransaction,'FOOD_PRIMARY_LOCAL_ATOMIC');
 assert.equal(n.queueSeating.normalFlowPersistsSeating,false);
 assert.equal(n.queueSeating.remoteOrHttpAllowed,false);
 assert.equal(n.queueSeating.conflictRollbackStatus,'CALLED');
 assert.equal(n.initialPrintIdentity.includesClientIdempotencyKey,false);
 assert.equal(n.initialPrintIdentity.sameContentDifferentKeyCreatesInitialJob,false);
 assert.deepEqual(n.initialPrintIdentity.fixedDocumentScenes,['ORDER','KITCHEN','RETURN']);
 assert.equal(n.initialPrintIdentity.additionalPaperRequiresAuditedReprint,true);
 assert.match(routes.find(a=>a.handler==='enqueue'&&a.controller==='BizPrintJobController').notes,/PREBILL.*snapshot.*换key同内容不新增/);
});

test('production list records exact public query parameters and recursive real pagination DTOs',()=>{
 const a=routes.find(a=>a.targetApiId==='FOOD-API-153');
 assert.equal(a.implementationId,'FOOD-NONPAY-IMPL-050');
 assert.equal(a.method,'GET');assert.equal(a.path,'/biz/x/food/stores/{storeNo}/production-consumptions');
 assert.equal(a.permission,'biz:order:view');assert.equal(a.requestBodyType,null);
 assert.deepEqual(a.requestParameters.filter(p=>p.in==='query').map(p=>[p.name,p.required]),
  [['from',true],['to',true],['page',false],['size',false],['skuNo',false],['itemRef',false],['status',false]]);
 assert.equal(a.queryObjectType,'ai.neargo.shop.food.dto.ProductionContracts.ListQuery');
 assert.equal(a.responseType,'ai.neargo.shop.common.PageData<ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO>');
 const dto=name=>n.actualDtoDefinitions.find(d=>d.name===name);
 assert.deepEqual(dto(a.queryObjectType).fields.map(f=>[f.name,f.type]),
  [['page','Integer'],['size','Integer'],['from','LocalDate'],['to','LocalDate'],['skuNo','String'],['itemRef','String'],['status','String']]);
 assert.deepEqual(dto('ai.neargo.shop.common.PageData').fields.map(f=>[f.name,f.type]),
  [['records','List<T>'],['total','long'],['page','long'],['size','long']]);
 const consumption=dto('ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO');
 assert.equal(consumption.fields.find(f=>f.name==='lines').type,'List<LineVO>');
 assert.equal(dto('ai.neargo.shop.food.dto.ProductionContracts.LineVO').fields.find(f=>f.name==='inventoryIssuedQty').type,'BigDecimal');
 assert(a.contractDifferences.some(d=>d.kind==='PAGINATION_SCALAR_TYPE_DIFFERS_FROM_TARGET'));
 assert(!n.boundaries.some(b=>b.includes('API153 has no matching')));
 const code=read('food-code-plan.json'),flow=read('food-method-plan.json');
 assert.equal(code.methodOverrides['FOOD-API-153'].method,'list');
 assert.equal(code.methodOverrides['FOOD-API-153'].controller,'BizProductionConsumptionController');
 assert(!flow.apiFlows['FOOD-API-153'].note.includes('PLANNED_NOT_IMPLEMENTED'));
});
test('current print resolution and automatic paper/KDS facts preserve protocol boundaries',()=>{
 assert.equal(n.automaticPrinting.sourceEvent,'DINING_ORDER_ACCEPTED');
 assert.equal(n.automaticPrinting.withoutConfiguredRouteCreatesJob,false);
 assert.equal(n.automaticPrinting.enqueueImpliesPrinted,false);
 assert.equal(n.kdsQueue.explicitBatchIncludesHistory,true);
 assert.deepEqual(n.kdsQueue.defaultFilters.taskStatusExcluded,['SERVED','VOIDED']);
 const r=n.claimEnvelopeReview.resolution;
 assert.equal(r.status,'CODED_PENDING_VERIFICATION');
 assert.match(r.acknowledgement,/latest attempt.*responseCode/);
 assert.match(r.clockBoundary,/does not reject past leases/);
 for(const finding of n.claimEnvelopeReview.risksAndRecommendations.filter(r=>r.priority==='P2')){
  assert.equal(finding.reviewFindingScope,'HISTORICAL_FINDING');assert(finding.currentResolution);
 }
});
