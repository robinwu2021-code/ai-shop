// 覆盖范围：支付运营端（/pay-ops/v1，TDD-支付服务-I5接入层 S9）。
//
// 规则落在 mock 层：两人复核（同一人不能通过两次）、已处置的异常不能再动 —— 与后端同一套。
import type { PayOpsApi, PayOpsExceptionRow, PayOpsNotifyRow, PayOpsOrderRow, PayOpsRefundRow } from "../contracts/payops";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

const now = Date.now();
const orders: PayOpsOrderRow[] = [
  { payOrderNo: "PYO20261026Q1", outOrderNo: "SO20261026001", bizType: "MALL_ORDER", storeId: 1, storeName: "中山店",
    market: "CN", currency: "CNY", amountMinor: 1990, paidMinor: 1990, refundedMinor: 0, status: "SUCCEEDED",
    payChannel: "WECHAT", payMethod: "WECHAT_PAY", fundsPath: "AGGREGATED", sourceType: "INTERNAL",
    createdAt: now - 7_200_000, succeededAt: now - 7_100_000 },
  { payOrderNo: "PYO20261026Q2", outOrderNo: "OUT-778", bizType: "PARTNER", storeId: 1, storeName: "中山店",
    market: "CN", currency: "CNY", amountMinor: 5600, paidMinor: 0, refundedMinor: 0, status: "CLOSED",
    payChannel: "ALIPAY", payMethod: "ALIPAY", fundsPath: "AGGREGATED", sourceType: "PARTNER",
    createdAt: now - 3_600_000, succeededAt: null },
];
const refunds: (PayOpsRefundRow & { reviewers: string[] })[] = [
  { payRefundNo: "PYR20261026R1", payOrderNo: "PYO20261026Q1", amountMinor: 800, currency: "CNY", status: "PENDING_REVIEW",
    reason: "七天无理由", requestedBy: "M-OP-1", sourceType: "BIZ", hitReasons: "NO_ACTIVE_RULE", createdAt: now - 600_000,
    reviewers: [] },
];
const notifies: PayOpsNotifyRow[] = [
  { id: 1, eventType: "PAYMENT_SUCCEEDED", eventKey: "PAYMENT_SUCCEEDED|O1|2", targetType: "WEBHOOK",
    targetUrl: "https://partner.example.com/hook", status: "GIVEN_UP", attempts: 15, nextAt: null, deliveredAt: null,
    createdAt: now - 90_000_000 },
];
const exceptions: PayOpsExceptionRow[] = [
  { id: 1, kind: "LATE_PAYMENT", payOrderNo: "PYO20261026Q2", amountMinor: 5600, currency: "CNY", status: "OPEN",
    resolution: null, note: null, handledBy: null, createdAt: now - 1_800_000 },
];
const ME = "OPS-ADMIN";

const strip = ({ reviewers: _r, ...row }: PayOpsRefundRow & { reviewers: string[] }): PayOpsRefundRow => row;

function exc(id: number) {
  const e = exceptions.find((x) => x.id === id);
  if (!e) notFound("支付异常", "Payment exception", String(id));
  if (e.status !== "OPEN") fail(`异常 ${id} 已处置（${e.status}）`, `Exception ${id} is already handled (${e.status})`);
  return e;
}

export const payOpsMock: PayOpsApi = {
  payOpsOrders: (q) => wait(orders.filter((o) => (!q?.status || o.status === q.status)
    && (!q?.payOrderNo || o.payOrderNo === q.payOrderNo) && (!q?.outOrderNo || o.outOrderNo === q.outOrderNo))),
  payOpsOrder: (no) => {
    const o = orders.find((x) => x.payOrderNo === no);
    if (!o) notFound("支付单", "Payment order", no);
    return wait({
      order: o,
      attempts: [{ paymentNo: "PM" + no.slice(3), direction: "PAY", outTradeNo: no, tradeNo: o.paidMinor ? "4200001" : null,
        payChannel: o.payChannel, amountMinor: o.amountMinor, currency: o.currency, status: o.paidMinor ? "SUCCESS" : "CLOSED",
        payMerchantId: 7, fundsPath: o.fundsPath, at: o.succeededAt }],
      refunds: refunds.filter((r) => r.payOrderNo === no).map(strip),
      vouchers: o.paidMinor ? [{ voucherNo: "LV" + no.slice(3), voucherType: "PAY_SUCCEEDED", totalMinor: o.paidMinor,
        currency: o.currency, fundsPath: o.fundsPath, bizDate: new Date(o.succeededAt ?? now).toISOString().slice(0, 10),
        memo: "支付成功 " + no }] : [],
      notifies: [],
      exceptions: exceptions.filter((e) => e.payOrderNo === no),
    });
  },
  payOpsRefunds: (q) => wait(refunds.filter((r) => !q?.status || r.status === q.status).map(strip)),
  payOpsReviewRefund: (no, v) => {
    const r = refunds.find((x) => x.payRefundNo === no);
    if (!r) notFound("退款单", "Refund", no);
    if (r.reviewers.includes(ME) || r.requestedBy === ME) fail("同一个人不能复核两次", "The same person cannot review twice");
    if (!v.approve) r.status = "REJECTED";
    else if (r.status === "PENDING_REVIEW") r.status = "PENDING_SECOND_REVIEW";
    else r.status = "APPROVED";
    r.reviewers.push(ME);
    return wait(strip(r));
  },
  payOpsNotifies: (q) => wait(notifies.filter((n) => !q?.status || n.status === q.status)),
  payOpsResendNotify: (id) => {
    const n = notifies.find((x) => x.id === id);
    if (!n) notFound("业务通知", "Notification", String(id));
    n.status = "DELIVERED";
    n.attempts += 1;
    n.deliveredAt = Date.now();
    return wait(undefined);
  },
  payOpsExceptions: (q) => wait(exceptions.filter((e) => !q?.status || e.status === q.status)),
  payOpsRefundException: (id) => {
    const e = exc(id);
    e.status = "RESOLVED";
    e.resolution = "REFUNDED";
    e.handledBy = ME;
    return wait(undefined);
  },
  payOpsIgnoreException: (id, note) => {
    const e = exc(id);
    e.status = "IGNORED";
    e.resolution = "IGNORED";
    e.note = note;
    e.handledBy = ME;
    return wait(undefined);
  },
  payOpsDisableQrCode: () => wait(undefined),
};
