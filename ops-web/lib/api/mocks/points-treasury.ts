import type { FinanceApi } from "../contracts/finance";
import { fail } from "@/lib/biz-error";
import { wait } from "./_wait";
function unavailable(): never { fail("演示环境未连接资金与计划服务，请使用隔离后端验证。", "The demo has no funds or plan service. Use an isolated backend for verification."); }
// No fabricated approvals, bank receipts or withdrawal success in the offline demo.
export const pointsTreasuryMock: Pick<FinanceApi, "pointsRequests" | "submitPointsRequest" | "reviewPointsRequest" | "pointsPlans" | "savePointsPlan" | "generatePointsPeriods" | "declarePointsReceipt" | "cancelPointsPeriod" | "executePointsWithdrawal"> = {
  pointsRequests: (q) => wait({ rows: [], next: q.after ?? 0, hasMore: false }),
  pointsPlans: (q) => wait({ rows: [], next: q.after ?? 0, hasMore: false }),
  submitPointsRequest: unavailable, reviewPointsRequest: unavailable,
  savePointsPlan: unavailable, generatePointsPeriods: unavailable,
  declarePointsReceipt: unavailable, cancelPointsPeriod: unavailable, executePointsWithdrawal: unavailable,
};
