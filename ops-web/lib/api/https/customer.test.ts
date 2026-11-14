import {afterEach,expect,it,vi} from "vitest";
vi.mock("../../auth",()=>({currentAuth:()=>({token:"TOKEN"}),scopeOf:()=>({tenantNo:"MAIN"}),refreshPerms:vi.fn(),useAuth:{getState:()=>({logout:vi.fn()})}}));
vi.mock("../../notify",()=>({notify:{error:vi.fn()}}));
vi.mock("../../stores/locale",()=>({useLocaleStore:{getState:()=>({locale:"zh"})}}));
import {customerHttp} from "./customer";
import {customerMock} from "../mocks/customer";
afterEach(()=>vi.unstubAllGlobals());
it("uses readonly customer resources and preserves true identifiers and relation filters",async()=>{
 const fetch=vi.fn().mockResolvedValue({ok:true,status:200,json:async()=>({code:0,data:{records:[],total:0,page:1,size:20}})});vi.stubGlobal("fetch",fetch);
 await customerHttp.platformMembers({keyword:"1234",storeNo:"S/1",brandNo:"B+1",page:2,size:20});await customerHttp.platformMember("PS/1");await customerHttp.customerRegistrationStores("U/1");await customerHttp.customerRegistrations({keyword:"owner"});await customerHttp.customerBrands({page:3});
 expect(fetch.mock.calls[0][0]).toContain("/ops/customers/members?keyword=1234&storeNo=S%2F1&brandNo=B%2B1&page=2&size=20");expect(fetch.mock.calls[1][0]).toContain("/ops/customers/members/PS%2F1");expect(fetch.mock.calls[2][0]).toContain("/ops/customers/users/U%2F1/stores");
 expect(fetch.mock.calls.every(c=>(c[1].method??"GET")==="GET"&&!c[1].body)).toBe(true);
});
it("preserves authoritative source store and never derives identity from phone tail",async()=>{
 const data={records:[{personNo:"P1",phoneTail:"1234",sourceStoreNo:"S1"},{personNo:"P2",phoneTail:"1234",sourceStoreNo:null}],total:2,page:1,size:20};vi.stubGlobal("fetch",vi.fn().mockResolvedValue({ok:true,status:200,json:async()=>({code:0,data})}));
 const result=await customerHttp.platformMembers();expect(result.records.map(p=>p.personNo)).toEqual(["P1","P2"]);expect(result.records[1].sourceStoreNo).toBeNull();
});
it("keeps failures visible and never fills a missing live customer source with mock facts",async()=>{
 vi.stubGlobal("fetch",vi.fn().mockResolvedValue({ok:false,status:503,json:async()=>({code:10503,msg:"unavailable"})}));await expect(customerHttp.customerRegistrations()).rejects.toThrow();await expect(customerMock.platformMembers()).rejects.toMatchObject({code:400,message:expect.stringContaining("客户报表需要连接真实后端")});
});
