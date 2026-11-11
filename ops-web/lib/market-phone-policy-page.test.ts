// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
const s=vi.hoisted(()=>({apply:vi.fn(),save:vi.fn(),policies:vi.fn()}));
vi.mock("@/lib/api",()=>({api:{marketPhonePolicies:s.policies,
  listIndustries:async()=>[],applyOnBehalf:s.apply,saveMarketPhonePolicy:s.save}}));
vi.mock("@/lib/notify",()=>({notify:{success:vi.fn(),error:vi.fn()}}));
vi.mock("@/lib/i18n",()=>({useT:()=>(x:string)=>x,useI18n:()=>({locale:"zh",t:(x:string)=>x})}));
import { OnBehalfTab } from "@/app/merchants/on-behalf-tab";
import { MERCHANTS_COPY } from "@/app/merchants/copy";
import { MarketPhonePolicyCard } from "@/app/system/market-phone-policy-card";
import { SYSTEM_COPY } from "@/app/system/copy";
let host:HTMLDivElement,root:Root,q:QueryClient;
beforeEach(()=>{(globalThis as typeof globalThis&{IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;vi.clearAllMocks();s.policies.mockResolvedValue([
 {market:"CN",callingPrefix:"+86",recordOnly:false,smsVerificationEnabled:true,smsChannelReady:true,marketEnabled:true},
 {market:"TW",callingPrefix:"+886",recordOnly:true,smsVerificationEnabled:false,smsChannelReady:false,marketEnabled:true}]);s.apply.mockResolvedValue({applyNo:"TEST-APPLY",ownerUserNo:"OWNER"});s.save.mockResolvedValue({market:"TW",recordOnly:true,smsVerificationEnabled:false});host=document.createElement("div");document.body.append(host);root=createRoot(host);q=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});});
afterEach(async()=>{await act(async()=>root.unmount());q.clear();host.remove();});
async function settle(){await act(async()=>{await new Promise(r=>setTimeout(r,30));});}
async function mount(child:ReturnType<typeof h>){await act(async()=>root.render(h(QueryClientProvider,{client:q},child)));await settle();}
async function input(id:string,value:string){await act(async()=>{const e=host.querySelector(id) as HTMLInputElement;Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"value")!.set!.call(e,value);e.dispatchEvent(new Event("input",{bubbles:true}));});}
function submit(){return [...host.querySelectorAll("button")].find(b=>b.textContent===MERCHANTS_COPY.zh.obSubmit)!;}
async function required(){await input("#ob-name","Test merchant");await input("#ob-license","/test-only/license.png");}
it("Taiwan accepts raw contact text and submits email ownership plus the selected region",async()=>{
 await mount(h(OnBehalfTab,{c:MERCHANTS_COPY.zh,canSubmit:true}));await required();await input("#ob-owner-email","owner@example.test");await input("#ob-phone","09 1234 5678 / 分机");
 expect(submit().disabled).toBe(false);expect(host.textContent).not.toContain(MERCHANTS_COPY.zh.soPhoneBad);
 await act(async()=>submit().click());await settle();expect(s.apply).toHaveBeenCalledWith(expect.objectContaining({market:"TW",ownerEmail:"owner@example.test",phone:"09 1234 5678 / 分机"}));
});
it("Taiwan may leave its contact phone empty but must identify a registered email owner",async()=>{
 await mount(h(OnBehalfTab,{c:MERCHANTS_COPY.zh,canSubmit:true}));await required();expect(submit().disabled).toBe(true);await input("#ob-owner-email","owner@example.test");expect(submit().disabled).toBe(false);
});
it("switching to mainland retains the original mobile validation and phone ownership",async()=>{
 await mount(h(OnBehalfTab,{c:MERCHANTS_COPY.zh,canSubmit:true}));await required();await act(async()=>{const e=host.querySelector("#ob-market") as HTMLSelectElement;e.value="CN";e.dispatchEvent(new Event("change",{bubbles:true}));});
 await input("#ob-phone","+886900001111");expect(submit().disabled).toBe(true);await input("#ob-phone"," 13800138000 ");expect(submit().disabled).toBe(false);await act(async()=>submit().click());await settle();expect(s.apply).toHaveBeenCalledWith(expect.objectContaining({market:"CN",ownerEmail:undefined,phone:"13800138000"}));
});
it("regional settings keep unconnected Taiwan SMS disabled and save only explicit contact policy",async()=>{
 await mount(h(MarketPhonePolicyCard,{c:SYSTEM_COPY.zh,canWrite:true}));const checks=[...host.querySelectorAll('[role="checkbox"]')] as HTMLButtonElement[];expect(checks[0]!.getAttribute("aria-checked")).toBe("true");expect(checks[1]!.disabled).toBe(true);await input("#phone-policy-reason","TW records contacts only");await act(async()=>[...host.querySelectorAll("button")].find(b=>b.textContent==="common.save")!.click());await settle();expect(s.save).toHaveBeenCalledWith("TW",{recordOnly:true,smsVerificationEnabled:false,reason:"TW records contacts only"});
});

it("a mainland-only deployment selects CN consistently and keeps its submission usable",async()=>{
 s.policies.mockResolvedValue([{market:"CN",callingPrefix:"+86",recordOnly:false,smsVerificationEnabled:true,smsChannelReady:true,marketEnabled:true},{market:"TW",callingPrefix:"+886",recordOnly:true,smsVerificationEnabled:false,smsChannelReady:false,marketEnabled:false}]);
 await mount(h(OnBehalfTab,{c:MERCHANTS_COPY.zh,canSubmit:true}));expect((host.querySelector("#ob-market") as HTMLSelectElement).value).toBe("CN");expect(host.querySelector("#ob-owner-email")).toBeNull();await required();await input("#ob-phone","13800138000");expect(submit().disabled).toBe(false);
});
it("system policy configuration also selects its sole available region",async()=>{
 s.policies.mockResolvedValue([{market:"CN",callingPrefix:"+86",recordOnly:false,smsVerificationEnabled:true,smsChannelReady:true,marketEnabled:true}]);await mount(h(MarketPhonePolicyCard,{c:SYSTEM_COPY.zh,canWrite:true}));expect((host.querySelector("#phone-policy-market") as HTMLSelectElement).value).toBe("CN");
});
