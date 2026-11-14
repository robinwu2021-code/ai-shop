import test from 'node:test';
import assert from 'node:assert/strict';
import {discoverFoodSource} from '../lib/food-source-inventory.mjs';
test('bare GET and POST annotations retain the class-level return stock path',()=>{
 const actual=discoverFoodSource(process.cwd());
 const routes=actual.endpoints.filter(e=>e.controllerClass.endsWith('.BizFoodReturnStockController'));
 const root='/biz/x/food/stores/{storeNo}/returns/{returnNo}/stock-dispositions';
 assert.equal(routes.length,3);
 assert.ok(routes.some(e=>e.method==='GET'&&e.path===root&&e.responseType==='PageData<DispositionVO>'));
 assert.ok(routes.some(e=>e.method==='POST'&&e.path===root&&e.parameters.some(p=>p.binding==='RequestBody')));
 assert.ok(routes.some(e=>e.method==='POST'&&e.path===root+'/{operationNo}:retry'));
});
