// @vitest-environment happy-dom
import {afterEach,beforeEach,describe,expect,it,vi} from "vitest";
import {act,createElement as h} from "react";
import {createRoot,type Root} from "react-dom/client";
import {QueryClient,QueryClientProvider} from "@tanstack/react-query";
const calls=vi.hoisted(()=>({apply:vi.fn(),status:vi.fn(),success:vi.fn(),allowed:true}));
vi.mock("@/lib/api",()=>({api:{applyPayService:calls.apply,payServiceStatus:calls.status}}));
vi.mock("@/lib/use-can",()=>({useCan:()=>(code:string)=>calls.allowed&&code==="merchant:merchant:pay-apply"}));
vi.mock("@/lib/notify",()=>({notify:{success:calls.success,error:vi.fn()}}));
vi.mock("@/lib/i18n",()=>({useT:()=>(v:string)=>v,useI18n:()=>({locale:"zh",t:(v:string)=>v})}));
import {PayServiceBlock,toApplyForm,PAY_APPLY_PERM} from "@/app/merchants/pay-service-block";
import {MERCHANTS_COPY} from "@/app/merchants/copy";
let host:HTMLDivElement,root:Root,q:QueryClient;
const approved={status:"APPROVED",merchantRef:"PM-test",linkStatus:"VERIFIED",profileStatus:"APPROVED",reviewReason:null,merchantOpsUrl:"https://pay.example/merchants?keyword=PM-test",onboardingOpsUrl:"https://pay.example/merchants/onboarding-review"};
beforeEach(()=>{
 (globalThis as typeof globalThis&{IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;
 vi.resetAllMocks();calls.allowed=true;calls.status.mockResolvedValue(approved);calls.apply.mockResolvedValue({merchantRef:"PM-test",status:"ACCEPTED",storeLinks:[],applicationRef:null});
 host=document.createElement("div");document.body.append(host);root=createRoot(host);q=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});
});
afterEach(async()=>{await act(async()=>root.unmount());q.clear();host.remove();});
async function settle(){await act(async()=>{await new Promise(r=>setTimeout(r,30));});}
async function mount(entity="M1"){await act(async()=>root.render(h(QueryClientProvider,{client:q},h(PayServiceBlock,{merchantNo:entity,merchantName:`Entity ${entity}`,c:MERCHANTS_COPY.zh}))));await settle();}
async function click(text:string){const button=[...document.querySelectorAll("button")].find(b=>b.textContent===text)!;expect(button).toBeDefined();await act(async()=>button.click());await settle();}
async function input(key:string,value:string){const el=document.getElementById(`${key}-label`)!.parentElement!.parentElement!.querySelector("input")!;await act(async()=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"value")!.set!.call(el,value);el.dispatchEvent(new Event("input",{bubbles:true}));});await settle();}
async function open(){await click(MERCHANTS_COPY.zh.payServiceApply);await input("registrationNo","TESTONLY");}
describe("支付资料登记与回查",()=>{
 it("trims the form and retains the existing permission",()=>{
  expect(toApplyForm({legalName:" Test company ",registrationNo:" TESTONLY ",representativeName:" ",contactPhone:""})).toEqual({legalName:"Test company",registrationNo:"TESTONLY",representativeName:undefined,contactPhone:undefined,registeredAddress:undefined});
  expect(PAY_APPLY_PERM).toBe("merchant:merchant:pay-apply");
 });
 it("reads actual profile approval while explaining the remaining collection steps",async()=>{
  await mount();expect(calls.status).toHaveBeenCalledWith("M1");expect(host.textContent).toContain("APPROVED");expect(host.textContent).toContain(MERCHANTS_COPY.zh.payServiceCollectionHelp);
  const link=host.querySelector("a")!;expect(link.href).toBe(approved.merchantOpsUrl);expect(link.rel).toBe("noopener noreferrer");
 });
 it("makes no payment read or submission without permission",async()=>{
  calls.allowed=false;await mount();expect(host.textContent).toBe("");expect(calls.status).not.toHaveBeenCalled();expect(calls.apply).not.toHaveBeenCalled();
 });
 it("keeps query failures visible without inventing an unsubmitted or approved state",async()=>{
  calls.status.mockRejectedValue(new Error("unavailable"));await mount();expect(host.querySelector('[role="alert"]')?.textContent).toBe(MERCHANTS_COPY.zh.payServiceLoadFailed);expect(host.textContent).not.toContain("APPROVED");expect(host.querySelector("a")).toBeNull();
 });
 it("discards an old approval after a new submission when the subsequent status lookup fails",async()=>{
  calls.status.mockReset().mockResolvedValueOnce(approved).mockRejectedValue(new Error("lookup unavailable"));
  await mount();expect(host.textContent).toContain("APPROVED");await open();await click("common.save");await settle();
  expect(calls.apply).toHaveBeenCalledOnce();expect(calls.status).toHaveBeenCalledTimes(2);
  expect(host.querySelector('[role="alert"]')?.textContent).toBe(MERCHANTS_COPY.zh.payServiceLoadFailed);
  expect(host.textContent).not.toContain("APPROVED");expect(host.textContent).not.toContain("PM-test");
 });
 it("retries an uncertain result with the same key and allocates another key for changed details",async()=>{
  calls.apply.mockRejectedValue(new Error("response lost"));await mount();await open();await click("common.save");await click("common.save");
  expect(calls.apply).toHaveBeenCalledTimes(2);expect(calls.apply.mock.calls[0]).toEqual(calls.apply.mock.calls[1]);expect(calls.apply.mock.calls[0][1].requestNo).toMatch(/^[A-Za-z0-9_-]{1,64}$/);
  await input("contactPhone","09 record only");await click("common.save");expect(calls.apply).toHaveBeenCalledTimes(3);expect(calls.apply.mock.calls[2][1].requestNo).not.toBe(calls.apply.mock.calls[0][1].requestNo);
 });
 it("does not close the next entity's form or announce the previous entity's late success",async()=>{
  let resolve!:(v:unknown)=>void;calls.apply.mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));await mount("M1");await open();await click("common.save");await mount("M2");await open();
  await act(async()=>resolve({merchantRef:"PM-old",status:"ACCEPTED",storeLinks:[],applicationRef:null}));await settle();expect(document.querySelector('[role="dialog"]')).not.toBeNull();expect(calls.success).not.toHaveBeenCalled();
  await click("common.save");expect(calls.apply.mock.calls[1][0]).toBe("M2");expect(calls.apply.mock.calls[1][1].requestNo).not.toBe(calls.apply.mock.calls[0][1].requestNo);
 });
});
