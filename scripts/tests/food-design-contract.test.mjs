import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {validateFoodDesign} from '../lib/food-design-validation.mjs';
const original = JSON.parse(fs.readFileSync(new URL('../../docs/technical/design/schema/food-system-design.json', import.meta.url)));
const copy = () => structuredClone(original);
test('current source resolves all table/API/DTO and module requirement references', () => {
  assert.equal(validateFoodDesign(copy()), true);
});
test('reject parent-only unique key that prevents a menu serving multiple stores', () => {
  const s=copy(); s.tables.find(t=>t.name==='fnb_menu_store').unique=[['menu_no']];
  assert.throws(()=>validateFoodDesign(s), /Cardinality key missing|Parent-only unique/);
});
test('reject parent-only unique keys on dish options, menu SKUs and station routing', () => {
  for(const name of ['fnb_dish_option_group','fnb_menu_item_sku','fnb_sku_station']){
    const s=copy();s.tables.find(t=>t.name===name).unique.push([name==='fnb_dish_option_group'?'goods_no':name==='fnb_menu_item_sku'?'menu_item_no':'sku_no']);
    assert.throws(()=>validateFoodDesign(s),/Parent-only unique/);
  }
});
test('reject a nullable business dedup key for plain dish kitchen dispatch', () => {
  const s=copy(),t=s.tables.find(t=>t.name==='fnb_kitchen_task');t.unique.push(['ticket_no','component_no','station_no']);
  assert.throws(()=>validateFoodDesign(s),/Nullable dedup key/);
});
test('reject duplicate effective route even if path parameter names differ', () => {
  const s=copy(),a=structuredClone(s.apis.find(a=>a.id==='FOOD-API-097'));a.id='FOOD-API-999';a.path=a.path.replace('{tableNo}','{resourceNo}');s.apis.push(a);
  assert.throws(()=>validateFoodDesign(s),/Duplicate route/);
});
test('reject duplicate input fields and undefined DTOs', () => {
  const s=copy();s.apis[0].input='at?:datetime:time;at?:datetime:other';
  assert.throws(()=>validateFoodDesign(s),/Duplicate .* parameter/);
  s.apis[0].input='at?:MissingDTO:time';assert.throws(()=>validateFoodDesign(s),/Unknown DTO/);
});
test('reject a requirement with missing or duplicate primary module ownership', () => {
  const s=copy();s.businessModules[0].requirements.pop();assert.throws(()=>validateFoodDesign(s),/Incomplete module coverage/);
  const other=copy();other.businessModules[1].requirements.push(other.businessModules[0].requirements[0]);assert.throws(()=>validateFoodDesign(other),/Duplicate module requirement/);
});
test('reject fabricated alignment API or missing conclusion', () => {
  const s=copy();s.requirementAlignment[0].apiIds.push('FOOD-API-999');assert.throws(()=>validateFoodDesign(s),/Unknown alignment API/);
  const other=copy();other.requirementAlignment[0].note='';assert.throws(()=>validateFoodDesign(other),/Missing alignment conclusion/);
});
test('all customer write APIs keep the explicit whitelist and forbid cashier authority', () => {
  const allowed=new Set([26,127,128,129,130,131,132,133,134,135,136,137,138,139,140].map(n=>'FOOD-API-'+String(n).padStart(3,'0')));
  for(const a of original.apis.filter(a=>a.path.startsWith('/mp/')&&a.method!=='GET')){
    assert(allowed.has(a.id), a.id);
    assert(!a.input.split(';').some(f=>/^(buyerNo|cashEvidenceRef|corePaymentToken)[?:]/.test(f)),a.id);
    assert(!/:(execute|sign|finalize|ready|serve)$/.test(a.path),a.path);
  }
});
