// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
const m=vi.hoisted(()=>({
 show:()=>{},call:vi.fn(),command:vi.fn(),pick:vi.fn(),prompt:vi.fn(),permissions:['*'] as string[],
 invoice:{} as Record<string,unknown>,
}));
vi.mock('@dcloudio/uni-app',()=>({onLoad:(fn:(q:{invoiceNo:string})=>void)=>fn({invoiceNo:'INV1'}),onShow:(fn:()=>void)=>{m.show=fn;}}));
vi.mock('vue-i18n',()=>({useI18n:()=>({t:(key:string)=>key})}));
vi.mock('@/stores/merchant',()=>({useMerchantStore:()=>({storeNo:'S1',can:(p:string)=>m.permissions.includes('*')||m.permissions.includes(p)})}));
vi.mock('@/api',()=>({api:{mSiDetail:async()=>m.invoice}}));
vi.mock('@ai-shop/ui/prompt',()=>({confirm:vi.fn(),pick:m.pick,prompt:m.prompt}));
vi.mock('@/api/print',()=>({callPrint:m.call,PrintCommands:class {run(...args:unknown[]){return m.command(...args);}}}));
vi.stubGlobal('uni',{showToast:vi.fn(),navigateTo:vi.fn()});
import Page from '@/pages/sales-invoice/index.vue';
const ok={operationNo:'OP',status:'SUCCEEDED',targetNo:'PR1',result:null};
beforeEach(()=>{
 vi.clearAllMocks();m.permissions=['*'];m.command.mockResolvedValue(ok);
 m.invoice={invoiceNo:'INV1',status:'ISSUED',printMark:true,currency:'TWD',totalMinor:100,allowedMinor:10,salesMinor:95,taxMinor:5,uploadStatus:'SENT',allowances:[{allowanceNo:'AL1',amountMinor:10,reason:'r',createdAt:'t'}]};
});
async function render(){const w=mount(Page,{global:{stubs:{'sh-scaffold':{template:'<div><slot/></div>'},'sh-kv':{template:'<div><slot/></div>'},'sh-empty':true,'biz-store-tag':true}}});m.show();await flushPromises();return w;}
const tap=async(w:Awaited<ReturnType<typeof render>>,key:string)=>{await w.findAll('.sh-btn').find(b=>b.text()===key)!.trigger('tap');await flushPromises();};
const job=(over:Record<string,unknown>)=>({jobNo:'J1',scene:'INVOICE_PROOF',status:'COMPLETED',revision:3,documentKey:'INV1:INVOICE_PROOF',allowedActions:['REPRINT'],...over});

it('prints transaction details and each allowance certificate from their own facts',async()=>{
 const w=await render();
 await tap(w,'salesInvoice.printDetail');
 expect(m.command).toHaveBeenLastCalledWith('J03',expect.objectContaining({scene:'INVOICE_DETAIL',sourceRef:{type:'INVOICE',no:'INV1',factNo:'INV1',revision:'1',scene:'INVOICE_DETAIL'},printMode:'EXISTING_FACT'}));
 await tap(w,'salesInvoice.printAllowance');
 expect(m.command).toHaveBeenLastCalledWith('J03',expect.objectContaining({scene:'INVOICE_ALLOWANCE',sourceRef:{type:'INVOICE_ALLOWANCE',no:'AL1',factNo:'AL1',revision:'1',scene:'INVOICE_ALLOWANCE'}}));
});

it('offers detail printing for carrier invoices but hides proof print and reprint',async()=>{
 m.invoice.printMark=false;
 const w=await render();
 const labels=w.findAll('.sh-btn').map(b=>b.text());
 expect(labels).toContain('salesInvoice.printDetail');
 expect(labels).not.toContain('salesInvoice.print');
 expect(labels).not.toContain('salesInvoice.reprint');
});

it('hides every print action without biz:print:submit or reprint',async()=>{
 m.permissions=['biz:invoice:read'];
 const w=await render();
 expect(w.findAll('.sh-btn').map(b=>b.text()).filter(x=>x.startsWith('salesInvoice.print')||x==='salesInvoice.reprint')).toEqual([]);
});

it('reprints the newest proof job of this invoice with the chosen reason',async()=>{
 m.call.mockResolvedValue({records:[job({jobNo:'OTHER',documentKey:'INV9:INVOICE_PROOF'}),job({})],total:2,page:1,size:100});
 m.pick.mockResolvedValue(0);
 const w=await render();
 await tap(w,'salesInvoice.reprint');
 expect(m.call).toHaveBeenCalledWith('J06',{storeNo:'S1',scene:'INVOICE_PROOF',page:1,size:100});
 expect(m.command).toHaveBeenLastCalledWith('J13',{expectedRevision:3,reason:'salesInvoice.reprintReason.JAM',riskAcknowledged:false},{jobNo:'J1'});
});

it('asks for the original first when the proof was never printed',async()=>{
 m.call.mockResolvedValue({records:[],total:0,page:1,size:100});
 const w=await render();
 await tap(w,'salesInvoice.reprint');
 expect(m.pick).not.toHaveBeenCalled();
 expect(m.command).not.toHaveBeenCalled();
 expect(w.text()).toContain('salesInvoice.reprintNoOriginal');
});

it('does not reprint while the proof is still printing or its result is unconfirmed',async()=>{
 m.call.mockResolvedValue({records:[job({status:'PRINTING',allowedActions:[]})],total:1,page:1,size:100});
 let w=await render();
 await tap(w,'salesInvoice.reprint');
 expect(w.text()).toContain('salesInvoice.reprintBusy');
 m.call.mockResolvedValue({records:[job({status:'UNKNOWN'})],total:1,page:1,size:100});
 w=await render();
 await tap(w,'salesInvoice.reprint');
 expect(w.text()).toContain('salesInvoice.reprintUnknown');
 expect(m.command).not.toHaveBeenCalled();
});

it('keeps a failed print request visible on the page',async()=>{
 m.command.mockRejectedValue(new Error('打印操作未完成'));
 const w=await render();
 await tap(w,'salesInvoice.printDetail');
 expect(w.text()).toContain('打印操作未完成');
});
