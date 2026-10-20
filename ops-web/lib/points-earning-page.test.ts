// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
const mock = vi.hoisted(() => ({ context: vi.fn(), rules: vi.fn(), history: vi.fn(), change: vi.fn(), events: vi.fn(), retry: vi.fn() }));
vi.mock("@/lib/api", () => ({ api: { pointsEarningContext: mock.context, pointsEarningRules: mock.rules, pointsEarningHistory: mock.history, pointsEvents: mock.events, retryPointsEvent: mock.retry, changePointsEarningRule: mock.change } }));
vi.mock("@/components/ui/drawer", async () => {
  const { createElement:h }=await import("react");
  return {Drawer:({open,title,children,footer}:{open:boolean;title:string;children:unknown;footer:unknown})=>open?h("aside",{},h("h2",{},title),children as never,footer as never):null};
});
vi.mock("@/components/ui/data-table", async () => {
  const {createElement:h}=await import("react");
  return {DataTable:({rows,columns,rowProps}:{rows:never[];columns:{cell:(r:never)=>unknown}[];rowProps:(r:never)=>object})=>h("div",{},...(rows??[]).map((r,i)=>h("button",{key:i,...rowProps(r)},...columns.map((c,j)=>h("span",{key:j},c.cell(r) as never)))))};
});
import { EarningEvents } from "@/app/points/earning-events";
import { PointsEarningRulesPanel } from "@/app/points/earning-rules-panel";
let root:Root; let host:HTMLDivElement; let query:QueryClient;
const row={typeCode:"P",scope:"DEFAULT",revision:1,activeRevision:1,mode:"RATIO",value:100,activeMode:"RATIO",activeValue:100,actor:"ops",remark:"",at:1};
beforeEach(()=>{
  (globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;
  vi.clearAllMocks();
  mock.context.mockResolvedValue({type:{typeCode:"P",name:"消费积分",currency:"CNY",scale:2,rate:{pointsUnit:1,minorUnit:1},status:"ACTIVE"},categories:[{categoryNo:"C",parentNo:null,name:"食品",path:"食品"}]});
  mock.rules.mockResolvedValue({rows:[row],total:1,page:1,size:50}); mock.history.mockResolvedValue([row]);
  host=document.createElement("div");document.body.append(host);root=createRoot(host);query=new QueryClient({defaultOptions:{queries:{retry:false}}});
});
afterEach(async()=>{await act(async()=>root.unmount());query.clear();host.remove();});
async function render(){await act(async()=>root.render(h(QueryClientProvider,{client:query},h(PointsEarningRulesPanel,{canEdit:true}))));await act(async()=>{await new Promise(r=>setTimeout(r,25));});}
it("shows independent published rules and opens category editing in a drawer",async()=>{
 await render();expect(host.textContent).toContain("1.00%");expect(host.textContent).toContain("消费积分");
 const add=[...host.querySelectorAll("button")].find(b=>b.textContent==="新增品类规则");expect(add).toBeDefined();await act(async()=>add!.click());
 expect(host.querySelector("aside")).not.toBeNull();expect(host.querySelector('select[name="scope"]')).not.toBeNull();
 expect(host.querySelector('input[name="ratio"]')?.getAttribute("type")).toBe("number");
});
it("switching to no reward removes ratio input and saving uses zero basis points",async()=>{
 await render();await act(async()=>[...host.querySelectorAll("button")].find(b=>b.textContent==="新增品类规则")!.click());
 const scope=host.querySelector('select[name="scope"]') as HTMLSelectElement;scope.value="C";scope.dispatchEvent(new Event("change",{bubbles:true}));
 const mode=host.querySelector('select[name="mode"]') as HTMLSelectElement;
 await act(async()=>{mode.value="NONE";mode.dispatchEvent(new Event("change",{bubbles:true}));});
 expect(host.querySelector('input[name="ratio"]')).toBeNull();
 await act(async()=>host.querySelector("form")!.dispatchEvent(new Event("submit",{bubbles:true,cancelable:true})));
 expect(mock.change.mock.calls[0][0]).toMatchObject({scope:"C",action:"SAVE",ratioBp:0,expectedRevision:0});
});

it("offers audited resubmission only for manually recoverable events",async()=>{
 const event={id:1,eventNo:"E",kind:"EARN_GRANT",state:"MANUAL",attempt:12,nextAttempt:0,version:3,subOrderNo:"O",reason:"POINTS_POOL_INSUFFICIENT"};
 mock.events.mockResolvedValue({rows:[event],next:1,hasMore:false});mock.retry.mockResolvedValue({requestId:"R"});
 await act(async()=>root.render(h(QueryClientProvider,{client:query},h(EarningEvents,{typeCode:"P",open:true,onOpenChange:()=>{},canRetry:true}))));
 await act(async()=>{await new Promise(r=>setTimeout(r,25));});
 await act(async()=>[...host.querySelectorAll("button")].find(b=>b.textContent?.includes("POINTS_POOL_INSUFFICIENT"))!.click());
 expect(host.textContent).toContain("重新提交处理");
 (host.querySelector('input[name="remark"]') as HTMLInputElement).value="预算已补足";
 await act(async()=>host.querySelector("form")!.dispatchEvent(new Event("submit",{bubbles:true,cancelable:true})));
 expect(mock.retry.mock.calls[0][0]).toMatchObject({typeCode:"P",eventId:1,expectedVersion:3,reason:"预算已补足"});
});
