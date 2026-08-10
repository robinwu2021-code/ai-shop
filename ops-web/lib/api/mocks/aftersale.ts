// 覆盖范围：售后治理（P-6.1）。退款金额校验要跨域查订单 —— 这是本域最容易漏的一条。
import * as db from "@/lib/mock/db";
import { LIABILITY_SHARE_TOTAL, MIN_FAST_REFUND_HOURS } from "@/lib/constants";
import { AFTERSALE_TRANSITIONS, type AfterSale } from "@/lib/types";
import type { AfterSaleApi } from "../contracts/aftersale";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

const TERMINAL = new Set(["REFUNDED", "CLOSED"]);

function find(asNo: string): AfterSale {
  const a = db.afterSales.find((x) => x.asNo === asNo);
  if (!a) notFound("售后单", "After-sales case", asNo);
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
        db.kwHit(q.keyword, a.asNo, a.orderNo, a.merchantName, a.buyerNickname, a.reason),
      ),
    ),

  setAfterSaleStatus: async (asNo, status) => {
    const a = find(asNo);
    db.assertTransition(AFTERSALE_TRANSITIONS, a.status, status, "售后单", "After-sales case");
    a.status = status;
    return wait(a, 400);
  },

  decideAfterSale: async ({ asNo, liability, share, verdict, amount }) => {
    const a = find(asNo);
    if (TERMINAL.has(a.status)) fail("该售后单已结束，无法再裁决", "This case is already closed and cannot be ruled on again");
    if (!verdict?.trim()) fail("裁决说明必填，用户与商家都会看到", "A ruling note is required — both the shopper and the merchant see it");
    const sum = share.platform + share.merchant + share.pickup;
    if (sum !== LIABILITY_SHARE_TOTAL) {
      fail(`赔付比例之和必须为 ${LIABILITY_SHARE_TOTAL}%，当前 ${sum}%`, `Liability shares must add up to ${LIABILITY_SHARE_TOTAL}%, currently ${sum}%`);
    }
    // 跨域校验：退款不能超过订单实付。没有这条，改一个数字就能把平台的钱退出去。
    const order = db.orders.find((o) => o.orderNo === a.orderNo);
    if (!order) notFound("关联订单", "Linked order", a.orderNo);
    if (amount > order.payAmount) {
      fail(`退款金额不得超过订单实付（${(order.payAmount / 100).toFixed(2)} 元）`, `The refund cannot exceed what was paid (¥${(order.payAmount / 100).toFixed(2)})`);
    }
    a.liability = liability;
    a.share = share;
    a.verdict = verdict.trim();
    a.amount = amount;
    a.status = "REFUNDING";
    // E4 退款回退分账依赖资金域（P-12），未接。留标记而不是假装已完成。
    a.refundSplitPending = true;
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
