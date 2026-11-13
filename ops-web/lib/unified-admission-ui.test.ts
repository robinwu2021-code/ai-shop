// @vitest-environment happy-dom
import {act,createElement as h} from "react";
import {createRoot,type Root} from "react-dom/client";
import {QueryClient,QueryClientProvider} from "@tanstack/react-query";
import {beforeEach,afterEach,it,expect,vi} from "vitest";
import type {AdmissionEntry} from "./types";
const state=vi.hoisted(()=>({list:vi.fn(),create:vi.fn(),accept:vi.fn(),addStore:vi.fn(),context:vi.fn()}));
vi.mock("@/lib/api",()=>({api:{
 listAdmissions:state.list,selfOperatedContext:state.context,onBehalfContext:state.context,
 createSelfOperated:state.create,acceptApply:state.accept,addSelfOperatedStore:state.addStore,
 listCommunities:async()=>({records:[]}),listAuthCodes:async()=>[],listIndustries:async()=>[],
 onBehalfStoreIndustries:async()=>[],
}}));
vi.mock("@/lib/api/master-data",()=>({useCodeLabel:()=>({industry:(v:string)=>v,subject:(v:string)=>v})}));
vi.mock("@/lib/i18n",()=>({useI18n:()=>({locale:"zh",t:(k:string)=>k}),useT:()=>((k:string)=>k)}));
vi.mock("@/lib/notify",()=>({notify:{success:vi.fn(),error:vi.fn()}}));
import {OnBehalfTab} from "@/app/merchants/on-behalf-tab";
import {MERCHANTS_COPY} from "@/app/merchants/copy";
const c=MERCHANTS_COPY.zh;
let host:HTMLDivElement,root:Root,client:QueryClient;
const entries:AdmissionEntry[]=[
 {key:"APPLY:A1",type:"ON_BEHALF",name:"代客测试",ownerUserNo:"OWNER-A",status:"PENDING",createdAt:1,application:{applyNo:"A1",name:"代客测试",status:"PENDING",onBehalf:true,createdAt:1,subject:"ENTERPRISE",contactName:"Test",contactPhone:"",category:"",desc:""}},
 {key:"SELF:M1",type:"SELF_OPERATED",name:"自营测试",ownerUserNo:"OWNER-S",status:"ACTIVE",createdAt:2,selfOperated:{merchantNo:"M1",name:"自营测试",ownerUserNo:"OWNER-S",status:"ACTIVE",serviceScope:"CITY",createdAt:2}},
];
beforeEach(()=>{(globalThis as typeof globalThis&{IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;
 vi.clearAllMocks();state.list.mockResolvedValue({records:entries,total:2});state.context.mockResolvedValue({market:"TW",recordOnly:true,marketEnabled:true});
 state.create.mockResolvedValue({merchantNo:"M-new",created:true});state.addStore.mockResolvedValue({merchantNo:"M1",storeNo:"S-new",name:"实际新增门店"});
 host=document.createElement("div");document.body.append(host);root=createRoot(host);client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});
});
afterEach(async()=>{await act(async()=>root.unmount());client.clear();host.remove();});
async function settle(){await act(async()=>{await new Promise(r=>setTimeout(r,20));});}
async function render(props={canSubmit:true,canAudit:true,canSelfOp:true}){await act(async()=>root.render(h(QueryClientProvider,{client},h(OnBehalfTab,{c,...props}))));await settle();}
async function click(text:string){const b=[...document.querySelectorAll("button")].find(b=>b.textContent===text)!;await act(async()=>b.click());await settle();}
async function input(id:string,value:string){const el=document.getElementById(id) as HTMLInputElement;await act(async()=>{Object.getOwnPropertyDescriptor(el.tagName==="SELECT"?HTMLSelectElement.prototype:HTMLInputElement.prototype,"value")!.set!.call(el,value);el.dispatchEvent(new Event(el.tagName==="SELECT"?"change":"input",{bubbles:true}));});await settle();}
it("shows both records in one concise table and keeps review actions on applications",async()=>{
 await render();expect(host.querySelectorAll("table")).toHaveLength(1);
 expect([...host.querySelectorAll("thead th")].map(e=>e.textContent)).toEqual([c.admissionName,c.admissionType,c.admissionOwner,c.admissionStatus,c.colCreatedAt,c.colActions]);
 const rows=[...host.querySelectorAll("tbody tr")];expect(rows[0].textContent).toContain(c.admissionAssisted);expect(rows[1].textContent).toContain(c.admissionSelf);
 expect(rows[1].textContent).not.toContain(c.applyActionAccept);await click(c.applyActionAccept);expect(state.accept).toHaveBeenCalledWith("A1");
});
it("filters through the server and resets pagination",async()=>{
 await render();const el=host.querySelector('select[aria-label="入驻类型"]') as HTMLSelectElement;
 await act(async()=>{el.value="SELF_OPERATED";el.dispatchEvent(new Event("change",{bubbles:true}));});await settle();
 expect(state.list).toHaveBeenLastCalledWith(expect.objectContaining({type:"SELF_OPERATED",page:1}));
});
it("switches the creation dialog between assisted and platform-owned without changing region",async()=>{
 await render();await click(c.obNew);expect(document.querySelector("#ob-owner-email")).not.toBeNull();
 await input("admission-create-type","SELF_OPERATED");
 expect(document.querySelector("#ob-owner-email")).toBeNull();expect(document.querySelector("#so-owner")?.getAttribute("type")).toBe("email");
 expect(document.querySelector('[role="dialog"]')?.textContent).not.toContain("大陆");
 expect(document.querySelector("#ob-market")).toBeNull();
 await input("admission-create-type","ON_BEHALF");expect(document.querySelector("#ob-owner-email")).not.toBeNull();
});
it("self-operation success closes the dialog and refreshes the unified list",async()=>{
 await render();await click(c.obNew);await input("admission-create-type","SELF_OPERATED");
 await input("so-owner","owner@example.test");await input("so-name","平台主体");await input("so-scope","CITY");
 const before=state.list.mock.calls.length;await click(c.soSubmit);
 expect(state.create).toHaveBeenCalledWith(expect.objectContaining({ownerEmail:"owner@example.test",name:"平台主体",phone:undefined}));
 expect(document.querySelector('[role="dialog"]')).toBeNull();expect(state.list.mock.calls.length).toBeGreaterThan(before);
});
it("self-operation-only users do not load assisted context or offer assisted creation",async()=>{
 state.list.mockResolvedValue({records:[entries[1]],total:1});await render({canSubmit:false,canAudit:false,canSelfOp:true});await click(c.obNew);
 const select=document.getElementById("admission-create-type") as HTMLSelectElement;
 expect([...select.options].map(o=>o.value)).toEqual(["SELF_OPERATED"]);expect(document.querySelector("#ob-owner-email")).toBeNull();
});
it("assisted-only users cannot select platform-owned creation",async()=>{
 state.list.mockResolvedValue({records:[entries[0]],total:1});await render({canSubmit:true,canAudit:false,canSelfOp:false});await click(c.obNew);
 expect([...(document.getElementById("admission-create-type") as HTMLSelectElement).options].map(o=>o.value)).toEqual(["ON_BEHALF"]);
});
it("platform-owned row adds a store using the actual merchant key",async()=>{
 await render();const row=host.querySelectorAll("tbody tr")[1];await act(async()=>row.querySelector("button")!.click());await settle();
 await input("admission-store-name","实际新增门店");await click(c.soStoreSubmit);
 expect(state.addStore).toHaveBeenCalledWith({merchantNo:"M1",name:"实际新增门店",address:undefined});
 expect(document.querySelector('[role="dialog"]')?.textContent).toContain("S-new");
});
