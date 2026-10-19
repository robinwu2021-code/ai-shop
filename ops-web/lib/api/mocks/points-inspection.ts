import type { FinanceApi } from "../contracts/finance";
import { fail } from "@/lib/biz-error";
import { wait } from "./_wait";
function unavailable(): never { fail("演示没有对应账务事实，请使用隔离后端验证。", "No corresponding ledger facts in this demo. Verify with an isolated backend."); }
export const pointsInspectionMock: Pick<FinanceApi, "pointsAccount" | "freezePointsAccount" | "pointsVouchers" | "pointsEntries" | "pointsFlows" | "pointsPosition" | "pointsStats" | "rebuildPointsStats" | "pointsReconciliation" | "reconcilePoints"> = {
  pointsAccount: (q) => wait({ snapshot: { ...q, buckets: {}, frozen: false, expireAt: null, expiryRevision: 0, lastActiveAt: 0 }, exists: false, version: -1 }),
  freezePointsAccount: unavailable,
  pointsVouchers: (q) => wait({ rows: [], next: q.after ?? 0, hasMore: false }),
  pointsEntries: (q) => wait({ rows: [], next: q.after ?? 0, hasMore: false }),
  pointsFlows: (q) => wait({ rows: [], next: q.after ?? 0, hasMore: false }),
  pointsPosition: unavailable, pointsStats: () => wait(null), rebuildPointsStats: unavailable,
  pointsReconciliation: () => wait(null), reconcilePoints: unavailable,
};
