import type { CurrencyCode } from "@shared/types";
export type StoredValueType = "ALL" | "RECHARGE" | "REDEMPTION";
export interface StoredValueStore {
  /** Store identifier scoping the requested operation or returned business record. */
  storeNo: string;
  /** Store display name captured for the record or selection. */
  storeName: string;
  /** Time of the most recent account record; null when there are no records. */
  lastOccurredAt: string;
  /** Type of the most recent stored-value record, excluding the ALL query filter. */
  lastType: Exclude<StoredValueType, "ALL">;
}
export interface StoredValueStoresQuery {
  /** Opaque continuation cursor from the preceding page; omit for the first page. */
  cursor?: string;
  /** Maximum records per page requested or returned by the server. */
  size?: number }
export interface StoredValueStoresPage {
  /** Business records returned by the current query. */
  records: StoredValueStore[];
  /** Opaque cursor for the next page; null when the result is exhausted. */
  nextCursor: string | null;
  /** Whether a further page is available using nextCursor. */
  hasMore: boolean;
}
export interface StoredValueRecordsQuery extends StoredValueStoresQuery {
  /** Record, target or calculation classification used to interpret this object. */
  type?: StoredValueType;
  /** Inclusive first business date in YYYY-MM-DD format. */
  startDate?: string;
  /** Inclusive final business date in YYYY-MM-DD format. */
  endDate?: string;
  /** Human-facing document number to locate within the account's authorized records. */
  focusDocumentNo?: string;
}
export interface StoredValueRecord {
  /** Record, target or calculation classification used to interpret this object. */
  type: Exclude<StoredValueType, "ALL">;
  /** Human-readable title of the stored-value business record. */
  title: string;
  /** ISO currency code determining the interpretation of monetary amounts. */
  currency: CurrencyCode;
  /** Signed change in stored-value balance, in minor currency units. */
  signedAmountMinor: number;
  /** Time the server recorded the business operation. */
  occurredAt: string;
  /** Human-facing document number; null or absent for historical unmapped records. */
  documentNo: string | null;
}
export interface StoredValueRecordsPage {
  /** Authorized store identity and business time zone for the returned records. */
  store: {
  /** Store identifier scoping the requested operation or returned business record. */
  storeNo: string;
  /** Store display name captured for the record or selection. */
  storeName: string;
  /** IANA time zone used to interpret the store's business dates. */
  businessTimeZone: string };
  /** Effective record filters after the server has normalized the request. */
  filter: {
  /** Record, target or calculation classification used to interpret this object. */
  type: StoredValueType;
  /** Inclusive first business date in YYYY-MM-DD format. */
  startDate: string | null;
  /** Inclusive final business date in YYYY-MM-DD format. */
  endDate: string | null };
  /** Business records returned by the current query. */
  records: StoredValueRecord[];
  /** Opaque cursor for the next page; null when the result is exhausted. */
  nextCursor: string | null;
  /** Whether a further page is available using nextCursor. */
  hasMore: boolean;
}
export interface StoredValueNotificationCapability {
  /** Delivery or collection channel used by this capability or route. */
  channel: "WXSUB";
  /** Whether this capability or preset is available in the current deployment. */
  available: boolean;
  /** Messaging template identifier; null when the capability is not configured. */
  templateId: string | null;
  /** Machine-readable reason this capability is unavailable; null when available. */
  reasonCode: string | null;
}
