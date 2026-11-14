// @vitest-environment happy-dom
import {afterEach,beforeEach,expect,it,vi} from "vitest";
import {act,createElement as h} from "react";
import {createRoot,type Root} from "react-dom/client";
import {QueryClient,QueryClientProvider} from "@tanstack/react-query";
const s=vi.hoisted(()=>({username:"OP1",place:vi.fn(),lookup:vi.fn()}));
vi.mock("@/lib/auth",()=>({useAuth:(select:(v:{username:string;tenantNo:string})=>unknown)=>select({username:s.username,tenantNo:"MAIN"})}));
vi.mock("@/lib/api",()=>({api:{entityPlanAssistanceContext:async()=>({owners:[{accountNo:"OWNER1",displayName:"Owner",subjectKind:"USR"}],stores:[{storeNo:"S1",name:"Store"}]}),assistedEntityPlanPurchase:s.place,entityPlanPurchases:s.lookup}}));
vi.mock("@/lib/use-copy",()=>({useCopy:(c:{zh:unknown})=>c.zh}));
vi.mock("@/lib/i18n",()=>({useI18n:()=>({t:(k:string)=>k})}));
import {EntityAssistedPurchase} from "@/app/merchants/plan-assisted-purchase";
import {ASSISTED_PURCHASE_COPY} from "@/app/merchants/plan-purchases-copy";
const c=ASSISTED_PURCHASE_COPY.zh;
let root:Root,host:HTMLDivElement,client:QueryClient;
const offers=[{offerNo:"F1",version:1,planCode:"PRO",name:"Plan",days:30,price:{currency:"TWD",minor:100},storeQuota:2,staffQuota:2,crossStoreStats:false,enabled:true}];
async function render(show=true){await act(async()=>root.render(h(QueryClientProvider,{client},show?h(EntityAssistedPurchase,{entityNo:"E1",offers,onChanged:async()=>{}}):h("div",null,"another host tab"))));await act(async()=>{await new Promise(r=>setTimeout(r,20));});}
async function select(value:string){const el=[...host.querySelectorAll("select")].find(x=>[...x.options].some(o=>o.value===value))!;await act(async()=>{el.value=value;el.dispatchEvent(new Event("change",{bubbles:true}));});}
const button=(label:string)=>[...host.querySelectorAll("button")].find(x=>x.textContent===label)!;
beforeEach(()=>{Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});vi.clearAllMocks();s.username="OP1";s.place.mockRejectedValue(new Error("network unknown"));s.lookup.mockResolvedValue([]);host=document.createElement("div");document.body.append(host);root=createRoot(host);client=new QueryClient({defaultOptions:{queries:{retry:false}}});});
afterEach(async()=>{await act(async()=>root.unmount());client.clear();host.remove();});
async function submit(){await render();await select("OWNER1");await select("S1");await select("F1");await act(async()=>button(c.buy).click());await act(async()=>{await new Promise(r=>setTimeout(r,10));});return s.place.mock.calls[0][1];}
it("restores the original unknown request after host tab unmount and retries only its original key",async()=>{
 const original=await submit();await render(false);await render();
 expect(host.textContent).toContain(original.requestNo);expect(button(c.buy)).toBeUndefined();expect(s.place).toHaveBeenCalledTimes(1);
 await act(async()=>button(c.lookup).click());await render();await act(async()=>button(c.retry).click());
 expect(s.place.mock.calls[1][1]).toEqual(original);
});
it("keeps pending assistance isolated by operator and restores it when the original operator returns",async()=>{
 const original=await submit();s.username="OP2";await render();expect(host.textContent).not.toContain(original.requestNo);expect(button(c.buy)).toBeDefined();
 s.username="OP1";await render();expect(host.textContent).toContain(original.requestNo);expect(s.place).toHaveBeenCalledTimes(1);
});
it("clears retained intent only after finding the original server purchase",async()=>{
 const original=await submit();await render(false);await render();s.lookup.mockResolvedValue([{requestNo:original.requestNo}]);
 await act(async()=>button(c.lookup).click());await render(false);await render();expect(host.textContent).not.toContain(original.requestNo);expect(button(c.buy)).toBeDefined();expect(s.place).toHaveBeenCalledTimes(1);
});
