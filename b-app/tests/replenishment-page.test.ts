// @vitest-environment happy-dom
import {beforeEach,it,expect,vi} from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
import {createI18n} from 'vue-i18n';
import './component-setup';
import zh from '../src/i18n/locale/zh-CN';
import en from '../src/i18n/locale/en';
import ar from '../src/i18n/locale/ar';
const m=vi.hoisted(()=>({show:undefined as undefined|(()=>Promise<void>),context:vi.fn(),list:vi.fn(),lookup:vi.fn(),receive:vi.fn(),config:vi.fn(),configLookup:vi.fn(),cancel:vi.fn(),
 merchant:{storeNo:'S',profile:{merchantNo:'ACCOUNT'},currentStore:{name:'Store'},can:()=>true,ensureStores:async()=>{},ensureScope:async()=>{}}}));
vi.mock('@/api',()=>({api:{mRpContext:m.context,mRpList:m.list,mRpState:m.lookup,mRpCommand:m.lookup,mRpCancel:m.cancel,mRpReceive:m.receive,mRpConfigure:m.config,mRpConfigurationCommand:m.configLookup}}));
vi.mock('@/stores/merchant',async()=>{const {reactive}=await import('vue');m.merchant=reactive(m.merchant);return {useMerchantStore:()=>m.merchant};});
vi.mock('@dcloudio/uni-app',()=>({onShow:(cb:()=>Promise<void>)=>{m.show=cb;}}));
import Page from '../src/pages/stock-replenishments/index.vue';
import {rpStorageKey} from '../src/shared/replenishment-command';
const scope={entityNo:'E',storeNo:'S',actorNo:'ACT'};
const context={...scope,connection:{enabled:false,generation:0,revision:0,sourceSystem:null,sourceOwner:null,postingWatermark:0}};
const row={replenishmentNo:'R',sourceDocument:'OUT',sourceLine:1,baseUnit:'G',expectedQuantity:20,receivedQuantity:0,differenceQuantity:0,returnedQuantity:0,returnAcceptedQuantity:0,status:'PENDING'};
let storage:Map<string,string>;
beforeEach(()=>{
 vi.clearAllMocks();storage=new Map();m.merchant.storeNo='S';m.merchant.profile={merchantNo:'ACCOUNT'};
 uni.getStorageSync=vi.fn(k=>storage.get(k)||'');uni.setStorageSync=vi.fn((k,v)=>{storage.set(k,v)});uni.removeStorageSync=vi.fn(k=>{storage.delete(k)});
 m.context.mockResolvedValue(context);m.list.mockResolvedValue({items:[row],nextCursor:null,throughId:1});m.lookup.mockResolvedValue({status:'NOT_COMMITTED',original:null,connection:null});m.configLookup.mockResolvedValue(null);
});
async function render(){const w=mount(Page,{global:{plugins:[createI18n({legacy:false,locale:'zh-CN',messages:{'zh-CN':zh,en,ar}})],stubs:{'sh-scaffold':{template:'<main><slot/></main>'}}}});await m.show?.();await flushPromises();return w;}
it('renders actual pending receipts and preserves original body/key across an unknown response',async()=>{
 m.receive.mockRejectedValueOnce(Error('offline')).mockResolvedValue({...row,receivedQuantity:0.125});
 const w=await render();expect(w.text()).toContain('OUT');
 await w.find('picker').trigger('change',{detail:{value:'1'}});await w.find('input[placeholder="本地交接单号"]').setValue('R');await w.find('input[placeholder="累计实际数量"]').setValue('0.125000');
 await w.findAll('button').find(b=>b.text()==='提交操作')!.trigger('tap');await flushPromises();
 expect(m.receive).toHaveBeenCalledTimes(1);const key=m.receive.mock.calls[0]![3];expect(storage.size).toBe(1);expect(w.text()).toContain('待核对操作');
 await w.find('input[placeholder="累计实际数量"]').setValue('9');await w.findAll('button').find(b=>b.text()==='核对并恢复')!.trigger('tap');await flushPromises();
 expect(m.receive.mock.calls[1]).toEqual([expect.objectContaining(scope),'R',{cumulativeQuantity:'0.125000'},key]);expect(storage.size).toBe(0);w.unmount();
});
it('does not resend an old pending request after a store switch during lookup',async()=>{
 const pending={scope,key:'OLD',kind:'receive',no:'R',body:{cumulativeQuantity:'2'},attempted:true};storage.set(rpStorageKey(scope),JSON.stringify([pending]));
 let resolve!:(v:unknown)=>void;m.lookup.mockImplementation(()=>new Promise(r=>{resolve=r}));const w=await render();
 await w.findAll('button').find(b=>b.text()==='核对并恢复')!.trigger('tap');await flushPromises();m.merchant.storeNo='OTHER';await flushPromises();resolve({status:'NOT_COMMITTED',original:null,connection:null});await flushPromises();
 expect(m.receive).not.toHaveBeenCalled();expect(storage.get(rpStorageKey(scope))).toContain('OLD');expect(w.text()).not.toContain('OUT');w.unmount();
});
it('does not turn a failed context lookup into an empty-stock claim',async()=>{
 m.context.mockRejectedValue(Error('service unavailable'));const w=await render();expect(w.text()).not.toContain('暂无待收或待核对单据');expect(m.list).not.toHaveBeenCalled();w.unmount();
});
it('offers close/open controls with server revision and original idempotency identity',async()=>{
 m.config.mockResolvedValue({...context.connection,enabled:true,generation:1,revision:1});const w=await render();await w.find('input[placeholder="操作原因"]').setValue('补货接入');
 await w.findAll('button').find(b=>b.text()==='启用新补货接入')!.trigger('tap');await flushPromises();
 expect(m.config).toHaveBeenCalledWith(expect.objectContaining(scope),{enabled:true,expectedVersion:0,reason:'补货接入'},expect.any(String));expect(storage.size).toBe(0);w.unmount();
});

it('can seal a permanently rejected unknown request and then submit a new original',async()=>{
 const old={scope,key:'OLD',kind:'receive',no:'R',body:{cumulativeQuantity:'2'},attempted:true};storage.set(rpStorageKey(scope),JSON.stringify([old]));
 m.cancel.mockResolvedValue({status:'CANCELLED',original:null,connection:null});m.receive.mockResolvedValue({...row,receivedQuantity:3});const w=await render();
 await w.findAll('button').find(b=>b.text()==='核对并结束未提交操作')!.trigger('tap');await flushPromises();expect(m.cancel).toHaveBeenCalledWith(expect.objectContaining(scope),'REPLENISH_RECEIVE','OLD',expect.any(String));expect(storage.size).toBe(0);
 await w.find('picker').trigger('change',{detail:{value:'1'}});await w.find('input[placeholder="本地交接单号"]').setValue('R');await w.find('input[placeholder="累计实际数量"]').setValue('3');await w.findAll('button').find(b=>b.text()==='提交操作')!.trigger('tap');await flushPromises();
 expect(m.receive).toHaveBeenCalled();expect(m.receive.mock.calls[0]![3]).not.toBe('OLD');w.unmount();
});
it('offers explicit OFF while an unknown enable exists and preserves that original',async()=>{
 const old={scope,key:'ENABLE',kind:'configure',body:{enabled:true,expectedVersion:0,reason:'start'},attempted:true};storage.set(rpStorageKey(scope),JSON.stringify([old]));
 m.config.mockResolvedValue({...context.connection,revision:1});const w=await render();await w.find('input[placeholder="操作原因"]').setValue('停止');
 await w.findAll('button').find(b=>b.text()==='停止新增接入')!.trigger('tap');await flushPromises();
 expect(m.config).toHaveBeenCalledWith(expect.objectContaining(scope),{enabled:false,expectedVersion:0,reason:'停止'},expect.any(String));expect(storage.get(rpStorageKey(scope))).toContain('ENABLE');w.unmount();
});
