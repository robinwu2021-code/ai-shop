import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {renderFoodDeliveryStatus, validateFoodDeliveryStatus} from '../lib/food-delivery-status.mjs';
const design=JSON.parse(fs.readFileSync(new URL('../../docs/technical/design/schema/food-system-design.json',import.meta.url)));
test('current delivery has distinct evidence without promoting historical route acceptance',()=>{
 validateFoodDeliveryStatus(design.currentDelivery);
 assert.equal(design.currentDelivery.production.sourceCommit,'4440eb46a23279b1fe6687bf7c012d26e9b3e687');
 assert.equal(design.currentDelivery.production.shape,'food-embedded');
 assert.equal(design.currentDelivery.businessAcceptance,'PENDING_TERMINAL_AND_REAL_OWNER_E2E');
 assert.equal(design.currentDelivery.production.reportEnabled,false);
 assert.equal(design.currentDelivery.production.paymentReleased,false);
 assert.equal(design.nonPaymentImplementation.implementedApis[0].status,'CODED_PENDING_VERIFICATION');
 const text=renderFoodDeliveryStatus(design.currentDelivery);
 assert.match(text,/FOOD V25/);assert.match(text,/历史/);assert.match(text,/营业验收/);
});
test('delivery metadata rejects missing evidence or accidental full acceptance',()=>{
 assert.throws(()=>validateFoodDeliveryStatus({...design.currentDelivery,evidence:[]}),/evidence/);
 assert.throws(()=>validateFoodDeliveryStatus({...design.currentDelivery,businessAcceptance:'PASS'}),/acceptance/);
});
test('generated original books lead with current release evidence and label historical boundaries',()=>{
 for(const name of ['餐饮系统-API设计.md','餐饮系统-数据库设计.md','餐饮系统-领域对象与设计总览.md']) {
  const text=fs.readFileSync(new URL('../../docs/technical/design/'+name,import.meta.url),'utf8');
  assert.match(text.split('\n').slice(0,8).join('\n'),/状态[：:]/);
  assert.match(text.slice(0,2200),/当前交付证据/);assert.match(text.slice(0,2200),/4440eb46a/);
 }
});
test('committed domain OpenAPI references resolve to actual local definitions',()=>{
 for(const file of ['food-business-support','pricing-config','entitlement-backend','print-operations']) {
  const doc=JSON.parse(fs.readFileSync(new URL('../../docs/api/'+file+'.openapi.json',import.meta.url),'utf8'));
  const visit=x=>{
   if(Array.isArray(x))return x.forEach(visit);
   if(!x||typeof x!=='object')return;
   if(x.$ref?.startsWith('#/')) {
    let node=doc;
    for(const key of x.$ref.slice(2).split('/'))node=node?.[key.replaceAll('~1','/').replaceAll('~0','~')];
    assert.notEqual(node,undefined,`${file}: ${x.$ref}`);
   }
   Object.values(x).forEach(visit);
  };
  visit(doc);
 }
});
test('generated headers discover current source migrations separately from recorded production baseline',()=>{
 const latest=dir=>Math.max(...fs.readdirSync(new URL('../../'+dir+'/',import.meta.url)).map(n=>Number(/^V(\d+)__/.exec(n)?.[1]||0)));
 const core=latest('backend/shop-app/src/main/resources/db/migration');
 const inventory=latest('backend/shop-inventory/src/main/resources/db/inventory');
 const doc=fs.readFileSync(new URL('../../docs/technical/design/餐饮系统-API设计.md',import.meta.url),'utf8');
 assert.ok(doc.includes(`源码迁移目标 FOOD V25 / CORE V${core} / INVENTORY V${inventory}`));
});
