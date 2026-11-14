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
it("assisted purchase sends exact owner/store/offer without pretending money arrived",async()=>{
 const fetch=vi.fn().mockResolvedValue({ok:true,status:200,json:async()=>({code:0,data:{orderNo:"O1",status:"AWAITING_PAYMENT",purchaseSource:"OPERATOR"}})});vi.stubGlobal("fetch",fetch);
 const cmd={requestNo:"R1",offerNo:"F1",offerVersion:7,storeNo:"S1",ownerAccountNo:"OWNER1"};
 await merchantHttp.entityPlanAssistanceContext("E/1");const r=await merchantHttp.assistedEntityPlanPurchase("E/1",cmd);expect(r.status).toBe("AWAITING_PAYMENT");expect(fetch.mock.calls[0][0]).toContain("/E%2F1/assistance-context");expect(fetch.mock.calls[1][0]).toContain("/E%2F1/assisted");expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual(cmd);
 await merchantHttp.confirmEntityPlanPurchase("E/1","O1",{actionNo:"ACT",claimNo:null,amount:{currency:"TWD",minor:15000},paidAt:"2026-11-11T00:00:00Z",bankReference:"REAL_BANK",evidenceKey:"REAL_PROOF",reason:"checked"});expect(JSON.parse(fetch.mock.calls[2][1].body).claimNo).toBeNull();
});
it("entity refund proof and release proof reserve distinct private purposes",async()=>{
 const fetch=vi.fn().mockResolvedValue({ok:true,status:200,json:async()=>({code:0,data:{assetKey:"K1"}})});vi.stubGlobal("fetch",fetch);
 await merchantHttp.reserveEntityPlanEvidence("E1","O1","REFUND_PROOF");await merchantHttp.reserveEntityPlanEvidence("E1","O1","RECONCILIATION");expect(fetch.mock.calls[0][0]).toContain("purpose=REFUND_PROOF");expect(fetch.mock.calls[1][0]).toContain("purpose=RECONCILIATION");
});
it("entity cash refund writes preserve original refund/action and external bank return",async()=>{
 const fetch=vi.fn().mockResolvedValue({ok:true,status:200,json:async()=>({code:0,data:{refundNo:"cash-R1",status:"PAYOUT_ACCEPTED"}})});vi.stubGlobal("fetch",fetch);
 const request={refundNo:"cash-R1",paymentNo:"P1",reason:"duplicate cash"};const payout={actionNo:"A1",actualAmount:{currency:"TWD",minor:"10001"},paidOutAt:"2026-11-11T00:00:00Z",payeeReference:"VERIFIED_ORIGINAL_PAYER",bankReference:"ACTUAL_BANK_RETURN",evidenceKey:"PRIVATE_PROOF"};
 await merchantHttp.entityCashReceipts("E/1","O/1");await merchantHttp.entityCashRefunds("E/1","O/1");await merchantHttp.requestEntityCashRefund("E/1","O/1",request);await merchantHttp.payoutEntityCashRefund("E/1","O/1","cash-R1",payout);await merchantHttp.recoverEntityCashRefund("E/1","O/1","cash-R1");
 expect(fetch.mock.calls[0][0]).toContain("/E%2F1/O%2F1/cash-receipts");expect(fetch.mock.calls[1][1].body).toBeUndefined();expect(JSON.parse(fetch.mock.calls[2][1].body)).toEqual(request);expect(JSON.parse(fetch.mock.calls[3][1].body)).toEqual(payout);expect(fetch.mock.calls[4][0]).toContain("/cash-refunds/cash-R1/recover");expect(JSON.parse(fetch.mock.calls[4][1].body)).toEqual({});
});
it("pages one exact entity without falling back to the legacy capped list", async () => {
 const fetch=vi.fn().mockResolvedValue({ok:true,status:200,json:async()=>({code:0,data:[]})});vi.stubGlobal("fetch",fetch);
 await merchantHttp.entityPlanPurchasePage("E/1",{afterOrderNo:"EQ/20",limit:20});
 expect(fetch.mock.calls[0][0]).toContain("/ops/merchant-plan-purchases/E%2F1");
 expect(fetch.mock.calls[0][0]).toContain("afterOrderNo=EQ%2F20");expect(fetch.mock.calls[0][0]).toContain("limit=20");
});
it("keeps paged entity purchase errors visible instead of returning a mock empty list", async () => {
 const fetch=vi.fn().mockResolvedValue({ok:false,status:403,json:async()=>({code:10403,msg:"Forbidden"})});vi.stubGlobal("fetch",fetch);
 await expect(merchantHttp.entityPlanPurchasePage("E1")).rejects.toThrow();expect(fetch).toHaveBeenCalledTimes(1);
});
