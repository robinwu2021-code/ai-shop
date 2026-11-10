/** Entity quota contracts, intentionally distinct from store mall entitlements. */
export type PlanMoney = {currency:string;minor:number};
export type EntityPlanOffer={offerNo:string;version:number;planCode:string;name:string;days:number;price:PlanMoney;storeQuota:number;staffQuota:number;crossStoreStats:boolean;enabled:boolean};
export type EntityPlanOfferEdit={offerNo:string;expectedVersion:number;planCode:string;name:string;days:number;price:PlanMoney;enabled:boolean};
export type EntityPlanClaim={claimNo:string;amount:PlanMoney;paidAt:string;bankReference:string;evidenceKey:string};
export type EntityPlanConfirm=Omit<EntityPlanClaim,"claimNo">&{actionNo:string;claimNo:string;reason:string};
export type EntityPlanVoid={actionNo:string;reference:string;evidenceKey:string;reason:string};
export type EntityPlanAction={actionNo:string;status:string;paymentNo:string|null;reason:string};
export type EntityPlanPurchase={orderNo:string;requestNo:string;entityNo:string;storeNo:string;offer:EntityPlanOffer;status:string;dueAt:string;instructions:null|{platformMerchantNo:string;instructions:Record<string,string>};claims:{claimNo:string;status:string;declaration:EntityPlanClaim;reviewNote:string|null}[];actions:EntityPlanAction[]};
export interface EntityPlanPurchaseApi {
 entityPlanOffers(entityNo:string):Promise<EntityPlanOffer[]>;
 saveEntityPlanOffer(entityNo:string,command:EntityPlanOfferEdit):Promise<EntityPlanOffer>;
 entityPlanPurchases(entityNo:string):Promise<EntityPlanPurchase[]>;
 entityPlanPurchase(entityNo:string,orderNo:string):Promise<EntityPlanPurchase>;
 confirmEntityPlanPurchase(entityNo:string,orderNo:string,command:EntityPlanConfirm):Promise<EntityPlanAction>;
 declineEntityPlanPurchase(entityNo:string,orderNo:string,claimNo:string,reason:string):Promise<unknown>;
 recoverEntityPlanPurchase(entityNo:string,orderNo:string):Promise<EntityPlanPurchase>;
 cancelEntityPlanPurchase(entityNo:string,orderNo:string,command:EntityPlanVoid):Promise<EntityPlanPurchase>;
 reserveEntityPlanEvidence(entityNo:string,orderNo:string):Promise<{assetKey:string}>;
 uploadEntityPlanEvidence(entityNo:string,orderNo:string,assetKey:string,file:Blob):Promise<{assetKey:string}>;
 entityPlanEvidenceUrl(entityNo:string,orderNo:string,assetKey:string):Promise<{url:string}>;
}
