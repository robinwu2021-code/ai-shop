// @vitest-environment happy-dom
import {act,createElement as h} from "react";
import {createRoot} from "react-dom/client";
import {QueryClient,QueryClientProvider} from "@tanstack/react-query";
import {expect,it,vi} from "vitest";
import {PlanRefundReviewPanel} from "@/app/merchants/plan-refund-review";
const api=vi.hoisted(()=>({entityPlanRefundReview:vi.fn(),closeEntityPlanRefundReview:vi.fn()}));
vi.mock("@/lib/api",()=>({api}));
vi.mock("@/lib/use-copy",()=>({useCopy:(copy:{zh:unknown})=>copy.zh}));

it("reads the review in order context, records the operator note and displays the closed decision",async()=>{
 Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});
 const host=document.createElement("div"); document.body.append(host);
 const root=createRoot(host),client=new QueryClient({defaultOptions:{queries:{retry:false}}});
 const quota={planCode:"CHAIN",stores:20,staff:30,storeOverride:null,staffOverride:8,overridesKnown:true,crossStore:true,expiry:null};
 const review={refundNo:"REF-1",storeNo:"S1",before:{...quota,planCode:"FREE",stores:2},current:quota,quotaRevision:"2:3",decision:null};
 api.entityPlanRefundReview.mockResolvedValue(review);
 const changed=vi.fn();
 const mount=async(canClose:boolean)=>{await act(async()=>root.render(h(QueryClientProvider,{client},h(PlanRefundReviewPanel,{entityNo:"E1",orderNo:"O1",canClose,onChanged:changed}))));};
 try{
  await mount(false);
  await act(async()=>{await new Promise(r=>setTimeout(r,20));});
  expect(api.entityPlanRefundReview).toHaveBeenCalledWith("E1","O1");
  expect(host.textContent).toContain("REF-1");
  expect(host.textContent).toContain("FREE");expect(host.textContent).toContain("CHAIN");
  expect(host.querySelector("input")).toBeNull();
  await mount(true);
  const input=host.querySelector("input")!;
  await act(async()=>{
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"value")!.set!.call(input,"续购额度已核实");
    input.dispatchEvent(new Event("input",{bubbles:true}));
  });
  const command={refundNo:"REF-1",quotaRevision:"2:3",reason:"续购额度已核实"};
  api.closeEntityPlanRefundReview.mockResolvedValue({});
  api.entityPlanRefundReview.mockResolvedValue({...review,decision:{operatorNo:"OPS1",command,reviewedAt:"2026-11-27T03:00:00Z",retainedQuota:quota}});
  await act(async()=>host.querySelector("button")!.click());
  await act(async()=>{await new Promise(r=>setTimeout(r,20));});
  expect(api.closeEntityPlanRefundReview).toHaveBeenCalledWith("E1","O1",command);
  expect(host.textContent).toContain("OPS1");expect(host.textContent).toContain(command.reason);
  expect(host.querySelector("input")).toBeNull();
 }finally{await act(async()=>root.unmount());client.clear();host.remove();}
});
