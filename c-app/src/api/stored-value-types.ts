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
  signedAmountMinor: number; occurredAt: string; documentNo: string | null;
}
export interface StoredValueRecordsPage {
  store: { storeNo: string; storeName: string; businessTimeZone: string };
  filter: { type: StoredValueType; startDate: string | null; endDate: string | null };
  records: StoredValueRecord[]; nextCursor: string | null; hasMore: boolean;
}
export interface StoredValueNotificationCapability {
  channel: "WXSUB"; available: boolean; templateId: string | null; reasonCode: string | null;
}
