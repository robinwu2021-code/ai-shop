import {beforeEach,expect,it,vi} from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
const m=vi.hoisted(()=>({read:vi.fn(),confirm:vi.fn()}));
vi.mock('@/api',()=>({api:{storedValueService:m.read,storedValueAuthorize:m.confirm}}));
vi.mock('vue-i18n',()=>({useI18n:()=>({t:(s:string)=>s})}));
import Sheet from '@/pages/stored-value/StoredValueServiceSheet.vue';
const service={serviceOrderNo:'ORDER1',status:'WAIT_AUTH',storeName:'Store B',items:[{itemNo:'CARE',title:'Care',quantity:1}],confirmation:{orderRevision:2,quoteRef:'SQ1',selectionDigest:'a'.repeat(64),expiresAt:'2099-01-01T00:00:00Z'},payment:{assetKind:'STORED_VALUE',scopeType:'BRAND',issuerName:'Brand wallet',currency:'CNY',amountMinor:5000}};
const stubs={'sh-sheet':{props:['visible'],template:'<div v-if="visible"><slot/><slot name="foot"/></div>'},'sh-kv':{props:['label'],template:'<div>{{label}}<slot/></div>'},'sh-empty':true};
beforeEach(()=>{vi.clearAllMocks();m.read.mockResolvedValue(service);});
it('shows the frozen issuer and retries the exact confirmation without sending client amounts or account identities',async()=>{
 m.confirm.mockRejectedValueOnce(new Error('timeout')).mockResolvedValueOnce({status:'AUTHORIZED'});
 const w=mount(Sheet,{props:{orderNo:'ORDER1'},global:{stubs}});await flushPromises();
 expect(w.text()).toContain('Brand wallet');expect(w.text()).toContain('storedValue.brandScope');
 const action=()=>w.findAll('[role="button"]').find(x=>x.text()==='storedValue.confirmConsumption')!;
 await action().trigger('tap');await flushPromises();await action().trigger('tap');await flushPromises();
 expect(m.confirm).toHaveBeenCalledTimes(2);expect(m.confirm.mock.calls[1]).toEqual(m.confirm.mock.calls[0]);
 expect(m.confirm.mock.calls[0]).toEqual(['ORDER1',{orderRevision:2,quoteRef:'SQ1',selectionDigest:'a'.repeat(64),requestKey:'SV_CONFIRM_SQ1'}]);
 expect(w.emitted('confirmed')).toHaveLength(1);w.unmount();
});
it('does not offer confirmation for an expired or already-consented service',async()=>{
 m.read.mockResolvedValue({...service,status:'EXPIRED'});const w=mount(Sheet,{props:{orderNo:'ORDER1'},global:{stubs}});await flushPromises();
 expect(w.findAll('[role="button"]').some(x=>x.text()==='storedValue.confirmConsumption')).toBe(false);
 expect(m.confirm).not.toHaveBeenCalled();expect(w.text()).toContain('storedValue.serviceState.EXPIRED');expect(w.text()).not.toContain('storedValue.waitingMerchant');w.unmount();
});
