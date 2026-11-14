// @vitest-environment happy-dom
import {it,expect,vi,beforeEach} from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
import './component-setup';
import {createPinia} from 'pinia';
const mocks=vi.hoisted(()=>({context:vi.fn(),resources:vi.fn(),recipients:vi.fn(),grant:vi.fn(),brand:vi.fn(),status:vi.fn()}));
vi.mock('@dcloudio/uni-app',()=>({onLoad:(f:(q:unknown)=>void)=>f({m:'M1',b:'B1'}),onShow:(f:()=>void)=>f()}));
vi.mock('@/api',()=>({api:{mManagementContext:mocks.context,mManagementResources:mocks.resources,mManagementRecipients:mocks.recipients,mManagementGrant:mocks.grant,mBrand:mocks.brand,mBrandStatus:mocks.status,mBrandStores:async()=>({items:[],nextCursor:null}),mBrandAudit:async()=>({items:[],nextCursor:null})}}));
vi.mock('vue-i18n',()=>({useI18n:()=>({t:(s:string)=>s})}));
vi.mock('@ai-shop/ui/prompt',async importOriginal=>({...await importOriginal<typeof import('@ai-shop/ui/prompt')>(),confirm:async()=>true}));
import Grants from '@/pages/management-grants/index.vue';
import Detail from '@/pages/brand-detail/index.vue';
const grant={grantNo:'G1',actorNo:'MCH:A',resourceType:'STORE',resourceNo:'S1',permissionCode:'store.read',revision:3,status:'REVOKED',resourceName:'甲店',delegatable:true};
beforeEach(()=>{vi.clearAllMocks();mocks.context.mockResolvedValue({actorKey:'MCH:OWNER',spaces:[]});mocks.resources.mockImplementation(async(_m,q)=>({items:q?.view==='EFFECTIVE'||q?.actorNo==='MCH:OWNER'?[{...grant,status:'ACTIVE',actorNo:'MCH:OWNER'}]:[grant],nextCursor:null}));mocks.recipients.mockResolvedValue({items:[{actorNo:'MCH:A',displayName:'同事'}],nextCursor:null});mocks.grant.mockResolvedValue({});mocks.brand.mockResolvedValue({brandNo:'B1',managementNo:'M1',name:'品牌',revision:0,status:'ACTIVE',actions:['brand.manage']});mocks.status.mockResolvedValue({status:'INACTIVE',revision:1});});
const tap=async(w:ReturnType<typeof mount>,text:string)=>{const target=w.findAll('view').filter(v=>v.text()===text).at(-1);expect(target).toBeTruthy();await target!.trigger('tap');await flushPromises();};
it('uses management-scoped recipients and explicitly reactivates a revoked permission',async()=>{
 const w=mount(Grants,{global:{plugins:[createPinia()]}});await flushPromises();await tap(w,'management.staffmanagement.choose');
 expect(mocks.recipients).toHaveBeenCalledWith('M1',undefined);await tap(w,'同事');await tap(w,'management.resourcemanagement.choose');
 await tap(w,'甲店 · management.permission.store_read');await tap(w,'management.grant');
 expect(mocks.grant).toHaveBeenCalledWith('M1',expect.objectContaining({actorNo:'MCH:A',expectedRevision:3,reactivate:true,resourceNo:'S1'}));w.unmount();
});
it('reloads authorized brand actions after changing status',async()=>{
 const w=mount(Detail,{global:{plugins:[createPinia()]}});await flushPromises();await tap(w,'management.stopBrand');
 expect(mocks.status).toHaveBeenCalled();expect(mocks.brand).toHaveBeenCalledTimes(2);expect(w.text()).toContain('management.edit');w.unmount();
});

it('uses server-verified delegation authority for an entity owner whose receipt permission is not onward-delegatable',async()=>{
 const financial={...grant,actorNo:'MCH:OWNER',resourceType:'ENTITY',resourceNo:'E1',resourceName:'Entity',permissionCode:'sv.settlement.receipt.confirm',status:'ACTIVE',delegatable:false};mocks.resources.mockImplementation(async(_m,q)=>({items:q?.actorNo==='MCH:A'?[]:[financial],nextCursor:null}));const w=mount(Grants,{global:{plugins:[createPinia()]}});await flushPromises();await tap(w,'management.staffmanagement.choose');await tap(w,'同事');await tap(w,'management.resourcemanagement.choose');await tap(w,'Entity · management.permission.sv_settlement_receipt_confirm');await tap(w,'management.grant');expect(mocks.resources).toHaveBeenCalledWith('M1',expect.objectContaining({view:'GRANTS',actorNo:'MCH:OWNER'}));expect(mocks.grant).toHaveBeenCalledWith('M1',expect.objectContaining({actorNo:'MCH:A',resourceType:'ENTITY',resourceNo:'E1',permissionCode:'sv.settlement.receipt.confirm'}));w.unmount();
});

it('formats management timestamps consistently when the native locale formatter falls back to an English date',async()=>{
 const {managementTime}=await import('@/shared/management');
 const native=vi.spyOn(Date.prototype,'toLocaleString').mockReturnValue('Wed Oct 21 2026 23:51:53 GMT+0800 (CST)');
 try{expect(managementTime('2026-10-21T15:51:53Z')).toBe('2026-10-21 23:51');expect(managementTime('invalid')).toBe('—');expect(managementTime()).toBe('—')}finally{native.mockRestore()}
});
