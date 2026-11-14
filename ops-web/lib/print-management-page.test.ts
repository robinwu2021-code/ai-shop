// @vitest-environment happy-dom
import { afterEach,beforeEach,expect,it,vi } from "vitest";
import { act,createElement as h } from "react";
import { createRoot,type Root } from "react-dom/client";
import { QueryClient,QueryClientProvider } from "@tanstack/react-query";
const m=vi.hoisted(()=>({health:vi.fn(),incidents:vi.fn(),reconcile:vi.fn(),operation:vi.fn(),read:true,resolve:true,closed:false}));
vi.mock("@/lib/api",()=>({IS_MOCK:false,api:{printHealth:m.health,printIncidents:m.incidents,reconcilePrint:m.reconcile,printOperation:m.operation}}));
vi.mock("@/lib/use-can",()=>({useCan:()=> (p:string)=>p.endsWith(":read")?m.read:m.resolve}));
vi.mock("@/lib/stores/server-menu",()=>({useServerMenu:()=>m.closed,isPointUnimplemented:()=>m.closed}));
import PrintingPage from "@/app/printing/page";
let host:HTMLDivElement,root:Root,q:QueryClient;
const settle=()=>act(async()=>{await new Promise(r=>setTimeout(r,25));});
const field=(name:string)=>host.querySelector(`[name="${name}"]`) as HTMLInputElement;
async function input(name:string,value:string){await act(async()=>{const el=field(name);Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"value")!.set!.call(el,value);el.dispatchEvent(new Event("input",{bubbles:true}));});}
async function form(index:number){await act(async()=>{host.querySelectorAll("form")[index].dispatchEvent(new Event("submit",{bubbles:true,cancelable:true}));});await settle();}
async function mount(){await act(async()=>root.render(h(QueryClientProvider,{client:q},h(PrintingPage))));await settle();}
async function target(store="S"){await input("entityNo","E");await input("storeNo",store);await form(0);}
async function chooseSource(source:string){await act(async()=>{const select=host.querySelector('[name="sourceKind"]') as HTMLSelectElement;select.value=source;select.dispatchEvent(new Event("change",{bubbles:true}));});}
async function prepare(){await chooseSource("SUB_ORDER");await input("from","2026-01-01T00:00");await input("to","2026-01-02T00:00");await input("reason","investigate");}
const op=(status="UNKNOWN")=>({operationNo:"OP1",type:"OPS_PRINT_RECONCILE",status,targetType:"STORE",targetNo:"S",result:null,error:null,createdAt:"2026-01-01T00:00:00Z",finishedAt:null});
beforeEach(()=>{
 (globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;
 vi.clearAllMocks();m.read=true;m.resolve=true;m.closed=false;
 m.health.mockResolvedValue({stores:[{activePrinters:1,pendingJobs:2,unknownJobs:3,blockedRequests:4,asOf:"2026-01-01T00:00:00Z"}],asOf:"2026-01-01T00:00:00Z"});
 m.incidents.mockResolvedValue({records:[],total:21,page:1,size:20});m.reconcile.mockResolvedValue(op());m.operation.mockResolvedValue(op());
 host=document.createElement("div");document.body.append(host);root=createRoot(host);q=new QueryClient({defaultOptions:{queries:{retry:false}}});
});
afterEach(async()=>{await act(async()=>root.unmount());q.clear();host.remove();});
it("does not fetch without read permission or when deployment gate is closed",async()=>{
 m.read=false;await mount();expect(host.textContent).toContain("没有打印查看权限");expect(m.health).not.toHaveBeenCalled();
 m.read=true;m.closed=true;await mount();expect(host.textContent).toContain("此部署未启用");expect(host.querySelector("form")).toBeNull();
});
it("read-only users can inspect but cannot submit",async()=>{m.resolve=false;await mount();await target();expect(m.health).toHaveBeenCalledWith({tenantNo:"MAIN",entityNo:"E",storeNo:"S"});expect(host.textContent).toContain("仅有查看权限");expect(field("sourceKind")).toBeNull();});
it("validates target and range without sending mutation",async()=>{await mount();await input("entityNo"," ");await form(0);expect(m.health).not.toHaveBeenCalled();await target();await form(2);expect(m.reconcile).not.toHaveBeenCalled();expect(host.querySelector('[role="alert"]')).not.toBeNull();});
it("paginates and resets page when status changes",async()=>{await mount();await target();await act(async()=>{[...host.querySelectorAll("button")].find(b=>b.textContent==="下一页")!.click();});await settle();expect(m.incidents.mock.lastCall?.[0].page).toBe(2);await input("status","OPEN");await form(1);expect(m.incidents.mock.lastCall?.[0]).toMatchObject({status:"OPEN",page:1,tenantNo:"MAIN",entityNo:"E",storeNo:"S"});});
it("drops late health results and operation intent immediately on target edits",async()=>{
 let done!:(v:unknown)=>void;m.health.mockImplementationOnce(()=>new Promise(r=>{done=r;}));await mount();await target();await input("storeNo","OTHER");await act(async()=>done({stores:[{activePrinters:777}],asOf:"OLD-TARGET"}));await settle();expect(host.textContent).not.toContain("OLD-TARGET");expect(field("reason")).toBeNull();await form(0);expect(m.health.mock.lastCall?.[0].storeNo).toBe("OTHER");
});
it("defaults to dry run and keeps UNKNOWN distinct from completed output",async()=>{await mount();await target();await prepare();await form(2);expect(m.reconcile.mock.lastCall?.[0]).toMatchObject({dryRun:true,reason:"investigate",tenantNo:"MAIN",entityNo:"E",storeNo:"S"});expect(m.reconcile.mock.lastCall?.[1]).toBeTruthy();expect(host.textContent).toContain("结果未知，需人工核查");});
it("requires explicit confirmation for actual execution",async()=>{await mount();await target();await prepare();await act(async()=>field("dryRun").click());await form(2);expect(m.reconcile).not.toHaveBeenCalled();await act(async()=>field("confirm").click());await form(2);expect(m.reconcile.mock.lastCall?.[0].dryRun).toBe(false);});
it("locks duplicate submissions and retains exact failed intent key for error recovery",async()=>{
 let fail!:(e:Error)=>void;m.reconcile.mockImplementationOnce(()=>new Promise((_,r)=>{fail=r;}));await mount();await target();await prepare();await form(2);await form(2);expect(m.reconcile).toHaveBeenCalledTimes(1);const key=m.reconcile.mock.calls[0][1];await act(async()=>fail(Error("network")));await settle();expect(host.textContent).toContain("network");await form(2);expect(m.reconcile.mock.lastCall?.[1]).toBe(key);
 await input("reason","new intent");await form(2);expect(m.reconcile.mock.lastCall?.[1]).not.toBe(key);
});
it("scopes operation lookup and clears old response while editing reference",async()=>{await mount();await target();await input("operationNo"," OP1 ");await form(3);expect(m.operation).toHaveBeenCalledWith("OP1",{tenantNo:"MAIN",entityNo:"E",storeNo:"S"});expect(host.querySelector('[data-operation="OP1"]')).not.toBeNull();await input("operationNo","OP2");expect(host.querySelector('[data-operation="OP1"]')).toBeNull();});
it("recovers health errors without presenting an empty success snapshot",async()=>{m.health.mockRejectedValueOnce(Error("unavailable"));await mount();await target();expect(host.textContent).toContain("unavailable");await act(async()=>{[...host.querySelectorAll("button")].find(b=>b.textContent==="重试")!.click();});await settle();expect(host.textContent).not.toContain("unavailable");expect(host.textContent).toContain("活动打印机");});
it("shows accepted operations as unfinished and looks up pending results manually",async()=>{m.reconcile.mockResolvedValue(op("PENDING"));await mount();await target();await prepare();await form(2);expect(host.textContent).toContain("已受理，等待结果");await input("operationNo","OP1");await form(3);expect(host.textContent).toContain("结果未知，需人工核查");});
it("discards a reconciliation response arriving after target switch",async()=>{let done!:(v:unknown)=>void;m.reconcile.mockImplementationOnce(()=>new Promise(r=>{done=r;}));await mount();await target();await prepare();await form(2);await input("storeNo","OTHER");await form(0);await act(async()=>done({...op(),operationNo:"OLD-OP"}));await settle();expect(host.textContent).not.toContain("OLD-OP");expect(field("reason").value).toBe("");expect(field("dryRun").checked).toBe(true);});

it("offers business labels with authoritative source values and keeps cashier unavailable",async()=>{
 await mount();await target();await prepare();
 const select=host.querySelector('[name="sourceKind"]') as HTMLSelectElement;
 expect([...select.options].map(o=>[o.value,o.textContent])).toEqual([["SUB_ORDER","订单支付"],["REFUND","退款结果"],["RECHARGE","储值充值"]]);
 for(const sourceKind of ["SUB_ORDER","REFUND","RECHARGE"]){await chooseSource(sourceKind);await form(2);expect(m.reconcile.mock.lastCall?.[0].sourceKind).toBe(sourceKind);}
 expect(host.textContent).not.toContain("CASHIER");
});
it("matches tenant and entity/store input limits to backend identities",async()=>{
 await mount();expect(field("tenantNo").maxLength).toBe(32);expect(field("entityNo").maxLength).toBe(64);expect(field("storeNo").maxLength).toBe(64);
});

it("explicitly refreshes first-page incidents when the normalized filter is unchanged",async()=>{
 await mount();await target();
 expect(m.incidents).toHaveBeenCalledTimes(1);
 m.incidents.mockResolvedValue({records:[],total:3,page:1,size:20});
 await form(1);
 expect(m.incidents).toHaveBeenCalledTimes(2);
 expect(m.incidents.mock.lastCall?.[0]).toMatchObject({status:"",page:1,tenantNo:"MAIN",entityNo:"E",storeNo:"S"});
 expect(host.textContent).toContain("总条数: 3");
 await input("status","   ");await form(1);
 expect(m.incidents).toHaveBeenCalledTimes(3);
});
it("fetches changed incident query keys once without also refetching the old page or filter",async()=>{
 await mount();await target();
 await input("status","OPEN");await form(1);
 expect(m.incidents).toHaveBeenCalledTimes(2);
 expect(m.incidents.mock.lastCall?.[0]).toMatchObject({status:"OPEN",page:1});
 await act(async()=>{[...host.querySelectorAll("button")].find(b=>b.textContent==="下一页")!.click();});await settle();
 expect(m.incidents).toHaveBeenCalledTimes(3);
 expect(m.incidents.mock.lastCall?.[0]).toMatchObject({status:"OPEN",page:2});
 await form(1);
 expect(m.incidents).toHaveBeenCalledTimes(4);
 expect(m.incidents.mock.lastCall?.[0]).toMatchObject({status:"OPEN",page:1});
});
