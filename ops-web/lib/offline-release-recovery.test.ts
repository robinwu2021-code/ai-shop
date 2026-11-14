import {expect,it,vi} from "vitest";
import {lookupOfflineRelease} from "./offline-release-recovery";
import type {EntityPlanPurchase} from "./entity-plan-purchase-types";
const entityOrder=(status:string)=>({actions:[{actionNo:"ORIGINAL",status,paymentNo:null,reason:"bank reconciliation"}]} as EntityPlanPurchase);
const mocks=()=>({
 entityPlanPurchase:vi.fn().mockResolvedValueOnce(entityOrder("ACCEPTED")).mockResolvedValueOnce(entityOrder("APPLIED")),
 recoverEntityPlanPurchase:vi.fn().mockResolvedValue(entityOrder("APPLIED")),
 offlineAction:vi.fn().mockResolvedValueOnce({actionNo:"ORIGINAL",status:"ACCEPTED",paymentNo:null,reason:null}).mockResolvedValueOnce({actionNo:"ORIGINAL",status:"APPLIED",paymentNo:null,reason:null}),
 recoverOfflineOrder:vi.fn().mockResolvedValue({recovered:true}),
});
it.each(["entity","mall"] as const)("accepted %s release recovers the owning order and re-reads the identical action",async kind=>{
 const api=mocks(),accepted=vi.fn();const result=await lookupOfflineRelease(api,kind,"SCOPE","ORDER","ORIGINAL",accepted);
 expect(result?.status).toBe("APPLIED");expect(accepted).toHaveBeenCalledOnce();
 if(kind==="entity"){
  expect(api.recoverEntityPlanPurchase).toHaveBeenCalledExactlyOnceWith("SCOPE","ORDER");expect(api.entityPlanPurchase.mock.calls).toEqual([["SCOPE","ORDER"],["SCOPE","ORDER"]]);expect(api.recoverOfflineOrder).not.toHaveBeenCalled();
 }else{
  expect(api.recoverOfflineOrder).toHaveBeenCalledExactlyOnceWith("SCOPE","ORDER");expect(api.offlineAction.mock.calls).toEqual([["SCOPE","ORDER","ORIGINAL"],["SCOPE","ORDER","ORIGINAL"]]);expect(api.recoverEntityPlanPurchase).not.toHaveBeenCalled();
 }
});
it("remembers acceptance before a failed recovery and does not invent completion",async()=>{
 const api=mocks(),accepted=vi.fn();api.recoverOfflineOrder.mockRejectedValue(new Error("timeout"));await expect(lookupOfflineRelease(api,"mall","S","O","ORIGINAL",accepted)).rejects.toThrow("timeout");expect(accepted).toHaveBeenCalledOnce();expect(api.offlineAction).toHaveBeenCalledOnce();
});
it.each([null,{actionNo:"ORIGINAL",status:"REJECTED",paymentNo:null,reason:"BANK_CHECK_FAILED"}])("absent or terminal release is read without invoking recovery",async action=>{
 const api=mocks(),accepted=vi.fn();api.offlineAction.mockReset().mockResolvedValue(action);expect(await lookupOfflineRelease(api,"mall","S","O","ORIGINAL",accepted)).toEqual(action);expect(api.recoverOfflineOrder).not.toHaveBeenCalled();expect(accepted).not.toHaveBeenCalled();
});
