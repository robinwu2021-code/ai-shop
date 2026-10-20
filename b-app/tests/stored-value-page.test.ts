// @vitest-environment happy-dom
import { beforeEach,expect,it,vi } from 'vitest';
import { mount,flushPromises } from '@vue/test-utils';
import { reactive } from 'vue';
import './component-setup';
const m=vi.hoisted(()=>({records:vi.fn()}));
const store=reactive({storeNo:'S1'});
vi.mock('@/api',()=>({api:{mSvRecords:m.records}}));
vi.mock('@/stores/merchant',()=>({useMerchantStore:()=>store}));
vi.mock('vue-i18n',()=>({useI18n:()=>({t:(k:string)=>k})}));
import Records from '@/pages/stored-value/components/StoredValueRecordList.vue';
import Tabs from '@ai-shop/ui/components/sh-tabs.vue';
import Sheet from '@ai-shop/ui/components/sh-sheet.vue';
const row={recordNo:'R1',maskedName:'陈女士',maskedPhone:'138****1234',type:'RECHARGE',title:'充50送10',storeNo:'S1',storeName:'本店',currency:'CNY',signedAmountMinor:6000,balanceAfterMinor:6000,occurredAt:'2026-10-20T02:00:00Z',businessNo:'B1'};
beforeEach(()=>{vi.clearAllMocks();store.storeNo='S1';m.records.mockResolvedValue({records:[row],nextCursor:null,hasMore:false});});
it('switches types directly and only applies time after confirmation',async()=>{
 const w=mount(Records);await flushPromises();
 w.findComponent(Tabs).vm.$emit('change','REDEMPTION');await flushPromises();
 expect(m.records.mock.lastCall?.[0]).toMatchObject({storeNo:'S1',type:'REDEMPTION'});
 await w.findAll('button').find(x=>x.text()==='storedValue.time')!.trigger('tap');
 await w.findAll('picker')[0]!.trigger('change',{detail:{value:'2026-10-20'}});
 const before=m.records.mock.calls.length;w.findComponent(Sheet).vm.$emit('close');await flushPromises();expect(m.records).toHaveBeenCalledTimes(before);
 await w.findAll('button').find(x=>x.text()==='storedValue.time')!.trigger('tap');
 await w.findAll('picker')[0]!.trigger('change',{detail:{value:'2026-10-20'}});
 await w.findAll('button').find(x=>x.text()==='storedValue.confirm')!.trigger('tap');await flushPromises();
 expect(m.records.mock.lastCall?.[0]).toMatchObject({type:'REDEMPTION',startDate:'2026-10-20'});w.unmount();
});
it('never displays an old store response after switching stores',async()=>{
 let old!:(value:unknown)=>void;m.records.mockImplementationOnce(()=>new Promise(r=>{old=r;}));
 const w=mount(Records);await flushPromises();store.storeNo='S2';m.records.mockResolvedValue({records:[],nextCursor:null,hasMore:false});await flushPromises();
 old({records:[row],nextCursor:null,hasMore:false});await flushPromises();expect(w.text()).not.toContain('陈女士');expect(m.records.mock.lastCall?.[0].storeNo).toBe('S2');w.unmount();
});
