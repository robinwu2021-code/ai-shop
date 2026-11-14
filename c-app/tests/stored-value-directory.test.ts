import {expect,it,vi} from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
const m=vi.hoisted(()=>({issuers:vi.fn(),pending:vi.fn()}));
vi.mock('@/api',()=>({api:{storedValueIssuers:m.issuers,storedValuePendingServices:m.pending}}));
vi.mock('@/shared/stored-value-session',()=>({readStoredValueWithSession:(fn:()=>unknown)=>fn()}));
vi.mock('@dcloudio/uni-app',()=>({onShow:(fn:()=>void)=>fn(),onReachBottom:()=>{},onUnload:()=>{}}));
import Page from '@/pages/stored-value/index.vue';
it('shows each funded issuer and only current stored-value confirmations with their real state',async()=>{
 const service={serviceOrderNo:'PENDING',storeName:'Store B',status:'PROCESSING',confirmation:{quoteRef:'Q1'},payment:{assetKind:'STORED_VALUE'}};
 m.issuers.mockResolvedValue({records:[{issuerRef:'BRAND',scopeType:'BRAND',name:'Brand wallet',lastType:'RECHARGE',lastOccurredAt:'2026-10-24T00:00:00Z'}],nextCursor:null,hasMore:false});
 m.pending.mockResolvedValue([service,{...service,serviceOrderNo:'OLD',storeName:'Expired store',status:'EXPIRED'},{...service,serviceOrderNo:'COUPON',storeName:'Coupon store',payment:{assetKind:'COUPON'}}]);
 const w=mount(Page,{global:{mocks:{$t:(s:string)=>s},stubs:{'sh-scaffold':{template:'<div><slot/></div>'},'sh-empty':true,StoredValueServiceSheet:true}}});await flushPromises();
 expect(w.text()).toContain('Brand wallet');expect(w.text()).toContain('Store B');expect(w.text()).toContain('storedValue.serviceState.PROCESSING');expect(w.text()).not.toContain('Expired store');expect(w.text()).not.toContain('Coupon store');w.unmount();
});
