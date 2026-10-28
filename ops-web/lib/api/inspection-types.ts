import type { PointsAccountQ, PointsAccountView, PointsCursorPage, PointsVoucherView, PointsEntryView } from "@/lib/types";
export type PointsAccountsQ = Omit<PointsAccountQ, "holderNo"> & { holderNo?: string; after?: number; limit?: number };
export type PointsTransactionDirection = "EARN" | "REDEEM" | "REVERSAL";
export interface PointsPeriodQ { typeCode: string; from: string; to: string }
export interface PointsTransactionsQ extends PointsPeriodQ { direction: PointsTransactionDirection; storeNo?: string; userNo?: string; after?: number; limit?: number }
export interface PointsTransaction { direction: PointsTransactionDirection; voucher: PointsVoucherView }
export interface PointsTransactionDetail extends PointsTransaction { entries: PointsCursorPage<PointsEntryView> }
export interface PointsPeriodStats extends PointsPeriodQ { metrics: Record<string, number>; availableDays: number; missingDays: number }
export type PointsAccounts = PointsCursorPage<PointsAccountView>;
export type PointsTransactions = PointsCursorPage<PointsTransaction>;
export interface PrintScope { tenantNo: string; entityNo: string; storeNo: string }
export interface PrintReconcile extends PrintScope { sourceKind: string; from: string; to: string; dryRun: boolean; reason: string }
export interface PrintHealth { activePrinters: number; pendingJobs: number; unknownJobs: number; blockedRequests: number; asOf: string }
export interface PrintFleetHealth { stores: PrintHealth[]; asOf: string }
export interface PrintIncident { incidentNo: string; storeNo: string; incidentType: string; targetType: string; targetNo: string; severity: string; status: string; occurrenceCount: number; firstSeenAt: string; lastSeenAt: string; revision: number }
export interface PrintOperation { operationNo: string; type: string; status: string; targetType: string; targetNo: string; result: { canonicalJson: string; sha256: string } | null; error: { key: string; retryable: boolean; traceNo: string; conflicts: { canonicalJson: string; sha256: string } | null } | null; createdAt: string; finishedAt: string | null }
