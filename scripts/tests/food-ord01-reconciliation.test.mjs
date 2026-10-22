import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const root=new URL('../../',import.meta.url);
const read=n=>JSON.parse(readFileSync(new URL('docs/technical/design/schema/'+n,root),'utf8'));
test('ORD01 documents shared cart identity and explicit guest membership without another table',()=>{
 const s=read('food-system-design.json'),c=read('food-code-plan.json'),m=read('food-method-plan.json');
 const grant=s.apis.find(a=>a.id==='FOOD-API-156');
 assert.equal(grant?.path,'/biz/x/food/checks/{checkNo}/cart-sessions');
 assert.equal(c.apiOwners[grant.id],'MOD-03');
 assert.equal(c.methodOverrides[grant.id].service,'CheckCartService');
 assert.match(s.dtos.OrderLineInput,/menuRevisionNo:string/);
 assert.match(s.dtos.OrderLineInput,/bundleChoices:CartBundleChoiceInput\[\]/);
 assert.match(s.dtos.Cart,/sessionNo\?:string/);
 assert(m.apiFlows[grant.id].writeTables.includes('fnb_guest_session'));
 for(const id of ['037','038','039','040','126','127','128','129','130']){
  const a=s.apis.find(a=>a.id==='FOOD-API-'+id);
  assert.equal(a.implementation,'IMPLEMENTED_BACKEND_DRAFT');
  assert.equal(c.methodOverrides[a.id].service,'CheckCartService');
 }
});
test('ORD01 method inventory preserves actual customer access contract',()=>{
 const v=read('food-method-inventory.json');
 const e=v.endpoints.find(a=>a.apiId==='FOOD-API-129');
 assert.equal(e.serviceMethod,'customerReplaceLine');
 assert.match(e.serviceSignature,/CartAccess access/);
 assert(!e.serviceSignature.includes('String entityNo'));
 const get=v.endpoints.find(a=>a.apiId==='FOOD-API-127');
 assert.equal(read('food-module-code-inventory.json').endpoints.find(a=>a.apiId==='FOOD-API-127').requestClass,null);
 assert.match(get.controllerSignature,/@RequestParam\("sessionNo"\)/);
 assert(!get.controllerSignature.includes('@ModelAttribute'));
});
test('implemented ORD01 call lists describe actual tombstone and insertion methods',()=>{
 const v=read('food-method-inventory.json');
 const e=id=>v.endpoints.find(a=>a.apiId==='FOOD-API-'+id);
 for(const id of ['037','038','039','040','126','127','128','129','130','156']){
  assert(!e(id).controllerSignature.includes('undefined'),id);
 }
 assert(!e('126').mapperCalls.some(c=>c.method==='closeCartIfNoPlacingBatch'));
 for(const id of ['039','040','129','130']){
  assert(e(id).mapperCalls.some(c=>c.method==='history'),id);
  assert(e(id).mapperCalls.some(c=>c.method==='retain'),id);
 }
 assert(e('128').mapperCalls.some(c=>c.table==='fnb_guest_session'&&c.method==='insert'));
 assert(!e('128').mapperCalls.some(c=>c.table==='fnb_guest_session'&&c.method==='update'));
});
