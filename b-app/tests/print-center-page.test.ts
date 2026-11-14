// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
const m=vi.hoisted(()=>({
 show:()=>{},hide:()=>{},call:vi.fn(),command:vi.fn(),sourceNo:'',permissions:['*'],facts:[] as Record<string,unknown>[],
 merchant:null as unknown as {storeNo:string;can:(permission:string)=>boolean;ensureScope:()=>Promise<void>},
}));
vi.mock('@dcloudio/uni-app',()=>({onLoad:(fn:(query:{orderNo:string})=>void)=>fn({orderNo:m.sourceNo}),onShow:(fn:()=>void)=>{m.show=fn;},onHide:(fn:()=>void)=>{m.hide=fn;},onUnload:vi.fn(),onPullDownRefresh:vi.fn()}));
vi.mock('vue-i18n',()=>({useI18n:()=>({t:(key:string)=>key})}));
vi.mock('@/stores/merchant',async()=>{const {reactive}=await import('vue');m.merchant=reactive({storeNo:'S1',can:(permission:string)=>m.permissions.includes('*')||m.permissions.includes(permission),ensureScope:async()=>{}});return {useMerchantStore:()=>m.merchant};});
vi.mock('@/api',()=>({USE_MOCK:false}));
vi.mock('@ai-shop/ui/prompt',()=>({confirm:vi.fn(),prompt:vi.fn()}));
vi.mock('@/api/print',()=>({
 callPrint:m.call,PrintCommands:class {run(...args:unknown[]){return m.command(...args);}clear(){}},
 SIMULATION_FIXTURES:['SUCCESS'],decodeFrozen:(value:{canonicalJson:string})=>JSON.parse(value.canonicalJson),
}));
import Page from '@/pages/print-center/index.vue';
let wrapper:VueWrapper|undefined;
const operation={operationNo:'OLD-SUCCESS',status:'SUCCEEDED',targetNo:'SIM1',result:null};
beforeEach(()=>{
 vi.clearAllMocks();m.sourceNo='';m.permissions=['*'];m.facts=[];m.merchant.storeNo='S1';m.command.mockResolvedValue(operation);
 m.call.mockImplementation(async(id:string)=>id==='C01'?['ORDER_PAID','REFUND_RECEIPT','RECHARGE_RECEIPT'].map(scene=>({scene,title:scene,sourceAvailable:true})):id==='J11'?{generationState:'NO_PRINT_REQUIRED',expectedCount:0,confirmedCount:0,replacementResolvedCount:0,unknownCount:0,sourceFacts:m.facts}:{records:id==='J06'?[{jobNo:'J1',scene:'ORDER_PAID',status:'UNKNOWN',revision:1,allowedActions:[]}]:[],total:1,page:1,size:20});
});
afterEach(()=>{m.hide();wrapper?.unmount();wrapper=undefined;vi.useRealTimers();});
async function render(){wrapper=mount(Page,{global:{stubs:{'sh-scaffold':{template:'<div><slot/></div>'}}}});m.show();await flushPromises();return wrapper;}
function button(w:VueWrapper,key:string){return w.findAll('button').find(b=>b.text()===key)!;}
it('keeps simulation rejection visible without switching tab or fetching a success list',async()=>{
 const w=await render();m.call.mockClear();m.command.mockRejectedValueOnce(new Error('simulation rejected'));
 await button(w,'printCenter.runSimulation').trigger('tap');await flushPromises();
 expect(w.get('[role="alert"]').text()).toBe('simulation rejected');
 expect(w.get('.print-tabs .print-selected').text()).toBe('printCenter.tabs.jobs');
 expect(m.call).not.toHaveBeenCalled();expect(w.text()).not.toContain('OLD-SUCCESS');
});
it('keeps export rejection visible without switching tab or fetching exports',async()=>{
 const w=await render();m.call.mockClear();m.command.mockRejectedValueOnce(new Error('export rejected'));
 await button(w,'printCenter.exportPdf').trigger('tap');await flushPromises();
 expect(w.get('[role="alert"]').text()).toBe('export rejected');
 expect(w.get('.print-tabs .print-selected').text()).toBe('printCenter.tabs.jobs');
 expect(m.call).not.toHaveBeenCalled();
});
it.each(['printCenter.runSimulation','printCenter.exportPdf'])('discards late successful %s after switching stores',async(key)=>{
 let finish!:(value:typeof operation)=>void;const w=await render();m.command.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));
 await button(w,key).trigger('tap');m.merchant.storeNo='S2';await flushPromises();m.call.mockClear();
 finish(operation);await flushPromises();
 expect(w.text()).not.toContain('OLD-SUCCESS');expect(w.get('.print-tabs .print-selected').text()).toBe('printCenter.tabs.jobs');
 expect(m.call).not.toHaveBeenCalled();
});
it('clears a previous store success and ignores its late failure after switching stores',async()=>{
 const w=await render();await button(w,'printCenter.runSimulation').trigger('tap');await flushPromises();expect(w.text()).toContain('OLD-SUCCESS');
 let reject!:(error:Error)=>void;m.command.mockImplementationOnce(()=>new Promise((_resolve,fail)=>{reject=fail;}));
 await button(w,'printCenter.runSimulation').trigger('tap');m.merchant.storeNo='S2';await flushPromises();reject(new Error('old store rejected'));await flushPromises();
 expect(w.text()).not.toContain('OLD-SUCCESS');expect(w.text()).not.toContain('old store rejected');
});

it('keeps a command failure visible when the background list refresh runs after 15 seconds',async()=>{
 vi.useFakeTimers({toFake:['setInterval','clearInterval']});
 const w=await render();m.command.mockRejectedValueOnce(new Error('simulation rejected'));
 await button(w,'printCenter.runSimulation').trigger('tap');await flushPromises();m.call.mockClear();
 await vi.advanceTimersByTimeAsync(15000);await flushPromises();
 expect(m.call).toHaveBeenCalledWith('J06',expect.objectContaining({storeNo:'S1'}));
 expect(w.get('[role="alert"]').text()).toBe('simulation rejected');
});

const sourceFact=(scene:string,sourceType:string)=>({tenantNo:'TENANT',entityNo:'ENTITY',storeNo:'S1',scene,sourceType,factNo:'FACT-'+scene,bizRevision:'7',snapshotHash:'a'.repeat(64)});
it('discovers recharge facts for wallet-only staff without order permission',async()=>{
 m.sourceNo='RECHARGE-1';m.permissions=['biz:print:read','biz:print:submit','pay:sv:recharge:read','pay:sv:account:read'];m.facts=[sourceFact('RECHARGE_RECEIPT','RECHARGE')];
 const w=await render();expect(m.call).toHaveBeenCalledWith('J11',{storeNo:'S1'},{orderNo:'RECHARGE-1'});
 expect(w.get('[data-test="source-scene"]').text()).toContain('RECHARGE_RECEIPT');
 expect(button(w,'printCenter.submit')).toBeDefined();
});
it.each([['REFUND_RECEIPT','REFUND',1],['RECHARGE_RECEIPT','RECHARGE',2]] as const)('submits the selected %s fact instead of the order-paid fact',async(scene,sourceType,index)=>{
 m.sourceNo='BUSINESS-1';m.facts=[sourceFact('ORDER_PAID','SUB_ORDER'),sourceFact(scene,sourceType)];const w=await render();
 await w.get('[data-test="simulation-scene"]').trigger('change',{detail:{value:index}});await flushPromises();
 await button(w,'printCenter.submit').trigger('tap');await flushPromises();
 expect(m.command).toHaveBeenCalledWith('J03',expect.objectContaining({scene,expectedSourceRevision:'7',sourceRef:{type:sourceType,no:'BUSINESS-1',factNo:'FACT-'+scene,revision:'7',scene}}));
});
it('hides business submission when the selected scene has no authorized fact',async()=>{
 m.sourceNo='BUSINESS-1';m.facts=[sourceFact('ORDER_PAID','SUB_ORDER')];const w=await render();
 expect(button(w,'printCenter.submit')).toBeDefined();
 await w.get('[data-test="simulation-scene"]').trigger('change',{detail:{value:2}});await flushPromises();
 expect(button(w,'printCenter.submit')).toBeUndefined();expect(m.command).not.toHaveBeenCalled();
});
it('keeps the chosen fact and revision when two partial refunds share one scene',async()=>{
 m.sourceNo='ORDER-1';m.facts=[sourceFact('ORDER_PAID','SUB_ORDER'),{...sourceFact('REFUND_RECEIPT','REFUND'),factNo:'REFUND-A',bizRevision:'7'},{...sourceFact('REFUND_RECEIPT','REFUND'),factNo:'REFUND-B',bizRevision:'8'}];
 const w=await render();await w.get('[data-test="source-scene"]').trigger('change',{detail:{value:2}});await flushPromises();
 expect(w.get('[data-test="source-scene"]').text()).toContain('REFUND-B');
 await button(w,'printCenter.refresh').trigger('tap');await flushPromises();
 await button(w,'printCenter.submit').trigger('tap');await flushPromises();
 expect(m.command).toHaveBeenCalledWith('J03',expect.objectContaining({scene:'REFUND_RECEIPT',expectedSourceRevision:'8',sourceRef:{type:'REFUND',no:'ORDER-1',factNo:'REFUND-B',revision:'8',scene:'REFUND_RECEIPT'}}));
});
it.each(['success','failure'])('discards an old %s after an A to B to A store round trip',async(outcome)=>{
 let finish!:(value:typeof operation)=>void,fail!:(error:Error)=>void;const w=await render();
 m.command.mockImplementationOnce(()=>new Promise((resolve,reject)=>{finish=resolve;fail=reject;}));
 await button(w,'printCenter.runSimulation').trigger('tap');m.merchant.storeNo='S2';await flushPromises();m.merchant.storeNo='S1';await flushPromises();m.call.mockClear();
 if(outcome==='success')finish(operation);else fail(new Error('stale A failure'));await flushPromises();
 expect(w.text()).not.toContain('OLD-SUCCESS');expect(w.text()).not.toContain('stale A failure');
 expect(w.get('.print-tabs .print-selected').text()).toBe('printCenter.tabs.jobs');expect(m.call).not.toHaveBeenCalled();
});
