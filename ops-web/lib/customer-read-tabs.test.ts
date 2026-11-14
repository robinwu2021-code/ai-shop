// @vitest-environment happy-dom
import {afterEach,beforeEach,expect,it,vi} from "vitest";
import {act,createElement as h} from "react";
import {createRoot,type Root} from "react-dom/client";
import {QueryClient,QueryClientProvider} from "@tanstack/react-query";
const s=vi.hoisted(()=>({users:vi.fn(),members:vi.fn(),detail:vi.fn(),brands:vi.fn(),stores:vi.fn(),query:"",perms:new Set<string>()}));
vi.mock("@/lib/api",()=>({api:{customerRegistrations:s.users,platformMembers:s.members,platformMember:s.detail,customerBrands:s.brands,customerRegistrationStores:s.stores}}));
vi.mock("@/lib/use-reporting-identity",()=>({useReportingIdentity:()=>["MAIN","OPS1",1]}));
vi.mock("next/navigation",()=>({useSearchParams:()=>new URLSearchParams(s.query)}));
vi.mock("@/lib/use-can",()=>({useCan:()=>(p:string)=>s.perms.has(p)}));
vi.mock("@/lib/use-copy",()=>({useCopy:(c:{zh:unknown})=>c.zh}));
vi.mock("@/lib/i18n",()=>({useI18n:()=>({locale:"zh",t:(k:string)=>k}),useT:()=>(k:string)=>k}));
vi.mock("@/components/ui/drawer",()=>({Drawer:({open,children}:{open:boolean;children:unknown})=>open?h("section",{"data-testid":"detail"},children as never):null,DrawerSection:({children}:{children:unknown})=>h("div",null,children as never),FieldGrid:({children}:{children:unknown})=>h("div",null,children as never),Field:({label,children}:{label:string;children:unknown})=>h("div",null,label,children as never)}));
import {RegistrationsTab,PlatformMembersTab,BrandsTab} from "@/app/customers/read-tabs";
let root:Root,host:HTMLDivElement,client:QueryClient;
async function mount(component:typeof RegistrationsTab){await act(async()=>root.render(h(QueryClientProvider,{client},h(component))));await act(async()=>{await new Promise(r=>setTimeout(r,25));});}
beforeEach(()=>{Object.assign(globalThis,{IS_REACT_ACT_ENVIRONMENT:true});vi.clearAllMocks();s.query="";s.perms=new Set();host=document.createElement("div");document.body.append(host);root=createRoot(host);client=new QueryClient({defaultOptions:{queries:{retry:false}}});s.stores.mockResolvedValue([]);});
afterEach(async()=>{await act(async()=>root.unmount());client.clear();host.remove();});
it("shows authoritative registration totals and includes an account with no store",async()=>{
 s.users.mockResolvedValue({page:{records:[{userNo:"U0",nickname:"Unbuilt account",status:"NORMAL",market:"TW",registeredAt:1000,entityCount:0,storeCount:0}],total:4,page:1,size:20},total:4,withoutStores:1,newLast30Days:2,trend:[{date:"2026-11-14",count:2}]});await mount(RegistrationsTab);
 expect(host.textContent).toContain("Unbuilt account");expect(host.textContent).toContain("尚未关联门店");expect(host.textContent).toContain("2026-11-14");expect(s.users).toHaveBeenCalledWith(expect.objectContaining({page:1}));
});
it("displays distinct actual person IDs sharing a phone tail and preserves original source while filtering later stores",async()=>{
 s.query="storeNo=S2&brandNo=B1";const person={personNo:"PS1",phoneTail:"1234",status:"ACTIVE",joinedAt:1000,membershipCount:2,sourceType:"SCAN",sourceStoreNo:"S1",sourceStoreName:"Original store"};s.members.mockResolvedValue({records:[person,{...person,personNo:"PS2",sourceStoreNo:null,sourceStoreName:null}],total:2,page:1,size:20});s.detail.mockResolvedValue({person,memberships:[],sources:[{sourceNo:"SRC2",memberNo:"M1",sourceType:"ORDER",storeNo:"S2",storeName:"Later store",occurredAt:2000,first:false}],stores:[]});await mount(PlatformMembersTab);
 expect(s.members).toHaveBeenCalledWith(expect.objectContaining({storeNo:"S2",brandNo:"B1"}));expect(host.textContent).toContain("PS1");expect(host.textContent).toContain("PS2");expect(host.textContent).toContain("Original store");expect(host.textContent).toContain("未知门店来源");
 await act(async()=>[...host.querySelectorAll("button")].find(b=>b.textContent==="PS1")!.click());await act(async()=>{await new Promise(r=>setTimeout(r,20));});expect(host.textContent).toContain("Later store");expect(host.textContent).toContain("Original store");
});
it("renders failed brand reads as errors instead of zero-count success",async()=>{
 s.brands.mockRejectedValue(new Error("brand query unavailable"));await mount(BrandsTab);expect(host.textContent).toContain("table.errorTitle");expect(host.textContent).not.toContain("没有符合条件的数据");
});
