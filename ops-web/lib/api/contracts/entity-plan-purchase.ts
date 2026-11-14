import type {MallEvidencePurpose} from "../../types/commerce";
import type {
  EntityPlanOffer,EntityPlanOfferEdit,EntityAssistanceContext,EntityAssistedRequest,
  EntityPlanPurchase,EntityPlanConfirm,EntityPlanAction,EntityPlanVoid,
  EntityPlanEvidenceTicket,EntityPlanSignedRead,
} from "../../entity-plan-purchase-types";

/** Entity quota purchases remain distinct from store mall entitlement orders. */
export interface EntityPlanPurchaseApi {
  entityPlanAssistanceContext(entityNo:string):Promise<EntityAssistanceContext>;
  assistedEntityPlanPurchase(entityNo:string,command:EntityAssistedRequest):Promise<EntityPlanPurchase>;
  entityPlanOffers(entityNo:string):Promise<EntityPlanOffer[]>;
  saveEntityPlanOffer(entityNo:string,command:EntityPlanOfferEdit):Promise<EntityPlanOffer>;
  entityPlanPurchases(entityNo:string):Promise<EntityPlanPurchase[]>;
  /** Authorized operator page; orderNo cursor, limit1..100. No legacy100-row fallback. */
  entityPlanPurchasePage(entityNo:string,q?:{afterOrderNo?:string;limit?:number}):Promise<EntityPlanPurchase[]>;
  entityPlanPurchase(entityNo:string,orderNo:string):Promise<EntityPlanPurchase>;
  confirmEntityPlanPurchase(entityNo:string,orderNo:string,command:EntityPlanConfirm):Promise<EntityPlanAction>;
  declineEntityPlanPurchase(entityNo:string,orderNo:string,claimNo:string,reason:string):Promise<unknown>;
  recoverEntityPlanPurchase(entityNo:string,orderNo:string):Promise<EntityPlanPurchase>;
  cancelEntityPlanPurchase(entityNo:string,orderNo:string,command:EntityPlanVoid):Promise<EntityPlanPurchase>;
  reserveEntityPlanEvidence(entityNo:string,orderNo:string,purpose?:MallEvidencePurpose):Promise<EntityPlanEvidenceTicket>;
  uploadEntityPlanEvidence(entityNo:string,orderNo:string,assetKey:string,file:Blob):Promise<EntityPlanEvidenceTicket>;
  entityPlanEvidenceUrl(entityNo:string,orderNo:string,assetKey:string):Promise<EntityPlanSignedRead>;
}
