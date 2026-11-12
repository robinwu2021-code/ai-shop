// @vitest-environment happy-dom
import {afterEach,beforeEach,describe,expect,it,vi} from "vitest";
import {act,createElement as h} from "react";
import {createRoot,type Root} from "react-dom/client";
import {QueryClient,QueryClientProvider} from "@tanstack/react-query";
const calls=vi.hoisted(()=>({board:vi.fn()}));
vi.mock("@/lib/api",()=>({api:{nearPayOnboardingBoard:calls.board}}));
vi.mock("@/lib/auth",async()=>{
 const {create}=await import("zustand");
 return {useAuth:create(()=>({username:"ops",token:"session",tenantNo:"MAIN",merchantNo:"",communityNo:"",pickupNo:"",perms:["merchant:admission:read"]})),scopeOf:(a:{tenantNo:string;merchantNo:string;communityNo:string;pickupNo:string})=>({tenantNo:a.tenantNo,merchantNo:a.merchantNo,communityNo:a.communityNo,pickupNo:a.pickupNo})};
});
vi.mock("@/lib/i18n",()=>({useT:()=>(v:string)=>v,useI18n:()=>({locale:"zh",t:(v:string)=>v})}));
import {OnboardingTab} from "@/app/merchants/onboarding-tab";
import {MERCHANTS_COPY} from "@/app/merchants/copy";
import {useAuth} from "@/lib/auth";
const c=MERCHANTS_COPY.zh;
const channel={onboardingNo:"OB1",payChannel:"EZPAY",status:"CHANNEL_REVIEW",reviewStatus:"APPROVED",submitOutcome:"ACCEPTED",resultCode:"PENDING_REVIEW",handoffStatus:"WAITING",ezpayBindingStatus:"UNBOUND",reason:"channel pending",updatedAt:1000};
const row={entityNo:"E1",entityName:"Entity one",status:"APPROVED",merchantRef:"PM-1",linkStatus:"VERIFIED",profileStatus:"APPROVED",reviewReason:null,onboardings:[channel],onboardingOpsUrl:"https://pay.example/merchants/onboarding-review?tab=fiat&keyword=PM-1"};
let host:HTMLDivElement,root:Root,q:QueryClient;
beforeEach(()=>{(globalThis as typeof globalThis&{IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;vi.resetAllMocks();useAuth.setState({token:"session",username:"ops",perms:["merchant:admission:read"],merchantNo:"",communityNo:"",pickupNo:""});calls.board.mockResolvedValue({records:[row],total:21,page:1,size:20});host=document.createElement("div");document.body.append(host);root=createRoot(host);q=new QueryClient({defaultOptions:{queries:{retry:false}}});});
afterEach(async()=>{await act(async()=>root.unmount());q.clear();host.remove();});
async function settle(){await act(async()=>{await new Promise(r=>setTimeout(r,30));});}
async function mount(copy=c){await act(async()=>root.render(h(QueryClientProvider,{client:q},h(OnboardingTab,{c:copy}))));await settle();}
async function click(text:string){const b=[...host.querySelectorAll("button")].find(b=>b.textContent===text)!;expect(b).toBeDefined();await act(async()=>b.click());await settle();}
describe("NearPay onboarding board",()=>{
 it("renders source approval independently from channel, review and handoff facts",async()=>{await mount();expect(calls.board).toHaveBeenCalledWith({keyword:"",page:1,size:20});for(const text of [c.obStateApproved,c.obStateChannelReview,c.obStateVerified,"WAITING","UNBOUND","PENDING_REVIEW","channel pending"])expect(host.textContent).toContain(text);expect(host.textContent).not.toContain("canReceiveMoney");expect(host.querySelector("a")?.href).toBe(row.onboardingOpsUrl);expect(host.querySelector("a")?.rel).toBe("noopener noreferrer");expect(host.querySelector("a")?.classList.contains("focus-ring")).toBe(true);expect(host.querySelectorAll("select")).toHaveLength(0);});
 it("preserves cancelled and unknown channel states and distinguishes no application",async()=>{calls.board.mockResolvedValue({records:[{...row,onboardings:[{...channel,status:"CANCELLED",reviewStatus:"FUTURE_STATE"}]},{...row,entityNo:"E2",status:"NONE",merchantRef:null,onboardings:[],onboardingOpsUrl:null}],total:2});await mount();expect(host.textContent).toContain(c.obStateCancelled);expect(host.textContent).toContain("FUTURE_STATE");expect(host.textContent).toContain(c.obNoApplication);});
 it("shows a failure and retries a read without substituting an empty result",async()=>{calls.board.mockRejectedValueOnce(new Error("near-pay unavailable"));await mount();expect(host.textContent).not.toContain(c.obEmpty);await click("table.retry");expect(calls.board).toHaveBeenCalledTimes(2);expect(host.textContent).toContain(c.obStateChannelReview);});
 it("reloads only the read endpoint",async()=>{await mount();await click(c.obReload);expect(calls.board).toHaveBeenCalledTimes(2);});
 it("does not read without admission permission",async()=>{useAuth.setState({perms:[]});await mount();expect(calls.board).not.toHaveBeenCalled();expect(host.textContent).toBe("");});
 it("isolates a late response after switching authenticated scope",async()=>{let resolve!:(v:unknown)=>void;calls.board.mockImplementationOnce(()=>new Promise(r=>{resolve=r;}));await mount();await act(async()=>useAuth.setState({merchantNo:"E2"}));await settle();await act(async()=>resolve({records:[{...row,entityName:"OLD SCOPE"}],total:1}));await settle();expect(host.textContent).not.toContain("OLD SCOPE");expect(calls.board).toHaveBeenCalledTimes(2);});
 it("isolates a late page response after returning to the previous entity page",async()=>{let resolve!:(v:unknown)=>void;calls.board.mockResolvedValueOnce({records:[row],total:41}).mockImplementationOnce(()=>new Promise(r=>{resolve=r;})).mockResolvedValueOnce({records:[{...row,entityName:"CURRENT PAGE ONE"}],total:41});await mount();await click("common.nextPage");await click("common.prevPage");expect(calls.board).toHaveBeenLastCalledWith({keyword:"",page:1,size:20});await act(async()=>resolve({records:[{...row,entityName:"LATE PAGE TWO"}],total:41}));await settle();expect(host.textContent).toContain("CURRENT PAGE ONE");expect(host.textContent).not.toContain("LATE PAGE TWO");});

 it.each(["zh","en"] as const)("labels known states in %s while retaining unknown codes and result codes",async(locale)=>{
  const copy=MERCHANTS_COPY[locale];
  const source=["NONE","PENDING","NEED_MORE","REJECTED","APPROVED"];
  const channels=["DRAFT","SUBMITTED","CHANNEL_REVIEW","NEED_SIGN","APPROVED","REJECTED","CANCELLED","SUPERSEDED"];
  const links=["VERIFIED","VERIFY_PENDING","MISMATCH"];
  const handoffs=["PENDING","ACCEPTED","KEY_RECEIPT_MISSING"];
  const bindings=["NOT_REQUIRED","PENDING","BOUND","FAILED"];
  calls.board.mockResolvedValue({records:channels.map((status,i)=>({...row,entityNo:`E${i}`,status:source[i%source.length],linkStatus:links[i%links.length],profileStatus:source[i%source.length],onboardings:[{...channel,status,reviewStatus:"UNKNOWN_REVIEW",handoffStatus:handoffs[i%handoffs.length],ezpayBindingStatus:bindings[i%bindings.length],resultCode:"APPROVED"}]})),total:8});
  await mount(copy);
  for(const label of [copy.obStateNone,copy.obStatePending,copy.obStateNeedMore,copy.obStateRejected,copy.obStateApproved,copy.obStateDraft,copy.obStateSubmitted,copy.obStateChannelReview,copy.obStateNeedSign,copy.obStateCancelled,copy.obStateSuperseded,copy.obStateVerified,copy.obStateVerifyPending,copy.obStateMismatch,copy.obStateHandoffPending,copy.obStateAccepted,copy.obStateKeyReceiptMissing,copy.obStateNotRequired,copy.obStateBindingPending,copy.obStateBound,copy.obStateFailed,"UNKNOWN_REVIEW","APPROVED"]) expect(host.textContent).toContain(label);
 });

});
