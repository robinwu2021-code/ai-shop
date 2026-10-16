import type { FinanceApi } from "../contracts/finance";
import { fail } from "@/lib/biz-error";
import { wait } from "./_wait";
function unavailable(): never { fail("演示未接入订单及资金事实，请使用隔离后端验证。", "No source order or bank facts in this demo. Verify with an isolated backend."); }
export const pointsWorkbenchMock: Pick<FinanceApi, "pointsStatements" | "draftPointsStatements" | "changePointsStatement" | "pointsRefund" | "revisePointsRefund" | "preparePointsReceivable" | "pointsFunds" | "recoverPointsFunds" | "pointsEvents" | "retryPointsEvent"> = {
  pointsStatements: (q) => wait({ rows: [], next: q.after ?? 0, hasMore: false }),
  pointsEvents: (q) => wait({ rows: [], next: q.after ?? 0, hasMore: false }),
  draftPointsStatements: unavailable, changePointsStatement: unavailable, pointsRefund: unavailable,
  revisePointsRefund: unavailable, preparePointsReceivable: unavailable, pointsFunds: unavailable,
  recoverPointsFunds: unavailable, retryPointsEvent: unavailable,
};
