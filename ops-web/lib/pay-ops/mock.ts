// 支付中心 mock：内存数据 + 与 near-pay 同口径的状态机与乐观锁（版本不对 409、状态不允许 409）。
// 只在 NEXT_PUBLIC_USE_MOCK≠0 时使用；数据台湾主场景（TWD、台北门店），见记忆「TW 主场景」。
import { MockPayError, VersionedTable, mockDelay, pageOf } from "./mock-store";
import type { Cmd, PayCenterApi } from "./api";
import type {
  AlertEventRow, AlertRuleRow, AppGrant, AppRow, ApprovalFlowRow, ChannelCallRow, ChannelCallbackRow, OperatingSettings, RefundListRow, RequestLogRow, TerminalRow,
  ActionResult, ApprovalDetail, ApprovalRow, ApprovalTask, ChannelRow, DataScopeRow, Detail, EntryRow, ExceptionRow,
  MerchantRow, OnboardingRow, OrderRow, ProductRow, StoreRow,
} from "./types";

const T0 = Date.UTC(2026, 10, 20, 2, 0);
const H = 3_600_000;
const MERCHANTS: [string, string][] = [["PM1001", "台北早餐店"], ["PM1002", "信义咖啡"], ["PM1003", "大安面包坊"], ["PM1004", "松山便当"]];
const STORES: [string, string, number][] = [["ST2001", "台北早餐店·信义店", 0], ["ST2002", "台北早餐店·大安店", 0], ["ST2003", "信义咖啡·市府店", 1], ["ST2004", "大安面包坊·忠孝店", 2], ["ST2005", "松山便当·民生店", 3]];

function need(cond: boolean, msg: string) { if (!cond) throw new MockPayError("STATE_CONFLICT", msg); }
function reasonOf(r: string | undefined, min = 1) { if (!r || r.trim().length < min) throw new MockPayError("VALIDATION_ERROR", "请填写原因"); }
const ok = (resourceNo: string, status: string, version: number, approvalNo: string | null = null): ActionResult => ({ resourceNo, status, version, approvalNo, jobNo: null });
const wrap = <T,>(record: T, version: number, allowedActions: string[] = []): Detail<T> => ({ record, version, allowedActions, timeline: [] });
const run = async <T,>(f: () => T | Promise<T>): Promise<T> => mockDelay(await f());

export function createMockPayCenterApi(): PayCenterApi {
  // ── 数据 ──
  const ORDER_ST = ["SUCCEEDED", "AWAITING_PAYMENT", "AWAITING_CONFIRM", "FAILED", "CLOSED", "PARTIALLY_REFUNDED", "SUCCEEDED", "CREATED"] as const;
  const CH = ["NEWEBPAY", "LINEPAY", "JKOPAY", "MOCKPAY"];
  let orders: (OrderRow & { version: number })[] = Array.from({ length: 46 }, (_, i) => {
    const [storeNo, storeName, m] = STORES[i % STORES.length];
    const status = ORDER_ST[i % ORDER_ST.length];
    const amount = 3500 + i * 1250;
    return {
      payOrderNo: `PO2026112${String(i + 1).padStart(4, "0")}`, outOrderNo: `SO${880000 + i}`, bizType: "RETAIL",
      storeNo, storeName, merchantNo: MERCHANTS[m][0], merchantName: MERCHANTS[m][1], currency: "TWD",
      amountMinor: amount, paidMinor: ["SUCCEEDED", "PARTIALLY_REFUNDED"].includes(status) ? amount : 0,
      refundedMinor: status === "PARTIALLY_REFUNDED" ? 1000 : 0, status, payChannel: status === "CREATED" ? null : CH[i % CH.length],
      payMethod: "CARD", sourceType: "H5", createdAt: T0 - i * H, succeededAt: status === "SUCCEEDED" ? T0 - i * H + 60_000 : null,
      closedAt: status === "CLOSED" ? T0 - i * H + 900_000 : null, expireAt: T0 - i * H + 900_000,
      followupStatus: status === "AWAITING_CONFIRM" ? "ESCALATED" : null, payerNote: null, version: 0,
    };
  });
  const exceptions = new VersionedTable<ExceptionRow & { version: number }>(Array.from({ length: 9 }, (_, i) => ({
    id: 501 + i, kind: ["LATE_PAYMENT", "AMOUNT_MISMATCH", "DUPLICATE_PAYMENT"][i % 3], payOrderNo: orders[i * 3].payOrderNo,
    amountMinor: orders[i * 3].amountMinor, currency: "TWD", status: (["OPEN", "OPEN", "AUTO_REFUNDING", "RESOLVED", "IGNORED"] as const)[i % 5],
    resolution: null, note: null, handledBy: null, createdAt: T0 - i * 5 * H, version: 0,
  })), (r) => String(r.id));
  const merchants = new VersionedTable<MerchantRow>(MERCHANTS.map(([no, name], i) => ({
    merchantNo: no, displayName: name, sourceSystem: "AI_SHOP", entityNo: `EN${3001 + i}`, entityName: `${name}有限公司`, market: "TW",
    profileStatus: ["APPROVED", "SUBMITTED", "APPROVED", "NEED_MORE"][i], verifyStatus: ["VERIFIED", "PENDING", "VERIFIED", "MISMATCH"][i],
    duplicateSuspect: i === 3, payMerchantNos: i % 2 ? null : [`NWP${7000 + i}`], storeCount: STORES.filter((s) => s[2] === i).length,
    status: i === 2 ? "SUSPENDED" : "ACTIVE", statusReason: i === 2 ? "资料待更新" : null, createdAt: T0 - (30 + i) * 24 * H, version: 1,
  })), (r) => r.merchantNo);
  const stores = new VersionedTable<StoreRow & { pendingChange: { targetMerchantNo: string; reason: string; requestedBy: string; requestedAt: number } | null }>(
    STORES.map(([no, name, m], i) => ({
      storeNo: no, storeName: name, sourceSystem: "AI_SHOP", externalStoreId: `S${100 + i}`, entityNo: `EN${3001 + m}`,
      merchantNo: MERCHANTS[m][0], merchantName: MERCHANTS[m][1], market: "TW", sourceStatus: i === 4 ? "PENDING_SYNC" : "ACTIVE",
      configMode: i === 1 ? "OVERRIDE" : "INHERIT", payMerchantNos: [`NWP${7000 + m}`], syncIssue: i === 4 ? "ENTITY_CHANGED" : null,
      syncedAt: T0 - i * H, version: 1, pendingChange: null,
    })), (r) => r.storeNo);
  const policies = new Map<string, { mode: string; productCodes: string[]; version: number }>();
  const onboardings = new VersionedTable<OnboardingRow>(MERCHANTS.map(([no, name], i) => ({
    onboardingNo: `OB${4001 + i}`, merchantNo: no, merchantName: name, payChannel: i % 2 ? "LINEPAY" : "NEWEBPAY", submitMode: i % 2 ? "API" : "MANUAL",
    materialVersion: i === 0 ? 0 : 1, reviewStatus: ["NONE", "PENDING", "APPROVED", "NEED_MORE"][i], status: ["DRAFT", "SUBMITTED", "CHANNEL_REVIEW", "SUBMITTED"][i],
    channelApplyNo: i === 2 ? "NWP-APPLY-0091" : null, signUrl: null, rejectReason: i === 3 ? "营业登记影本不清晰" : null, payMerchantNo: null,
    updatedAt: T0 - i * 6 * H, version: 1,
  })), (r) => r.onboardingNo);
  const approvals = new VersionedTable<ApprovalRow & { taskNo: string; taskVersion: number; taskStatus: string }>(
    [["DATA_SCOPE_ENABLE", "ENABLE", "数据委托 DS6001"], ["PLATFORM_MERCHANT_RESUME", "RESUME", "恢复商户 大安面包坊"], ["STORE_ASSOCIATION_CHANGE", "EXECUTE", "门店归属变更 ST2003"], ["CHANNEL_ACCOUNT_ENABLE", "ENABLE", "启用渠道账户 NWP7000"]]
      .map(([bizType, action, title], i) => ({
        approvalNo: `AP${5001 + i}`, bizType, action, resourceNo: null, subjectTitle: title, applicantNo: i % 2 ? "bd01" : "finance01",
        nodeNo: 1, assigneeNo: i === 1 ? "admin" : null, materialVersion: 1, status: i === 3 ? "APPROVED" : "PENDING_FIRST",
        executionStatus: i === 3 ? "NOT_STARTED" : "NOT_STARTED", expiresAt: T0 + 48 * H, escalated: i === 2, createdAt: T0 - i * 3 * H, version: 1,
        taskNo: `TK${5001 + i}`, taskVersion: 1, taskStatus: i === 1 ? "CLAIMED" : i === 3 ? "DONE" : "READY",
      })), (r) => r.approvalNo);
  const channels = new VersionedTable<ChannelRow>(["NEWEBPAY:蓝新金流:NEWEBPAY", "LINEPAY:LINE Pay:LINEPAY", "JKOPAY:街口支付:JKOPAY", "MOCKPAY:测试渠道:MOCKPAY"].map((s, i) => {
    const [code, name, adapter] = s.split(":");
    return {
      channelCode: code, name, channelType: "FIAT", adapterCode: adapter, platformCredentialKeyId: i === 3 ? null : 11 + i,
      status: i === 2 ? "DISABLED" : "ENABLED", sortNo: i * 10, enabledProductCount: [3, 1, 0, 2][i],
      endpoints: [{ environment: "SANDBOX", baseUrl: `https://sandbox.${code.toLowerCase()}.example`, callbackStyle: "PATH", enabled: true }],
      version: 1, updatedAt: T0 - i * 24 * H, updatedBy: "admin", market: "TW", testChannel: code === "MOCKPAY",
    };
  }), (r) => r.channelCode);
  const products = new VersionedTable<ProductRow>([
    ["NEWEBPAY_CARD", "NEWEBPAY", "信用卡（蓝新）", "CARD"], ["NEWEBPAY_TWQR", "NEWEBPAY", "台湾 Pay QR", "TWQR"], ["LINEPAY_H5", "LINEPAY", "LINE Pay", "LINE_PAY"], ["MOCKPAY_TEST", "MOCKPAY", "测试支付", "TEST_PAY"],
  ].map(([code, ch, name, pm], i) => ({
    productCode: code, channelCode: ch, displayName: name, tradeMode: "ONE_TIME", paymentMethods: [pm],
    accessMethods: [{ accessMethod: "H5", channelMethod: "WEB", enabled: true }], accountModes: ["PLATFORM_SUB"],
    currencyLimits: [{ currency: "TWD", amountScale: 0, minMinor: 1, maxMinor: 0, enabled: true }], refundRule: { refundMode: "PARTIAL", maxPartialRefunds: 5 },
    twoPhase: false, status: i === 1 ? "DISABLED" : "ENABLED", sortNo: i * 10, version: 1, updatedAt: T0 - i * 24 * H, updatedBy: "admin", protocolProductCode: null,
  })), (r) => r.productCode);
  const entries = new VersionedTable<EntryRow>(STORES.slice(0, 4).map(([storeNo, storeName, m], i) => ({
    entryNo: `CE${6001 + i}`, name: `${storeName} · ${["H5", "POS", "API", "H5"][i]}`, merchantNo: MERCHANTS[m][0], storeNo, accessMethod: ["H5", "POS", "API", "H5"][i],
    productCodes: ["NEWEBPAY_CARD"], appNo: i === 2 ? "APP9001" : null, allowedDomain: ["H5"].includes(["H5", "POS", "API", "H5"][i]) ? "pay.umeshop.ai" : null,
    sessionTimeoutSeconds: ["H5", "POS", "API", "H5"][i] === "H5" ? 900 : null, clientAppIdentifier: null, terminalNo: i === 1 ? "T0001" : null,
    status: (["ACTIVE", "ACTIVE", "DRAFT", "DISABLED"] as const)[i], statusReason: null, version: 1,
    readiness: { ready: i !== 2, reasons: i === 2 ? ["APP_NOT_ACTIVE"] : [] },
  })), (r) => r.entryNo);
  const scopes = new VersionedTable<DataScopeRow & { version: number }>([
    { scopeNo: "DS6001", scopeType: "USER", subjectNo: "finance01", marketSet: ["TW"], entityNos: ["*"], storeNos: [], fundsAccess: "MASKED_READ", expiresAt: T0 + 180 * 24 * H, status: "DRAFT", reason: "财务月结对账", enteredBy: "admin", enabledBy: null, revokedAt: null, version: 0 },
    { scopeNo: "DS6002", scopeType: "ROLE", subjectNo: "MERCHANT_BD", marketSet: ["TW"], entityNos: ["EN3001", "EN3002"], storeNos: [], fundsAccess: "NONE", expiresAt: T0 + 90 * 24 * H, status: "ACTIVE", reason: "BD 负责商户", enteredBy: "admin", enabledBy: "admin2", revokedAt: null, version: 1 },
  ], (r) => r.scopeNo);


  // ── P2 数据 ──
  const revisions = new Map<string, { revisionNo: string; revision: number; purpose: string; reviewStatus: string; submittedBy: string; submittedAt: number; version: number; profile: Record<string, string> }>(
    MERCHANTS.map(([no, name], i) => [no, { revisionNo: `PR${no}-${i + 2}`, revision: i + 2, purpose: ["GENERAL", "FIAT", "GENERAL", "FIAT"][i], reviewStatus: i === 1 || i === 3 ? "SUBMITTED" : "APPROVED", submittedBy: "bd01", submittedAt: T0 - (i + 1) * 5 * H, version: 1, profile: { legalName: `${name}有限公司`, contactName: "林小姐", contactPhone: "0912-345-678", address: "台北市信义区松仁路 100 号" } }]));
  let refunds: RefundListRow[] = Array.from({ length: 14 }, (_, i) => ({
    payRefundNo: `RF2026112${String(i + 1).padStart(4, "0")}`, payOrderNo: orders[i * 2].payOrderNo, amountMinor: 1000 + i * 350, currency: "TWD",
    status: ["PENDING_REVIEW", "PENDING_SECOND_REVIEW", "SUCCEEDED", "REJECTED", "DISPATCHED"][i % 5], reason: ["买家取消", "商品缺货", "重复扣款"][i % 3],
    requestedBy: i % 2 ? "cs01" : "admin", sourceType: "OPS", hitReasons: i % 4 === 0 ? "AMOUNT_OVER_THRESHOLD" : null, createdAt: T0 - i * 2 * H,
  }));
  const refundReviewers = new Map<string, string>();
  const terminals = new VersionedTable<TerminalRow>(STORES.slice(0, 3).map(([storeNo, name, m], i) => ({
    terminalNo: `TM${8001 + i}`, name: `${name} 收银台`, merchantNo: MERCHANTS[m][0], storeNo, payChannel: "NEWEBPAY", payMerchantNo: `NWP${7000 + m}`, channelTerminalNo: `CT${100 + i}`,
    serialNo: `SN-PAX-${900 + i}`, model: "PAX A920", connectionMode: i === 2 ? "ECR" : "STANDALONE", productCodes: ["NEWEBPAY_CARD"], receiptName: name, remark: null,
    dailyRefundLimitMinor: 5_000_00, market: "TW", currency: "TWD", configurationStatus: ["ENABLED", "DRAFT", "DISABLED"][i], verificationStatus: i === 1 ? "UNVERIFIED" : "VERIFIED",
    configuredBy: "admin", verifiedBy: i === 1 ? null : "ops02", evidenceRef: i === 1 ? null : `EV-${i}`, deviceStatus: ["ACTIVE", "REGISTERED", "ACTIVE"][i], credentialRef: i === 1 ? null : `secret:terminal/TM${8001 + i}`,
    statusReason: null, version: 1, configurationEligibility: { canEnable: i !== 1, reasons: i === 1 ? ["UNVERIFIED"] : [] }, readiness: { ready: i === 0, canReceivePayments: i === 0, reasons: i === 0 ? [] : ["DEVICE_NOT_ACTIVE"] },
  })), (r) => r.terminalNo);
  const apps = new VersionedTable<AppRow & { grants: AppGrant[] }>([["APP9001", "信义咖啡点餐 App", "MERCHANT", "PM1002", "ACTIVE"], ["APP9002", "平台对账服务", "PLATFORM", null, "DRAFT"], ["APP9003", "松山便当外送", "MERCHANT", "PM1004", "SUSPENDED"]].map(([no, name, owner, m, st], i) => ({
    appNo: no as string, name: name as string, purpose: "收款与退款", ownerKind: owner as "MERCHANT", merchantNo: m as string | null, environment: i === 1 ? "SANDBOX" : "LIVE",
    scopes: ["payment.create", "payment.query", "refund.create"], notifyUrl: `https://hooks.example.com/${no}`, callbackDomains: ["hooks.example.com"], callbackStatus: i === 0 ? "VERIFIED" : "UNVERIFIED",
    ipWhitelist: "203.0.113.0/24", rateLimitQps: 20, dailyRefundLimitMinor: 50_000_00, credentialStatus: i === 1 ? "NONE" : "ACTIVE", grantCount: i === 0 ? 1 : 0, status: st as string,
    lastAccessAt: i === 0 ? T0 - H : null, lastAccessOutcome: i === 0 ? "ACCEPTED" : null, version: 1,
    grants: i === 0 ? [{ appNo: no as string, storeNo: "ST2003", merchantNo: "PM1002", actions: ["payment.create"], status: "ACTIVE", grantedBy: "admin", grantedAt: T0 - 48 * H, version: 1 }] : [],
  })), (r) => r.appNo);
  const reqLogs: RequestLogRow[] = Array.from({ length: 30 }, (_, i) => ({ requestId: `rq-${(9000 + i).toString(16)}`, entry: ["OPEN_API", "CHECKOUT", "OPS_API", "TERMINAL_API"][i % 4], appNo: i % 4 === 0 ? "APP9001" : null, method: i % 3 ? "POST" : "GET", path: ["/open/pay/v2/payments", "/checkout/sessions", "/pay-ops/v2/orders", "/terminal/v1/charge"][i % 4], httpStatus: i % 7 === 0 ? 401 : 200, businessCode: i % 7 === 0 ? "AUTH_FAILED" : "0", latencyMs: 30 + i * 7, authResult: i % 7 === 0 ? "REJECTED_AUTH" : "ACCEPTED", occurredAt: T0 - i * 600_000 }));
  const calls: ChannelCallRow[] = Array.from({ length: 24 }, (_, i) => ({ callNo: `CC${7001 + i}`, attempt: 1 + (i % 2), payChannel: CH[i % 3], payMerchantNo: `NWP${7000 + (i % 4)}`, action: ["PAY", "QUERY", "REFUND"][i % 3], api: "/MPG/mpg_gateway", bizNo: orders[i].payOrderNo, requestId: reqLogs[i].requestId, resultCode: i % 6 === 0 ? "TIMEOUT" : "SUCCESS", latencyMs: 200 + i * 30, status: i % 6 === 0 ? "UNKNOWN" : "SUCCEEDED", transport: i % 6 === 0 ? "TIMEOUT" : "RESPONDED", occurredAt: T0 - i * 900_000 }));
  const callbacks: ChannelCallbackRow[] = Array.from({ length: 18 }, (_, i) => ({ callbackNo: `CB${6001 + i}`, payChannel: CH[i % 3], notifyType: "PAYMENT", requestId: null, verifyResult: i % 5 === 0 ? "FAILED" : "PASSED", outcome: i % 5 === 0 ? "REJECTED" : i % 4 === 0 ? "DUPLICATE" : "APPLIED", bizNo: orders[i].payOrderNo, channelTradeNo: `T${5000 + i}`, occurredAt: T0 - i * 1_200_000 }));
  const alertEvents = new VersionedTable<AlertEventRow>(Array.from({ length: 6 }, (_, i) => ({ eventNo: `AE${3001 + i}`, ruleNo: `AR${2001 + (i % 3)}`, ruleRevisionNo: 1, metric: ["CHANNEL_SUCCESS_RATE", "CALLBACK_FAILURE", "CHANNEL_LATENCY"][i % 3], severity: ["P1", "P2", "P3"][i % 3], scopeType: "CHANNEL", scopeRef: CH[i % 3], observedValue: ["82.5", "17", "4200"][i % 3], threshold: ["95", "10", "3000"][i % 3], operator: ["LT", "GT", "GT"][i % 3], triggerCount: 3 + i, firstTriggeredAt: T0 - (i + 2) * H, firedAt: T0 - i * 600_000, assigneeNo: i === 1 ? "admin" : null, resolution: i === 3 ? "FIXED" : null, resolutionNote: null, relatedRef: null, status: ["OPEN", "CLAIMED", "OPEN", "RESOLVED", "CLOSED", "OPEN"][i], version: 1 })), (r) => r.eventNo);
  const alertRules = new VersionedTable<AlertRuleRow>([["渠道成功率过低", "CHANNEL_SUCCESS_RATE", "LT", "95", "P1"], ["回调失败过多", "CALLBACK_FAILURE", "GT", "10", "P2"], ["渠道延迟过高", "CHANNEL_LATENCY", "GT", "3000", "P3"]].map(([name, metric, op, th, sev], i) => ({ ruleNo: `AR${2001 + i}`, revisionNo: 1, name, metric, scopeType: "CHANNEL", scopeRef: null, operator: op, threshold: th, unit: ["PERCENT", "COUNT", "MILLISECOND"][i], windowSeconds: 300, minSampleCount: 20, consecutiveBreaches: 2, severity: sev, notifyTargets: ["ops-oncall"], status: i === 2 ? "DRAFT" : "PUBLISHED", enteredBy: "admin2", publishedBy: i === 2 ? null : "admin", publishedRevisionNo: i === 2 ? null : 1, version: 1 })), (r) => r.ruleNo);
  const flows: ApprovalFlowRow[] = [["AF101", "数据委托启用", "DATA_SCOPE_ENABLE", 1], ["AF102", "恢复商户", "PLATFORM_MERCHANT_RESUME", 2], ["AF103", "门店归属变更", "STORE_ASSOCIATION_CHANGE", 2]].map(([no, name, biz, n]) => ({ flowNo: no as string, revisionNo: 1, name: name as string, bizType: biz as string, market: "TW", nodes: Array.from({ length: n as number }, (_, k) => ({ nodeNo: k + 1, roleRef: k ? "FINANCE_LEAD" : "OPS_LEAD" })), sensitive: n === 2, leaseMinutes: 30, timeoutHours: 48, escalateAfterHours: 24, status: "PUBLISHED", version: 1 }));
  let settings: OperatingSettings = { market: "TW", currency: "TWD", currencyScale: 0, timezone: "Asia/Taipei", defaultChannelCode: "NEWEBPAY", channelOrder: ["NEWEBPAY", "LINEPAY", "JKOPAY"], version: 3, updatedAt: T0 - 72 * H, updatedBy: "admin" };

  const cmdCheck = (c: Cmd) => reasonOf(c.reason);
  const setStatus = async <R extends { version: number; status: string }>(t: VersionedTable<R>, id: string, c: Cmd, from: string[], to: string) => {
    cmdCheck(c);
    need(from.includes(t.get(id).status), "当前状态不允许此操作");
    const r = await t.update(id, { status: to } as Partial<R>, c.expectedVersion);
    return ok(id, to, r.version);
  };
  const setStatusField = async <R extends { version: number }>(t: VersionedTable<R>, id: string, c: Cmd, field: keyof R, from: string[], to: string) => {
    cmdCheck(c);
    need(from.includes(String(t.get(id)[field])), "当前状态不允许此操作");
    const r = await t.update(id, { [field]: to } as Partial<R>, c.expectedVersion);
    return ok(id, to, r.version);
  };
  const getOrder = (no: string) => { const o = orders.find((x) => x.payOrderNo === no); if (!o) throw new MockPayError("NOT_FOUND", "订单不存在"); return o; };
  const patchOrder = (no: string, p: Partial<OrderRow>) => { orders = orders.map((o) => (o.payOrderNo === no ? { ...o, ...p } : o)); };

  return {
    source: "mock",
    orders: {
      list: (q) => pageOf(orders.filter((o) => (q.needsAttention ? o.followupStatus === "ESCALATED" : true)), { ...q, needsAttention: undefined }, ["payOrderNo", "outOrderNo"]),
      detail: (no) => run(() => {
        const o = getOrder(no);
        const acts = [
          ...(["AWAITING_PAYMENT", "AWAITING_CONFIRM", "AUTHORIZED"].includes(o.status) ? ["recover"] : []),
          ...(["AWAITING_PAYMENT", "AUTHORIZED", "AWAITING_CONFIRM", "CLOSED", "FAILED"].includes(o.status) && o.payChannel ? ["manualSettle"] : []),
          ...(o.followupStatus === "ESCALATED" ? ["dismiss"] : []),
        ];
        return wrap({
          order: o,
          attempts: o.payChannel ? [{ paymentNo: `PY${o.payOrderNo.slice(2)}`, direction: "PAY", outTradeNo: o.payOrderNo, tradeNo: o.status === "SUCCEEDED" ? `T${o.payOrderNo.slice(-4)}` : null, payChannel: o.payChannel, amountMinor: o.amountMinor, currency: o.currency, status: o.status, at: o.createdAt }] : [],
          refunds: o.refundedMinor ? [{ payRefundNo: `RF${o.payOrderNo.slice(2)}`, payOrderNo: o.payOrderNo, amountMinor: o.refundedMinor, currency: o.currency, status: "SUCCEEDED", reason: "部分退货", createdAt: o.createdAt }] : [],
        }, 0, acts);
      }),
      logs: (no) => run(() => ({ payOrderNo: no, entries: [{ kind: "API_REQUEST", traceId: "tr-1", at: getOrder(no).createdAt, title: "创建支付单", status: "OK", elapsedMs: 42, note: null }, { kind: "CHANNEL_CALL", traceId: "tr-1", at: (getOrder(no).createdAt ?? 0) + 1200, title: "调用渠道下单", status: "OK", elapsedMs: 380, note: null }] })),
      recover: (no) => run(() => { const o = getOrder(no); need(["AWAITING_PAYMENT", "AWAITING_CONFIRM", "AUTHORIZED"].includes(o.status), "当前状态不需要补查"); patchOrder(no, { status: "SUCCEEDED", paidMinor: o.amountMinor, succeededAt: Date.now(), followupStatus: null }); return { payOrderNo: no, accepted: true }; }),
      manualSettle: (no, b) => run(() => { reasonOf(b.reason, 4); const o = getOrder(no); need(!!o.payChannel && o.status !== "SUCCEEDED", "当前状态不能人工确认到账"); patchOrder(no, { status: "SUCCEEDED", paidMinor: b.amountMinor, succeededAt: b.paidAt, followupStatus: null }); return { payOrderNo: no, settled: true }; }),
      dismiss: (no, reason) => run(() => { reasonOf(reason, 4); need(getOrder(no).followupStatus === "ESCALATED", "没有待人工处理的跟进"); patchOrder(no, { followupStatus: "CANCELLED" }); return { payOrderNo: no, dismissed: 1 }; }),
    },
    exceptions: {
      list: (q) => pageOf(exceptions.all(), q, ["payOrderNo"]),
      refund: (id, reason) => run(async () => { reasonOf(reason); need(exceptions.get(String(id)).status === "OPEN", "只能处理待处理的异常"); await exceptions.update(String(id), { status: "AUTO_REFUNDING", note: reason, handledBy: "admin" }); return { id, action: "REFUND" }; }),
      ignore: (id, reason) => run(async () => { reasonOf(reason, 4); need(exceptions.get(String(id)).status === "OPEN", "只能处理待处理的异常"); await exceptions.update(String(id), { status: "IGNORED", note: reason, handledBy: "admin" }); return { id, action: "IGNORE" }; }),
    },
    merchants: {
      list: (q) => pageOf(merchants.all(), q, ["merchantNo", "displayName", "entityName"]),
      detail: (no) => run(() => {
        const m = merchants.get(no);
        return wrap({
          merchant: m,
          sourceLinks: [{ linkNo: `SL${no}`, sourceSystem: "AI_SHOP", sourceScope: "ENTITY", externalEntityId: m.entityNo ?? "", entityName: m.entityName, verifyStatus: m.verifyStatus, verifiedBy: m.verifyStatus === "VERIFIED" ? "admin" : null, verifiedAt: m.verifyStatus === "VERIFIED" ? T0 : null, version: 1 }],
          profiles: [{ revisionNo: `PR${no}-1`, revision: 1, purpose: "ONBOARDING", reviewStatus: m.profileStatus === "NONE" ? "SUBMITTED" : m.profileStatus, submittedBy: "bd01", submittedAt: T0 - 72 * H, reviewedBy: null, reviewedAt: null, reviewReason: null, version: 1 }],
        }, m.version, m.status === "ACTIVE" ? ["suspend"] : m.status === "SUSPENDED" ? ["submitResume", "activate"] : []);
      }),
      suspend: (no, c) => run(() => setStatus(merchants, no, c, ["ACTIVE"], "SUSPENDED")),
      activate: (no, c) => run(() => setStatus(merchants, no, c, ["SUSPENDED"], "ACTIVE")),
    },
    stores: {
      list: (q) => pageOf(stores.all(), { ...q, syncIssue: undefined }, ["storeNo", "storeName", "merchantName"]),
      detail: (no) => run(() => {
        const s = stores.get(no);
        return wrap({ store: s, associations: [{ linkNo: `AS${no}`, merchantNo: s.merchantNo ?? "", merchantName: s.merchantName, origin: "SOURCE_LINK", validFrom: T0 - 60 * 24 * H, validTo: null, endedReason: null }], pendingChange: s.pendingChange },
          s.version, s.pendingChange ? ["editCollectionPolicy", "submitAssociationApproval", "cancelAssociationChange", "executeAssociationChange"] : ["editCollectionPolicy", "requestAssociationChange"]);
      }),
      policy: (no) => run(() => { const p = policies.get(no) ?? { mode: stores.get(no).configMode, productCodes: [], version: 0 }; return { ownerType: "STORE", ownerRef: no, mode: p.mode, productCodes: p.productCodes, version: p.version, merchantMode: "UNRESTRICTED", merchantProductCodes: [] }; }),
      savePolicy: (no, b) => run(async () => {
        reasonOf(b.reason);
        const cur = policies.get(no) ?? { mode: stores.get(no).configMode, productCodes: [], version: 0 };
        if (cur.version !== b.expectedVersion) throw new MockPayError("VERSION_CONFLICT", "记录已被他人修改，请刷新后重试");
        policies.set(no, { mode: b.mode, productCodes: b.mode === "OVERRIDE" ? b.productCodes : [], version: cur.version + 1 });
        await stores.update(no, { configMode: b.mode });
        return ok(no, b.mode, cur.version + 1);
      }),
      requestChange: (no, b) => run(async () => { reasonOf(b.reason); need(!stores.get(no).pendingChange, "已有待处理的归属变更"); const r = await stores.update(no, { pendingChange: { targetMerchantNo: b.targetMerchantNo, reason: b.reason, requestedBy: "admin", requestedAt: Date.now() } }, b.expectedVersion); return ok(no, "PENDING_APPROVAL", r.version); }),
      cancelChange: (no, c) => run(async () => { cmdCheck(c); need(!!stores.get(no).pendingChange, "没有待处理的归属变更"); const r = await stores.update(no, { pendingChange: null }, c.expectedVersion); return ok(no, "CANCELLED", r.version); }),
      executeChange: (no, c) => run(async () => {
        cmdCheck(c); const s = stores.get(no); need(!!s.pendingChange, "没有待处理的归属变更");
        const target = merchants.get(s.pendingChange!.targetMerchantNo);
        const r = await stores.update(no, { merchantNo: target.merchantNo, merchantName: target.displayName, pendingChange: null }, c.expectedVersion);
        return ok(no, "EXECUTED", r.version);
      }),
    },
    onboardings: {
      list: (q) => pageOf(onboardings.all(), q, ["onboardingNo", "merchantNo", "merchantName"]),
      detail: (no) => run(() => {
        const o = onboardings.get(no);
        const acts = o.status === "DRAFT" ? ["submit", "cancel"] : o.status === "SUBMITTED" && o.reviewStatus === "PENDING" ? ["review", "cancel"]
          : o.reviewStatus === "NEED_MORE" ? ["submit", "cancel"] : o.reviewStatus === "APPROVED" && o.status === "SUBMITTED" ? ["dispatch"] : o.status === "CHANNEL_REVIEW" ? ["refresh"] : [];
        return wrap({ onboarding: o, versions: o.materialVersion ? [{ versionIndex: o.materialVersion, reviewStatus: o.reviewStatus, reviewReason: o.rejectReason, reviewedBy: null, status: o.status, submittedBy: "bd01", submittedAt: o.updatedAt, materials: [{ fieldCode: "legalForm", fieldKind: "TEXT", display: "有限公司" }, { fieldCode: "settleAccount", fieldKind: "CIPHER", display: "••••5678" }] }] : [] }, o.version, acts);
      }),
      create: (b) => run(async () => {
        const m = merchants.get(b.merchantNo);
        const row: OnboardingRow = { onboardingNo: `OB${4001 + onboardings.all().length}`, merchantNo: m.merchantNo, merchantName: m.displayName, payChannel: b.payChannel, submitMode: "MANUAL", materialVersion: 0, reviewStatus: "NONE", status: "DRAFT", channelApplyNo: null, signUrl: null, rejectReason: null, payMerchantNo: null, updatedAt: Date.now(), version: 1 };
        await onboardings.insert(row);
        return wrap({ onboarding: row, versions: [] }, row.version, ["submit", "cancel"]);
      }),
      submit: (no, b) => run(async () => { const o = onboardings.get(no); need(o.status === "DRAFT" || o.reviewStatus === "NEED_MORE", "当前状态不能提交资料"); const r = await onboardings.update(no, { status: "SUBMITTED", reviewStatus: "PENDING", materialVersion: b.materialVersion, rejectReason: null, updatedAt: Date.now() }, b.expectedVersion); return ok(no, r.status, r.version); }),
      review: (no, b) => run(async () => { reasonOf(b.reason); const o = onboardings.get(no); need(o.reviewStatus === "PENDING", "不在待审核状态"); const reviewStatus = b.decision === "APPROVE" ? "APPROVED" : b.decision === "REJECT" ? "REJECTED" : "NEED_MORE"; const r = await onboardings.update(no, { reviewStatus, status: b.decision === "REJECT" ? "REJECTED" : "SUBMITTED", rejectReason: b.decision === "APPROVE" ? null : b.reason, updatedAt: Date.now() }, b.expectedVersion); return ok(no, reviewStatus, r.version, `AP${no}`); }),
      dispatch: (no, b) => run(async () => { reasonOf(b.reason); need(onboardings.get(no).reviewStatus === "APPROVED", "平台审核通过后才能送件"); const r = await onboardings.update(no, { status: "CHANNEL_REVIEW", channelApplyNo: b.channelApplyNo, updatedAt: Date.now() }, b.expectedVersion); return ok(no, r.status, r.version); }),
      refresh: (no, c) => run(async () => { cmdCheck(c); const r = await onboardings.update(no, { updatedAt: Date.now() }, c.expectedVersion); return ok(no, r.status, r.version); }),
      cancel: (no, c) => run(() => setStatus(onboardings, no, c, ["DRAFT", "SUBMITTED"], "CANCELLED")),
    },
    approvals: {
      list: (q) => {
        const view = (q.view as string) ?? "CANDIDATE";
        const rows = approvals.all().filter((a) => view === "ALL" ? true : view === "MINE" ? a.applicantNo === "admin" : view === "CLAIMED" ? a.taskStatus === "CLAIMED" && a.assigneeNo === "admin" : a.taskStatus === "READY");
        return pageOf(rows.map(({ taskNo: _t, taskVersion: _v, taskStatus: _s, ...r }) => r), { ...q, view: undefined }, ["approvalNo", "subjectTitle"]);
      },
      detail: (no) => run((): ApprovalDetail => {
        const a = approvals.get(no);
        const task: ApprovalTask = { taskNo: a.taskNo, approvalNo: no, nodeNo: 1, roleRef: "REVIEWER", status: a.taskStatus, assigneeNo: a.assigneeNo, decision: null, decisionReason: null, decidedAt: null, version: a.taskVersion };
        const acts = a.status === "APPROVED" ? ["execute"] : a.status.startsWith("PENDING") ? (a.taskStatus === "READY" ? ["claim", "assign", "withdraw"] : ["review", "release", "assign", "withdraw"]) : [];
        return {
          record: { approvalNo: no, subjectType: a.bizType, action: a.action, subjectRef: a.resourceNo ?? no, materialVersion: a.materialVersion, status: a.status, version: a.version, expiresAt: a.expiresAt, activeTask: a.status.startsWith("PENDING") ? task : null, execution: { status: a.executionStatus, executedAt: null, executedBy: null }, applicantNo: a.applicantNo, rejectReason: null, createdAt: a.createdAt },
          subjectTitle: a.subjectTitle, snapshotJson: JSON.stringify({ subject: a.subjectTitle, requestedBy: a.applicantNo }, null, 2), sourcePage: null, tasks: [task], decisions: [], timeline: [], allowedActions: acts,
        };
      }),
      review: (no, b) => run(async () => { reasonOf(b.reason); const a = approvals.get(no); need(a.taskStatus === "CLAIMED", "请先认领"); const r = await approvals.update(no, { status: b.decision === "APPROVE" ? "APPROVED" : b.decision === "REJECT" ? "REJECTED" : "CANCELLED", taskStatus: "DONE" }, b.expectedVersion); return r; }),
      withdraw: (no, c) => run(async () => { cmdCheck(c); need(approvals.get(no).status.startsWith("PENDING"), "只能撤回审批中的申请"); return approvals.update(no, { status: "CANCELLED", taskStatus: "DONE" }, c.expectedVersion); }),
      execute: (no, c) => run(async () => { cmdCheck(c); need(approvals.get(no).status === "APPROVED", "审批通过后才能执行"); return approvals.update(no, { executionStatus: "SUCCEEDED" }, c.expectedVersion); }),
      claim: (taskNo, c) => run(async () => { cmdCheck(c); const a = approvals.all().find((x) => x.taskNo === taskNo)!; need(a.taskStatus === "READY", "任务已被认领"); if (a.taskVersion !== c.expectedVersion) throw new MockPayError("VERSION_CONFLICT", "记录已被他人修改，请刷新后重试"); await approvals.update(a.approvalNo, { taskStatus: "CLAIMED", assigneeNo: "admin", taskVersion: a.taskVersion + 1 }); return { taskNo, approvalNo: a.approvalNo, nodeNo: 1, roleRef: null, status: "CLAIMED", assigneeNo: "admin", decision: null, decisionReason: null, decidedAt: null, version: a.taskVersion + 1 }; }),
      release: (taskNo, c) => run(async () => { cmdCheck(c); const a = approvals.all().find((x) => x.taskNo === taskNo)!; need(a.taskStatus === "CLAIMED", "任务未被认领"); await approvals.update(a.approvalNo, { taskStatus: "READY", assigneeNo: null, taskVersion: a.taskVersion + 1 }); return { taskNo, approvalNo: a.approvalNo, nodeNo: 1, roleRef: null, status: "READY", assigneeNo: null, decision: null, decisionReason: null, decidedAt: null, version: a.taskVersion + 1 }; }),
      assign: (taskNo, b) => run(async () => { cmdCheck(b); const a = approvals.all().find((x) => x.taskNo === taskNo)!; await approvals.update(a.approvalNo, { taskStatus: "CLAIMED", assigneeNo: b.assigneeNo, taskVersion: a.taskVersion + 1 }); return { taskNo, approvalNo: a.approvalNo, nodeNo: 1, roleRef: null, status: "CLAIMED", assigneeNo: b.assigneeNo, decision: null, decisionReason: null, decidedAt: null, version: a.taskVersion + 1 }; }),
    },
    channels: {
      list: (q) => pageOf(channels.all(), q, ["channelCode", "name"]),
      detail: (code) => run(() => { const c = channels.get(code); return wrap(c, c.version, ["EDIT", c.status === "ENABLED" ? "DISABLE" : "ENABLE", "DIAGNOSE"]); }),
      create: (b) => run(async () => { reasonOf(b.reason); need(b.expectedVersion === 0, "新建时版本必须为 0"); if (channels.find(b.channelCode)) throw new MockPayError("STATE_CONFLICT", "渠道编码已存在"); const row: ChannelRow = { channelCode: b.channelCode, name: b.name, channelType: b.channelType as ChannelRow["channelType"], adapterCode: b.adapterCode, platformCredentialKeyId: b.platformCredentialKeyId ?? null, status: "DISABLED", sortNo: b.sortNo ?? 0, enabledProductCount: 0, endpoints: b.endpoints.map((x) => ({ ...x, environment: x.environment as "SANDBOX", callbackStyle: x.callbackStyle as "PATH" | null, enabled: true })), version: 1, updatedAt: Date.now(), updatedBy: "admin", market: b.market, testChannel: !!b.testChannel }; await channels.insert(row); return wrap(row, 1, ["EDIT", "ENABLE", "DIAGNOSE"]); }),
      update: (code, b) => run(async () => { reasonOf(b.reason); const r = await channels.update(code, { name: b.name, adapterCode: b.adapterCode, sortNo: b.sortNo ?? 0, market: b.market, testChannel: !!b.testChannel, platformCredentialKeyId: b.platformCredentialKeyId ?? null, endpoints: b.endpoints.map((x) => ({ ...x, environment: x.environment as "SANDBOX", callbackStyle: x.callbackStyle as "PATH" | null, enabled: true })), updatedAt: Date.now() }, b.expectedVersion); return ok(code, r.status, r.version); }),
      enable: (code, c) => run(() => setStatus(channels, code, c, ["DISABLED"], "ENABLED")),
      disable: (code, c) => run(() => setStatus(channels, code, c, ["ENABLED"], "DISABLED")),
      diagnose: (code, c) => run(() => { cmdCheck(c); channels.get(code); return { jobNo: `JOB${Date.now() % 100000}`, status: "SUCCEEDED", errorCode: null }; }),
    },
    products: {
      list: (q) => pageOf(products.all(), q, ["productCode", "displayName"]),
      detail: (code) => run(() => { const p = products.get(code); return wrap(p, p.version, ["EDIT", p.status === "ENABLED" ? "DISABLE" : "ENABLE"]); }),
      create: (b) => run(async () => { reasonOf(b.reason); need(b.expectedVersion === 0, "新建时版本必须为 0"); if (products.find(b.productCode)) throw new MockPayError("STATE_CONFLICT", "产品编码已存在"); const { expectedVersion: _v, reason: _r, ...rest } = b; const row: ProductRow = { ...rest, status: "DISABLED", version: 1, updatedAt: Date.now(), updatedBy: "admin" }; await products.insert(row); return wrap(row, 1, ["EDIT", "ENABLE"]); }),
      update: (code, b) => run(async () => { reasonOf(b.reason); const { expectedVersion, reason: _r, ...rest } = b; const r = await products.update(code, { ...rest, updatedAt: Date.now() }, expectedVersion); return ok(code, r.status, r.version); }),
      enable: (code, c) => run(() => setStatus(products, code, c, ["DISABLED"], "ENABLED")),
      disable: (code, c) => run(() => setStatus(products, code, c, ["ENABLED"], "DISABLED")),
    },
    entries: {
      list: (q) => pageOf(entries.all(), q, ["entryNo", "name", "storeNo"]),
      detail: (no) => run(() => { const x = entries.get(no); return wrap(x, x.version, x.status === "ACTIVE" ? ["update", "disable"] : x.readiness?.ready ? ["update", "enable"] : ["update"]); }),
      create: (b) => run(async () => { reasonOf(b.reason); need(b.expectedVersion === 0, "新建时版本必须为 0"); const { expectedVersion: _v, reason: _r, ...rest } = b; const row: EntryRow = { entryNo: `CE${6001 + entries.all().length}`, appNo: null, allowedDomain: null, sessionTimeoutSeconds: null, clientAppIdentifier: null, terminalNo: null, ...rest, status: "DRAFT", statusReason: null, version: 1, readiness: { ready: true, reasons: [] } }; await entries.insert(row); return wrap(row, 1, ["update", "enable"]); }),
      update: (no, b) => run(async () => { reasonOf(b.reason); const { expectedVersion, reason: _r, merchantNo: _m, storeNo: _s, accessMethod: _a, ...rest } = b; const r = await entries.update(no, rest, expectedVersion); return ok(no, r.status, r.version); }),
      enable: (no, c) => run(async () => { cmdCheck(c); const x = entries.get(no); need(!!x.readiness?.ready, `未就绪：${x.readiness?.reasons.join("、")}`); return setStatus(entries, no, c, ["DRAFT", "DISABLED"], "ACTIVE"); }),
      disable: (no, c) => run(() => setStatus(entries, no, c, ["ACTIVE"], "DISABLED")),
    },
    merchantChanges: {
      list: (q) => pageOf(merchants.all().filter((m) => revisions.get(m.merchantNo)?.reviewStatus === "SUBMITTED").map((m) => { const r = revisions.get(m.merchantNo)!; return { ...m, profileStatus: "SUBMITTED", latestProfile: { revisionNo: r.revisionNo, revision: r.revision, purpose: r.purpose, reviewStatus: r.reviewStatus } }; }), { ...q, profileStatus: undefined }, ["merchantNo", "displayName"]),
      revision: (m, rev) => run(() => { const r = revisions.get(m); if (!r || r.revisionNo !== rev) throw new MockPayError("NOT_FOUND", "资料版本不存在"); return wrap({ ...r, market: "TW", reviewedBy: null, reviewedAt: null, reviewReason: null, materials: [{ kind: "FILE", ref: "file:biz-license.pdf", purpose: "BUSINESS_LICENSE" }] }, r.version, r.reviewStatus === "SUBMITTED" ? ["submitApproval", "approve", "reject", "requestMore"] : []); }),
      decide: (m, rev, d, c) => run(async () => { cmdCheck(c); const r = revisions.get(m); if (!r || r.revisionNo !== rev) throw new MockPayError("NOT_FOUND", "资料版本不存在"); need(r.reviewStatus === "SUBMITTED", "这版资料已不在待审核状态"); if (r.version !== c.expectedVersion) throw new MockPayError("VERSION_CONFLICT", "记录已被他人修改，请刷新后重试"); const st = d === "approve" ? "APPROVED" : d === "reject" ? "REJECTED" : "NEED_MORE"; revisions.set(m, { ...r, reviewStatus: st, version: r.version + 1 }); await merchants.update(m, { profileStatus: st }); return ok(rev, st, r.version + 1, d === "approve" ? `AP-${rev}` : null); }),
    },
    refunds: {
      list: (q) => run(() => refunds.filter((r) => !q.status || r.status === q.status).slice(0, q.limit ?? 200)),
      review: (no, b) => run(() => {
        reasonOf(b.reason); const r = refunds.find((x) => x.payRefundNo === no); if (!r) throw new MockPayError("NOT_FOUND", "退款不存在");
        need(["PENDING_REVIEW", "PENDING_SECOND_REVIEW"].includes(r.status), "这笔退款不在待复核状态");
        need(r.requestedBy !== "admin", "申请人不能复核自己的退款");
        need(refundReviewers.get(no) !== "admin", "两次复核需要不同的人");
        const status = !b.approve ? "REJECTED" : r.status === "PENDING_REVIEW" ? "PENDING_SECOND_REVIEW" : "APPROVED";
        refundReviewers.set(no, "admin");
        const next = { ...r, status }; refunds = refunds.map((x) => (x.payRefundNo === no ? next : x)); return next;
      }),
    },
    terminals: {
      list: (q) => pageOf(terminals.all(), q, ["terminalNo", "name", "serialNo", "storeNo"]),
      detail: (no) => run(() => { const t = terminals.get(no); const acts = t.deviceStatus === "RETIRED" ? [] : t.configurationStatus === "ENABLED" ? ["disable"] : ["update", ...(t.verificationStatus === "UNVERIFIED" ? ["verify"] : []), ...(t.configurationEligibility?.canEnable ? ["enable"] : [])]; return wrap(t, t.version, acts); }),
      create: (b) => run(async () => { reasonOf(b.reason); const row: TerminalRow = { terminalNo: `TM${8001 + terminals.all().length}`, ...b, payMerchantNo: b.payMerchantNo || null, channelTerminalNo: b.channelTerminalNo || null, model: b.model || null, receiptName: b.receiptName || null, remark: b.remark || null, market: "TW", currency: "TWD", configurationStatus: "DRAFT", verificationStatus: "UNVERIFIED", configuredBy: "admin", verifiedBy: null, evidenceRef: null, deviceStatus: "REGISTERED", credentialRef: null, statusReason: null, version: 1, configurationEligibility: { canEnable: false, reasons: ["UNVERIFIED"] }, readiness: { ready: false, canReceivePayments: false, reasons: ["UNVERIFIED", "DEVICE_NOT_ACTIVE"] } }; await terminals.insert(row); return wrap(row, 1, ["update", "verify"]); }),
      update: (no, b) => run(async () => { reasonOf(b.reason); const { expectedVersion, reason: _r, ...rest } = b; const r = await terminals.update(no, { ...rest, payMerchantNo: rest.payMerchantNo || null }, expectedVersion); return ok(no, r.configurationStatus, r.version); }),
      verify: (no, b) => run(async () => { reasonOf(b.reason); const t = terminals.get(no); need(t.verificationStatus === "UNVERIFIED", "已核验"); need(t.configuredBy !== "admin2", "核验人不能是配置人"); const r = await terminals.update(no, { verificationStatus: "VERIFIED", verifiedBy: "admin", evidenceRef: b.evidenceRef, configurationEligibility: { canEnable: true, reasons: [] } }, b.expectedVersion); return ok(no, r.configurationStatus, r.version); }),
      enable: (no, c) => run(async () => { need(terminals.get(no).verificationStatus === "VERIFIED", "核验通过后才能启用"); return setStatusField(terminals, no, c, "configurationStatus", ["DRAFT", "DISABLED"], "ENABLED"); }),
      disable: (no, c) => run(() => setStatusField(terminals, no, c, "configurationStatus", ["ENABLED"], "DISABLED")),
      activationCode: (no) => run(() => { need(terminals.get(no).deviceStatus !== "RETIRED", "已退役的终端不能生成激活码"); return { activationCode: `${Math.floor(100000 + Math.random() * 900000)}` }; }),
      lost: (no) => run(async () => { need(terminals.get(no).deviceStatus !== "RETIRED", "已退役"); await terminals.update(no, { deviceStatus: "LOST", configurationStatus: "DISABLED" }); return null; }),
      retire: (no, c) => run(async () => { cmdCheck(c); const r = await terminals.update(no, { deviceStatus: "RETIRED", configurationStatus: "DISABLED" }, c.expectedVersion); return { terminalNo: no, serialNo: r.serialNo, deviceStatus: "RETIRED", retirementStatus: "COMPLETED", version: r.version }; }),
    },
    apps: {
      list: (q) => pageOf(apps.all().map(({ grants: _g, ...a }) => a), q, ["appNo", "name"]),
      detail: (no) => run(() => { const { grants: _g, ...a } = apps.get(no); const acts = ({ DRAFT: ["update", "scopes", "callback", "verifyCallback", "enable", "revoke"], ACTIVE: ["update", "scopes", "suspend", "revoke"], SUSPENDED: ["update", "scopes", "callback", "verifyCallback", "resume", "revoke"] } as Record<string, string[]>)[a.status] ?? []; return wrap({ app: a, registeredBy: "admin", callbackSetBy: "admin", callbackVerifiedBy: a.callbackStatus === "VERIFIED" ? "admin2" : null, callbackEvidenceRef: null, statusReason: null, enableApprovalNo: null }, a.version, acts); }),
      grants: (no) => run(() => ({ records: apps.get(no).grants, total: apps.get(no).grants.length })),
      create: (b) => run(async () => { reasonOf(b.reason); const row = { appNo: `APP${9001 + apps.all().length}`, name: b.name, purpose: b.purpose, ownerKind: b.ownerKind as "MERCHANT", merchantNo: b.merchantNo, environment: (b.environment ?? "SANDBOX") as "SANDBOX", scopes: b.scopes, notifyUrl: b.notifyUrl, callbackDomains: b.callbackDomains, callbackStatus: "UNVERIFIED", ipWhitelist: b.ipWhitelist, rateLimitQps: b.rateLimitQps, dailyRefundLimitMinor: b.dailyRefundLimitMinor, credentialStatus: "NONE", grantCount: 0, status: "DRAFT", lastAccessAt: null, lastAccessOutcome: null, version: 1, grants: [] }; await apps.insert(row); const { grants: _g, ...a } = row; return wrap({ app: a, registeredBy: "admin", callbackSetBy: null, callbackVerifiedBy: null, callbackEvidenceRef: null, statusReason: null, enableApprovalNo: null }, 1, ["update"]); }),
      update: (no, b) => run(async () => { reasonOf(b.reason); const { expectedVersion, reason: _r, ...rest } = b; const r = await apps.update(no, rest, expectedVersion); return ok(no, r.status, r.version); }),
      scopes: (no, b) => run(async () => { reasonOf(b.reason); const r = await apps.update(no, { scopes: b.scopes }, b.expectedVersion); return ok(no, r.status, r.version); }),
      callback: (no, b) => run(async () => { reasonOf(b.reason); const r = await apps.update(no, { notifyUrl: b.notifyUrl, callbackDomains: b.callbackDomains, callbackStatus: "UNVERIFIED" }, b.expectedVersion); return ok(no, r.status, r.version); }),
      verifyCallback: (no, b) => run(async () => { reasonOf(b.reason); const r = await apps.update(no, { callbackStatus: "VERIFIED" }, b.expectedVersion); return ok(no, r.status, r.version); }),
      command: (no, action, c) => run(() => {
        const from: Record<string, string[]> = { enable: ["DRAFT"], resume: ["SUSPENDED"], suspend: ["ACTIVE"], revoke: ["DRAFT", "ACTIVE", "SUSPENDED"] };
        const to: Record<string, string> = { enable: "ACTIVE", resume: "ACTIVE", suspend: "SUSPENDED", revoke: "REVOKED" };
        if (action === "enable" || action === "resume") need(apps.get(no).callbackStatus === "VERIFIED", "回调地址核验通过后才能启用");
        return setStatus(apps, no, c, from[action], to[action]);
      }),
      issueCredential: (no, b) => run(async () => { reasonOf(b.reason); need(apps.get(no).status !== "REVOKED", "已吊销的应用不能签发密钥"); await apps.update(no, { credentialStatus: "ACTIVE" }); const secret = Array.from({ length: 32 }, () => "abcdefghijkmnpqrstuvwxyz23456789"[Math.floor(Math.random() * 32)]).join(""); return { keyId: Date.now() % 100000, versionIndex: 1, serial: `${no}-1`, secret, fingerprint: `SHA256:${secret.slice(0, 8)}…`, status: "ACTIVE" }; }),
    },
    traces: {
      requestLogs: (q) => pageOf(reqLogs, q, ["requestId", "path", "appNo"]),
      requestLog: (id) => run(() => { const r = reqLogs.find((x) => x.requestId === id); if (!r) throw new MockPayError("NOT_FOUND", "请求不存在"); return { record: r, requestMasked: `{"amount":3500,"cardNo":"****1234"}`, responseMasked: `{"code":"${r.businessCode}"}`, sourceType: r.entry, operatorNo: r.entry === "OPS_API" ? "admin" : null, idempotencyKey: `idem-${id}`, calls: calls.filter((c) => c.requestId === id), callbacks: [] }; }),
      calls: (q) => pageOf(calls, q, ["callNo", "bizNo", "requestId"]),
      call: (no) => run(() => { const r = calls.find((x) => x.callNo === no); if (!r) throw new MockPayError("NOT_FOUND", "调用不存在"); return { callNo: no, attempts: [{ record: r, httpMethod: "POST", urlPath: r.api, httpStatus: r.transport === "TIMEOUT" ? null : 200, requestMasked: `{"MerchantID":"****${r.payMerchantNo?.slice(-3)}"}`, responseMasked: r.transport === "TIMEOUT" ? null : `{"Status":"SUCCESS"}`, errorMessage: r.transport === "TIMEOUT" ? "read timeout after 10s" : null, breakerState: "CLOSED" }], callbacks: callbacks.filter((c) => c.bizNo === r.bizNo) }; }),
      callbacks: (q) => pageOf(callbacks, q, ["callbackNo", "bizNo", "channelTradeNo"]),
      callback: (no) => run(() => { const r = callbacks.find((x) => x.callbackNo === no); if (!r) throw new MockPayError("NOT_FOUND", "回调不存在"); return { record: r, headersMasked: "content-type: application/x-www-form-urlencoded", bodyMasked: "Status=SUCCESS&TradeInfo=****", outcomeReason: r.outcome === "REJECTED" ? "签名校验失败" : null, ackBody: "SUCCESS" }; }),
    },
    alerts: {
      events: (q) => pageOf(alertEvents.all(), q, ["eventNo", "ruleNo"]),
      event: (no) => run(() => { const a = alertEvents.get(no); const acts = a.status === "OPEN" ? ["claim", "resolve"] : a.status === "CLAIMED" ? ["assign", "resolve"] : a.status === "RESOLVED" ? ["close"] : []; return wrap(a, a.version, acts); }),
      claim: (no, c) => run(async () => { cmdCheck(c); need(alertEvents.get(no).status === "OPEN", "只能认领待处理的告警"); const r = await alertEvents.update(no, { status: "CLAIMED", assigneeNo: "admin" }, c.expectedVersion); return ok(no, r.status, r.version); }),
      assign: (no, b) => run(async () => { cmdCheck(b); need(["OPEN", "CLAIMED"].includes(alertEvents.get(no).status), "当前状态不能指派"); const r = await alertEvents.update(no, { status: "CLAIMED", assigneeNo: b.assigneeNo }, b.expectedVersion); return ok(no, r.status, r.version); }),
      resolve: (no, b) => run(async () => { cmdCheck(b); need(["OPEN", "CLAIMED"].includes(alertEvents.get(no).status), "当前状态不能处置"); const r = await alertEvents.update(no, { status: "RESOLVED", resolution: b.resolution, resolutionNote: b.reason, relatedRef: b.relatedRef }, b.expectedVersion); return ok(no, r.status, r.version); }),
      close: (no, c) => run(() => setStatus(alertEvents, no, c, ["RESOLVED"], "CLOSED")),
      rules: (q) => pageOf(alertRules.all(), q, ["ruleNo", "name"]),
      rule: (no) => run(() => { const r = alertRules.get(no); return { ...wrap(r, r.version, r.status === "DRAFT" ? ["update", "enable"] : r.status === "PUBLISHED" ? ["update", "disable"] : ["update"]), revisions: [r] }; }),
      createRule: (b) => run(async () => { reasonOf(b.reason); need(b.expectedVersion === 0, "新建时版本必须为 0"); const { expectedVersion: _v, reason: _r, ...rest } = b; const row: AlertRuleRow = { ruleNo: `AR${2001 + alertRules.all().length}`, revisionNo: 1, ...rest, unit: null, status: "DRAFT", enteredBy: "admin", publishedBy: null, publishedRevisionNo: null, version: 1 }; await alertRules.insert(row); return wrap(row, 1, ["update", "enable"]); }),
      updateRule: (no, b) => run(async () => { reasonOf(b.reason); const { expectedVersion, reason: _r, ...rest } = b; const r = await alertRules.update(no, { ...rest, status: "DRAFT", enteredBy: "admin" }, expectedVersion); return ok(no, r.status, r.version); }),
      enableRule: (no, c) => run(async () => { cmdCheck(c); need(alertRules.get(no).enteredBy !== "admin", "发布人不能是录入人"); return setStatus(alertRules, no, c, ["DRAFT"], "PUBLISHED"); }),
      disableRule: (no, c) => run(() => setStatus(alertRules, no, c, ["PUBLISHED", "DRAFT"], "RETIRED")),
    },
    approvalFlows: {
      list: (q) => pageOf(flows, q, ["flowNo", "name"]),
      detail: (no) => run(() => { const f = flows.find((x) => x.flowNo === no); if (!f) throw new MockPayError("NOT_FOUND", "流程不存在"); return { record: f, revisions: [f] }; }),
    },
    settings: {
      get: () => run(() => settings),
      save: (b) => run(() => { reasonOf(b.reason); if (b.expectedVersion !== settings.version) throw new MockPayError("VERSION_CONFLICT", "记录已被他人修改，请刷新后重试"); settings = { ...settings, defaultChannelCode: b.defaultChannelCode, channelOrder: b.channelOrder, version: settings.version + 1, updatedAt: Date.now(), updatedBy: "admin" }; return settings; }),
    },
    dataScopes: {
      list: (q) => pageOf(scopes.all(), q, ["scopeNo", "subjectNo"]),
      detail: (no) => run(() => { const s = scopes.get(no); return wrap(s, s.version, s.status === "DRAFT" ? ["update", "enable", "revoke"] : ["ACTIVE", "EXPIRED"].includes(s.status) ? ["update", "revoke"] : []); }),
      create: (b) => run(async () => { reasonOf(b.reason); need(b.expectedVersion === 0, "新建时版本必须为 0"); const row = { scopeNo: `DS${6001 + scopes.all().length}`, scopeType: b.scopeType as "USER", subjectNo: b.subjectNo, marketSet: b.marketSet, entityNos: b.entityNos, storeNos: b.storeNos, fundsAccess: b.fundsAccess as "NONE", expiresAt: b.expiresAt, status: "DRAFT" as const, reason: b.reason, enteredBy: "admin", enabledBy: null, revokedAt: null, version: 0 }; await scopes.insert(row); return wrap(row, 0, ["update", "enable", "revoke"]); }),
      update: (no, b) => run(async () => { reasonOf(b.reason); const r = await scopes.update(no, { marketSet: b.marketSet, entityNos: b.entityNos, storeNos: b.storeNos, fundsAccess: b.fundsAccess as "NONE", expiresAt: b.expiresAt, reason: b.reason, status: "DRAFT" }, b.expectedVersion); return ok(no, r.status, r.version); }),
      enable: (no, c) => run(() => setStatus(scopes, no, c, ["DRAFT"], "ACTIVE")),
      revoke: (no, c) => run(async () => { const r = await setStatus(scopes, no, c, ["DRAFT", "ACTIVE", "EXPIRED"], "REVOKED"); await scopes.update(no, { revokedAt: Date.now() }); return r; }),
    },
  };
}
