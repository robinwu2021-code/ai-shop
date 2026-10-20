// @vitest-environment happy-dom
import { expect,it,vi,beforeEach } from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
import './component-setup';
const m=vi.hoisted(()=>({select:vi.fn(),plans:vi.fn(),confirm:vi.fn(),query:vi.fn(),hide:()=>{},show:()=>{}}));
vi.mock('@dcloudio/uni-app',()=>({onHide:(f:()=>void)=>{m.hide=f;},onShow:(f:()=>void)=>{m.show=f;}}));
vi.mock('@/api',()=>({api:{mSvSelectCustomer:m.select,mSvPlans:m.plans,mSvConfirmOffline:m.confirm,mSvRecharge:m.query}}));
vi.mock('@/stores/merchant',()=>({useMerchantStore:()=>({storeNo:'S1',profile:{merchantNo:'M1'},can:()=>true})}));
vi.mock('vue-i18n',()=>({useI18n:()=>({t:(s:string)=>s})}));
Object.assign(uni,{removeStorageSync:vi.fn()});
import Sheet from '@/pages/stored-value/components/StoredValueRechargeSheet.vue';
const plan={planNo:'P1',versionNo:'V1',storeNo:'S1',name:'充100送30',currency:'CNY',paidAmountMinor:10000,bonusAmountMinor:3000,totalAmountMinor:13000,status:'ACTIVE'};
beforeEach(()=>{vi.clearAllMocks();m.select.mockResolvedValue({customerRef:'REF',expiresAt:new Date(Date.now()+120000).toISOString(),displayName:'林女士',phoneMasked:'138****0001',account:null});m.plans.mockResolvedValue({records:[plan],nextCursor:null});});
it('opens plans directly and submits only identity and the chosen plan with the same retry key',async()=>{
 m.confirm.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({rechargeNo:'R1',status:'CREDITED',revision:2,storeNo:'S1',currency:'CNY',creditedTotalMinor:13000});
 const w=mount(Sheet,{props:{visible:true,source:{type:'MEMBER',memberNo:'MEM1'}}});await flushPromises();
 expect(w.findAll('input')).toHaveLength(0);
 expect(m.select).toHaveBeenCalledWith({storeNo:'S1',source:{type:'MEMBER',memberNo:'MEM1'}});
 await w.find('[data-testid="recharge-plan"]').trigger('tap');
 await w.find('[data-testid="confirm-offline"]').trigger('tap');await flushPromises();
 await w.find('[data-testid="confirm-offline"]').trigger('tap');await flushPromises();
 expect(m.confirm).toHaveBeenCalledTimes(2);
 expect(m.confirm.mock.calls[0][0]).toEqual({storeNo:'S1',customerRef:'REF',planNo:'P1',versionNo:'V1'});
 expect(m.confirm.mock.calls[1]).toEqual(m.confirm.mock.calls[0]);
 expect(w.text()).toContain('storedValue.credited');expect(w.emitted('credited')).toHaveLength(1);w.unmount();
});

it('does not duplicate an in-flight confirmation or apply its result after the sheet closes',async()=>{
 let resolve!:(r:unknown)=>void;m.confirm.mockImplementation(()=>new Promise(r=>{resolve=r;}));
 const w=mount(Sheet,{props:{visible:true,source:{type:'MEMBER',memberNo:'MEM1'}}});await flushPromises();
 await w.find('[data-testid="recharge-plan"]').trigger('tap');
 await w.find('[data-testid="confirm-offline"]').trigger('tap');
 await w.find('[data-testid="confirm-offline"]').trigger('tap');expect(m.confirm).toHaveBeenCalledTimes(1);
 await w.setProps({visible:false});resolve({rechargeNo:'R1',status:'CREDITED',storeNo:'S1',currency:'CNY',creditedTotalMinor:13000});await flushPromises();
 expect(w.emitted('credited')).toBeUndefined();w.unmount();
});

it('reloads a first selection discarded while the app was hidden',async()=>{
 let resolve!:(r:unknown)=>void;m.plans.mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));
 const w=mount(Sheet,{props:{visible:true,source:{type:'MEMBER',memberNo:'MEM1'}}});await flushPromises();
 m.hide();resolve({records:[plan],nextCursor:null});await flushPromises();
 expect(w.findAll('[data-testid="recharge-plan"]')).toHaveLength(0);
 m.show();await flushPromises();
 expect(w.findAll('[data-testid="recharge-plan"]')).toHaveLength(1);
 expect(m.select).toHaveBeenCalledTimes(2);expect(m.confirm).not.toHaveBeenCalled();w.unmount();
});
