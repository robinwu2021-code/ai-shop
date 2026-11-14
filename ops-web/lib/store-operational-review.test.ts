// @vitest-environment happy-dom
import {afterEach,beforeEach,expect,it,vi} from "vitest";
import {act,createElement as h} from "react";
import {createRoot,type Root} from "react-dom/client";
import {QueryClient,QueryClientProvider} from "@tanstack/react-query";
const s=vi.hoisted(()=>({read:vi.fn(),submit:vi.fn(),decide:vi.fn(),confirm:vi.fn(),canAudit:true}));
vi.mock("@/lib/api",()=>({api:{getStoreOperationalReview:s.read,submitStoreOperationalReview:s.submit,decideStoreOperationalReview:s.decide}}));
vi.mock("@/lib/use-can",()=>({useCan:()=>()=>s.canAudit}));
vi.mock("@/lib/i18n",()=>({useI18n:()=>({locale:"en",t:(key:string)=>key})}));
vi.mock("@/components/ui/confirm-dialog",()=>({useConfirm:()=>({confirm:s.confirm,dialog:null})}));
vi.mock("@/components/ui/drawer",()=>({DrawerSection:({children}:{children:unknown})=>h("section",{},children as never),Field:({label,children}:{label:string;children:unknown})=>h("div",{},label,children as never)}));
import {OperationalReview} from "@/app/merchants/operational-review";
import {storeHttp} from "@/lib/api/https/store";
import {client} from "@/lib/api/http-client";
vi.mock("@/lib/api/http-client",()=>({client:{get:vi.fn(),post:vi.fn()}}));
let root:Root,host:HTMLDivElement,q:QueryClient;
const historic={storeNo:"S1",status:"ACTIVE",review:null,source:null,orderNo:null,decidedAt:null,submittedAt:null};
const pending={...historic,status:"PENDING_OPEN",review:"PENDING",submittedAt:1};
async function settle(){await act(async()=>{await new Promise(r=>setTimeout(r,15));});}
async function mount(){await act(async()=>root.render(h(QueryClientProvider,{client:q},h(OperationalReview,{storeNo:"S1"}))));await settle();}
function button(text:string){return [...host.querySelectorAll("button")].find(b=>b.textContent===text)!;}
beforeEach(()=>{
  (globalThis as typeof globalThis&{IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;
  vi.clearAllMocks();s.canAudit=true;s.read.mockResolvedValue(historic);s.submit.mockResolvedValue(pending);
  host=document.createElement("div");document.body.append(host);root=createRoot(host);q=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});
});
afterEach(async()=>{await act(async()=>root.unmount());q.clear();host.remove();});
it("requires an explicit confirmation before pausing an unsubmitted store",async()=>{
  await mount();expect(host.textContent).toContain("No operating review submitted");
  await act(async()=>button("Submit review").click());expect(s.submit).not.toHaveBeenCalled();
  const options=s.confirm.mock.calls[0]![0];expect(options.desc).toContain("pauses store operations");
  await act(async()=>options.action(""));await settle();expect(s.submit).toHaveBeenCalledWith("S1");expect(host.textContent).toContain("Awaiting activation");
});
it("uses scoped store, decision and mandatory reason through the shared dialog",async()=>{
  s.read.mockResolvedValue(pending);s.decide.mockResolvedValue({...pending,review:"APPROVED",status:"ACTIVE",source:"MANUAL"});await mount();
  await act(async()=>button("Approve").click());const options=s.confirm.mock.calls[0]![0];expect(options.requireReason).toBe(true);expect(options.reasonHint).toBe("Reason (recorded in the platform audit log)");
  await act(async()=>options.action("checked test evidence"));await settle();expect(s.decide).toHaveBeenCalledWith("S1",true,"checked test evidence");expect(host.textContent).toContain("Manual review");
});
it("does not expose write controls without audit authority",async()=>{
  s.canAudit=false;s.read.mockResolvedValue(pending);await mount();expect(host.querySelector("button")).toBeNull();expect(s.decide).not.toHaveBeenCalled();
});
it("does not turn read failure into an approval or a new submission",async()=>{
  s.read.mockRejectedValue(new Error("denied"));await mount();expect(host.querySelector('[role="alert"]')?.textContent).toContain("Could not load");expect(button("Submit review")).toBeUndefined();expect(s.submit).not.toHaveBeenCalled();
});
it("uses only the dedicated operating review endpoints",async()=>{
  await storeHttp.getStoreOperationalReview("S1");await storeHttp.submitStoreOperationalReview("S1");await storeHttp.decideStoreOperationalReview("S1",false,"incomplete");
  expect(client.get).toHaveBeenCalledWith("/ops/stores/S1/operational-review");expect(client.post).toHaveBeenCalledWith("/ops/stores/S1/operational-review/submit");expect(client.post).toHaveBeenCalledWith("/ops/stores/S1/operational-review/decide",{approved:false,reason:"incomplete"});
});
