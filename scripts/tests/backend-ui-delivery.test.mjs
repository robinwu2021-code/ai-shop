import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validateDeferredDelivery, readDeferredDelivery} from '../lib/backend-ui-delivery.mjs';
import {mkdtempSync, mkdirSync, writeFileSync, rmSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
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

test('pricing configuration staging uses its own contract without weakening the entitlement default', () => {
    const root = mkdtempSync(join(tmpdir(), 'pricing-delivery-'));
    try {
        mkdirSync(join(root, 'docs/api'), {recursive: true});
        mkdirSync(join(root, 'docs/technical'), {recursive: true});
        writeFileSync(join(root, 'docs/technical/pricing.md'), '# Pricing UI follows backend');
        const path = '/ops/pricing-templates/{no}/draft';
        writeFileSync(join(root, 'docs/api/stage.json'), JSON.stringify({version: 1,
            stage: 'JAVA_BACKEND_UI_DEFERRED', reason: 'Backend first', plan: 'docs/technical/pricing.md',
            entries: [{app: 'ops-web', method: 'GET', path}]}));
        writeFileSync(join(root, 'docs/api/pricing-config.openapi.json'), JSON.stringify({paths: {[path]: {get: {}}}}));
        const endpoint = {method: 'GET', path};
        const opts = {app: 'ops-web', prefix: '/ops', handlers: [endpoint], missing: [endpoint],
            contract: 'docs/api/pricing-config.openapi.json'};
        assert.deepEqual([...readDeferredDelivery(root, 'docs/api/stage.json', opts)], ['GET /ops/pricing-templates/*/draft']);
        assert.throws(() => readDeferredDelivery(root, 'docs/api/stage.json', {...opts,
            contract: 'docs/api/entitlement-backend.openapi.json'}));
    } finally { rmSync(root, {recursive: true, force: true}); }
});
