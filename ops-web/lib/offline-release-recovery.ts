import type {CommerceApi} from "./api/contracts/commerce";
import type {EntityPlanPurchaseApi} from "./entity-plan-purchase-types";

type ReleaseRecoveryApi=Pick<CommerceApi,"offlineAction"|"recoverOfflineOrder">&Pick<EntityPlanPurchaseApi,"entityPlanPurchase"|"recoverEntityPlanPurchase">;
/** Read the original action, advance its owning workflow, then read that same action again. */
export async function lookupOfflineRelease(api:ReleaseRecoveryApi,kind:"entity"|"mall",scope:string,orderNo:string,actionNo:string,onAccepted:()=>void){
 const read=async()=>kind==="entity"
  ?(await api.entityPlanPurchase(scope,orderNo)).actions.find(a=>a.actionNo===actionNo)??null
  :api.offlineAction(scope,orderNo,actionNo);
 const action=await read();
 if(action?.status!=="ACCEPTED")return action;
 // Remember acceptance before recovery: a timeout/stale subsequent read must not permit resubmission.
 onAccepted();
 if(kind==="entity")await api.recoverEntityPlanPurchase(scope,orderNo);
 else await api.recoverOfflineOrder(scope,orderNo);
 return read();
}
