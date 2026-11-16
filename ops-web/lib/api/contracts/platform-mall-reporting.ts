/** Money is an exact decimal string in minor units, including aggregate values beyond int64. */
export type PurchaseModel = "ENTITY" | "STORE";
export type PlatformOrderFilter = { model?: PurchaseModel; entityNo?: string; storeNo?: string; orderNo?: string; status?: string; currency?: string; createdFrom?: number; createdTo?: number; after?: string; limit?: number };
export type PlatformOrderRow = { model: PurchaseModel; orderNo: string; entityNo: string; storeNo: string | null; currency: string; payableMinor: string; status: string; claimStatus: string | null; createdAt: number };
export type PlatformOrderPage = { items: PlatformOrderRow[]; nextCursor: string | null; includedModels: PurchaseModel[]; queriedAt: number };
export type PlatformCashFilter = { fromInclusive: number; toExclusive: number; currency?: string; model?: PurchaseModel; entityNo?: string; storeNo?: string; after?: string; limit?: number };
export type PlatformCashSummary = { model: PurchaseModel; currency: string; classification: string; receivedMinor: string; refundedMinor: string; netMinor: string; receiptCount: number; refundCount: number };
export type PlatformCashRow = { paymentNo: string; orderNo: string; originalPaymentNo: string | null; direction: string; currency: string; amountMinor: string; occurredAt: number; classification: string; model: PurchaseModel; entityNo: string; storeNo: string | null };
export type PlatformCashPage = { summary: PlatformCashSummary[]; items: PlatformCashRow[]; nextCursor: string | null; queriedAt: number; coverage: string };
export interface PlatformMallReportingApi {
  platformMallOrders(filter: PlatformOrderFilter): Promise<PlatformOrderPage>;
  platformMallClaims(filter: PlatformOrderFilter): Promise<PlatformOrderPage>;
  platformMallCash(filter: PlatformCashFilter): Promise<PlatformCashPage>;
}
