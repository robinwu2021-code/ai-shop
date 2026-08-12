// 覆盖范围：售后治理（P-6.1）。
import * as db from "@/lib/mock/db";
import { MIN_FAST_REFUND_HOURS } from "@/lib/constants";
import type { AfterSale } from "@/lib/types";
import type { AfterSaleApi } from "../contracts/aftersale";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

function find(afterSaleNo: string): AfterSale {
  const a = db.afterSales.find((x) => x.afterSaleNo === afterSaleNo);
  if (!a) notFound("售后单", "After-sales case", afterSaleNo);
  return a;
}

export const afterSaleMock: AfterSaleApi = {
  listAfterSales: (q = {}) =>
    wait(
      db.paginate(db.afterSales, q.page, q.size, (a) =>
        db.scopeHit(q, a) &&
        db.eqHit(q.type, a.type) &&
        db.eqHit(q.status, a.status) &&
        // intervene=1 只看平台介入队列：客服的主视图是这一条，不是全量
        (q.intervene !== "1" || a.status === "ARBITRATING") &&
        db.kwHit(q.keyword, a.afterSaleNo, a.orderNo, a.merchantName, a.buyerNickname, a.reason),
      ),
    ),

  // 只能裁「已上升到平台」的单：还没上升就裁，等于替商家做了他还没做的决定（同后端 arbitrate）
  decideAfterSale: async ({ afterSaleNo, refund, liability, verdict }) => {
    const a = find(afterSaleNo);
    if (a.status !== "ARBITRATING") fail("只能裁决已上升到平台的单", "Only cases escalated to the platform can be ruled on");
    if (!verdict?.trim()) fail("裁决说明必填，用户与商家都会看到", "A ruling note is required — both the shopper and the merchant see it");
    a.liability = liability;
    a.verdict = verdict.trim();
    a.status = refund ? "REFUNDING" : "CLOSED";
    return wait(a, 400);
  },

  getFastRefundRule: async () => wait(db.fastRefundRule),

  saveFastRefundRule: async (v) => {
    if (v.maxAmount <= 0) fail("极速退金额上限必须大于 0", "The instant-refund cap must be greater than 0");
    // 0 小时等于关掉极速退，但开关还显示"已启用" —— 看起来在跑，实际一单都不会自动过
    if (v.withinHours < MIN_FAST_REFUND_HOURS) fail(`时限至少 ${MIN_FAST_REFUND_HOURS} 小时`, `The window must be at least ${MIN_FAST_REFUND_HOURS} hours`);
    Object.assign(db.fastRefundRule, v, { updatedAt: "2026-08-06T00:00:00Z", updatedBy: "admin" });
    return wait(db.fastRefundRule, 400);
  },
};
