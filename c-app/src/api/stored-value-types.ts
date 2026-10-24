import type { CurrencyCode } from "@shared/types";
export type StoredValueType = "ALL" | "RECHARGE" | "REDEMPTION";
export interface StoredValueStore {
  storeNo: string; storeName: string; lastOccurredAt: string;
  lastType: Exclude<StoredValueType, "ALL">;
}
export interface StoredValueStoresQuery { cursor?: string; size?: number }
export interface StoredValueStoresPage {
  records: StoredValueStore[]; nextCursor: string | null; hasMore: boolean;
}
export interface StoredValueRecordsQuery extends StoredValueStoresQuery {
  type?: StoredValueType; startDate?: string; endDate?: string; focusDocumentNo?: string;
}
export interface StoredValueRecord {
  type: Exclude<StoredValueType, "ALL">; title: string; currency: CurrencyCode;
  signedAmountMinor: number; occurredAt: string; documentNo: string | null; storeNo?:string|null;storeName?:string|null;
}
export interface StoredValueRecordsPage {
  issuer?:StoredValueIssuer;
  store: { storeNo: string; storeName: string; businessTimeZone: string };
  filter: { type: StoredValueType; startDate: string | null; endDate: string | null };
  records: StoredValueRecord[]; nextCursor: string | null; hasMore: boolean;
}
export interface StoredValueNotificationCapability {
  channel: "WXSUB"; available: boolean; templateId: string | null; reasonCode: string | null;
}

export interface StoredValueIssuer {issuerRef:string;scopeType:"STORE"|"BRAND";name:string;entityNo:string;storeNo:string|null;currency:CurrencyCode;businessTimeZone:string|null;lastType:Exclude<StoredValueType,"ALL">;lastOccurredAt:string}
export interface StoredValueIssuersPage {records:StoredValueIssuer[];nextCursor:string|null;hasMore:boolean}
export interface StoredValueIssuerRecordsPage {issuer:StoredValueIssuer;filter:StoredValueRecordsPage["filter"];records:StoredValueRecord[];nextCursor:string|null;hasMore:boolean}

export interface StoredValueService {
 serviceOrderNo:string;operationNo:string;status:string;storeNo:string;storeName:string;serviceStaffName:string|null;serviceAt:string|null;
 items:Array<{itemNo:string;title:string;quantity:number}>;
 confirmation:{orderRevision:number;quoteRef:string;selectionDigest:string;expiresAt:string}|null;
 payment:{assetKind:string;currency:CurrencyCode;amountMinor:number;scopeType:'STORE'|'BRAND';issuerName:string|null}|null;
}
export interface StoredValueAuthorization {status:string;operationNo:string|null;useStatus:string|null}
