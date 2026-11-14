// @vitest-environment happy-dom
import {beforeEach,expect,it,vi} from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
import './component-setup';
const m=vi.hoisted(()=>({select:vi.fn(),options:vi.fn(),confirm:vi.fn()}));
vi.mock('@dcloudio/uni-app',()=>({onHide:()=>{},onShow:()=>{}}));
vi.mock('@/api',()=>({api:{mManagementContext:async()=>({actorKey:'ACTOR'}),mSvSelectCustomer:m.select,mSvRechargeOptions:m.options,mSvConfirmOffline:m.confirm}}));
vi.mock('@/stores/merchant',()=>({useMerchantStore:()=>({storeNo:'S1',profile:{merchantNo:'M1'},can:()=>true})}));
vi.mock('vue-i18n',()=>({useI18n:()=>({t:(s:string)=>s})}));
import Sheet from '@/pages/stored-value/components/StoredValueRechargeSheet.vue';
import {storedValuePending} from '@/shared/stored-value-pending';
import type {SvPending} from '@/shared/stored-value-pending';
const plan={planNo:'P1',versionNo:'PV1',name:'Local',storeNo:'S1',currency:'CNY',paidAmountMinor:5000,bonusAmountMinor:0,totalAmountMinor:5000};
const brand={optionRef:'SIGNED_BRAND_TIER',activityName:'Brand',currency:'CNY',paidAmountMinor:10000,bonusAmountMinor:3000,totalAmountMinor:13000};
beforeEach(()=>{
 vi.clearAllMocks();Object.assign(uni,{removeStorageSync:vi.fn()});vi.mocked(uni.getStorageSync).mockReturnValue('');
 m.select.mockResolvedValue({customerRef:'CREF',pendingScope:'PERSON',displayName:'Customer',phoneMasked:'***',expiresAt:new Date(Date.now()+60000).toISOString(),account:null});
 m.options.mockImplementation(async q=>({issuerOptions:{legacyMode:false,issuers:[{scopeType:'STORE',canRecharge:true},{scopeType:'BRAND',canRecharge:true}]},plans:{records:q.scopeType==='STORE'?[plan]:[],nextCursor:null},brandOptions:q.scopeType==='BRAND'?{records:[brand],nextCursor:null}:null}));
 m.confirm.mockResolvedValue({rechargeNo:'R1',status:'CREDITED',currency:'CNY',creditedTotalMinor:13000});
});
it('switches to brand tiers explicitly and sends the opaque tier without plan IDs or client amounts',async()=>{
 const w=mount(Sheet,{props:{visible:true,source:{type:'MEMBER',memberNo:'MEMBER'}}});await flushPromises();
 const tabs=w.findComponent({name:'sh-tabs'});expect(tabs.exists()).toBe(true);tabs.vm.$emit('change','BRAND');await flushPromises();
 await w.find('[data-testid="recharge-plan"]').trigger('tap');await w.find('[data-testid="confirm-offline"]').trigger('tap');await flushPromises();
 expect(m.confirm.mock.calls[0][0]).toEqual({storeNo:'S1',customerRef:'CREF',scopeType:'BRAND',planNo:null,versionNo:null,optionRef:'SIGNED_BRAND_TIER'});
 expect(w.emitted('credited')).toHaveLength(1);w.unmount();
});
it('isolates pending storage by actor and customer plus financial scope',()=>{
 const saved=new Map<string,string>();vi.mocked(uni.getStorageSync).mockImplementation(k=>saved.get(String(k))||'');vi.mocked(uni.setStorageSync).mockImplementation((k,v)=>{saved.set(String(k),String(v));});
 const value={key:'K1',body:{storeNo:'S1',customerRef:'CREF',scopeType:'BRAND',planNo:null,versionNo:null,optionRef:brand.optionRef},plan:brand,customer:{customerRef:'CREF'},progress:null} as SvPending;
 const a=storedValuePending('M1','S1','A');a.write('PERSON:BRAND',value);
 expect(storedValuePending('M1','S1','B').entries()).toEqual({});
 expect(a.entries()['PERSON:STORE']).toBeUndefined();expect(a.entries()['PERSON:BRAND']?.key).toBe('K1');
});
