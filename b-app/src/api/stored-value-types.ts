import type { CurrencyCode } from '@shared/types';
export interface SvPage<T> { records:T[];nextCursor:string|null;hasMore:boolean }
export interface SvContext { currency:CurrencyCode;currencyScale:number;timeZone:string }
export interface SvPlan { planNo:string;versionNo:string;revision:number;storeNo:string;name:string;currency:CurrencyCode;paidAmountMinor:number;bonusAmountMinor:number;totalAmountMinor:number;status:'ACTIVE'|'INACTIVE';createdAt:string;updatedAt:string|null }
export interface SvPlanCreate { storeNo:string;name:string;currency:CurrencyCode;paidAmountMinor:number;bonusAmountMinor:number }
export interface SvAccount { accountRef:string;maskedName:string;maskedPhone:string;currency:CurrencyCode;balanceMinor:number;heldMinor:number;availableMinor:number;lastOccurredAt:string|null;lastRechargeAt?:string|null }
export interface SvRecord { documentNo?:string|null;voucherDocumentNo?:string|null; recordNo:string;maskedName:string;maskedPhone:string;type:'RECHARGE'|'REDEMPTION';title:string;storeNo:string;storeName:string;currency:CurrencyCode;signedAmountMinor:number;balanceAfterMinor:number;occurredAt:string;businessNo:string;planNo:string|null;versionNo:string|null;paidAmountMinor:number|null;bonusAmountMinor:number|null }
export interface SvRecordsQuery { storeNo:string;type:'ALL'|'RECHARGE'|'REDEMPTION';startDate?:string;endDate?:string;accountRef?:string;cursor?:string;size?:number }
export interface SvProgress { rechargeNo:string;documentNo?:string|null;revision:number;status:string;storeNo:string;currency:CurrencyCode;paidAmountMinor:number|null;creditedTotalMinor:number|null;creditedAt:string|null;receivedAt:string|null }
export type SvRechargeCreate={storeNo:string;customerRef:string}&({scopeType?:'STORE';planNo:string;versionNo:string;optionRef?:never}|{scopeType:'BRAND';planNo:null;versionNo:null;optionRef:string});
export interface SvBrandOption {optionRef:string;activityName:string;currency:CurrencyCode;paidAmountMinor:number;bonusAmountMinor:number;totalAmountMinor:number}
export type SvRechargeOffer=SvPlan|SvBrandOption;
export function isBrandOffer(offer:SvRechargeOffer):offer is SvBrandOption{return 'optionRef' in offer;}
export function offerKey(offer:SvRechargeOffer){return isBrandOffer(offer)?offer.optionRef:offer.planNo;}
export function offerName(offer:SvRechargeOffer){return isBrandOffer(offer)?offer.activityName:offer.name;}
export interface SvWallet {scopeType:'STORE'|'BRAND';issuerRef:string;name:string;currency:CurrencyCode;accountRef:string|null;account:{balanceMinor:number;heldMinor:number;availableMinor:number}|null;canRecharge:boolean;canRedeem:boolean;unavailableReason:string|null}

export interface SvReceipt { expectedRevision:number;actualAmountMinor:number;collectionMethod:'CASH'|'TRANSFER';receiptNo:string }
export interface SvReceive { expectedRevision:number;receiptNo:string;confirmationNote:string }
export interface SvCustomer { customerRef:string;maskedName:string;expiresAt:string }

export type SvSelectionSource={type:'MEMBER';memberNo:string;memberScope?:'OPERATING'}|{type:'ACCOUNT';accountRef:string};
export interface SvCandidate {memberNo:string;displayName:string;phoneMasked:string;memberStatus:string;canRecharge:boolean;unavailableReason:string|null;account:{accountRef:string;currency:CurrencyCode;availableMinor:number}|null}
export interface SvSelectedCustomer {customerRef:string;expiresAt:string;pendingScope?:string;sourceStoreNo?:string|null;sourceStoreName?:string|null;memberNo?:string|null;displayName:string;phoneMasked:string;account:SvCandidate['account']}

export interface ScopeMemberCandidate extends Omit<SvCandidate,'memberNo'> {memberNo:string|null;sourceStoreNo:string|null;sourceStoreName:string|null;lastRechargeAt?:string|null}
export interface ScopeCandidatePage {items:ScopeMemberCandidate[];nextCursor:string|null;identityConflictCount:number}
export type ScopeSelectionSource={type:'MEMBER';memberNo:string}|{type:'ACCOUNT';accountRef:string}|{type:'MEMBER_CODE';code:string};
export interface ScopeSelectedCustomer {selection:SvSelectedCustomer;sourceStoreNo:string|null;sourceStoreName:string|null}
export interface ScopeMemberSummary {scopeMemberNo:string;displayName:string;phoneMasked:string;status:string;sourceStoreNo:string;sourceStoreName:string;remark:string|null}

export interface SvServiceView {
 serviceOrderNo:string;operationNo:string;status:string;storeNo:string;storeName:string;serviceStaffName:string|null;
 items:Array<{itemNo:string;title:string;quantity:number}>;serviceAt:string|null;
 confirmation:{orderRevision:number;quoteRef:string;selectionDigest:string;expiresAt:string}|null;
 payment:{assetKind:'STORED_VALUE';currency:CurrencyCode;amountMinor:number;scopeType:'STORE'|'BRAND';issuerName:string|null}|null;
}
export interface SvConsumptionCreate {storeNo:string;customerRef:string;items:Array<{skuNo:string;quantity:number}>;issuerRef:string;accountRef:string}
export interface SvConsumptionPreview {service:SvServiceView;quote:{amountMinor:number;applicableMinor:number;currency:CurrencyCode;redeemable:boolean;reason:string;scopeType:'STORE'|'BRAND';issuerName:string|null;expiresAt:string}}
export interface SvOperation {operationNo:string;status:string;usageNo:string|null}
