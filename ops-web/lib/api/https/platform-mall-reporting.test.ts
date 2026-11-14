import {afterEach,expect,it,vi} from "vitest";
vi.mock("../../auth",()=>({currentAuth:()=>({token:"TOKEN"}),scopeOf:()=>({tenantNo:"MAIN"}),refreshPerms:vi.fn(),useAuth:{getState:()=>({logout:vi.fn()})}}));
vi.mock("../../notify",()=>({notify:{error:vi.fn()}}));
vi.mock("../../stores/locale",()=>({useLocaleStore:{getState:()=>({locale:"zh"})}}));
import {commerceHttp} from "./commerce";
import {commerceMock} from "../mocks/commerce";
afterEach(()=>vi.unstubAllGlobals());
it("queries global orders and pending claims without a fabricated store or writes",async()=>{
 const fetch=vi.fn().mockResolvedValue({ok:true,status:200,json:async()=>({code:0,data:{items:[],nextCursor:null,includedModels:["ENTITY"],queriedAt:1}})});vi.stubGlobal("fetch",fetch);
 await commerceHttp.platformMallOrders({entityNo:"E/1",after:"opaque+/=",limit:20});
 await commerceHttp.platformMallClaims({model:"ENTITY",currency:"TWD"});
 expect(fetch.mock.calls[0][0]).toContain("/ops/platform-mall/orders?");expect(fetch.mock.calls[0][0]).toContain("entityNo=E%2F1");expect(fetch.mock.calls[0][0]).toContain("after=opaque%2B%2F%3D");
 expect(fetch.mock.calls[1][0]).toContain("/ops/platform-mall/claims?");expect(fetch.mock.calls.every(c=>!c[1].body)).toBe(true);
});
it("preserves exact minor strings in full-window cash summary and detail",async()=>{
 const data={summary:[{model:"STORE",currency:"TWD",classification:"NORMAL",receivedMinor:"9007199254740993",refundedMinor:"1",netMinor:"9007199254740992",receiptCount:2,refundCount:1}],items:[],nextCursor:null,queriedAt:1,coverage:"CONSISTENT_PER_REQUEST"};
 const fetch=vi.fn().mockResolvedValue({ok:true,status:200,json:async()=>({code:0,data})});vi.stubGlobal("fetch",fetch);
 const r=await commerceHttp.platformMallCash({fromInclusive:1,toExclusive:2});expect(r.summary[0].receivedMinor).toBe("9007199254740993");expect(fetch.mock.calls[0][0]).toContain("fromInclusive=1&toExclusive=2");
});
it("keeps report errors visible and mock explicitly unavailable",async()=>{
 vi.stubGlobal("fetch",vi.fn().mockResolvedValue({ok:false,status:503,json:async()=>({code:10503,msg:"unavailable"})}));
 await expect(commerceHttp.platformMallCash({fromInclusive:1,toExclusive:2})).rejects.toThrow();
 await expect(commerceMock.platformMallOrders({})).rejects.toThrow();await expect(commerceMock.platformMallClaims({})).rejects.toThrow();await expect(commerceMock.platformMallCash({fromInclusive:1,toExclusive:2})).rejects.toThrow();
});
