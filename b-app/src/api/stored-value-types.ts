import type { CurrencyCode } from '@shared/types';
export interface SvPage<T> { records:T[];nextCursor:string|null;hasMore:boolean }
export interface SvContext { currency:CurrencyCode;currencyScale:number;timeZone:string }
export interface SvPlan { planNo:string;versionNo:string;revision:number;storeNo:string;name:string;currency:CurrencyCode;paidAmountMinor:number;bonusAmountMinor:number;totalAmountMinor:number;status:'ACTIVE'|'INACTIVE';createdAt:string;updatedAt:string|null }
export interface SvPlanCreate { storeNo:string;name:string;currency:CurrencyCode;paidAmountMinor:number;bonusAmountMinor:number }
export interface SvAccount { accountRef:string;maskedName:string;maskedPhone:string;currency:CurrencyCode;balanceMinor:number;heldMinor:number;availableMinor:number;lastOccurredAt:string|null }
export interface SvRecord { recordNo:string;maskedName:string;maskedPhone:string;type:'RECHARGE'|'REDEMPTION';title:string;storeNo:string;storeName:string;currency:CurrencyCode;signedAmountMinor:number;balanceAfterMinor:number;occurredAt:string;businessNo:string;planNo:string|null;versionNo:string|null;paidAmountMinor:number|null;bonusAmountMinor:number|null }
export interface SvRecordsQuery { storeNo:string;type:'ALL'|'RECHARGE'|'REDEMPTION';startDate?:string;endDate?:string;accountRef?:string;cursor?:string;size?:number }
export interface SvProgress { rechargeNo:string;revision:number;status:string;storeNo:string;currency:CurrencyCode;paidAmountMinor:number|null;creditedTotalMinor:number|null;creditedAt:string|null;receivedAt:string|null }
export interface SvRechargeCreate { storeNo:string;customerRef:string;planNo:string;versionNo:string }
export interface SvReceipt { expectedRevision:number;actualAmountMinor:number;collectionMethod:'CASH'|'TRANSFER';receiptNo:string }
export interface SvReceive { expectedRevision:number;receiptNo:string;confirmationNote:string }
export interface SvCustomer { customerRef:string;maskedName:string;expiresAt:string }
