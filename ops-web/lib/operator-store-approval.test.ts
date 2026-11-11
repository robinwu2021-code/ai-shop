// @vitest-environment happy-dom
import {afterEach,beforeEach,expect,it,vi} from "vitest";
import {act,createElement as h} from "react";
import {createRoot,type Root} from "react-dom/client";
import {QueryClient,QueryClientProvider} from "@tanstack/react-query";
const calls=vi.hoisted(()=>({audit:vi.fn(),subscription:vi.fn()}));
const profile={name:"Taipei store",address:"Test address",industryCode:"RETAIL",subIndustryCode:"SHOP",businessTypeCode:"RETAIL"};
const application={applyNo:"MA-TEST",name:"Test entity",status:"PENDING",ownerUserNo:"TARGET-OWNER",marketCode:"TW",storeProfile:profile,serviceScope:"CITY",communityNos:[],categoryCodes:[],createdAt:1,contactName:"Owner",contactPhone:"09 test",industry:"RETAIL",subject:"INDIVIDUAL",category:"Retail",onBehalf:true,agreedAt:0};
vi.mock("@/lib/api",()=>({api:{listApplies:async()=>({records:[application],total:1}),listCommunities:async()=>({records:[]}),listAuthCodes:async()=>[],auditApply:calls.audit,mallSubscription:calls.subscription}}));
vi.mock("@/lib/api/master-data",()=>({useCodeLabel:()=>({industry:(v:string)=>v,subject:(v:string)=>v})}));
vi.mock("@/lib/notify",()=>({notify:{success:vi.fn(),error:vi.fn()}}));
vi.mock("@/lib/i18n",()=>({useT:()=>(v:string)=>v,useI18n:()=>({locale:"zh",t:(v:string)=>v})}));
import {ApplyTab} from "@/app/merchants/apply-tab";
import {MERCHANTS_COPY} from "@/app/merchants/copy";
let host:HTMLDivElement,root:Root,q:QueryClient;
beforeEach(()=>{(globalThis as typeof globalThis&{IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;vi.clearAllMocks();host=document.createElement("div");document.body.append(host);root=createRoot(host);q=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});calls.audit.mockResolvedValue({...application,status:"APPROVED",merchantNo:"ENTITY-RESULT",storeNo:"STORE-RESULT"});calls.subscription.mockResolvedValue({currentPlanVersionNo:"FREE-V1",businessTypeCode:"RETAIL",currency:"TWD",sources:[{permanent:true,planVersionNo:"FREE-V1"}]});});
afterEach(async()=>{await act(async()=>root.unmount());q.clear();host.remove();});
async function settle(){await act(async()=>{await new Promise(r=>setTimeout(r,40));});}
async function mount(canReadSubscription:boolean){await act(async()=>root.render(h(QueryClientProvider,{client:q},h(ApplyTab,{c:MERCHANTS_COPY.zh,canAudit:true,canReadSubscription}))));await settle();}
async function click(text:string){const button=[...document.querySelectorAll("button")].find(b=>b.textContent===text)!;expect(button).toBeDefined();await act(async()=>button.click());await settle();}
it("approval keeps the server receipt visible and reads the actual FREE source for the returned store",async()=>{
 await mount(true);expect(calls.subscription).not.toHaveBeenCalled();await click(MERCHANTS_COPY.zh.actionView);expect(document.body.textContent).toContain("TARGET-OWNER");await click(MERCHANTS_COPY.zh.applyActionApprove);await settle();
 expect(calls.audit).toHaveBeenCalledWith("MA-TEST",true,"","CITY",[],[]);expect(calls.subscription).toHaveBeenCalledWith("STORE-RESULT");expect(document.body.textContent).toContain("ENTITY-RESULT / STORE-RESULT");expect(document.body.textContent).toContain(`${MERCHANTS_COPY.zh.applyFreePlan} · TWD · RETAIL`);expect([...document.querySelectorAll("button")].some(b=>b.textContent===MERCHANTS_COPY.zh.applyActionApprove)).toBe(false);
});
it("an approver without subscription permission still sees the creation receipt without making a forbidden read",async()=>{
 await mount(false);await click(MERCHANTS_COPY.zh.actionView);await click(MERCHANTS_COPY.zh.applyActionApprove);expect(document.body.textContent).toContain("ENTITY-RESULT / STORE-RESULT");expect(calls.subscription).not.toHaveBeenCalled();
});
