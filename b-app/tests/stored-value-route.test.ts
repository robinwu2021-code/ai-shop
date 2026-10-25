// @vitest-environment happy-dom
import {beforeEach,expect,it,vi} from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
import './component-setup';
const m=vi.hoisted(()=>({load:null as null|((q:Record<string,string>)=>Promise<void>),context:vi.fn()}));
vi.mock('@dcloudio/uni-app',()=>({onLoad:(f:typeof m.load)=>{m.load=f;},onShow:vi.fn(),onHide:vi.fn()}));
vi.mock('@/stores/merchant',()=>({useMerchantStore:()=>({storeNo:'S1',profile:{merchantNo:'M1'},ensureStores:async()=>{}})}));
vi.mock('vue-i18n',()=>({useI18n:()=>({t:(s:string)=>s})}));
vi.mock('@/api',()=>({api:{mManagementContext:m.context}}));
import Route from '@/pages/stored-value-recharge/index.vue';
Object.assign(uni,{redirectTo:vi.fn()});
const options={global:{stubs:{LegacyRecharge:{template:'<div data-testid="legacy-recovery"/>'},StoredValueRechargeSheet:{props:['visible'],template:'<div v-if="visible" data-testid="original-recovery"/>'},'sh-scaffold':{props:['failed','pending'],template:'<div><button v-if="failed" data-testid="retry" @click="$emit(\'retry\')">retry</button><slot/></div>'}}}};
beforeEach(()=>{vi.clearAllMocks();m.context.mockResolvedValue({actorKey:'ACTOR'});vi.mocked(uni.getStorageSync).mockReturnValue('');});
it('redirects old ordinary entry to the single directory and member links to detail',async()=>{
 const w=mount(Route,options);await m.load?.({planNo:'P1'});await flushPromises();
 expect(uni.redirectTo).toHaveBeenCalledWith({url:'/pages/stored-value/index?tab=customers&planNo=P1'});
 await m.load?.({memberNo:'MEM1',storeNo:'S1'});
 expect(uni.redirectTo).toHaveBeenLastCalledWith({url:'/pages/stored-value-account/index?memberNo=MEM1&storeNo=S1'});
 expect(w.findAll('input')).toHaveLength(0);w.unmount();
});
it('keeps legacy and actor-scoped original commands recoverable instead of starting a new recharge',async()=>{
 vi.mocked(uni.getStorageSync).mockImplementation(k=>String(k).startsWith('sv.recharge:')?'original':'');
 const a=mount(Route,options);await m.load?.({});await flushPromises();expect(a.find('[data-testid="legacy-recovery"]').exists()).toBe(true);expect(uni.redirectTo).not.toHaveBeenCalled();a.unmount();
 vi.mocked(uni.getStorageSync).mockImplementation(k=>String(k)==='sv.offline.v3:M1:S1:ACTOR'?'original':'');
 const b=mount(Route,options);await m.load?.({});await flushPromises();expect(b.find('[data-testid="original-recovery"]').exists()).toBe(true);expect(uni.redirectTo).not.toHaveBeenCalled();b.unmount();
});

it('shows a retry after actor lookup fails and resumes the original route',async()=>{
 m.context.mockRejectedValueOnce(new Error('offline'));
 const w=mount(Route,options);await m.load?.({planNo:'P1'});await flushPromises();
 expect(uni.redirectTo).not.toHaveBeenCalled();expect(w.find('[data-testid="retry"]').exists()).toBe(true);
 await w.find('[data-testid="retry"]').trigger('click');await flushPromises();
 expect(uni.redirectTo).toHaveBeenCalledWith({url:'/pages/stored-value/index?tab=customers&planNo=P1'});w.unmount();
});
