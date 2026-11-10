import {afterEach,expect,it,vi} from "vitest";
vi.mock("../../auth",()=>({currentAuth:()=>({token:"TOKEN"}),scopeOf:()=>({tenantNo:"MAIN"}),refreshPerms:vi.fn(),useAuth:{getState:()=>({logout:vi.fn()})}}));
vi.mock("../../notify",()=>({notify:{error:vi.fn()}}));
vi.mock("../../stores/locale",()=>({useLocaleStore:{getState:()=>({locale:"zh"})}}));
import {merchantHttp} from "./merchant";
afterEach(()=>vi.unstubAllGlobals());
it("entity quota confirmation preserves original action and never accepts client recipient",async()=>{
 const fetch=vi.fn().mockResolvedValue({ok:true,status:200,json:async()=>({code:0,data:{actionNo:"A1",status:"APPLIED"}})});vi.stubGlobal("fetch",fetch);
 const command={actionNo:"A1",claimNo:"C1",amount:{currency:"TWD",minor:19900},paidAt:"2026-11-11T00:00:00Z",bankReference:"BANK1",evidenceKey:"K1",reason:"checked"};
 await merchantHttp.confirmEntityPlanPurchase("E1","EQ1",command);
 expect(fetch.mock.calls[0][0]).toContain("/ops/merchant-plan-purchases/E1/EQ1/confirm");expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual(command);
});
it("entity quota evidence uses private raw bytes and scoped encoded keys",async()=>{
 const fetch=vi.fn().mockResolvedValue({ok:true,status:200,json:async()=>({code:0,data:{assetKey:"E1/S1/K"}})});vi.stubGlobal("fetch",fetch);
 const body=new Blob([new Uint8Array([137,80,78,71])],{type:"image/png"});await merchantHttp.uploadEntityPlanEvidence("E1","EQ1","E1/S1/K",body);
 expect(fetch.mock.calls[0][0]).toContain("assetKey=E1%2FS1%2FK");expect(fetch.mock.calls[0][1].body).toBe(body);
});
