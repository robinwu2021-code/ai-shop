// @vitest-environment happy-dom
import {beforeEach,it,expect,vi} from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
import {createI18n} from 'vue-i18n';
import './component-setup';
import zh from '../src/i18n/locale/zh-CN';
import en from '../src/i18n/locale/en';
import ar from '../src/i18n/locale/ar';
const m=vi.hoisted(()=>({show:undefined as undefined|(()=>Promise<void>),context:vi.fn(),list:vi.fn(),receive:vi.fn(),config:vi.fn(),adjust:vi.fn(),food:vi.fn(),
 merchant:{storeNo:'S',profile:{merchantNo:'ACCOUNT'},currentStore:{name:'Store'},can:()=>true,ensureStores:async()=>{},ensureScope:async()=>{}}}));
vi.mock('@/api',()=>({api:{mRrContext:m.context,mRrList:m.list,mRrReceive:m.receive,mRrConfigure:m.config,mRrAdjust:m.adjust,mRpReceive:m.food,mRpRegister:m.food}}));
vi.mock('@/stores/merchant',async()=>{const {reactive}=await import('vue');m.merchant=reactive(m.merchant);return {useMerchantStore:()=>m.merchant};});
vi.mock('@dcloudio/uni-app',()=>({onShow:(cb:()=>Promise<void>)=>{m.show=cb;}}));
import Page from '../src/pages/retail-replenishments/index.vue';
import {rrStorageKey} from '../src/shared/retail-replenishment-command';
const scope={entityNo:'E',storeNo:'S',actorNo:'ACT'};
const context={...scope,connection:{enabled:true,generation:1,revision:1,sourceSystem:'INV',sourceOwner:'OWNER',postingWatermark:10}};
const target={entityNo:'E',storeNo:'S',skuNo:'SKU',market:'CN',baseUnit:'EA',quantityScope:'STORE',poolRef:'S',identity:'h'};
const row={original:{originalNo:'SRP1',targetOwner:'PLATFORM',source:{sourceSystem:'INV',sourceOwner:'OWNER',documentNo:'OUT',lineNo:1,itemNo:'SRC',baseUnit:'EA',quantity:20,targetStoreNo:'S',postingSequence:11},
 mapping:{itemNo:'SKU',baseUnit:'EA',factor:1},target,expectedQuantity:20,receivedQuantity:0,status:'PENDING'},differenceQuantity:0,returnedQuantity:0,returnAcceptedQuantity:0,closeReason:null};
let storage:Map<string,string>;
beforeEach(()=>{
 vi.clearAllMocks();storage=new Map();m.merchant.storeNo='S';m.merchant.profile={merchantNo:'ACCOUNT'};
 uni.getStorageSync=vi.fn(k=>storage.get(k)||'');uni.setStorageSync=vi.fn((k,v)=>{storage.set(k,v)});uni.removeStorageSync=vi.fn(k=>{storage.delete(k)});
 m.context.mockResolvedValue(context);m.list.mockResolvedValue({items:[row],nextCursor:null});
});
async function render(){const w=mount(Page,{global:{plugins:[createI18n({legacy:false,locale:'zh-CN',messages:{'zh-CN':zh,en,ar}})],stubs:{'sh-scaffold':{template:'<main><slot/></main>'}}}});await m.show?.();await flushPromises();return w;}
const button=(w:Awaited<ReturnType<typeof render>>,text:string)=>w.findAll('button').find(b=>b.text()===text)!;
it('shows the real store pool and separates physical stock from online quotas',async()=>{
 const w=await render();expect(w.text()).toContain('OUT');expect(w.text()).toContain('门店库存池 S');expect(w.text()).toContain('线上可售配额另行设置');w.unmount();
});
it('keeps the original key and integer body after an unknown result and resends exactly that',async()=>{
 m.receive.mockRejectedValueOnce(Error('offline')).mockResolvedValue({...row,original:{...row.original,receivedQuantity:18}});
 const w=await render();await w.findAll('.rr-row')[0]!.trigger('tap');
 await w.find('picker').trigger('change',{detail:{value:'1'}});await w.find('input[placeholder="累计实际数量（整数）"]').setValue('18');
 await button(w,'提交操作').trigger('tap');await flushPromises();
 expect(m.receive).toHaveBeenCalledTimes(1);const key=m.receive.mock.calls[0]![3];expect(storage.get(rrStorageKey(scope))).toContain(key);expect(w.text()).toContain('待核对操作');
 await w.find('input[placeholder="累计实际数量（整数）"]').setValue('19');await button(w,'按原请求重发').trigger('tap');await flushPromises();
 expect(m.receive.mock.calls[1]).toEqual([expect.objectContaining(scope),'SRP1',{cumulativeQuantity:18},key]);expect(storage.size).toBe(0);
 expect(m.food).not.toHaveBeenCalled();w.unmount();
});
it('drops a definite rejection but never rounds a fractional count',async()=>{
 m.receive.mockRejectedValue(Object.assign(Error('conflict'),{httpStatus:409}));const w=await render();await w.findAll('.rr-row')[0]!.trigger('tap');
 await w.find('picker').trigger('change',{detail:{value:'1'}});await w.find('input[placeholder="累计实际数量（整数）"]').setValue('1.5');
 await button(w,'提交操作').trigger('tap');await flushPromises();expect(m.receive).not.toHaveBeenCalled();expect(w.text()).toContain('请输入整数件数');
 await w.find('input[placeholder="累计实际数量（整数）"]').setValue('5');await button(w,'提交操作').trigger('tap');await flushPromises();
 expect(m.receive).toHaveBeenCalledTimes(1);expect(storage.size).toBe(0);w.unmount();
});
it('does not resend a pending request after a store switch',async()=>{
 const pending={scope,key:'OLD',kind:'receive',no:'SRP1',body:{cumulativeQuantity:2},attempted:true};storage.set(rrStorageKey(scope),JSON.stringify([pending]));
 const w=await render();m.merchant.storeNo='OTHER';await flushPromises();
 expect(w.text()).not.toContain('按原请求重发');expect(m.receive).not.toHaveBeenCalled();expect(storage.get(rrStorageKey(scope))).toContain('OLD');w.unmount();
});
it('adjusts with a signed count and offers the resulting movement for linking',async()=>{
 m.adjust.mockResolvedValue({movementNo:'SRM1',target,delta:-2,onHandBefore:10,onHandAfter:8,reservedQuantity:0});
 const w=await render();await w.find('picker').trigger('change',{detail:{value:'5'}});
 await w.find('input[placeholder="本地SKU编号"]').setValue('SKU');await w.find('input[placeholder="本地基础单位"]').setValue('EA');
 await w.find('input[placeholder="调整数量（正数入库，负数出库）"]').setValue('-2');await w.find('input[placeholder="操作原因"]').setValue('loss');
 await button(w,'提交操作').trigger('tap');await flushPromises();
 expect(m.adjust).toHaveBeenCalledWith(expect.objectContaining(scope),{itemNo:'SKU',baseUnit:'EA',delta:-2,reason:'loss'},expect.any(String));
 expect(w.text()).toContain('SRM1');expect(w.text()).toContain('10 → 8');w.unmount();
});
it('does not turn a failed context lookup into an empty list',async()=>{
 m.context.mockRejectedValue(Error('service unavailable'));const w=await render();expect(w.text()).not.toContain('暂无待处理补货单');expect(m.list).not.toHaveBeenCalled();w.unmount();
});
