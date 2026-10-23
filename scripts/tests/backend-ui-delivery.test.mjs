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

test('multiple explicit contracts preserve handler and stale-entry enforcement', async () => {
    const {mkdtempSync, mkdirSync, writeFileSync, rmSync} = await import('node:fs');
    const {tmpdir} = await import('node:os');
    const {join} = await import('node:path');
    const {readDeferredDelivery} = await import('../lib/backend-ui-delivery.mjs');
    const root = mkdtempSync(join(tmpdir(), 'delivery-contracts-'));
    try {
        mkdirSync(join(root, 'docs/api'), {recursive:true});
        mkdirSync(join(root, 'docs/technical'));
        writeFileSync(join(root, 'docs/technical/plan.md'), 'Frontend tasks');
        const spec = paths => JSON.stringify({openapi:'3.0.3', paths});
        writeFileSync(join(root, 'docs/api/entitlement-backend.openapi.json'), spec({'/ops/pay/entitlement-exceptions':{get:{}}}));
        writeFileSync(join(root, 'docs/api/print-operations.openapi.json'), spec({'/ops/print/health':{get:{}}}));
        const data = {...manifest(), plan:'docs/technical/plan.md'};
        const entries = [{method:'GET',path:'/ops/pay/entitlement-exceptions'}, {method:'GET',path:'/ops/print/health'}];
        const opts = {app:'ops-web',prefix:'/ops',handlers:entries,missing:entries};
        const run = (override={}) => {
            writeFileSync(join(root, 'docs/api/staged.json'), JSON.stringify({...data,...override}));
            return readDeferredDelivery(root,'docs/api/staged.json',opts);
        };
        assert.deepEqual([...run()], [key]); // original default contract remains supported
        const contracts = ['docs/api/entitlement-backend.openapi.json','docs/api/print-operations.openapi.json'];
        const both = entries.map(row=>({...row,app:'ops-web'}));
        assert.equal(run({contracts,entries:both}).size,2);
        for (const invalid of [[],[contracts[0],contracts[0]],['../escape'],['docs/api/../escape.openapi.json'],['docs/api/missing.openapi.json'],['docs\\api\\escape.openapi.json']]) {
            assert.throws(()=>run({contracts:invalid}));
        }
        assert.throws(()=>run({entries:both})); // print API needs its own contract
        opts.handlers = [entries[0]];
        assert.throws(()=>run({contracts,entries:both}));
        opts.handlers = entries; opts.missing = [entries[0]];
        assert.throws(()=>run({contracts,entries:both}));
    } finally { rmSync(root,{recursive:true,force:true}); }
});
