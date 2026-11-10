/** Entity quota contracts, intentionally distinct from store mall entitlements. */
export type PlanMoney = {currency:string;minor:number};
export type EntityPlanOffer={offerNo:string;version:number;planCode:string;name:string;days:number;price:PlanMoney;storeQuota:number;staffQuota:number;crossStoreStats:boolean;enabled:boolean};
export type EntityPlanOfferEdit={offerNo:string;expectedVersion:number;planCode:string;name:string;days:number;price:PlanMoney;enabled:boolean};
export type EntityPlanClaim={claimNo:string;amount:PlanMoney;paidAt:string;bankReference:string;evidenceKey:string};
export type EntityPlanConfirm=Omit<EntityPlanClaim,"claimNo">&{actionNo:string;claimNo:string|null;reason:string};
export type EntityPlanVoid={actionNo:string;reference:string;evidenceKey:string;reason:string};
export type EntityPlanAction={actionNo:string;status:string;paymentNo:string|null;reason:string};
export type EntityPlanPurchase={orderNo:string;requestNo:string;entityNo:string;storeNo:string;offer:EntityPlanOffer;status:string;purchaseSource?:string;dueAt:string;instructions:null|{platformMerchantNo:string;instructions:Record<string,string>};claims:{claimNo:string;status:string;declaration:EntityPlanClaim;reviewNote:string|null}[];actions:EntityPlanAction[]};
export type EntityAssistedRequest={requestNo:string;offerNo:string;offerVersion:number;storeNo:string;ownerAccountNo:string};
export type EntityAssistanceContext={owners:{accountNo:string;displayName:string;subjectKind:string}[];stores:{storeNo:string;name:string}[]};
export type EntityPlanEvidenceTicket={assetKey:string};
export type EntityPlanSignedRead={url:string};
export type {EntityPlanPurchaseApi} from "./api/contracts/entity-plan-purchase";
