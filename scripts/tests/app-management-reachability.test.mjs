import {test} from 'node:test';
import assert from 'node:assert/strict';
import {unreachable} from '../check-app-orphan.mjs';

test('existing management entry reaches brand and governed stored-value pages through real managementGo calls', () => {
    const unreachablePages = new Set(unreachable().filter(row => row.app === 'b-app').map(row => row.page));
    for (const route of ['brand-list', 'brand-edit', 'brand-detail', 'management-resources', 'store-brand',
        'management-grants', 'brand-change', 'stored-value-issuer', 'stored-value-policy',
        'stored-value-approval', 'stored-value-activity', 'stored-value-activity-edit']) {
        assert(!unreachablePages.has(`pages/${route}/index`), `Real management route not followed: ${route}`);
    }
});


test('unrelated functions, bare aliases and dynamic arguments cannot make pages reachable', async () => {
    const {managementRouteRef} = await import('../lib/management-route-refs.mjs');
    const realImport = "import {managementGo} from '@/shared/management';";
    assert.equal(managementRouteRef(realImport + "managementGo('brand_list', {m: 'M'})", 'brand_list'), true);
    assert.equal(managementRouteRef("managementGo('brand_list')", 'brand_list'), false);
    assert.equal(managementRouteRef("import {managementGo} from './unrelated';managementGo('brand_list')", 'brand_list'), false);
    assert.equal(managementRouteRef(realImport + "managementGo(page)", 'brand_list'), false);
    assert.equal(managementRouteRef(realImport + "managementGo('brand_list_other')", 'brand_list'), false);
    assert.equal(managementRouteRef(realImport + "const route = 'brand_list'", 'brand_list'), false);
});
