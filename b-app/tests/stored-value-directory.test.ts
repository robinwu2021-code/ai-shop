// @vitest-environment happy-dom
import {beforeEach,expect,it,vi} from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
import {reactive} from 'vue';
import './component-setup';
const m=vi.hoisted(()=>({accounts:vi.fn(),candidates:vi.fn(),select:vi.fn(),account:vi.fn(),records:vi.fn(),rechargeOptions:vi.fn(),load:null as null|((q:Record<string,string>)=>void)}));
vi.mock('@dcloudio/uni-app',()=>({onLoad:(f:typeof m.load)=>{m.load=f;},onShow:vi.fn(),onHide:vi.fn()}));
vi.mock('@/api',()=>({api:{mSvAccounts:m.accounts,mScopeCandidates:m.candidates,mSvSelectCustomer:m.select,mSvAccount:m.account,mSvRecords:m.records,mSvRechargeOptions:m.rechargeOptions}}));
const store=reactive({storeNo:'S1',profile:{merchantNo:'M1'},multiStore:false,can:()=>true,ensureStores:async()=>{}});
vi.mock('@/stores/merchant',()=>({useMerchantStore:()=>store}));
vi.mock('vue-i18n',()=>({useI18n:()=>({t:(k:string)=>k})}));
import Directory from '@/pages/stored-value/components/CustomerAccountList.vue';
import Detail from '@/pages/stored-value-account/index.vue';
const mountOptions={global:{mocks:{$t:(key:string)=>key}}};
const funded={accountRef:'A1',maskedName:'已储值会员',maskedPhone:'138****0002',currency:'CNY',balanceMinor:0,heldMinor:0,availableMinor:0,lastOccurredAt:null,lastRechargeAt:'2026-10-20T02:00:00Z'};
const candidate={memberNo:'M2',displayName:'未储值会员',phoneMasked:'138****0001',memberStatus:'LEAD',canRecharge:true,unavailableReason:null,account:null};
const selected={...candidate,customerRef:'REF',expiresAt:new Date(Date.now()+120000).toISOString()};
beforeEach(()=>{vi.clearAllMocks();store.storeNo='S1';m.accounts.mockResolvedValue({records:[funded],nextCursor:null});m.candidates.mockResolvedValue({items:[candidate],nextCursor:null,identityConflictCount:0});m.select.mockResolvedValue(selected);m.records.mockResolvedValue({records:[],nextCursor:null});m.rechargeOptions.mockResolvedValue({issuerOptions:{storeNo:'S1',legacyMode:true,issuers:[]},plans:{records:[],nextCursor:null}});});
it('searches members from the directory, opens detail without a balance, and clears back to funded members',async()=>{
 const w=mount(Directory,mountOptions);await flushPromises();expect(w.text()).toContain('已储值会员');
 await w.find('input').setValue('13800000001');await w.find('input').trigger('confirm');await flushPromises();
 expect(w.text()).toContain('未储值会员');expect(w.text()).not.toContain('storedValue.balance');
 await w.find('[data-testid="sv-customer"]').trigger('tap');
 expect(uni.navigateTo).toHaveBeenCalledWith({url:'/pages/stored-value-account/index?scopeMemberNo=M2&storeNo=S1'});
 await w.find('input').setValue('');await flushPromises();expect(w.text()).toContain('已储值会员');w.unmount();
});
it('does not apply a member search after it is cleared or the store changes',async()=>{
 let resolve!:(r:unknown)=>void;m.candidates.mockImplementation(()=>new Promise(r=>{resolve=r;}));
 const w=mount(Directory,mountOptions);await flushPromises();await w.find('input').setValue('13800000001');await w.find('input').trigger('confirm');
 await w.find('input').setValue('');await flushPromises();store.storeNo='S2';await flushPromises();
 resolve({items:[candidate],nextCursor:null,identityConflictCount:0});await flushPromises();expect(w.text()).not.toContain('未储值会员');w.unmount();
});
it('opens the plan sheet only after the bottom recharge action on an unfunded member detail',async()=>{
 const w=mount(Detail,{global:{mocks:mountOptions.global.mocks,stubs:{'sh-scaffold':{template:'<div><slot/></div>'}}}});m.load?.({memberNo:'M2',storeNo:'S1'});await flushPromises();
 expect(w.text()).toContain('未储值会员');expect(w.text()).not.toContain('storedValue.balance');
 expect(w.find('[data-testid="confirm-offline"]').exists()).toBe(false);
 await w.find('[data-testid="detail-recharge"]').trigger('tap');await flushPromises();
 expect(m.rechargeOptions).toHaveBeenCalledWith({storeNo:'S1',customerRef:'REF'});
 expect(w.text()).not.toContain('common.loadFailed');expect(w.find('[data-testid="confirm-offline"]').exists()).toBe(true);expect(w.findAll('select')).toHaveLength(0);w.unmount();
});

it('debounces partial member suggestions and discards the old query after clearing',async()=>{
 vi.useFakeTimers();const w=mount(Directory,mountOptions);await flushPromises();
 await w.find('input').setValue('138');await vi.advanceTimersByTimeAsync(150);
 await w.find('input').setValue('1380');expect(m.candidates).not.toHaveBeenCalled();
 await vi.advanceTimersByTimeAsync(350);await flushPromises();expect(m.candidates).toHaveBeenCalledTimes(1);
 expect(m.candidates).toHaveBeenCalledWith('S1',{keyword:'1380',cursor:undefined});
 expect(w.text()).toContain('未储值会员');w.unmount();vi.useRealTimers();
});
