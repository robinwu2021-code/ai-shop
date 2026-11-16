// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
const s=vi.hoisted(()=>({perms:new Set<string>(),mall:vi.fn(),entity:vi.fn(),detail:vi.fn(),offers:vi.fn(),query:""}));
vi.mock("@/lib/api",()=>({api:{mallOrders:s.mall,entityPlanPurchasePage:s.entity,entityPlanPurchase:s.detail,entityPlanOffers:s.offers}}));
vi.mock("next/navigation",()=>({useSearchParams:()=>new URLSearchParams(s.query)}));
vi.mock("@/lib/use-can",()=>({useCan:()=> (p:string)=>s.perms.has(p)}));
vi.mock("@/lib/use-copy",()=>({useCopy:(copy:{zh:unknown})=>copy.zh}));
vi.mock("@/lib/i18n",()=>({useI18n:()=>({locale:"zh",t:(k:string)=>k}),useT:()=> (k:string)=>k}));
vi.mock("@/app/mall/order-drawer",()=>({OrderDrawer:({orderNo,onClose}:{orderNo:string|null;onClose:()=>void})=>orderNo?h("button",{onClick:onClose},"关闭详情"):null}));
vi.mock("@/app/merchants/plan-purchases",()=>({EntityPlanPurchaseDetail:()=>h("div",null,"主体订单详情")}));
vi.mock("@/app/merchants/plan-assisted-purchase",async()=>{
 const {useState}=await import("react");const {useFinancialLock}=await import("@/components/offline/financial-lock");
 return {EntityAssistedPurchase:()=>{const [pending,setPending]=useState(false);useFinancialLock("assisted:test",pending);return h("button",{onClick:()=>setPending(true)},pending?"结果待核实":"开始代购");}};
});
import { PurchaseWorkspace } from "@/app/mall/purchase-workspace";
let root:Root,host:HTMLDivElement,client:QueryClient;
async function flush(){await act(async()=>{await new Promise(r=>setTimeout(r,20));});}
async function start(kind:"ENTITY"|"STORE"="STORE") {await act(async()=>root.render(h(QueryClientProvider,{client},h(PurchaseWorkspace,{initialKind:kind}))));await flush();}
async function scope(no:string){const input=host.querySelector('input[aria-label="查询对象"]') as HTMLInputElement;await act(async()=>{const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"value")!.set!;setter.call(input,no);input.dispatchEvent(new Event("input",{bubbles:true}));input.dispatchEvent(new Event("change",{bubbles:true}));});const button=[...host.querySelectorAll("button")].find(b=>b.textContent==="查询")!;await act(async()=>button.click());await flush();}
const button=(text:string)=>[...document.querySelectorAll("button")].find(b=>b.textContent===text)!;
beforeEach(()=>{Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});vi.clearAllMocks();s.perms=new Set();s.query="";s.mall.mockResolvedValue([]);s.entity.mockResolvedValue([]);s.detail.mockResolvedValue(null);s.offers.mockResolvedValue([]);host=document.createElement("div");document.body.append(host);root=createRoot(host);client=new QueryClient({defaultOptions:{queries:{retry:false}}});});
afterEach(async()=>{await act(async()=>root.unmount());client.clear();host.remove();});
it("does not query before selecting a scope and never queries a model without read permission",async()=>{s.perms.add("commerce:mall:order:read");await start("ENTITY");expect(host.textContent).not.toContain("主体套餐");expect(s.mall).not.toHaveBeenCalled();expect(s.entity).not.toHaveBeenCalled();await scope("S1");expect(s.mall).toHaveBeenCalledWith("S1",expect.anything());expect(s.entity).not.toHaveBeenCalled();});
it("uses a bounded exact-entity query and keeps scopes separate when switching model",async()=>{s.perms=new Set(["commerce:mall:order:read","merchant:merchant:read"]);await start("ENTITY");await scope("E1");expect(s.entity).toHaveBeenCalledWith("E1",expect.objectContaining({limit:20}));const select=host.querySelector('select[aria-label="套餐类型"]') as HTMLSelectElement;await act(async()=>{select.value="STORE";select.dispatchEvent(new Event("change",{bubbles:true}));});await flush();expect(s.mall).not.toHaveBeenCalled();await scope("S2");expect(s.mall).toHaveBeenCalledWith("S2",expect.anything());});
it("pages entity purchases using the last order rather than the legacy capped list",async()=>{s.perms.add("merchant:merchant:read");s.entity.mockResolvedValueOnce(Array.from({length:20},(_,i)=>({orderNo:`EQ${i}`,storeNo:"S1",offer:{name:"PRO",price:{currency:"TWD",minor:100}},status:"SUBMITTED"}))).mockResolvedValueOnce([]);await start("ENTITY");await scope("E1");await act(async()=>button("下一页").click());await flush();expect(s.entity).toHaveBeenLastCalledWith("E1",{afterOrderNo:"EQ19",limit:20});});
it("locks scope changes while a store order detail is open",async()=>{s.perms=new Set(["commerce:mall:order:read","merchant:merchant:read"]);s.mall.mockResolvedValue([{order:{orderNo:"O1",payable:{currency:"TWD",minor:"100"},status:"PAID"}}]);await start();await scope("S1");await act(async()=>button("详情").click());await flush();expect((host.querySelector('input[aria-label="查询对象"]') as HTMLInputElement).disabled).toBe(true);expect((host.querySelector('select[aria-label="套餐类型"]') as HTMLSelectElement).disabled).toBe(true);await act(async()=>button("关闭详情").click());await flush();expect((host.querySelector('input[aria-label="查询对象"]') as HTMLInputElement).disabled).toBe(false);});
it("shows query failure instead of falling back to another model",async()=>{s.perms.add("merchant:merchant:read");s.entity.mockRejectedValue(new Error("unavailable"));await start("ENTITY");await scope("E1");expect(host.textContent).toContain("table.errorTitle");expect(s.mall).not.toHaveBeenCalled();});

it("preserves an unresolved entity-assisted command when offer refresh fails",async()=>{
 s.perms=new Set(["merchant:merchant:read","merchant:merchant:ban","commerce:mall:assisted:purchase"]);
 s.entity.mockResolvedValue([{orderNo:"E1-O1",storeNo:"S1",offer:{name:"PRO",price:{currency:"TWD",minor:100}},status:"SUBMITTED"}]);
 await start("ENTITY");await scope("E1");await act(async()=>button("运营代购").click());await flush();await act(async()=>button("开始代购").click());await flush();
 expect((host.querySelector('input[aria-label="查询对象"]') as HTMLInputElement).disabled).toBe(true);
 expect(button("详情").disabled).toBe(true);
 s.offers.mockRejectedValue(new Error("offline"));await act(async()=>{await client.invalidateQueries({queryKey:["entity-plan-offers","E1"]});});await flush();
 expect(document.body.textContent).toContain("结果待核实");expect((host.querySelector('input[aria-label="查询对象"]') as HTMLInputElement).disabled).toBe(true);
});
it("does not display a delayed prior-entity response after selecting another entity",async()=>{
 s.perms.add("merchant:merchant:read");let resolve!:(rows:unknown[])=>void;const pending=new Promise<unknown[]>(r=>{resolve=r;});
 const row=(orderNo:string)=>({orderNo,storeNo:"S1",offer:{name:"PRO",price:{currency:"TWD",minor:100}},status:"SUBMITTED"});
 s.entity.mockImplementation((entity:string)=>entity==="E1"?pending:Promise.resolve([row("E2-ORDER")]));await start("ENTITY");await scope("E1");await scope("E2");
 await act(async()=>resolve([row("E1-ORDER")]));await flush();expect(host.textContent).toContain("E2-ORDER");expect(host.textContent).not.toContain("E1-ORDER");
});

it("opens the real entity workspace directly from an authorized customer store link",async()=>{
 s.perms=new Set(["merchant:merchant:read","commerce:mall:order:read"]);s.query="tab=orders&kind=ENTITY&entity=E%2F1";
 await start("STORE");expect(s.entity).toHaveBeenCalledWith("E/1",expect.objectContaining({limit:20}));expect(s.mall).not.toHaveBeenCalled();
});
it("does not promote a deep link into missing model permission",async()=>{
 s.perms.add("commerce:mall:order:read");s.query="kind=ENTITY&entity=E1";await start("STORE");expect(s.entity).not.toHaveBeenCalled();expect(s.mall).not.toHaveBeenCalled();
});
