import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validateDeferredDelivery} from '../lib/backend-ui-delivery.mjs';
const key='GET /ops/pay/entitlement-exceptions';
const manifest=()=>({version:1,stage:'JAVA_BACKEND_UI_DEFERRED',reason:'Backend first; UI follows',
    entries:[{app:'ops-web',method:'GET',path:'/ops/pay/entitlement-exceptions'}]});
const options=()=>({app:'ops-web',prefix:'/ops',published:new Set([key]),handlers:new Set([key]),missing:new Set([key])});
test('explicit staged Ops API requires a real published contract and handler',()=>{
    assert.deepEqual([...validateDeferredDelivery(manifest(),options())],[key]);
    assert.throws(()=>validateDeferredDelivery(manifest(),{...options(),published:new Set()}));
    assert.throws(()=>validateDeferredDelivery(manifest(),{...options(),handlers:new Set()}));
});
test('a connected or deleted API must leave the staged manifest',()=>{
    assert.throws(()=>validateDeferredDelivery(manifest(),{...options(),missing:new Set()}));
});
test('duplicate paths, wrong realm and missing delivery rationale remain hard failures',()=>{
    let data=manifest();data.entries.push({...data.entries[0]});
    assert.throws(()=>validateDeferredDelivery(data,options()));
    data=manifest();data.entries[0].app='b-app';assert.throws(()=>validateDeferredDelivery(data,options()));
    data=manifest();data.reason='';assert.throws(()=>validateDeferredDelivery(data,options()));
    data=manifest();data.stage='IGNORE';assert.throws(()=>validateDeferredDelivery(data,options()));
});
