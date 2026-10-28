// 覆盖范围：支付运营端（/pay-ops/v1，TDD-支付服务-I5接入层 S9）。
//
// 规则落在 mock 层：两人复核（同一人不能通过两次）、已处置的异常不能再动 —— 与后端同一套。
import type { PayOpsApi, PayOpsAppRow, PayOpsCredentialRow, PayOpsExceptionRow, PayOpsKeyVersionRow, PayOpsNotifyRow,
  PayOpsOrderRow, PayOpsPlatformKeyRow, PayOpsQrRow, PayOpsRefundRow, PayOpsRequestLogRow } from "../contracts/payops";
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

// ---- 接入与密钥（M5b）：规则同后端 —— 吊销即生效、启用人 ≠ 录入人、只给指纹 ----
const apps: PayOpsAppRow[] = [
  { appNo: "APP-PARTNER-01", name: "试点接入方甲", ownerType: "PARTNER", status: "ACTIVE", scopes: "pay:create,pay:read",
    sandbox: false, signType: "HMAC", webhookUrl: "https://partner.example.com/hook", rateLimitQps: 50,
    dailyRefundLimitMinor: 500000, activeGrants: 2 },
  { appNo: "MALL", name: "商城", ownerType: "INTERNAL", status: "ACTIVE", scopes: null, sandbox: false, signType: null,
    webhookUrl: null, rateLimitQps: null, dailyRefundLimitMinor: null, activeGrants: 0 },
];
const credentials: Record<string, PayOpsCredentialRow[]> = {
  "APP-PARTNER-01": [
    { kind: "APP_SECRET", version: 1, status: "ROTATING", fingerprint: "3f9a1c0e", serial: "S20261001AB12", enteredBy: "OPS-A" },
    { kind: "APP_SECRET", version: 2, status: "ACTIVE", fingerprint: "b71d22c4", serial: "S20261027CD34", enteredBy: "OPS-A" },
    { kind: "ENCRYPT_KEY", version: 1, status: "ACTIVE", fingerprint: "0e55a9f1", serial: "E20261027EF56", enteredBy: "OPS-A" },
  ],
};
const requestLogs: PayOpsRequestLogRow[] = [
  { id: 2, traceId: "T20261028A1", entry: "INTERNAL_HTTP", api: "GET /internal/pay/v1/ops/orders", payAction: "QUERY",
    appNo: "MALL", operatorNo: "OPS-ADMIN", sourceService: "shop", sourceModule: "payclient", sourceInstance: "shop-1",
    sourceIp: "127.0.0.1", originClient: "OPS_WEB", originApi: "GET /ops/payments/orders", clientIp: null, httpStatus: 200,
    resultCode: null, outcome: "ACCEPTED", elapsedMs: 12, signKeyVersion: null, receivedAt: now - 60_000 },
  { id: 1, traceId: "T20261028A0", entry: "OPEN_API", api: "POST /open/pay/v1/orders", payAction: "CREATE_ONLINE",
    appNo: "APP-PARTNER-01", operatorNo: null, sourceService: null, sourceModule: null, sourceInstance: null, sourceIp: null,
    originClient: null, originApi: null, clientIp: "203.0.113.7", httpStatus: 401, resultCode: "UNAUTHORIZED",
    outcome: "REJECTED_AUTH", elapsedMs: 3, signKeyVersion: null, receivedAt: now - 120_000 },
];
const qrCodes: PayOpsQrRow[] = [
  { qrCode: "Q7KX2M9PA", storeId: 1, kind: "TABLE", tableRef: "A3", currency: "CNY", singleLimitMinor: 50000,
    dailyLimitMinor: 200000, status: "ACTIVE", createdByType: "BIZ", createdAt: now - 86_400_000 },
];
const platformKeys: PayOpsPlatformKeyRow[] = [
  { keyId: 11, ownerType: "CHANNEL_PLATFORM", ownerId: 1, direction: "COUNTERPARTY_VERIFY", usage: "VERIFY", name: "微信平台证书",
    counterparty: "WECHAT", status: "ACTIVE", currentVersion: 1, expiresAt: now + 200 * 86_400_000 },
];
const keyVersions: Record<number, PayOpsKeyVersionRow[]> = {
  11: [
    { version: 1, status: "ACTIVE", fingerprint: "9c1e40aa", serial: "5157F3A1", enteredBy: "OPS-A" },
    { version: 2, status: "VERIFIED", fingerprint: "41d0b7e2", serial: "6A20C9D4", enteredBy: ME },
  ],
};

function keyVersion(keyId: number, version: number) {
  const v = keyVersions[keyId]?.find((x) => x.version === version);
  if (!v) notFound("密钥版本", "Key version", `${keyId}/${version}`);
  return v;
}

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
  payOpsQrCodes: (q) => wait(qrCodes.filter((c) => (!q?.storeId || c.storeId === q.storeId) && (!q?.status || c.status === q.status))),
  payOpsApps: () => wait(apps),
  payOpsAppGrants: (appNo) => wait(appNo === "APP-PARTNER-01"
    ? [{ storeId: 1, actions: "COLLECT,READ", status: "ACTIVE", grantedBy: "M-OP-1", grantedAt: now - 5 * 86_400_000, revokedAt: null }]
    : []),
  payOpsAppCredentials: (appNo) => wait(credentials[appNo] ?? []),
  payOpsRevokeAppCredential: (appNo, kind, version, reason) => {
    const c = (credentials[appNo] ?? []).find((x) => x.kind === kind && x.version === version);
    if (!c) notFound("接入方密钥", "App credential", `${appNo}/${kind}/${version}`);
    if (!reason.trim()) fail("请写吊销原因", "A reason is required");
    c.status = "REVOKED";
    return wait(undefined);
  },
  payOpsRequestLogs: (q) => wait(requestLogs.filter((r) => (!q?.entry || r.entry === q.entry)
    && (!q?.traceId || r.traceId === q.traceId) && (!q?.appNo || r.appNo === q.appNo)
    && (!q?.sourceService || r.sourceService === q.sourceService) && (!q?.outcome || r.outcome === q.outcome))),
  payOpsRequestLog: (id) => {
    const row = requestLogs.find((r) => r.id === id);
    if (!row) notFound("请求记录", "Request log", String(id));
    return wait({ row, requestMasked: '{"payer":{"phone":"138****0000"}}', responseMasked: '{"code":0}' });
  },
  payOpsPlatformKeys: () => wait(platformKeys),
  payOpsPlatformKeyVersions: (keyId) => wait(keyVersions[keyId] ?? []),
  payOpsEnablePlatformKey: (keyId, version) => {
    const v = keyVersion(keyId, version);
    if (v.enteredBy === ME) fail("启用人不能是录入人（两人控制）", "The enabler must differ from the uploader");
    if (v.status !== "VERIFIED") fail(`版本 ${version} 未验证，不能启用`, `Version ${version} is not verified`);
    for (const x of keyVersions[keyId]) if (x.status === "ACTIVE") x.status = "ROTATING";
    v.status = "ACTIVE";
    return wait(undefined);
  },
  payOpsRevokePlatformKey: (keyId, version, reason) => {
    if (!reason.trim()) fail("请写吊销原因", "A reason is required");
    keyVersion(keyId, version).status = "REVOKED";
    return wait(undefined);
  },
};
