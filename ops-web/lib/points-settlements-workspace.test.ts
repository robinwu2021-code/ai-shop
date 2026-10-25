// @vitest-environment happy-dom
import {afterEach,beforeEach,expect,it,vi} from "vitest";
import {act,createElement as h} from "react";
import {createRoot,type Root} from "react-dom/client";
import {QueryClient,QueryClientProvider} from "@tanstack/react-query";
const m=vi.hoisted(()=>({draft:vi.fn(),change:vi.fn(),statements:vi.fn()}));
vi.mock("@/lib/api",()=>({IS_MOCK:false,api:{pointsTypes:async()=>[{typeCode:"P",name:"平台积分",currency:"CNY",scale:2}],pointsStatements:m.statements,pointsReceivables:async()=>({rows:[],next:0,hasMore:false}),draftPointsStatements:m.draft,changePointsStatement:m.change}}));
vi.mock("@/components/ui/drawer",()=>({Drawer:({open,title,children,onOpenChange}:{open:boolean;title:string;children:unknown;onOpenChange:(open:boolean)=>void})=>open?h("aside",{},h("h2",{},title),h("button",{onClick:()=>onOpenChange(false)},"关闭抽屉"),children as never):null}));
vi.mock("@/components/ui/data-table",()=>({DataTable:({rows=[],columns}:{rows:never[];columns:{header:string;cell:(r:never)=>unknown}[]})=>h("div",{},...rows.map((r,i)=>h("div",{key:i},...columns.map((c,j)=>h("span",{key:j},c.cell(r) as never)))))}));
import {PointsSettlementsTab} from "@/app/points/settlements-tab";
let host:HTMLDivElement,root:Root,q:QueryClient;
const flush=()=>act(async()=>{await new Promise(r=>setTimeout(r,20));});
beforeEach(()=>{(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;vi.clearAllMocks();m.statements.mockResolvedValue({rows:[{id:1,snapshot:{statementNo:"S1",state:"CONFIRMED",period:{fromMillis:1,toMillis:2},revisions:[{number:1,points:100,lines:[]}]}}],next:1,hasMore:false});host=document.createElement("div");document.body.append(host);root=createRoot(host);q=new QueryClient({defaultOptions:{queries:{retry:false}}});});
afterEach(async()=>{await act(async()=>root.unmount());host.remove();q.clear();});
async function load(canMake:boolean,canExecute:boolean,onBusyChange?: (busy:boolean)=>void){await act(async()=>root.render(h(QueryClientProvider,{client:q},h(PointsSettlementsTab,{canMake,canExecute,onBusyChange}))));await flush();host.querySelector<HTMLInputElement>('input[name="store"]')!.value="STORE";await act(async()=>host.querySelector("form")!.dispatchEvent(new Event("submit",{bubbles:true,cancelable:true})));await flush();}
async function click(text:string){await act(async()=>[...host.querySelectorAll("button")].find(b=>b.textContent===text)!.click());await flush();}
it("starts from statement rows and keeps unrelated forms in separate drawers",async()=>{await load(true,false);expect(host.textContent).toContain("S1");expect(host.querySelector("aside")).toBeNull();await click("查看详情");expect(host.querySelector("aside")!.textContent).toContain("S1");expect(host.querySelector("aside")!.textContent).not.toContain("准备本组付款（同店抵销 R）");expect(m.change).not.toHaveBeenCalled();});
it("does not expose drafting without write permission",async()=>{await load(false,false);expect([...host.querySelectorAll("button")].some(b=>b.textContent==="生成账单")).toBe(false);await click("查看详情");expect(host.textContent).not.toContain("准备本组付款（同店抵销 R）");});
it("rejects reversed dates before submitting a draft",async()=>{await load(true,true);await click("生成账单");const aside=host.querySelector("aside")!;aside.querySelector<HTMLInputElement>('input[name="from"]')!.value="2026-10-25T12:00";aside.querySelector<HTMLInputElement>('input[name="to"]')!.value="2026-10-24T12:00";await act(async()=>aside.querySelector("form")!.dispatchEvent(new Event("submit",{bubbles:true,cancelable:true})));expect(aside.textContent).toContain("结束时间必须晚于开始时间");expect(m.draft).not.toHaveBeenCalled();});

async function reason(value:string){const el=host.querySelector<HTMLInputElement>("aside input")!;await act(async()=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"value")!.set!.call(el,value);el.dispatchEvent(new Event("input",{bubbles:true}));});}
it("retains the original requestId after a failed command and drawer reopen",async()=>{
 m.change.mockRejectedValue(new Error("暂时失败"));await load(true,true);await click("查看详情");await reason("checked");await click("准备本组付款（同店抵销 R）");expect(m.change).toHaveBeenCalledTimes(1);
 const first=m.change.mock.calls[0][0];expect(first).toMatchObject({statementNo:"S1",revision:1,action:"PREPARE_PAYOUT",storeNo:"STORE",typeCode:"P"});
 await click("关闭抽屉");await click("查看详情");await reason("checked");await click("准备本组付款（同店抵销 R）");expect(m.change.mock.calls[1][0].requestId).toBe(first.requestId);
});
it("locks scope and close while a funds command is pending",async()=>{
 let finish!:(value:object)=>void;m.change.mockImplementation(()=>new Promise(resolve=>finish=resolve));const busy=vi.fn();await load(true,true,busy);await click("查看详情");await reason("checked");await click("准备本组付款（同店抵销 R）");
 expect(host.querySelector<HTMLInputElement>('input[name="store"]')!.disabled).toBe(true);expect(host.querySelector<HTMLSelectElement>('select[name="type"]')!.disabled).toBe(true);expect(busy).toHaveBeenLastCalledWith(true);
 await click("关闭抽屉");expect(host.querySelector("aside")).not.toBeNull();await act(async()=>finish({reference:"S1",requestId:"done"}));await flush();expect(host.querySelector("aside")).toBeNull();expect(busy).toHaveBeenLastCalledWith(false);
});
it("does not offer payouts for unknown state or missing revisions",async()=>{
 m.statements.mockResolvedValue({rows:[{id:1,snapshot:{statementNo:"S1",state:"FUTURE",period:{fromMillis:1,toMillis:2},revisions:[]}}],hasMore:false,next:1});await load(true,true);expect(host.textContent).toContain("状态待核实");await click("查看详情");expect(host.textContent).toContain("账单修订信息缺失");expect(host.textContent).not.toContain("准备本组付款（同店抵销 R）");
});
