// 商家端支付的本机假数据（mock 模式看界面用）。规则照真实后端：退款不能超过已付减已退、码值随机、撤销即 REVOKED
import type { PayApi, PayAppGrant, PayQrCode, PayRefundItem, PayStatementItem } from "../pay";

const today = () => new Date().toISOString().slice(0, 10);
const rnd = (n: number) => Array.from({ length: n }, () => "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"[
  Math.floor(Math.random() * 55)]).join("");

const paid: PayStatementItem[] = [
  { kind: "PAYMENT", no: "PYO20261026A1", payOrderNo: "PYO20261026A1", amountMinor: 2680, payChannel: "WECHAT",
    payMethod: "WECHAT_PAY", status: "SUCCEEDED", at: Date.now() - 3_600_000 },
  { kind: "PAYMENT", no: "PYO20261026A2", payOrderNo: "PYO20261026A2", amountMinor: 1990, payChannel: "ALIPAY",
    payMethod: "ALIPAY", status: "SUCCEEDED", at: Date.now() - 7_200_000 },
];
const refunds: PayRefundItem[] = [];
const codes: PayQrCode[] = [
  { qrCode: rnd(22), kind: "STORE", tableRef: null, currency: "CNY", singleLimitMinor: 50000, dailyLimitMinor: 500000,
    status: "ACTIVE", url: "https://pay.example.com/q/demo" },
];
const grants: PayAppGrant[] = [];

export const payMock: PayApi = {
  async mPayCollect(v) {
    const token = rnd(43);
    return { payOrderNo: "PYO" + Date.now(), token, expireAt: Date.now() + (v.expireMinutes ?? 15) * 60_000,
      checkoutUrl: "https://pay.example.com/c/" + token };
  },
  async mPayRefunds() {
    return [...refunds];
  },
  async mPayRefund(v) {
    const order = paid.find((p) => p.payOrderNo === v.payOrderNo);
    const done = refunds.filter((r) => r.payOrderId === Number(order?.no.slice(-1))).reduce((a, r) => a + r.amountMinor, 0);
    if (!order || v.amountMinor <= 0 || v.amountMinor > order.amountMinor - done) throw new Error("可退金额不足");
    const r: PayRefundItem = { payRefundNo: "PYR" + Date.now(), payOrderId: Number(order.no.slice(-1)),
      amountMinor: v.amountMinor, currency: "CNY", status: v.amountMinor >= 2000 ? "PENDING_REVIEW" : "DISPATCHED",
      reason: v.reason ?? null, requestedBy: "M-OWNER", createdAt: Date.now(), succeededAt: null };
    refunds.unshift(r);
    return r;
  },
  async mPayStatement(day) {
    const d = day ?? today();
    const items = d === today() ? paid : [];
    return { bizDate: d, currency: "CNY", paidCount: items.length, paidMinor: items.reduce((a, p) => a + p.amountMinor, 0),
      refundCount: 0, refundMinor: 0, items };
  },
  async mPayQrCodes() {
    return [...codes];
  },
  async mPayCreateQrCode(v) {
    const c: PayQrCode = { qrCode: rnd(22), kind: v.kind, tableRef: v.tableRef ?? null, currency: "CNY",
      singleLimitMinor: v.singleLimitMinor, dailyLimitMinor: v.dailyLimitMinor, status: "ACTIVE",
      url: "https://pay.example.com/q/" + rnd(8) };
    codes.unshift(c);
    return c;
  },
  async mPayDisableQrCode(qrCode) {
    const c = codes.find((x) => x.qrCode === qrCode);
    if (c) c.status = "DISABLED";
  },
  async mPayAppGrants() {
    return [...grants];
  },
  async mPayGrantApp(v) {
    if (!/^APP-/.test(v.appNo)) throw new Error("没有这个接入方");
    const g: PayAppGrant = { id: grants.length + 1, appNo: v.appNo, appName: "试点接入方", actions: v.actions,
      status: "ACTIVE", grantedAt: Date.now() };
    grants.unshift(g);
    return g;
  },
  async mPayRevokeGrant(id) {
    const g = grants.find((x) => x.id === id);
    if (g) g.status = "REVOKED";
  },
};
