import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {refsOf} from '../check-i18n-orphan.mjs';
import {managementRouteRefs,stripManagementPermissionData,stripTemplateActionData} from '../management-guard-refs.mjs';
import {pageRefs,unreachable} from '../check-app-orphan.mjs';

const aliases = new Map([['pages/brand-list/index','brand_list'],['pages/management-resources/index','management_resources']]);
test('imported management wrapper links only literal known route arguments', () => {
 const source = `import {managementGo as go} from '@/shared/management';
 go('brand_list',{m:'M1'}); go(variable); go('unknown'); go('management_resources'+suffix);`;
 assert.deepEqual([...managementRouteRefs(source,aliases)], ['pages/brand-list/index']);
 assert.deepEqual([...managementRouteRefs(`managementGo('brand_list')`,aliases)], []);
 assert.deepEqual([...managementRouteRefs(`import {managementGo} from './different';managementGo('brand_list')`,aliases)], []);
 assert.deepEqual([...pageRefs(source,[...aliases.keys()],aliases)], ['pages/brand-list/index']);
});
test('actual management navigation resolves ROUTES[page] and is reachable from app entry points', () => {
 const wrapper = readFileSync(new URL('../../b-app/src/shared/management.ts',import.meta.url),'utf8');
 assert.match(wrapper,/uni\.navigateTo\(\{url:ROUTES\[page\]/);
 const actual = readFileSync(new URL('../../b-app/src/pages/management/index.vue',import.meta.url),'utf8');
 assert.deepEqual([...managementRouteRefs(actual,aliases)].sort(),[...aliases.keys()].sort());
 const managementPages = ['brand-list','brand-edit','brand-detail','management-resources','store-brand','management-grants','brand-change','stored-value-issuer'];
 assert.deepEqual(unreachable().filter(r=>r.app==='b-app' && managementPages.some(p=>r.page===`pages/${p}/index`)),[]);
});
test('grant enum data maps to permission labels without hiding a real translation of the same string', () => {
 const source = `const add=(resourceType:string,resourceNo:string,permissionCode:string)=>api.mManagementGrant(m,{actorNo:actor,resourceType,resourceNo,permissionCode});
 for(const permission of ['store.read','store.brand.change'])await add('STORE',source.storeNo,permission);
 add('PAYMENT_MERCHANT',no,'payment.merchant.read');
 api.mManagementGrant(m,{permissionCode:'entity.read'});
 t('store.read');t('store.missing');const unrelated=['store.keep'];`;
 const keys=[];const clean=stripManagementPermissionData(source,key=>keys.push(key));
 assert.deepEqual(keys.sort(),['entity_read','payment_merchant_read','store_brand_change','store_read'].map(k=>'management.permission.'+k).sort());
 assert.match(clean,/t\('store.read'\)/);assert.match(clean,/t\('store.missing'\)/);
 assert.match(clean,/\['store.keep'\]/);assert.doesNotMatch(clean,/\['store.read'/);
 assert.doesNotMatch(clean,/'payment.merchant.read'/);
});
test('actions membership is enum data while raw translation calls still count',()=>{
 const script=`context.actions.includes('store.read');t('store.read')`;
 assert.equal((stripManagementPermissionData(script).match(/'store.read'/g)||[]).length,1);
 const template=`<view v-if="context.actions.includes('store.brand.change')">{{t('store.brand.change')}}{{t('store.missing')}}</view>`;
 const clean=stripTemplateActionData(template);
 assert.doesNotMatch(clean,/includes/);assert.match(clean,/t\('store.brand.change'\)/);assert.match(clean,/t\('store.missing'\)/);
});

test('translation scanner retains real missing keys beside grant and template enum data',()=>{
 const dir=mkdtempSync(join(tmpdir(),'management-i18n-'));
 try {
  const file=join(dir,'page.vue');
  writeFileSync(file,`<script setup lang="ts">
   api.mManagementGrant(m,{permissionCode:'payment.merchant.read'});
   t('payment.merchant.read');t('store.missing');
  </script><template><view v-if="context.actions.includes('store.brand.change')">{{t('store.templateMissing')}}</view></template>`);
  const {keys}=refsOf([file]);
  assert.ok(keys.has('management.permission.payment_merchant_read'));
  assert.ok(keys.has('payment.merchant.read'));
  assert.ok(keys.has('store.missing'));assert.ok(keys.has('store.templateMissing'));
  assert.ok(!keys.has('store.brand.change'));
 } finally {rmSync(dir,{recursive:true,force:true});}
});
