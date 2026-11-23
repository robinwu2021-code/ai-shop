// 支付中心 mock：内存数据 + 与 near-pay 同口径的状态机与乐观锁（版本不对 409、状态不允许 409）。
// 只在 NEXT_PUBLIC_USE_MOCK≠0 时使用；数据台湾主场景（TWD、台北门店），见记忆「TW 主场景」。
import { MockPayError, VersionedTable, mockDelay, pageOf } from "./mock-store";
import type { Cmd, PayCenterApi } from "./api";
import type {
  AttemptListRow, ChannelCostRow, CommissionRuleRow, LedgerEntryRow, PayoutRow, ReconDiffRow, SettlementRow, StoredValueAccountRow, StoredValueTransactionRow, WithdrawalRow,
  AlertEventRow, AlertRuleRow, AppGrant, AppRow, ApprovalFlowRow, ChannelCallRow, ChannelCallbackRow, OperatingSettings, RefundListRow, RequestLogRow, TerminalRow,
  ActionResult, ApprovalDetail, ApprovalRow, ApprovalTask, ChannelRow, DataScopeRow, Detail, EntryRow, ExceptionRow,
  MerchantRow, OnboardingRow, OrderRow, ProductRow, StoreRow, NewebpayModification, OnboardingDraft,
  AccountStatus, ChannelAccountClaim, ChannelAccountDetail, ChannelAccountRow, StoreBinding, VerificationStatus,
  ChannelAccountTransfer, QualificationVersion, StoreMethodView, StoreQrCode,
  MerchantMemberRow, ProfileRevisionRow, RepairRow,
  CredentialRow, CredentialVersion, CredentialPolicy, AuditEventRow,
  PosConfigView, UatRun, SoftPosBinding,
  AppApplicationRow, PlatformKeyRow, PlatformKeyUsage, SignType,
} from "./types";
import { rsaVerifyBase64 } from "./open-api-signer";

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
  // P4：商户默认收款策略、代填资料、主体资料、进件草稿与附件、蓝新修改
  const merchantPolicies = new Map<string, { mode: string; productCodes: string[]; version: number }>();
  const assisted = new Map<string, { version: number; state: string; values: Record<string, string>; revisionNo: string | null }>();
  const mask = (k: string, v: string) => (["registrationNo", "contactPhone"].includes(k) ? `****${v.slice(-4)}` : k === "representativeName" ? `${v.slice(0, 1)}*` : v);
  const drafts = new Map<string, { draftVersion: number; state: "EDITING" | "CONFIRMED" | "FROZEN"; values: Record<string, string>; supplementFields: string[]; supplementReason: string | null; confirmedBy: string | null }>();
  const draftOf = (no: string) => { let d = drafts.get(no); if (!d) { d = { draftVersion: 0, state: "EDITING", values: {}, supplementFields: [], supplementReason: null, confirmedBy: null }; drafts.set(no, d); } return d; };
  const REQUIRED_PLATFORM = ["LEGAL_FORM", "CONTACT_NAME", "CONTACT_PHONE", "SETTLE_ACCOUNT_TYPE", "SETTLE_ACCOUNT"];
  const files = new Map<string, Map<string, { fileNo: string; contentType: string; byteLength: number; blob: Blob }>>();
  const modifications = new Map<string, NewebpayModification[]>();
  let sealSeq = 1;
  let fileSeq = 1;
  const draftView = (no: string): OnboardingDraft => {
    const o = onboardings.get(no); const d = draftOf(no);
    const editable = (o.status === "DRAFT" && ["NONE", "NEED_MORE", "REJECTED"].includes(o.reviewStatus)) || o.status === "REJECTED";
    const filled = Object.keys(d.values).filter((k) => d.values[k]);
    const hasLicense = filled.some((k) => k.startsWith("LICENSE_"));
    const missing = [...REQUIRED_PLATFORM.filter((k) => !d.values[k]), ...(hasLicense ? [] : ["LICENSE_1"])];
    const plain = Object.fromEntries(Object.entries(d.values).filter(([k, v]) => !v.startsWith("cipher:") && !v.startsWith("file:") && !k.startsWith("LICENSE_")));
    return { onboardingNo: no, expectedVersion: o.version, draftVersion: d.draftVersion, state: editable ? d.state : "FROZEN", fields: plain, filledFields: filled, missing, editable,
      fileUploadAvailable: true, supplementFields: d.supplementFields, supplementReason: d.supplementReason, confirmedBy: d.confirmedBy, confirmedAt: d.confirmedBy ? Date.now() : null,
      confirmationMode: d.confirmedBy ? "OPS_ASSISTED" : null, supplementHistory: [], differences: [], channelMissing: [], channelIssues: [], schemaVersion: "NEWEBPAY_NDNA_1_1_1", representativeType: null };
  };
  const checkDraft = (no: string, b: { expectedVersion: number; expectedDraftVersion: number }) => {
    const o = onboardings.get(no); const d = draftOf(no);
    if (o.version !== b.expectedVersion || d.draftVersion !== b.expectedDraftVersion) throw new MockPayError("VERSION_CONFLICT", "记录已被他人修改，请刷新后重试");
    need(draftView(no).editable, "当前状态不能修改资料");
    return d;
  };
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
  const products_name = (code: string) => products.find(code)?.displayName ?? code;
  const products = new VersionedTable<ProductRow>([
    ["NEWEBPAY_CARD", "NEWEBPAY", "信用卡（蓝新）", "CARD"], ["NEWEBPAY_TWQR", "NEWEBPAY", "台湾 Pay QR", "TWQR"], ["LINEPAY_H5", "LINEPAY", "LINE Pay", "LINE_PAY"], ["MOCKPAY_TEST", "MOCKPAY", "测试支付", "TEST_PAY"],
  ].map(([code, ch, name, pm], i) => ({
    productCode: code, channelCode: ch, displayName: name, tradeMode: "ONE_TIME", paymentMethods: [pm],
    accessMethods: [{ accessMethod: "H5", channelMethod: "WEB", enabled: true }], accountModes: ["PLATFORM_SUB", "MERCHANT_OWN"],
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
  const apps = new VersionedTable<AppRow & { grants: AppGrant[] }>([["APP9001", "信义咖啡点餐 App", "MERCHANT", "PM1002", "ACTIVE"], ["APP9002", "平台对账服务", "PLATFORM", null, "DRAFT"], ["APP9003", "松山便当外送", "MERCHANT", "PM1004", "SUSPENDED"], ["APP9004", "台北早餐店 ERP（RSA）", "MERCHANT", "PM1001", "ACTIVE"]].map(([no, name, owner, m, st], i) => ({
    signType: (i === 3 ? "RSA" : "HMAC") as SignType,
    appNo: no as string, name: name as string, purpose: "收款与退款", ownerKind: owner as "MERCHANT", merchantNo: m as string | null, environment: i === 1 ? "SANDBOX" : "LIVE",
    scopes: ["payment.create", "payment.query", "refund.create"], notifyUrl: `https://hooks.example.com/${no}`, callbackDomains: ["hooks.example.com"], callbackStatus: i === 0 || i === 3 ? "VERIFIED" : "UNVERIFIED",
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


  // ── P3a 数据 ──
  const attempts: (AttemptListRow)[] = orders.filter((o) => o.payChannel).map((o, i) => ({
    paymentNo: `PY${o.payOrderNo.slice(2)}`, direction: i % 9 === 8 ? "REFUND" : "PAY", outTradeNo: `OT${o.payOrderNo.slice(2)}`, tradeNo: o.status === "SUCCEEDED" ? `T${o.payOrderNo.slice(-4)}` : null,
    payOrderNo: o.payOrderNo, merchantNo: o.merchantNo, storeNo: o.storeNo, storeName: o.storeName, payChannel: o.payChannel, payMethod: o.payMethod, payMerchantNo: "NWP7001",
    amountMinor: o.amountMinor, currency: o.currency, channelAmountMinor: o.amountMinor, channelCurrency: o.currency, channelFeeMinor: Math.round(o.amountMinor * 0.028),
    status: o.status === "SUCCEEDED" ? "SUCCESS" : ["AWAITING_PAYMENT", "AWAITING_CONFIRM", "AUTHORIZED"].includes(o.status) ? "PENDING" : o.status === "FAILED" ? "FAILED" : o.status === "CLOSED" ? "CLOSED" : "SUCCESS",
    errCode: o.status === "FAILED" ? "CARD_DECLINED" : null, errMsg: o.status === "FAILED" ? "发卡行拒绝" : null, createdAt: o.createdAt, succeededAt: o.succeededAt, closedAt: o.closedAt, reconciledAt: null, version: 1,
  }));
  const ledger: LedgerEntryRow[] = attempts.filter((a) => a.status === "SUCCESS").slice(0, 12).flatMap((a, i) => [
    { entryNo: `V${7700 + i}-1`, voucherNo: `V${7700 + i}`, voucherType: "PAYMENT", lineNo: 1, account: "1122-渠道应收", direction: "D" as const, amountMinor: a.amountMinor, currency: "TWD", payChannel: a.payChannel, market: "TW", fundsPath: "PLATFORM_SUB", payOrderNo: a.payOrderNo, merchantNo: a.merchantNo, storeNo: a.storeNo, bizDate: "2026-11-20", memo: null, reversalOfVoucherNo: null, createdAt: a.succeededAt ?? T0 },
    { entryNo: `V${7700 + i}-2`, voucherNo: `V${7700 + i}`, voucherType: "PAYMENT", lineNo: 2, account: "2241-应付商户", direction: "C" as const, amountMinor: a.amountMinor, currency: "TWD", payChannel: a.payChannel, market: "TW", fundsPath: "PLATFORM_SUB", payOrderNo: a.payOrderNo, merchantNo: a.merchantNo, storeNo: a.storeNo, bizDate: "2026-11-20", memo: null, reversalOfVoucherNo: null, createdAt: a.succeededAt ?? T0 },
  ]);
  const settlements: SettlementRow[] = MERCHANTS.map(([no], i) => ({ settlementNo: `SB2026112${i + 1}`, merchantNo: no, payChannel: "NEWEBPAY", settleCycle: "T+1", currency: "TWD", periodFrom: T0 - 48 * H, periodTo: T0 - 24 * H, dueAt: T0 + 24 * H, releasedAt: i === 0 ? T0 : null, status: ["RELEASED", "RECONCILED", "BLOCKED", "COLLECTED"][i], billCount: 12 + i * 3, grossMinor: 52_000_00 + i * 1_500_00, netMinor: 50_544_00 + i * 1_458_00, reconScope: i % 2 ? "SELF_ONLY" : "BOTH", blockedReason: i === 2 ? "RECON_DIFF_PENDING" : null, decidedBy: null, decideRemark: null, createdAt: T0 - 24 * H, version: 1 }));
  let payouts: PayoutRow[] = settlements.slice(0, 2).map((st, i) => ({ payoutNo: `PO-OUT-${3001 + i}`, batchNo: st.settlementNo, merchantNo: st.merchantNo, payMerchantNo: `NWP${7000 + i}`, accountName: `${MERCHANTS[i][1]}有限公司`, bankName: "台湾银行", bankBranch: "信义分行", accountNoMasked: "****5678", amountMinor: st.netMinor, billCount: st.billCount, currency: "TWD", status: i === 0 ? "PAID" : "EXPORTED", channel: "MANUAL", paymentRef: i === 0 ? "TWB-20261120-001" : null, bankFlowNo: null, exportedAt: T0 - H, paidAt: i === 0 ? T0 : null, paidBy: i === 0 ? "finance01" : null, matchedAt: null, failReason: null, createdAt: T0 - 2 * H, version: 1 }));
  let withdrawals: WithdrawalRow[] = MERCHANTS.map(([no, name], i) => ({ withdrawalNo: `WD${5101 + i}`, merchantNo: no, merchantName: name, amountMinor: [3_000_00, 8_200_00, 1_500_00, 600_00][i], availableBalanceMinor: 12_000_00, bankAccountMasked: "台湾银行 ****5678", status: ["PENDING", "PENDING", "APPROVED", "FAILED"][i], appliedAt: T0 - i * 3 * H, decidedAt: i >= 2 ? T0 - H : null, decidedBy: i >= 2 ? "finance01" : null, remark: i === 3 ? "收款账户户名不符" : null, version: 1 }));
  let reconDiffs: (ReconDiffRow & { claimedBy: string | null })[] = Array.from({ length: 7 }, (_, i) => ({ differenceNo: `RD${8801 + i}`, axis: i % 3 === 2 ? "REFUND" : "PAY", billDate: "2026-11-19", payChannel: CH[i % 3], diffType: ["AMOUNT_DIFF", "PLATFORM_ONLY", "REFUND_LATE_SUCCESS", "CHANNEL_ONLY"][i % 4], source: "SELF_CHECK", paymentNo: attempts[i]?.paymentNo ?? null, orderNo: attempts[i]?.payOrderNo ?? null, channelTxnNo: `CT${100 + i}`, channelAmountMinor: 3500 + i * 100, platformAmountMinor: 3500 + i * 100 - (i % 4 === 0 ? 100 : 0), diffMinor: i % 4 === 0 ? 100 : 0, status: i < 5 ? "PENDING" : i === 5 ? "RESOLVED" : "IGNORED", resolution: i === 5 ? "已补单" : null, note: "自查发现", recoveredOrderNo: null, createdAt: T0 - i * H, resolvedAt: i >= 5 ? T0 : null, resolvedBy: i >= 5 ? "finance01" : null, version: 1, claimedBy: i === 1 ? "finance01" : null }));
  const svAccounts: StoredValueAccountRow[] = Array.from({ length: 9 }, (_, i) => ({ accountNo: `SVA${4401 + i}`, merchantNo: MERCHANTS[i % 4][0], storeNo: STORES[i % 5][0], issuerNo: `ISS${i % 2 + 1}`, customerNo: `C${30001 + i}`, userNoMasked: `****${1200 + i}`, currency: "TWD", status: i === 4 ? "FROZEN" : "ACTIVE", principalMinor: 2_000_00 + i * 500_00, bonusMinor: 200_00 + i * 20_00, heldPrincipalMinor: i === 4 ? 1_000_00 : 0, heldBonusMinor: 0, availableMinor: 2_200_00 + i * 520_00 - (i === 4 ? 1_000_00 : 0), accountSeq: 3 + i, createdAt: T0 - (30 + i) * 24 * H, updatedAt: T0 - i * H, version: 1 }));
  const svTx: StoredValueTransactionRow[] = svAccounts.flatMap((a, i) => [
    { transactionNo: `SVT${a.accountNo.slice(3)}1`, accountNo: a.accountNo, merchantNo: a.merchantNo, storeNo: a.storeNo, issuerNo: a.issuerNo, currency: "TWD", accountSeq: 1, bizType: "RECHARGE", bizNo: `RC${5000 + i}`, principalDeltaMinor: a.principalMinor + 500_00, bonusDeltaMinor: a.bonusMinor, afterPrincipalMinor: a.principalMinor + 500_00, afterBonusMinor: a.bonusMinor, occurredAt: a.createdAt, createdAt: a.createdAt },
    { transactionNo: `SVT${a.accountNo.slice(3)}2`, accountNo: a.accountNo, merchantNo: a.merchantNo, storeNo: a.storeNo, issuerNo: a.issuerNo, currency: "TWD", accountSeq: 2, bizType: "CONSUME", bizNo: `SO${6000 + i}`, principalDeltaMinor: -500_00, bonusDeltaMinor: 0, afterPrincipalMinor: a.principalMinor, afterBonusMinor: a.bonusMinor, occurredAt: a.updatedAt, createdAt: a.updatedAt },
  ]);
  let costs: ChannelCostRow[] = [["NEWEBPAY", "CARD", 280, 0, "SUPERSEDED", -60], ["NEWEBPAY", "CARD", 260, 0, "ACTIVE", -10], ["NEWEBPAY", "TWQR", 150, 0, "ACTIVE", -30], ["LINEPAY", "LINE_PAY", 300, 500, "ACTIVE", -20], ["LINEPAY", "LINE_PAY", 280, 500, "SCHEDULED", 5]].map(([ch, pm, bps, min, st, d], i) => ({ costNo: `CR${9001 + i}`, payChannel: ch as string, payMethod: pm as string, legalForm: "*", market: "TW", rateBps: bps as number, minFeeMinor: min as number, effectiveFrom: T0 + (d as number) * 24 * H, status: st as string, remark: "渠道合同调价", createdAt: T0 + (d as number) * 24 * H - H, createdBy: "finance01", version: 1 }));
  let commissions: CommissionRuleRow[] = [["SELF_OPERATED", "PLATFORM", 500, "ACTIVE"], ["MARKETPLACE", "PLATFORM", 800, "ACTIVE"], ["MARKETPLACE", "MERCHANT_OWNED", 300, "ACTIVE"]].map(([bm, ts, bps, st], i) => ({ ruleNo: `FR${7001 + i}`, businessMode: bm as string, trafficSource: ts as string, rateBps: bps as number, effectiveFrom: T0 - 90 * 24 * H, status: st as string, remark: null, createdAt: T0 - 91 * 24 * H, createdBy: "admin" }));
  const costStatus = (rows: ChannelCostRow[]) => { /* 同维度：未到时间 SCHEDULED；已生效里最新 ACTIVE；其余 SUPERSEDED */
    const now = Date.now(); const out = rows.map((r) => ({ ...r }));
    const dims = new Map<string, ChannelCostRow[]>(); for (const r of out) { const k = [r.payChannel, r.payMethod, r.legalForm, r.market].join("|"); dims.set(k, [...(dims.get(k) ?? []), r]); }
    for (const list of dims.values()) { const live = list.filter((r) => r.effectiveFrom <= now).sort((a, b) => b.effectiveFrom - a.effectiveFrom); for (const r of list) r.status = r.effectiveFrom > now ? "SCHEDULED" : r === live[0] ? "ACTIVE" : "SUPERSEDED"; }
    return out;
  };

  // ── P5 渠道账户、门店绑定、认领（TDD §2.11）：PM1001 蓝新已启用 + LINE Pay 待核验；PM1003 蓝新仅退款 ──
  const accounts = new VersionedTable<ChannelAccountDetail & { version: number; payMerchantNo: string }>([
    ["NWP7000", 0, "NEWEBPAY", "MS350001091", "ACTIVE", "VERIFIED", 1, ["NEWEBPAY_CARD"]],
    ["LNP7100", 0, "LINEPAY", "2000781234", "UNVERIFIED", "PENDING", 0, []],
    ["NWP7002", 2, "NEWEBPAY", "MS350001093", "REFUND_ONLY", "VERIFIED", 1, ["NEWEBPAY_CARD"]],
  ].map(([no, m, ch, mid, st, vs, bound, prods]) => {
    const [merchantNo, merchantName] = MERCHANTS[m as number];
    const account: ChannelAccountRow = { payMerchantNo: no as string, ownerType: "MERCHANT", merchantNo, merchantName, payChannel: ch as string, market: "TW", currency: "TWD",
      channelMerchantId: `****${(mid as string).slice(-4)}`, subMerchantId: null, accountMode: "MERCHANT_OWN", fundsPath: "DIRECT", parentPayMerchantNo: null,
      source: st === "UNVERIFIED" ? "MANUAL" : "ONBOARDING", onboardingNo: st === "UNVERIFIED" ? null : `OB${no}`, evidenceRef: "file:签约证明", verificationStatus: vs as VerificationStatus,
      status: st as AccountStatus, credentialKeyId: st === "UNVERIFIED" ? null : 501, boundStoreCount: bound as number, updatedAt: T0 - 3 * H, version: 1 };
    return { account, payMerchantNo: no as string, version: 1, verifiedBy: vs === "VERIFIED" ? "admin" : null, verifiedAt: vs === "VERIFIED" ? T0 - 48 * H : null, verifyApprovalNo: null, verifyReason: null, registeredBy: "admin",
      products: (prods as string[]).map((code) => ({ productCode: code, productName: "信用卡（蓝新）", status: "OPEN" as const, source: "CHANNEL_RECEIPT" as const, evidenceRef: null, openedBy: "system", openedAt: T0 - 47 * H })),
      credentialVersion: st === "UNVERIFIED" ? null : 1, settleCycle: "T1", poolAccountRef: null, maxSplitRate: null,
      statusLogs: st === "UNVERIFIED" ? [] : [{ fromStatus: "UNVERIFIED", toStatus: "ACTIVE", reason: "进件通过", approvalNo: null, operatorNo: "admin", changedAt: T0 - 47 * H }] };
  }), (r) => r.payMerchantNo);
  const syncAccount = (no: string, patch: Partial<ChannelAccountRow>) => { const d = accounts.get(no); Object.assign(d.account, patch, { version: d.version }); };
  const bindings = new VersionedTable<StoreBinding>([
    { bindingNo: "SB5001", storeNo: "ST2001", merchantNo: "PM1001", payChannel: "NEWEBPAY", payMerchantNo: "NWP7000", status: "ACTIVE", reason: "进件通过自动绑定", approvalNo: null, boundBy: "system", effectiveFrom: T0 - 47 * H, effectiveTo: null, endedReason: null, pendingTargetPayMerchantNo: null, pendingReason: null, version: 1 },
    { bindingNo: "SB5002", storeNo: "ST2004", merchantNo: "PM1003", payChannel: "NEWEBPAY", payMerchantNo: "NWP7002", status: "ACTIVE", reason: "进件通过自动绑定", approvalNo: null, boundBy: "system", effectiveFrom: T0 - 40 * H, effectiveTo: null, endedReason: null, pendingTargetPayMerchantNo: null, pendingReason: null, version: 1 },
  ], (r) => r.bindingNo);
  const claims = new VersionedTable<ChannelAccountClaim>([
    { claimNo: "CL6001", requestNo: "REQ-CL6001", merchantNo: "PM1001", payChannel: "LINEPAY", channelMerchantMasked: "****1234", channelMerchantId: "2000781234", subMerchantId: null, state: "PENDING_VERIFY", version: 1, files: [], reason: null, payMerchantNo: null, accountVerificationStatus: null, accountStatus: null },
  ], (r) => r.claimNo);

  const cmdCheck = (c: Cmd) => reasonOf(c.reason);
  // ── P8：商户成员、资料版本、来源关联修复（TDD-支付中心运营页面 §2.15） ──
  type MemberRec = MerchantMemberRow & { version: number };
  const members = new VersionedTable<MemberRec>([
    { membershipNo: "MM9001", merchantNo: "PM1001", sourceSystem: "AI_SHOP", subjectType: "MCH_ACCOUNT", subjectRef: "MA-OWNER-1001", role: "MERCHANT_ADMIN", storeScope: "ALL", storeNos: [], actions: ["payment.create", "refund.create"], status: "ACTIVE", validUntil: null, grantedBy: "system", revokeReason: null, version: 1 },
    { membershipNo: "MM9002", merchantNo: "PM1001", sourceSystem: "AI_SHOP", subjectType: "MCH_ACCOUNT", subjectRef: "MA-CASHIER-1001", role: "CASHIER", storeScope: "LISTED", storeNos: ["ST2001"], actions: ["payment.create"], status: "ACTIVE", validUntil: null, grantedBy: "MA-OWNER-1001", revokeReason: null, version: 1 },
  ], (r) => r.membershipNo);
  const memberActions = (r: MemberRec) => (r.status === "ACTIVE" ? ["suspend", "revoke"] : r.status === "SUSPENDED" ? ["resume", "revoke"] : []);
  const revisionsOf = (no: string): ProfileRevisionRow[] => [
    { revisionNo: `${no}:GENERAL:2`, revision: 2, purpose: "GENERAL", market: "TW", sourceProfileRevision: 2, profile: { legalName: "示例商户" }, materials: [], profileHash: "h2", reviewStatus: "SUBMITTED", submittedBy: "ST-OPS-1", submittedAt: T0 - 2 * H, reviewedBy: null, reviewedAt: null, reviewReason: null, approvalNo: null, version: 1 },
    { revisionNo: `${no}:GENERAL:1`, revision: 1, purpose: "GENERAL", market: "TW", sourceProfileRevision: 1, profile: { legalName: "示例商户" }, materials: [], profileHash: "h1", reviewStatus: "APPROVED", submittedBy: "ST-OPS-1", submittedAt: T0 - 48 * H, reviewedBy: "ST-OPS-2", reviewedAt: T0 - 47 * H, reviewReason: "资料一致", approvalNo: "AP8001", version: 2 },
  ];
  const repairs = new VersionedTable<RepairRow>([
    { repairNo: "SR7001", requestNo: "ops-seed-1", merchantNo: "PM1002", linkNo: "LK-PM1002", sourceSystem: "AI_SHOP", sourceScope: "MAIN", fromOperatingEntityId: "E-OLD-1002", toOperatingEntityId: "E-NEW-1002", storeNos: ["ST2003"], evidence: "工单 OPS-1002：主体变更已核对证照", expectedVersion: 3, status: "PENDING_APPROVAL", approvalNo: "AP8051", approvalStatus: "PENDING_FIRST", approvalVersion: 1, requestedBy: "ST-OPS-1", executedBy: null, executedAt: null, beforeSnapshot: null, afterSnapshot: null, createdAt: T0 - 3 * H, version: 1 },
  ], (r) => r.repairNo);
  // ── P10：凭证管理、审计事件 ──
  type CredRec = CredentialRow & { versions: CredentialVersion[] };
  const ver = (i: number, status: string, by = "ST-OPS-1"): CredentialVersion => ({ versionIndex: i, status, fingerprint: `fp${i}a9c3`, displayPrefix: `sk_test_${i}…`, externalKeyId: null, source: "UPLOADED", validFrom: T0 - 72 * H, validTo: T0 + 300 * 24 * H, enteredBy: by, verifiedAt: status === "DRAFT" ? null : T0 - 70 * H, verifyEvidence: status === "DRAFT" ? null : "MANUAL:OPS-1", enabledBy: status === "ACTIVE" ? "ST-OPS-2" : null, enabledAt: status === "ACTIVE" ? T0 - 69 * H : null, overlapUntil: null, revokedBy: null, revokedAt: null, revokeReason: null });
  const creds = new VersionedTable<CredRec & { version: number }>([
    { keyId: 4101, credentialType: "NEWEBPAY_MERCHANT_HASH", ownerType: "PAY_MERCHANT", ownerRef: "NWP7000", name: "蓝新商户 HashKey/IV", counterparty: "NEWEBPAY", purpose: "MERCHANT_HASH", algorithm: "AES-256", materialType: "COMPOSITE", env: "PRODUCTION", status: "ACTIVE", currentVersion: 2, currentFingerprint: "fp2a9c3", expiresAt: T0 + 300 * 24 * H, expiryState: "OK", pendingVersions: 0, lastUsedAt: T0 - H, versions: [ver(2, "ACTIVE"), ver(1, "REVOKED")], version: 1 },
    { keyId: 4102, credentialType: "APP_SECRET", ownerType: "PARTNER_APP", ownerRef: "APP9001", name: "点餐 App 密钥", counterparty: null, purpose: "APP_SIGN", algorithm: "HMAC-SHA256", materialType: "SYMMETRIC_SECRET", env: "SANDBOX", status: "ACTIVE", currentVersion: 1, currentFingerprint: "fp1a9c3", expiresAt: T0 + 20 * 24 * H, expiryState: "EXPIRING", pendingVersions: 1, lastUsedAt: null, versions: [ver(2, "DRAFT"), ver(1, "ACTIVE")], version: 1 },
  ], (r) => String(r.keyId));
  const credPolicies: CredentialPolicy[] = ["CHANNEL_PLATFORM", "PAY_MERCHANT", "PARTNER_APP", "TERMINAL"].map((o) => ({ ownerType: o, env: "PRODUCTION", maxAgeDays: 365, warnBeforeDays: 30, overlapDays: 7, dualControl: true, verifyBeforeEnable: true, revealOnIssueOnly: true, version: 0 }));
  const credActions = (r: CredRec) => (r.status === "REVOKED" ? [] : [r.materialType === "SYMMETRIC_SECRET" && r.ownerType === "PARTNER_APP" ? "GENERATE" : "ADD_VERSION", ...(r.versions.some((v) => v.status === "DRAFT") ? ["VERIFY"] : []), ...(r.versions.some((v) => v.status === "VERIFIED") ? ["ENABLE"] : []), "REVOKE"]);

  // ── 审批闸（near-pay ApprovalGate）：应用启用 / 恢复、接入应用密钥启用，须本对象已批准且未执行的审批 ──
  // mock 只有 admin 一个身份（near-pay 单人审批开关默认打开，同一人可提交、审核、执行），所以这里只守「有没有批准」。
  const TWO_LEVEL = new Set(["CREDENTIAL_ENABLE"]);
  const approvalFor = (type: string, ref: string, expected?: string | null) => {
    const a = approvals.all().find((x) => x.bizType === type && x.resourceNo === ref && x.status === "APPROVED" && x.executionStatus === "NOT_STARTED" && (!expected || x.approvalNo === expected));
    if (!a) throw new MockPayError("STATE_CONFLICT", `执行 ${type} ${ref} 需要本对象已批准且未执行的审批${expected ? `（${expected}）` : ""}`);
    return a;
  };
  const executed = (approvalNo: string) => approvals.update(approvalNo, { executionStatus: "SUCCEEDED" });
  /** 应用启用 / 恢复（apps.command 与审批执行共用） */
  const activateApp = async (no: string, c: Cmd, action: "enable" | "resume") => {
    // near-pay OpenAppService#activate：没配回调地址可以直接启用；配了就必须先核验
    need(!apps.get(no).notifyUrl || apps.get(no).callbackStatus === "VERIFIED", "回调地址核验通过后才能启用");
    const a = approvalFor("OPEN_APP_ENABLE", no);
    const r = await setStatus(apps, no, c, action === "enable" ? ["DRAFT"] : ["SUSPENDED"], "ACTIVE");
    await executed(a.approvalNo); return r;
  };
  /** 密钥版本启用（credentials.enable 与审批执行共用）；接入应用的密钥走审批闸 */
  const enableCredVersion = async (id: number, vi: number, approvalRef: string | null) => {
    const r = creds.get(String(id)); const v = r.versions.find((x) => x.versionIndex === vi);
    need(v?.status === "VERIFIED", "只有已核验的版本可启用"); need(v!.enteredBy !== "admin", "启用人不能是录入人（双人复核）");
    const gate = r.ownerType === "PARTNER_APP" ? approvalFor("CREDENTIAL_ENABLE", `${id}:${vi}`, approvalRef) : null;
    const next = { ...v!, status: "ACTIVE", enabledBy: "admin", enabledAt: Date.now() };
    await creds.update(String(id), { versions: r.versions.map((x) => (x.versionIndex === vi ? next : x.status === "ACTIVE" ? { ...x, status: "ROTATING" } : x)), currentVersion: vi, pendingVersions: Math.max(0, r.pendingVersions - 1) });
    if (r.ownerType === "PARTNER_APP" && r.credentialType === "APP_SECRET") await apps.update(r.ownerRef, { credentialStatus: "ACTIVE" });
    if (gate) await executed(gate.approvalNo);
    return next;
  };

  // ── P12：开放平台密钥（PLATFORM_OPEN 凭证）、商家公钥、接入申请 ──
  const PEM = (tag: string) => `-----BEGIN PUBLIC KEY-----\nMOCK${tag}AAAAB3NzaC1yc2EAAAADAQABAAABAQ\n-----END PUBLIC KEY-----\n`;
  const publicPem = new Map<string, string>(); // `${keyId}:${v}` → PEM（只放公钥）
  const platformKey = (keyId: number, usage: PlatformKeyUsage, v: CredentialVersion): CredRec & { version: number } => ({ keyId, credentialType: usage === "SIGN" ? "OPEN_PLATFORM_SIGN" : "OPEN_PLATFORM_ENCRYPT", ownerType: "PLATFORM_OPEN", ownerRef: "OPEN", name: usage === "SIGN" ? "开放平台签名密钥" : "开放平台加密密钥", counterparty: null, purpose: usage, algorithm: usage === "SIGN" ? "RSA_SHA256" : "RSA_OAEP_SHA256", materialType: "PRIVATE_KEY", env: "PRODUCTION", status: "ACTIVE", currentVersion: v.status === "ACTIVE" ? v.versionIndex : 0, currentFingerprint: v.status === "ACTIVE" ? v.fingerprint : null, expiresAt: null, expiryState: "NONE", pendingVersions: v.status === "ACTIVE" ? 0 : 1, lastUsedAt: null, versions: [v], version: 1 });
  for (const [keyId, usage, status, serial] of [[4201, "SIGN", "ACTIVE", "P2026112200000101"], [4202, "ENCRYPT", "VERIFIED", "Q2026112200000101"]] as const) {
    const v = { ...ver(1, status, "ST-OPS-1"), externalKeyId: serial, source: "GENERATED", displayPrefix: serial };
    void creds.insert(platformKey(keyId, usage, v)); publicPem.set(`${keyId}:1`, PEM(serial));
  }
  const usageOf = (t: string): PlatformKeyUsage => (t === "OPEN_PLATFORM_SIGN" ? "SIGN" : "ENCRYPT");
  const challenges = new Map<string, { appNo: string; expiresAt: number }>();
  let keySeq = 1;
  const applications = new VersionedTable<AppApplicationRow>([
    { applicationNo: "AAP26112200001", merchantNo: "PM1001", appName: "台北早餐店 POS 对接", purpose: "门店 POS 建单与查单", environment: "LIVE", scopes: ["READ", "COLLECT"], notifyUrl: "https://erp.example.com/pay/notify", callbackDomains: ["example.com"], ipWhitelist: "203.0.113.10", rateLimitQps: 20, dailyRefundLimitMinor: 0, contactName: "王工", contactEmail: "dev@example.com", status: "SUBMITTED", submittedBy: "admin2", reviewedBy: null, reviewedAt: null, reviewReason: null, appNo: null, createdAt: T0 - H, version: 0, signType: "HMAC" },
    { applicationNo: "AAP26112200002", merchantNo: "PM1002", appName: "信义咖啡会员系统", purpose: "会员储值收款", environment: "LIVE", scopes: ["READ", "COLLECT", "REFUND"], notifyUrl: "https://crm.example.com/pay/notify", callbackDomains: ["example.com"], ipWhitelist: null, rateLimitQps: 10, dailyRefundLimitMinor: 20_000_00, contactName: "陈小姐", contactEmail: "it@example.com", status: "SUBMITTED", submittedBy: "admin", reviewedBy: null, reviewedAt: null, reviewReason: null, appNo: null, createdAt: T0 - 2 * H, version: 0, signType: "RSA" },
    { applicationNo: "AAP26112100003", merchantNo: "PM1001", appName: "台北早餐店 ERP（RSA）", purpose: "ERP 对账", environment: "LIVE", scopes: ["READ"], notifyUrl: null, callbackDomains: [], ipWhitelist: null, rateLimitQps: 20, dailyRefundLimitMinor: 0, contactName: null, contactEmail: null, status: "APPROVED", submittedBy: "admin2", reviewedBy: "admin", reviewedAt: T0 - 20 * H, reviewReason: "资料齐全", appNo: "APP9004", createdAt: T0 - 24 * H, version: 1, signType: "RSA" },
  ], (r) => r.applicationNo);
  const appActions = (a: AppApplicationRow) => (a.status === "SUBMITTED" ? ["approve", "reject", "withdraw"] : []);  const audits: AuditEventRow[] = Array.from({ length: 5 }, (_, i) => ({ auditNo: `AU${6001 + i}`, requestId: `req-${i}`, operatorNo: i % 2 ? "ST-OPS-2" : "ST-OPS-1", roleNos: ["SUPER_ADMIN"], face: "OPS", resourceType: ["MERCHANT", "CHANNEL_ACCOUNT", "STORE_BINDING", "CREDENTIAL", "ALERT_RULE"][i], resourceNo: ["PM1001", "NWP7000", "SB5001", "4101", "AR2001"][i], action: ["SUSPEND", "VERIFY", "UNBIND", "ENABLE", "PUBLISH"][i], result: "SUCCEEDED", reason: "例行操作", approvalRef: i === 3 ? "AP8100" : null, beforeSummary: '{"status":"ACTIVE"}', afterSummary: '{"status":"SUSPENDED"}', occurredAt: T0 - i * H }));
  // ── P11：PayBy POS 配置与验收、SoftPOS 绑定（状态机与 near-pay 同口径） ──
  const posConfigs: PosConfigView[] = [];
  const uatRuns: UatRun[] = [];
  const softposBindings: SoftPosBinding[] = [];
  const posOf = (t: string, id: number) => { const x = posConfigs.find((p) => p.terminalNo === t && p.id === id); if (!x) throw new MockPayError("NOT_FOUND", "没有这个 POS 配置"); return x; };
  const posBump = (x: PosConfigView, patch: Partial<PosConfigView>, expected: number) => {
    if (x.version !== expected) throw new MockPayError("VERSION_CONFLICT", "配置已被他人修改（POS_REVISION_CHANGED）");
    Object.assign(x, patch, { version: x.version + 1, audit: [{ operation: patch.state ?? "SAVE", state: patch.state ?? x.state, created_at: Date.now() }, ...x.audit] }); return x;
  };
  const posReady = (x: PosConfigView) => x.state === "VERIFIED" && !x.stale && !!x.binding;
  const repairActions = (r: RepairRow) => (r.status === "PENDING_APPROVAL" ? ["approve", "reject"] : r.status === "APPROVED" ? ["execute"] : []);
  const transfers: ChannelAccountTransfer[] = [];
  const qrCodes = new Map<string, StoreQrCode[]>();
  const methodViews = new Map<string, StoreMethodView>();
  const methodsOf = (no: string): StoreMethodView => methodViews.get(no) ?? { storeNo: no, mode: "INHERIT", version: 0, eligibilityRevision: "mock-rev", items: [
    { choice: { paymentMethodCode: "CARD", test: false }, configuredEnabled: true, effectiveEnabled: true, editable: true, blockedReason: null, productCodes: ["NEWEBPAY_CARD_ONETIME"], channelCodes: ["NEWEBPAY"] },
    { choice: { paymentMethodCode: "LINEPAY", test: false }, configuredEnabled: true, effectiveEnabled: true, editable: true, blockedReason: null, productCodes: ["LINEPAY_ONLINE"], channelCodes: ["LINEPAY"] },
    { choice: { paymentMethodCode: "JKOPAY", test: false }, configuredEnabled: false, effectiveEnabled: false, editable: false, blockedReason: "MERCHANT_POLICY", productCodes: [], channelCodes: ["JKOPAY"] },
  ] };
  const qualifications: QualificationVersion[] = [];
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
    // mock 里「我」就是 admin（与各状态机里的操作人一致）
    me: () => run(() => ({ operatorNo: "admin", roles: ["SUPER_ADMIN"] })),
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
      escalations: (q) => pageOf(orders.filter((o) => o.status === "AWAITING_CONFIRM" && (!q.storeNo || o.storeNo === q.storeNo)).map((o, k) => ({ order: o, followup: { id: 8000 + k, operation: "QUERY", payChannel: o.payChannel ?? "NEWEBPAY", status: "ESCALATED", recoveryPath: "MANUAL", attempts: 6, nextAt: null, lastResult: "渠道查询超时", resolvedAt: null, createdAt: T0 - (k + 1) * H, payOrderNo: o.payOrderNo } })), { ...q, storeNo: undefined }, []),
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
      revisions: (no, q) => run(() => { merchants.get(no); return revisionsOf(no).filter((r) => (!q?.purpose || r.purpose === q.purpose) && (!q?.reviewStatus || r.reviewStatus === q.reviewStatus)); }),
      workspace: (no) => run(() => {
        const m = merchants.get(no);
        const sec = (key: string, title: string, module: string, ready: boolean, count: number | null, summary: unknown = null) => ({ key, title, module, status: ready ? "READY" as const : "NOT_READY" as const, count, summaryJson: summary == null ? null : JSON.stringify(summary) });
        return { merchant: { merchantNo: m.merchantNo, displayName: m.displayName, status: m.status }, sections: [
          sec("profile", "资料", "BM01", true, 2, { GENERAL: `${no}:GENERAL:2` }),
          sec("members", "成员", "BM03", true, members.all().filter((x) => x.merchantNo === no).length),
          sec("stores", "门店", "BM02", true, m.storeCount),
          sec("channelAccounts", "渠道账户", "BM06", true, 1, { byStatus: { ACTIVE: 1 } }),
          sec("trades", "交易", "BM09", false, null),
          sec("funds", "资金", "BM29", false, null),
        ] };
      }),
      creationByRequest: () => run(() => { throw new MockPayError("NOT_FOUND", "创建操作尚未受理"); }),
      assistedOperation: () => run(() => { throw new MockPayError("NOT_FOUND", "此操作尚无已提交回执"); }),
      creationContext: () => run(() => ({ enabled: true })),
      create: (b) => run(async () => {
        need(/^[A-Za-z0-9_-]{1,64}$/.test(b.operatingEntityId), "经营主体号格式不对");
        need(!merchants.all().some((m) => m.entityNo === b.operatingEntityId), "该经营主体已有支付商户");
        const no = `PM${2001 + merchants.all().length}`;
        await merchants.insert({ merchantNo: no, displayName: b.name, sourceSystem: null, entityNo: b.operatingEntityId, entityName: b.name, market: "TW", profileStatus: "NONE", verifyStatus: "PENDING", duplicateSuspect: false, payMerchantNos: null, storeCount: 0, status: "ACTIVE", statusReason: null, createdAt: Date.now(), version: 1 });
        return { merchantNo: no, operatingEntityId: b.operatingEntityId, name: b.name, market: "TW", status: "ACTIVE" };
      }),
      testContext: () => run(() => ({ enabled: true, market: "TW" })),
      createTest: (b) => run(async () => {
        need(/^[A-Za-z0-9_-]{1,48}$/.test(b.batchNo), "批次号只能是字母、数字、下划线、短横线（1–48 位）");
        const no = `PMT${b.batchNo}`;
        if (!merchants.find(no)) await merchants.insert({ merchantNo: no, displayName: `[TEST] 蓝新进件-${b.batchNo}`, sourceSystem: null, entityNo: null, entityName: null, market: "TW", profileStatus: "SUBMITTED", verifyStatus: "PENDING", duplicateSuspect: false, payMerchantNos: null, storeCount: 0, status: "ACTIVE", statusReason: null, createdAt: Date.now(), version: 1 });
        return { merchantNo: no, displayName: `[TEST] 蓝新进件-${b.batchNo}`, market: "TW", synthetic: true };
      }),
      capability: (no) => run(() => {
        const ob = onboardings.all().find((o) => o.merchantNo === no);
        return { merchantNo: no, items: [
          { capability: "FIAT", stage: "S1", available: ob?.status === "APPROVED", applicationNo: ob?.onboardingNo ?? null, status: ob?.status ?? null, reviewStatus: ob?.reviewStatus ?? null, note: null, entry: "ME03" },
          { capability: "STORED_VALUE", stage: "S4", available: false, applicationNo: null, status: null, reviewStatus: null, note: "第四阶段开放", entry: null },
          { capability: "POINTS", stage: "S4", available: false, applicationNo: null, status: null, reviewStatus: null, note: "第四阶段开放", entry: null },
          { capability: "CRYPTO", stage: "SX", available: false, applicationNo: null, status: null, reviewStatus: null, note: "暂不开放", entry: null },
        ] };
      }),
      channels: (no) => run(() => {
        const m = merchants.get(no);
        const rows = [
          ...(m.payMerchantNos ?? []).map((pm) => ({ payChannel: "NEWEBPAY", channelName: "蓝新金流", test: false, stage: "OPEN", payMerchantNo: pm, channelMerchantId: "****0091", accountStatus: "ENABLED", verificationStatus: "VERIFIED", boundStoreCount: m.storeCount, products: [{ productCode: "NEWEBPAY_CREDIT", productName: "信用卡", status: "ENABLED" }], onboardingNo: null, onboardingStatus: null, reviewStatus: null, reason: null })),
          ...onboardings.all().filter((o) => o.merchantNo === no && !["APPROVED", "CANCELLED"].includes(o.status)).map((o) => ({ payChannel: o.payChannel, channelName: o.payChannel, test: false, stage: "APPLYING", payMerchantNo: null, channelMerchantId: null, accountStatus: null, verificationStatus: null, boundStoreCount: 0, products: [], onboardingNo: o.onboardingNo, onboardingStatus: o.status, reviewStatus: o.reviewStatus, reason: o.rejectReason })),
        ];
        return { merchantNo: no, rows, channels: channels.all().map((ch) => ({ payChannel: ch.channelCode, channelName: ch.name, onlineApplication: ch.channelCode === "NEWEBPAY" })) };
      }),
      policy: (no) => run(() => { const p = merchantPolicies.get(no) ?? { mode: "UNRESTRICTED", productCodes: [], version: 0 }; return { ownerType: "MERCHANT", ownerRef: no, mode: p.mode, productCodes: p.productCodes, version: p.version, merchantMode: null, merchantProductCodes: [] }; }),
      savePolicy: (no, b) => run(() => {
        reasonOf(b.reason);
        const cur = merchantPolicies.get(no) ?? { mode: "UNRESTRICTED", productCodes: [], version: 0 };
        if (cur.version !== b.expectedVersion) throw new MockPayError("VERSION_CONFLICT", "记录已被他人修改，请刷新后重试");
        need(b.mode !== "CUSTOM" || b.productCodes.length > 0, "自定义模式至少选一个产品");
        merchantPolicies.set(no, { mode: b.mode, productCodes: b.mode === "CUSTOM" ? b.productCodes : [], version: cur.version + 1 });
        return ok(no, b.mode, cur.version + 1);
      }),
      sourceEvents: (no, q) => pageOf([{ eventNo: `SE${no}-1`, eventType: "ENTITY_UPDATED", sourceSystem: "AI_SHOP", externalEntityId: merchants.get(no).entityNo, summary: "主体名称更新", occurredAt: T0 - 5 * H, createdAt: T0 - 5 * H }], q, []),
      assistedDraft: (no) => run(() => {
        need(!merchants.get(no).sourceSystem, "该商户的资料由来源系统维护，不能代填");
        const a = assisted.get(no) ?? { version: 0, state: "EDITING", values: {}, revisionNo: null };
        return { version: a.version, state: a.state, profile: Object.fromEntries(Object.entries(a.values).map(([k, v]) => [k, mask(k, v)])), filledFields: Object.keys(a.values), revisionNo: a.revisionNo };
      }),
      saveAssisted: (no, b) => run(() => {
        need(merchants.get(no).status === "ACTIVE", "只有正常状态的商户能代填资料");
        const a = assisted.get(no) ?? { version: 0, state: "EDITING", values: {}, revisionNo: null };
        if (a.version !== b.expectedVersion) throw new MockPayError("VERSION_CONFLICT", "记录已被他人修改，请刷新后重试");
        const next = { ...a.values };
        for (const [k, v] of Object.entries(b.fields)) { if (v == null || v === "") delete next[k]; else next[k] = v; }
        need(a.version > 0 || !!next.legalName, "第一次保存必须填写法定名称");
        const saved = { version: a.version + 1, state: "EDITING", values: next, revisionNo: a.revisionNo };
        assisted.set(no, saved);
        return { version: saved.version, state: saved.state, profile: Object.fromEntries(Object.entries(next).map(([k, v]) => [k, mask(k, v)])), filledFields: Object.keys(next), revisionNo: saved.revisionNo };
      }),
      submitAssisted: (no, b) => run(async () => {
        const a = assisted.get(no);
        need(!!a && a.version > 0, "请先保存资料草稿");
        need(a!.state !== "SUBMITTED", "资料已提交审核");
        if (a!.version !== b.expectedVersion) throw new MockPayError("VERSION_CONFLICT", "记录已被他人修改，请刷新后重试");
        const rev = `PR${no}-A${a!.version}`;
        assisted.set(no, { ...a!, state: "SUBMITTED", revisionNo: rev });
        await merchants.update(no, { profileStatus: "SUBMITTED" });
        return { version: a!.version, state: "SUBMITTED", profile: {}, filledFields: Object.keys(a!.values), revisionNo: rev };
      }),
      verifyLink: (no, link, b) => run(async () => { reasonOf(b.reason); const r = await merchants.update(no, { verifyStatus: b.result }); return ok(link, b.result, r.version); }),
      confirm: (no) => run(async () => { await merchants.update(no, { verifyStatus: "VERIFIED", profileStatus: "APPROVED" }); return { verifiedLinks: [`SL${no}`], approvedRevisions: [`PR${no}-1`] }; }),
      entityMaterials: (no) => run(() => ({ version: 1, fields: { legalForm: "COMPANY", companyName: `${merchants.get(no).displayName}有限公司`, taxNo: "24536806", representativeName: "王小明", bankCode: "812", bankAccount: "00123456789012" }, documents: { REGISTRATION: "file:entity-profile/0123456789abcdef0123456789abcdef", BANK_COVER: "file:entity-profile/fedcba9876543210fedcba9876543210" }, submittedRevision: 1 })),
      entityFile: () => run(() => new Blob([new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d])], { type: "application/pdf" })),
      entityRequestMore: (no, rev, b) => run(async () => { reasonOf(b.reason); need(b.fields.length + b.documents.length > 0, "至少勾一项要补的资料"); await merchants.update(no, { profileStatus: "NEED_MORE" }); return { revision: rev, reviewStatus: "NEED_MORE" }; }),
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
      qrCodes: (no) => run(() => qrCodes.get(no) ?? []),
      createQrCode: (no, b) => run(() => { reasonOf(b.reason); need(b.kind !== "TABLE" || !!b.tableRef?.trim(), "桌码须填桌号");
        const list = qrCodes.get(no) ?? []; const code = `QR${no.slice(-4)}${list.length + 1}`;
        const v: StoreQrCode = { qrCode: code, kind: b.kind, tableRef: b.tableRef, currency: "TWD", singleLimitMinor: b.singleLimitMinor ?? 500000, dailyLimitMinor: b.dailyLimitMinor ?? 5000000,
          status: "ACTIVE", url: `https://example.test/q/${code}`, storeCodeUrl: null, version: 0 };
        qrCodes.set(no, [...list, v]); return v; }),
      disableQrCode: (no, code, c) => run(() => { cmdCheck(c); const v = (qrCodes.get(no) ?? []).find((x) => x.qrCode === code); need(!!v, "静态码不存在");
        if (v!.status !== "DISABLED") { need(c.expectedVersion === v!.version, "版本已变化，请刷新"); v!.status = "DISABLED"; v!.version = (v!.version ?? 0) + 1; }
        return ok(code, "DISABLED", v!.version ?? 0); }),
      methods: (no) => run(() => methodsOf(no)),
      saveMethods: (no, b) => run(() => { const cur = methodsOf(no); need(b.expectedVersion === cur.version, "版本已变化，请刷新"); need(b.eligibilityRevision === cur.eligibilityRevision, "商户或门店渠道设置已变化，请刷新");
        need(b.mode === "OVERRIDE" || b.enabledMethods.length === 0, "继承模式无需指定付款方式");
        const on = new Set(b.enabledMethods.map((m) => m.paymentMethodCode));
        const next: StoreMethodView = { ...cur, mode: b.mode, version: cur.version + 1,
          items: cur.items.map((i) => ({ ...i, configuredEnabled: b.mode === "INHERIT" ? true : on.has(i.choice.paymentMethodCode), effectiveEnabled: i.editable && (b.mode === "INHERIT" || on.has(i.choice.paymentMethodCode)) })) };
        methodViews.set(no, next); return next; }),
      syncEvents: (no, q) => pageOf([{ eventNo: `SY${no.slice(-4)}1`, sourceSystem: "AI_SHOP", eventId: `ais-${no}-1`, channel: "SYNC", revision: 1, outcome: "APPLIED", issue: null, createdAt: Date.now() - 3600_000 }], q, ["eventNo"]),
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
      draft: (no) => run(() => draftView(no)),
      saveDraft: (no, b) => run(() => {
        const d = checkDraft(no, b);
        for (const [k, v] of Object.entries(b.fields)) {
          if (["CONTACT_PHONE", "SETTLE_ACCOUNT"].includes(k) && v && !v.startsWith("cipher:")) throw new MockPayError("VALIDATION_ERROR", `${k} 必须先封存`);
          if (v == null || v === "") delete d.values[k]; else d.values[k] = v;
        }
        d.draftVersion += 1; d.state = "EDITING"; d.confirmedBy = null;
        return draftView(no);
      }),
      confirmDraft: (no, b) => run(() => { const d = checkDraft(no, b); need(draftView(no).missing.length === 0, "还有必填资料没填"); d.state = "CONFIRMED"; d.confirmedBy = "admin"; d.draftVersion += 1; return draftView(no); }),
      submitDraft: (no, b) => run(async () => {
        const d = checkDraft(no, b); need(d.state === "CONFIRMED", "资料确认后才能提交");
        const o = onboardings.get(no);
        await onboardings.update(no, { reviewStatus: "PENDING", materialVersion: o.materialVersion + 1, rejectReason: null, updatedAt: Date.now() });
        d.state = "FROZEN"; d.draftVersion += 1; d.supplementFields = []; d.supplementReason = null;
        return draftView(no);
      }),
      requestMoreFields: (no, b) => run(async () => {
        reasonOf(b.reason); need(b.fields.length > 0 && b.fields.length < 50, "勾选 1–49 个字段");
        const o = onboardings.get(no); need(o.reviewStatus === "PENDING", "不在待审核状态");
        const d = draftOf(no); if (d.draftVersion !== b.expectedDraftVersion) throw new MockPayError("VERSION_CONFLICT", "记录已被他人修改，请刷新后重试");
        await onboardings.update(no, { reviewStatus: "NEED_MORE", rejectReason: b.reason, updatedAt: Date.now() }, b.expectedVersion);
        d.supplementFields = b.fields; d.supplementReason = b.reason; d.state = "EDITING"; d.draftVersion += 1;
        return draftView(no);
      }),
      draftCommand: (no, rn) => run(() => { onboardings.get(no); return { requestNo: rn, applied: false }; }),
      readiness: (no) => run(() => { const o = onboardings.get(no); const done = o.status === "APPROVED"; return { receiptStage: done ? "IMPORTED" : "NOT_RECEIVED", candidateStage: done ? "ACCEPTED" : null, payMerchantNo: done ? `NWP${o.onboardingNo.slice(-4)}` : null, accountStatus: done ? "ENABLED" : "NONE", verificationStatus: done ? "VERIFIED" : "NONE", credentialStatus: done ? "ACTIVE" : "NONE", openProductCount: done ? 2 : 0, storeReadinessEvaluated: done, channelReady: true }; }),
      retryHandoff: (no, c) => run(async () => { cmdCheck(c); need(onboardings.get(no).status === "APPROVED", "只有已通过的进件能重试交接"); const r = await onboardings.update(no, {}, c.expectedVersion); return { onboardingNo: no, version: r.version, candidateStatus: "ACCEPTED" }; }),
      seal: (no, b) => run(() => { need(onboardings.get(no).version === b.expectedVersion, "记录已被他人修改，请刷新后重试"); need(!!b.secret && b.secret.length <= 512, "敏感值 1–512 字"); return { fieldCode: b.fieldCode, cipherRef: `cipher:newebpay/${(sealSeq++).toString().padStart(6, "0")}` }; }),
      files: (no) => run(() => [...(files.get(no)?.entries() ?? [])].map(([slot, f]) => ({ slot, fileNo: f.fileNo, contentType: f.contentType, byteLength: f.byteLength, expiresAt: Date.now() + 7 * 24 * H, pinned: false, expired: false, legacy: false }))),
      upload: (no, f, file) => run(async () => {
        need(/^LICENSE_(10|[1-9])$/.test(f.slot), "附件槽位不对");
        need(file.size > 0 && file.size <= 5 * 1024 * 1024, "附件 1 字节到 5 MiB");
        need(/\.(png|jpe?g|pdf)$/i.test(file.name), "只收 PNG、JPEG、PDF");
        const d = checkDraft(no, f);
        const fileNo = (fileSeq++).toString(16).padStart(32, "0");
        const m = files.get(no) ?? new Map(); m.set(f.slot, { fileNo, contentType: file.type || "application/octet-stream", byteLength: file.size, blob: file }); files.set(no, m);
        d.values[f.slot] = `file:onboarding/${fileNo}`; d.draftVersion += 1; d.state = "EDITING"; d.confirmedBy = null;
        return { draft: draftView(no), attachment: { slot: f.slot, fileNo, contentType: file.type, byteLength: file.size, expiresAt: Date.now() + 7 * 24 * H, pinned: false, expired: false, legacy: false } };
      }),
      file: (no, fileNo) => run(() => { const f = [...(files.get(no)?.values() ?? [])].find((x) => x.fileNo === fileNo); if (!f) throw new MockPayError("NOT_FOUND", "附件不存在"); return f.blob; }),
      manualResult: (no, b) => run(async () => {
        reasonOf(b.reason); const o = onboardings.get(no); need(o.submitMode === "MANUAL" && ["CHANNEL_REVIEW", "NEED_SIGN"].includes(o.status), "只有人工送件、渠道审核中的进件能登记结果");
        if (b.result === "NEED_SIGN") need(/^https:\/\//.test(b.signUrl ?? ""), "签约链接必须是 https");
        if (b.result === "APPROVED") need(!!b.channelMerchantId && /^file:/.test(b.evidenceRef ?? ""), "登记通过要填渠道商户号与凭证引用（file:）");
        const r = await onboardings.update(no, { status: b.result, signUrl: b.result === "NEED_SIGN" ? b.signUrl ?? null : o.signUrl, rejectReason: b.result === "REJECTED" ? b.reason : null, candidateStatus: b.result === "APPROVED" ? "PENDING" : null, updatedAt: Date.now() }, b.expectedVersion);
        return ok(no, r.status, r.version);
      }),
      modifications: (no) => run(() => {
        need(onboardings.get(no).payChannel === "NEWEBPAY", "只有蓝新进件能改商户资料");
        return { legalForm: "COMPANY", fields: [{ code: "MerchantName", label: "商店中文名称", maxLength: 20, options: null }, { code: "MerchantEmail", label: "商店客服信箱", maxLength: 300, options: null }, { code: "BankAccount", label: "结算银行账号", maxLength: 30, options: null }], requests: modifications.get(no) ?? [] };
      }),
      createModification: (no, b) => run(() => {
        const list = modifications.get(no) ?? [];
        need(!list.some((m) => ["PENDING", "APPROVED", "SENDING", "UNKNOWN"].includes(m.status)), "已有未完成的修改申请");
        need(Object.keys(b.changes).length > 0, "至少改一个字段");
        const m: NewebpayModification = { changeNo: `NM${no.slice(-4)}${list.length + 1}`, status: "PENDING", fieldCodes: Object.keys(b.changes), maskedChanges: Object.fromEntries(Object.entries(b.changes).map(([k, v]) => [k, k === "BankAccount" ? `****${v.slice(-4)}` : v])), approvalNo: null, resultCode: null, version: 1 };
        modifications.set(no, [m, ...list]); return m;
      }),
      dispatchModification: (no, ch, c) => run(() => { cmdCheck(c); const list = modifications.get(no) ?? []; const m = list.find((x) => x.changeNo === ch); need(m?.status === "APPROVED", "审批通过后才能发送"); m!.status = "ACCEPTED"; m!.version += 1; return m!; }),
      cancelModification: (no, ch, c) => run(() => { cmdCheck(c); const list = modifications.get(no) ?? []; const m = list.find((x) => x.changeNo === ch); need(!!m && ["PENDING", "APPROVED"].includes(m.status), "只能取消未发送的修改"); m!.status = "CANCELLED"; m!.version += 1; return m!; }),
    },
    documents: {
      recognize: (file, documentType) => run(() => {
        need(file.size <= 12 * 1024 * 1024, "证件文件不能超过 12 MiB");
        const fields: Record<string, string | null> = documentType === "REGISTRATION" ? { name: "台北早餐有限公司", taxNo: "24536806", registeredAddress: "台北市信义区松仁路 100 号", incorporationDate: "2019-05-01", capitalAmount: "5000000", representativeName: "王小明" }
          : documentType === "BANK_COVER" ? { bankCode: "812", branchCode: "0123", accountName: "台北早餐有限公司", bankAccount: "00123456789012" }
          : { name: "王小明", identityNo: "A123456789", birthDate: "1985-03-02", issueDate: "2018-07-01", issuePlace: "北市", idIssueType: "换发" };
        return { recognized: true, documentType, fields, confidence: 0.92 };
      }),
    },
    approvals: {
      submit: (b) => run(async () => {
        reasonOf(b.reason);
        const no = `AP${5001 + approvals.all().length}`;
        await approvals.insert({ approvalNo: no, bizType: b.subjectType, action: b.action, resourceNo: b.subjectRef, subjectTitle: `${b.subjectType} ${b.subjectRef}`, applicantNo: "admin",
          nodeNo: 1, assigneeNo: null, materialVersion: b.subjectRevision, status: "PENDING_FIRST", executionStatus: "NOT_STARTED", expiresAt: Date.now() + 48 * H, escalated: false, createdAt: Date.now(), version: 1,
          taskNo: `TK${5001 + approvals.all().length}`, taskVersion: 1, taskStatus: "READY" });
        return { approvalNo: no };
      }),
      list: (q) => {
        const view = (q.view as string) ?? "CANDIDATE";
        const rows = approvals.all().filter((a) => view === "ALL" ? true : view === "MINE" ? a.applicantNo === "admin" : view === "CLAIMED" ? a.taskStatus === "CLAIMED" && a.assigneeNo === "admin" : a.taskStatus === "READY");
        return pageOf(rows.map(({ taskNo: _t, taskVersion: _v, taskStatus: _s, ...r }) => r), { ...q, view: undefined }, ["approvalNo", "subjectTitle"]);
      },
      detail: (no) => run((): ApprovalDetail => {
        const a = approvals.get(no);
        const task: ApprovalTask = { taskNo: a.taskNo, approvalNo: no, nodeNo: a.nodeNo ?? 1, roleRef: "REVIEWER", status: a.taskStatus, assigneeNo: a.assigneeNo, decision: null, decisionReason: null, decidedAt: null, version: a.taskVersion };
        const acts = a.status === "APPROVED" ? ["execute"] : a.status.startsWith("PENDING") ? (a.taskStatus === "READY" ? ["claim", "assign", "withdraw"] : ["review", "release", "assign", "withdraw"]) : [];
        return {
          record: { approvalNo: no, subjectType: a.bizType, action: a.action, subjectRef: a.resourceNo ?? no, materialVersion: a.materialVersion, status: a.status, version: a.version, expiresAt: a.expiresAt, activeTask: a.status.startsWith("PENDING") ? task : null, execution: { status: a.executionStatus, executedAt: null, executedBy: null }, applicantNo: a.applicantNo, rejectReason: null, createdAt: a.createdAt },
          subjectTitle: a.subjectTitle, snapshotJson: JSON.stringify({ subject: a.subjectTitle, requestedBy: a.applicantNo }, null, 2), sourcePage: null, tasks: [task], decisions: [], timeline: [], allowedActions: acts,
        };
      }),
      // 敏感对象两级审核（near-pay 策略：凭证启用 requiredNodes = 2）：一审同意 → 待二审，新开一条待认领任务
      review: (no, b) => run(async () => {
        reasonOf(b.reason); const a = approvals.get(no); need(a.taskStatus === "CLAIMED", "请先认领");
        if (b.decision === "APPROVE" && a.status === "PENDING_FIRST" && TWO_LEVEL.has(a.bizType)) return approvals.update(no, { status: "PENDING_SECOND", nodeNo: 2, taskStatus: "READY", assigneeNo: null, taskVersion: a.taskVersion + 1 }, b.expectedVersion);
        return approvals.update(no, { status: b.decision === "APPROVE" ? "APPROVED" : b.decision === "REJECT" ? "REJECTED" : "CANCELLED", taskStatus: "DONE" }, b.expectedVersion);
      }),
      withdraw: (no, c) => run(async () => { cmdCheck(c); need(approvals.get(no).status.startsWith("PENDING"), "只能撤回审批中的申请"); return approvals.update(no, { status: "CANCELLED", taskStatus: "DONE" }, c.expectedVersion); }),
      // 执行 = 让来源对象落地（near-pay ApprovalSubjectPort#execute）：应用启用 / 恢复、密钥版本启用；其他类型只记执行
      execute: (no, c) => run(async () => {
        cmdCheck(c); const a = approvals.get(no); need(a.status === "APPROVED", "审批通过后才能执行"); need(a.executionStatus === "NOT_STARTED", "审批已执行");
        if (a.version !== c.expectedVersion) throw new MockPayError("VERSION_CONFLICT", "记录已被他人修改，请刷新后重试");
        if (a.bizType === "OPEN_APP_ENABLE") { const app = apps.get(a.resourceNo!); await activateApp(app.appNo, { expectedVersion: app.version, reason: c.reason }, app.status === "SUSPENDED" ? "resume" : "enable"); }
        else if (a.bizType === "CREDENTIAL_ENABLE") { const [k, v] = a.resourceNo!.split(":").map(Number); await enableCredVersion(k, v, a.approvalNo); }
        else await executed(no);
        return approvals.get(no);
      }),
      claim: (taskNo, c) => run(async () => { cmdCheck(c); const a = approvals.all().find((x) => x.taskNo === taskNo)!; need(a.taskStatus === "READY", "任务已被认领"); if (a.taskVersion !== c.expectedVersion) throw new MockPayError("VERSION_CONFLICT", "记录已被他人修改，请刷新后重试"); await approvals.update(a.approvalNo, { taskStatus: "CLAIMED", assigneeNo: "admin", taskVersion: a.taskVersion + 1 }); return { taskNo, approvalNo: a.approvalNo, nodeNo: 1, roleRef: null, status: "CLAIMED", assigneeNo: "admin", decision: null, decisionReason: null, decidedAt: null, version: a.taskVersion + 1 }; }),
      release: (taskNo, c) => run(async () => { cmdCheck(c); const a = approvals.all().find((x) => x.taskNo === taskNo)!; need(a.taskStatus === "CLAIMED", "任务未被认领"); await approvals.update(a.approvalNo, { taskStatus: "READY", assigneeNo: null, taskVersion: a.taskVersion + 1 }); return { taskNo, approvalNo: a.approvalNo, nodeNo: 1, roleRef: null, status: "READY", assigneeNo: null, decision: null, decisionReason: null, decidedAt: null, version: a.taskVersion + 1 }; }),
      assign: (taskNo, b) => run(async () => { cmdCheck(b); const a = approvals.all().find((x) => x.taskNo === taskNo)!; await approvals.update(a.approvalNo, { taskStatus: "CLAIMED", assigneeNo: b.assigneeNo, taskVersion: a.taskVersion + 1 }); return { taskNo, approvalNo: a.approvalNo, nodeNo: 1, roleRef: null, status: "CLAIMED", assigneeNo: b.assigneeNo, decision: null, decisionReason: null, decidedAt: null, version: a.taskVersion + 1 }; }),
    },
    channels: {
      list: (q) => pageOf(channels.all(), q, ["channelCode", "name"]),
      detail: (code) => run(() => { const c = channels.get(code); return wrap(c, c.version, ["EDIT", c.status === "ENABLED" ? "DISABLE" : "ENABLE", "DIAGNOSE"]); }),
      create: (b) => run(async () => { reasonOf(b.reason); need(b.expectedVersion === 0, "新建时版本必须为 0"); if (channels.find(b.channelCode)) throw new MockPayError("STATE_CONFLICT", "渠道编码已存在"); const row: ChannelRow = { channelCode: b.channelCode, name: b.name, channelType: b.channelType as ChannelRow["channelType"], adapterCode: b.adapterCode, platformCredentialKeyId: b.platformCredentialKeyId ?? null, status: "DISABLED", sortNo: b.sortNo ?? 0, enabledProductCount: 0, endpoints: b.endpoints.map((x) => ({ ...x, environment: x.environment as "SANDBOX", callbackStyle: x.callbackStyle as "PATH" | null, enabled: true })), version: 1, updatedAt: Date.now(), updatedBy: "admin", market: b.market, testChannel: !!b.testChannel }; await channels.insert(row); return wrap(row, 1, ["EDIT", "ENABLE", "DIAGNOSE"]); }),
      update: (code, b) => run(async () => { reasonOf(b.reason); const r = await channels.update(code, { name: b.name, adapterCode: b.adapterCode, sortNo: b.sortNo ?? 0, market: b.market, testChannel: !!b.testChannel, platformCredentialKeyId: b.platformCredentialKeyId ?? null, endpoints: b.endpoints.map((x) => ({ ...x, environment: x.environment as "SANDBOX", callbackStyle: x.callbackStyle as "PATH" | null, enabled: true })), updatedAt: Date.now() }, b.expectedVersion); return ok(code, r.status, r.version); }),
      enable: (code, c) => run(() => setStatus(channels, code, c, ["DISABLED", "PENDING"], "ENABLED")),
      disable: (code, c) => run(() => setStatus(channels, code, c, ["ENABLED", "PENDING"], "DISABLED")),
      diagnose: (code, c) => run(() => { cmdCheck(c); channels.get(code); return { jobNo: `JOB${Date.now() % 100000}`, status: "SUCCEEDED", errorCode: null }; }),
      pending: (code, c) => run(() => setStatus(channels, code, c, ["ENABLED", "DISABLED"], "PENDING")),
      adapters: () => run(() => ["NEWEBPAY", "LINEPAY", "JKOPAY", "MOCKPAY", "PAYBY"].map((a) => ({ adapterCode: a, channelType: "FIAT", operations: ["PAY", "REFUND", "QUERY"], accountModes: ["MERCHANT_OWN"], channelMethods: ["WEB"], tradeModes: ["ONE_TIME"] }))),
    },
    products: {
      list: (q) => pageOf(products.all(), q, ["productCode", "displayName"]),
      detail: (code) => run(() => { const p = products.get(code); return wrap(p, p.version, ["EDIT", p.status === "ENABLED" ? "DISABLE" : "ENABLE"]); }),
      create: (b) => run(async () => { reasonOf(b.reason); need(b.expectedVersion === 0, "新建时版本必须为 0"); if (products.find(b.productCode)) throw new MockPayError("STATE_CONFLICT", "产品编码已存在"); const { expectedVersion: _v, reason: _r, ...rest } = b; const row: ProductRow = { ...rest, status: "DISABLED", version: 1, updatedAt: Date.now(), updatedBy: "admin" }; await products.insert(row); return wrap(row, 1, ["EDIT", "ENABLE"]); }),
      update: (code, b) => run(async () => { reasonOf(b.reason); const { expectedVersion, reason: _r, ...rest } = b; const r = await products.update(code, { ...rest, updatedAt: Date.now() }, expectedVersion); return ok(code, r.status, r.version); }),
      enable: (code, c) => run(() => setStatus(products, code, c, ["DISABLED"], "ENABLED")),
      disable: (code, c) => run(() => setStatus(products, code, c, ["ENABLED"], "DISABLED")),
      paymentMethods: () => run(() => [{ code: "CARD", name: "信用卡", category: "CARD" }, { code: "TWQR", name: "台湾 Pay QR", category: "QR" }, { code: "LINEPAY", name: "LINE Pay", category: "WALLET" }, { code: "JKOPAY", name: "街口支付", category: "WALLET" }]),
    },
    channelAccounts: {
      list: (q) => pageOf(accounts.all().map((d) => d.account).filter((a) => (!q.merchantNo || a.merchantNo === q.merchantNo) && (!q.status || a.status === q.status) && (!q.payChannel || a.payChannel === q.payChannel)), { ...q, merchantNo: undefined, status: undefined, payChannel: undefined, ownerType: undefined }, ["payMerchantNo", "channelMerchantId"]),
      detail: (no) => run(() => { const d = accounts.get(no); const a = d.account; return wrap(d, d.version, [
        ...(a.verificationStatus !== "VERIFIED" ? ["submitVerify", "verify"] : []), ...(a.verificationStatus === "VERIFIED" && a.status === "UNVERIFIED" ? ["activate"] : []),
        ...(a.status === "ACTIVE" ? ["refundOnly", "suspend"] : []), ...(a.status === "SUSPENDED" ? ["resume"] : []), ...(a.status !== "CLOSED" ? ["products", "credential"] : []),
      ]); }),
      register: (b) => run(async () => { reasonOf(b.reason); need(!accounts.all().some((d) => d.account.payChannel === b.payChannel && d.account.channelMerchantId.endsWith(b.channelMerchantId.slice(-4))), "同一渠道商户号只能登记一次");
        const no = `${b.payChannel.slice(0, 3)}${7200 + accounts.all().length}`; const m = merchants.get(b.merchantNo);
        const row = { payMerchantNo: no, version: 1, verifiedBy: null, verifiedAt: null, verifyApprovalNo: null, verifyReason: null, registeredBy: "admin", products: [], credentialVersion: null, settleCycle: null, poolAccountRef: null, maxSplitRate: null, statusLogs: [],
          account: { payMerchantNo: no, ownerType: "MERCHANT" as const, merchantNo: b.merchantNo, merchantName: m.displayName, payChannel: b.payChannel, market: "TW", currency: "TWD", channelMerchantId: `****${b.channelMerchantId.slice(-4)}`, subMerchantId: b.subMerchantId ?? null, accountMode: b.accountMode, fundsPath: b.fundsPath, parentPayMerchantNo: null, source: "MANUAL" as const, onboardingNo: null, evidenceRef: b.evidenceRef, verificationStatus: "PENDING" as const, status: "UNVERIFIED" as const, credentialKeyId: null, boundStoreCount: 0, updatedAt: Date.now(), version: 1 } };
        await accounts.insert(row); return wrap(row, 1, ["submitVerify", "verify", "products", "credential"]); }),
      reject: (no, c) => run(async () => { cmdCheck(c); need(accounts.get(no).account.verificationStatus === "PENDING", "只有待核验账户可驳回"); const r = await accounts.update(no, { verifyReason: c.reason }, c.expectedVersion); syncAccount(no, { verificationStatus: "REJECTED" }); return ok(no, "REJECTED", r.version); }),
      activate: (no, c) => run(async () => { cmdCheck(c); const d = accounts.get(no); need(d.account.verificationStatus === "VERIFIED" && d.products.some((p) => p.status === "OPEN") && !!d.account.credentialKeyId, "须已核验、至少开通一个产品、凭据就绪"); const r = await accounts.update(no, {}, c.expectedVersion); syncAccount(no, { status: "ACTIVE" }); return ok(no, "ACTIVE", r.version); }),
      suspend: (no, c) => run(async () => { cmdCheck(c); need(["ACTIVE", "REFUND_ONLY"].includes(accounts.get(no).account.status), "当前状态不允许停用"); const r = await accounts.update(no, {}, c.expectedVersion); syncAccount(no, { status: "SUSPENDED" }); return ok(no, "SUSPENDED", r.version); }),
      refundOnly: (no, c) => run(async () => { cmdCheck(c); need(accounts.get(no).account.status === "ACTIVE", "只有可收款账户可改为仅退款"); const r = await accounts.update(no, {}, c.expectedVersion); syncAccount(no, { status: "REFUND_ONLY" }); return ok(no, "REFUND_ONLY", r.version); }),
      update: (no, b) => run(async () => {
        reasonOf(b.reason); const cur = accounts.get(no); need(cur.account.status === "UNVERIFIED", "只有未核验的账户可修改坐标");
        need(!!b.channelMerchantId?.trim(), "请填写渠道商户号"); need(b.accountMode !== "MERCHANT_OWN" || !b.parentPayMerchantNo, "自有商户模式不能填上级账户");
        const r = await accounts.update(no, { account: { ...cur.account, channelMerchantId: `****${b.channelMerchantId.slice(-4)}`, subMerchantId: b.subMerchantId ? `****${b.subMerchantId.slice(-4)}` : null, accountMode: b.accountMode as never, fundsPath: b.fundsPath as never, parentPayMerchantNo: b.parentPayMerchantNo, evidenceRef: b.evidenceRef, verificationStatus: "PENDING" } }, b.expectedVersion);
        return ok(no, r.account.status, r.version);
      }),
      openProduct: (no, code, b) => run(async () => { reasonOf(b.reason); need(!!b.evidenceRef.trim(), "请填写证明引用"); const d = accounts.get(no); const products = [...d.products.filter((p) => p.productCode !== code), { productCode: code, productName: products_name(code), status: "OPEN" as const, source: "OPS_CONFIRMED" as const, evidenceRef: b.evidenceRef, openedBy: "admin", openedAt: Date.now() }]; const r = await accounts.update(no, { products }, b.expectedVersion); syncAccount(no, {}); return ok(no, d.account.status, r.version); }),
      closeProduct: (no, code, c) => run(async () => { cmdCheck(c); const d = accounts.get(no); need(d.account.status !== "ACTIVE" || d.products.filter((p) => p.status === "OPEN").length > 1, "可收款账户须保留至少一个产品"); const r = await accounts.update(no, { products: d.products.map((p) => (p.productCode === code ? { ...p, status: "CLOSED" as const } : p)) }, c.expectedVersion); syncAccount(no, {}); return ok(no, d.account.status, r.version); }),
      bindCredential: (no, b) => run(async () => { reasonOf(b.reason); const r = await accounts.update(no, { credentialVersion: 1 }, b.expectedVersion); syncAccount(no, { credentialKeyId: b.keyId }); return ok(no, accounts.get(no).account.status, r.version); }),
      enterKey: (no, b) => run(async () => { reasonOf(b.reason); const d = accounts.get(no); const first = d.account.status === "UNVERIFIED"; await accounts.update(no, { credentialVersion: (d.credentialVersion ?? 0) + 1, ...(first ? { verifiedBy: "admin", verifiedAt: Date.now() } : {}) }); syncAccount(no, first ? { credentialKeyId: 600 + accounts.all().length, verificationStatus: "VERIFIED", status: "ACTIVE" } : {}); const n = accounts.get(no); return wrap(n, n.version, []); }),
      bindings: (no) => pageOf(bindings.all().filter((b) => b.payMerchantNo === no), { size: 100 }, []),
    },
    transfers: {
      list: (q) => pageOf(transfers.filter((t) => !q.payMerchantNo || t.payMerchantNo === q.payMerchantNo), { ...q, payMerchantNo: undefined }, ["transferNo"]),
      detail: (no) => run(() => { const t = transfers.find((x) => x.transferNo === no); need(!!t, "转移申请不存在"); return wrap(t!, t!.version, t!.status === "PENDING_APPROVAL" ? ["approve", "reject"] : t!.status === "APPROVED" ? ["execute"] : []); }),
      create: (b) => run(() => { need(!!b.evidence.trim(), "请填写证明"); need(b.fromMerchantNo !== b.toMerchantNo, "目标商户不能与当前商户相同");
        need(!transfers.some((t) => t.payMerchantNo === b.payMerchantNo && ["PENDING_APPROVAL", "APPROVED"].includes(t.status)), "该账户已有进行中的转移");
        const t: ChannelAccountTransfer = { transferNo: `CT${7001 + transfers.length}`, requestNo: `RQ${Date.now()}`, ...b, status: "PENDING_APPROVAL", approvalNo: `AP${8001 + transfers.length}`, approvalStatus: "PENDING_FIRST", approvalVersion: 1,
          requestedBy: "admin", executedBy: null, executedAt: null, beforeSnapshot: { merchantNo: b.fromMerchantNo }, afterSnapshot: { merchantNo: b.toMerchantNo }, createdAt: Date.now(), version: 1 };
        transfers.unshift(t); return wrap(t, 1, ["approve", "reject"]); }),
      approve: (no, c) => run(() => { cmdCheck(c); const t = transfers.find((x) => x.transferNo === no)!; need(t.status === "PENDING_APPROVAL", "只有待审核的申请可审核"); need(t.requestedBy !== "reviewer", "申请人不能审核自己的申请"); Object.assign(t, { status: "APPROVED", approvalStatus: "APPROVED", approvalVersion: (t.approvalVersion ?? 0) + 1, version: t.version + 1 }); return ok(no, t.status, t.version, t.approvalNo); }),
      reject: (no, c) => run(() => { cmdCheck(c); const t = transfers.find((x) => x.transferNo === no)!; need(t.status === "PENDING_APPROVAL", "只有待审核的申请可驳回"); Object.assign(t, { status: "REJECTED", approvalStatus: "REJECTED", version: t.version + 1 }); return ok(no, t.status, t.version, t.approvalNo); }),
      execute: (no, c) => run(() => { cmdCheck(c); const t = transfers.find((x) => x.transferNo === no)!; need(t.status === "APPROVED", "审核通过后才能执行"); Object.assign(t, { status: "EXECUTED", executedBy: "admin", executedAt: Date.now(), version: t.version + 1 }); return ok(no, t.status, t.version, t.approvalNo); }),
    },
    qualifications: {
      ofAccount: (no) => run(() => qualifications.filter((x) => x.coordinates?.payMerchantNo === no)),
      prepare: (no, b) => run(() => {
        reasonOf(b.reason); need(/^[A-Z0-9_-]{1,32}$/.test(b.evidenceNo), "证据号格式不对"); need(b.trialEvidence.startsWith("sandbox-trial:"), "试运行证据须以 sandbox-trial: 开头");
        const v: QualificationVersion = { ref: `${b.evidenceNo}:${b.revision}`, revision: b.revision, channel: "NEWEBPAY", purpose: "SANDBOX_TRIAL", coordinates: { payMerchantNo: no, product: b.productCode, entry: "H5" }, coordinateKey: null, payloadJson: JSON.stringify({ returnUrl: b.returnUrl, notifyUrl: b.notifyUrl }), payloadSha256: "mock", status: "DRAFT", maker: "admin", approvalNo: null, validUntil: b.validUntil, version: 1 };
        qualifications.push(v); return v;
      }),
      detail: (ref) => run(() => { const v = qualifications.find((x) => x.ref === ref); need(!!v, "资质版本不存在"); return v!; }),
      importVersion: (b) => run(() => { reasonOf(b.reason); try { JSON.parse(b.payloadJson); } catch { need(false, "资质内容须为 JSON"); }
        const v: QualificationVersion = { ref: `${b.evidenceNo}:${b.revision}`, revision: b.revision, channel: b.channel, purpose: b.purpose, coordinates: JSON.parse(b.payloadJson).coordinates ?? null, coordinateKey: null,
          payloadJson: b.payloadJson, payloadSha256: "mock", status: "DRAFT", maker: "admin", approvalNo: null, validUntil: b.validUntil, version: 1 };
        need(!qualifications.some((x) => x.ref === v.ref), "同一证据号的版本已存在"); qualifications.unshift(v); return v; }),
      revoke: (ref, reason) => run(() => { reasonOf(reason); const v = qualifications.find((x) => x.ref === ref); need(!!v && v.status !== "REVOKED", "资质版本不存在或已吊销"); Object.assign(v!, { status: "REVOKED", version: v!.version + 1 }); return ref; }),
    },
    storeBindings: {
      list: (store) => pageOf(bindings.all().filter((b) => b.storeNo === store), { size: 100 }, []),
      bind: (store, b) => run(async () => { reasonOf(b.reason); need(!bindings.all().some((x) => x.storeNo === store && x.payChannel === b.payChannel && x.status === "ACTIVE"), "同门店同渠道已有生效绑定（BINDING_CONFLICT）"); const s = stores.get(store); const row: StoreBinding = { bindingNo: `SB${5100 + bindings.all().length}`, storeNo: store, merchantNo: s.merchantNo, payChannel: b.payChannel, payMerchantNo: b.payMerchantNo, status: "ACTIVE", reason: b.reason, approvalNo: null, boundBy: "admin", effectiveFrom: Date.now(), effectiveTo: null, endedReason: null, pendingTargetPayMerchantNo: null, pendingReason: null, version: 1 }; await bindings.insert(row); syncAccount(b.payMerchantNo, { boundStoreCount: accounts.get(b.payMerchantNo).account.boundStoreCount + 1 }); return wrap(row, 1, []); }),
      unbind: (store, b, c) => run(async () => { cmdCheck(c); const x = bindings.get(b); need(x.status === "ACTIVE" && x.storeNo === store, "只能解绑生效中的绑定"); const r = await bindings.update(b, { status: "INACTIVE", effectiveTo: Date.now(), endedReason: c.reason }, c.expectedVersion); syncAccount(x.payMerchantNo, { boundStoreCount: Math.max(0, accounts.get(x.payMerchantNo).account.boundStoreCount - 1) }); return ok(b, "INACTIVE", r.version); }),
      impact: (store, b, target) => run(() => { const x = bindings.get(b); const t = accounts.find(target); const bindable = !!t && t.account.verificationStatus === "VERIFIED" && ["ACTIVE", "REFUND_ONLY"].includes(t.account.status) && t.account.merchantNo === x.merchantNo; const cur = accounts.find(x.payMerchantNo)?.products.filter((p) => p.status === "OPEN").map((p) => p.productCode) ?? []; const next = t?.products.filter((p) => p.status === "OPEN").map((p) => p.productCode) ?? []; return { bindingNo: b, currentPayMerchantNo: x.payMerchantNo, targetPayMerchantNo: target, targetBindable: bindable, blockedReason: bindable ? null : "目标账户未核验或不可收款", productsKept: cur.filter((c) => next.includes(c)), productsLost: cur.filter((c) => !next.includes(c)), productsGained: next.filter((c) => !cur.includes(c)), inflightPayments: "UNKNOWN" as const }; }),
      rebindRequest: (store, b, body) => run(async () => { reasonOf(body.reason); need(!bindings.get(b).pendingTargetPayMerchantNo, "已有待审换绑"); const r = await bindings.update(b, { pendingTargetPayMerchantNo: body.targetPayMerchantNo, pendingReason: body.reason }, body.expectedVersion); return ok(b, "ACTIVE", r.version); }),
      rebindWithdraw: (store, b, c) => run(async () => { cmdCheck(c); need(!!bindings.get(b).pendingTargetPayMerchantNo, "没有待审换绑"); const r = await bindings.update(b, { pendingTargetPayMerchantNo: null, pendingReason: null }, c.expectedVersion); return ok(b, "ACTIVE", r.version); }),
      rebind: (store, b, c) => run(async () => {
        cmdCheck(c); const x = bindings.get(b); need(x.status === "ACTIVE" && x.storeNo === store && !!x.pendingTargetPayMerchantNo, "无待审换绑");
        await bindings.update(b, { status: "INACTIVE", effectiveTo: Date.now(), endedReason: c.reason }, c.expectedVersion);
        const row: StoreBinding = { ...x, bindingNo: `SB${5100 + bindings.all().length}`, payMerchantNo: x.pendingTargetPayMerchantNo!, pendingTargetPayMerchantNo: null, pendingReason: null, effectiveFrom: Date.now(), reason: c.reason, version: 1 };
        await bindings.insert(row); return ok(row.bindingNo, "ACTIVE", 1);
      }),
    },
    claims: {
      list: (merchantNo) => run(() => claims.all().filter((c) => c.merchantNo === merchantNo)),
      detail: (no) => run(() => claims.get(no)),
      resolve: (no, b) => run(async () => { reasonOf(b.reason); need(claims.get(no).state === "PENDING_VERIFY", "只有待核实的认领可处理"); return claims.update(no, { state: b.result, reason: b.reason, payMerchantNo: b.payMerchantNo ?? null }, b.expectedVersion); }),
      command: (_no, rn) => run(() => { throw new MockPayError("NOT_FOUND", `命令 ${rn} 没有回执`); }),
      file: () => Promise.reject(new MockPayError("NOT_FOUND", "mock 没有证明文件")),
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
      credentialReference: (no) => run(() => { const t = terminals.get(no); return { terminalNo: no, credentialRef: t.credentialRef ? `secret:terminal/${no}` : null, keyId: t.credentialRef ? 4100 : null }; }),
      retirement: (no) => run(() => { const t = terminals.get(no); return { terminalNo: no, serialNo: t.serialNo, deviceStatus: t.deviceStatus, retirementStatus: t.deviceStatus === "RETIRED" ? "RETIRED" : "NOT_STARTED", version: t.version }; }),
      lost: (no) => run(async () => { need(terminals.get(no).deviceStatus !== "RETIRED", "已退役"); await terminals.update(no, { deviceStatus: "LOST", configurationStatus: "DISABLED" }); return null; }),
      retire: (no, c) => run(async () => { cmdCheck(c); const r = await terminals.update(no, { deviceStatus: "RETIRED", configurationStatus: "DISABLED" }, c.expectedVersion); return { terminalNo: no, serialNo: r.serialNo, deviceStatus: "RETIRED", retirementStatus: "COMPLETED", version: r.version }; }),
    },
    apps: {
      list: (q) => pageOf(apps.all().map(({ grants: _g, ...a }) => a), q, ["appNo", "name"]),
      detail: (no) => run(() => { const { grants: _g, ...a } = apps.get(no); const acts = ({ DRAFT: ["update", "scopes", "callback", "verifyCallback", "enable", "revoke"], ACTIVE: ["update", "scopes", "suspend", "revoke"], SUSPENDED: ["update", "scopes", "callback", "verifyCallback", "resume", "revoke"] } as Record<string, string[]>)[a.status] ?? []; return wrap({ app: a, registeredBy: "admin", callbackSetBy: "admin", callbackVerifiedBy: a.callbackStatus === "VERIFIED" ? "admin2" : null, callbackEvidenceRef: null, statusReason: null, enableApprovalNo: null }, a.version, acts); }),
      grants: (no) => run(() => ({ records: apps.get(no).grants, total: apps.get(no).grants.length })),
      revokeGrant: (no, store, c) => run(async () => {
        cmdCheck(c); const a = apps.get(no); const g = a.grants.find((x) => x.storeNo === store); need(!!g, "没有这个门店的授权");
        if (g!.status === "REVOKED") return ok(no, "REVOKED", g!.version);
        if (g!.version !== c.expectedVersion) throw new MockPayError("VERSION_CONFLICT", "授权已被他人修改，请刷新后重试");
        await apps.update(no, { grants: a.grants.map((x) => (x.storeNo === store ? { ...x, status: "REVOKED", version: x.version + 1 } : x)) }); return ok(no, "REVOKED", g!.version + 1);
      }),
      create: (b) => run(async () => { reasonOf(b.reason); const row = { appNo: `APP${9001 + apps.all().length}`, name: b.name, purpose: b.purpose, ownerKind: b.ownerKind as "MERCHANT", merchantNo: b.merchantNo, environment: (b.environment ?? "SANDBOX") as "SANDBOX", scopes: b.scopes, notifyUrl: b.notifyUrl, callbackDomains: b.callbackDomains, callbackStatus: "UNVERIFIED", ipWhitelist: b.ipWhitelist, rateLimitQps: b.rateLimitQps, dailyRefundLimitMinor: b.dailyRefundLimitMinor, credentialStatus: "NONE", grantCount: 0, status: "DRAFT", lastAccessAt: null, lastAccessOutcome: null, version: 1, grants: [], signType: b.signType ?? "HMAC" }; await apps.insert(row); const { grants: _g, ...a } = row; return wrap({ app: a, registeredBy: "admin", callbackSetBy: null, callbackVerifiedBy: null, callbackEvidenceRef: null, statusReason: null, enableApprovalNo: null }, 1, ["update"]); }),
      update: (no, b) => run(async () => { reasonOf(b.reason); const { expectedVersion, reason: _r, ...rest } = b; const r = await apps.update(no, rest, expectedVersion); return ok(no, r.status, r.version); }),
      scopes: (no, b) => run(async () => { reasonOf(b.reason); const r = await apps.update(no, { scopes: b.scopes }, b.expectedVersion); return ok(no, r.status, r.version); }),
      callback: (no, b) => run(async () => { reasonOf(b.reason); const r = await apps.update(no, { notifyUrl: b.notifyUrl, callbackDomains: b.callbackDomains, callbackStatus: "UNVERIFIED" }, b.expectedVersion); return ok(no, r.status, r.version); }),
      verifyCallback: (no, b) => run(async () => { reasonOf(b.reason); const r = await apps.update(no, { callbackStatus: "VERIFIED" }, b.expectedVersion); return ok(no, r.status, r.version); }),
      command: (no, action, c) => run(() => {
        if (action === "enable" || action === "resume") return activateApp(no, c, action);
        return setStatus(apps, no, c, action === "suspend" ? ["ACTIVE"] : ["DRAFT", "ACTIVE", "SUSPENDED"], action === "suspend" ? "SUSPENDED" : "REVOKED");
      }),
      // 与 near-pay 同序（PayCredentialService#issueAppCredential）：按应用登记凭证、生成一版 DRAFT，明文只在这次响应里；
      // 之后核验 → 两人启用。mock 只有 admin 一个身份，录入人记成另一位运营（ST-OPS-1），好让 admin 走完「启用人 ≠ 录入人」。
      issueCredential: (no, b) => run(async () => {
        reasonOf(b.reason); const app = apps.get(no); need(app.status !== "REVOKED", "已吊销的应用不能签发密钥");
        const type = b.credentialType ?? "APP_SECRET";
        need(type === "APP_SECRET" || type === "APP_DATA_KEY", "只能签发 APP_SECRET / APP_DATA_KEY");
        const env = app.environment === "LIVE" ? "live" : "test";
        const secret = `sk_${env}_${Array.from({ length: 32 }, () => "abcdefghijkmnpqrstuvwxyz23456789"[Math.floor(Math.random() * 32)]).join("")}`;
        const existing = creds.all().find((r) => r.ownerType === "PARTNER_APP" && r.ownerRef === no && r.credentialType === type && r.status !== "REVOKED");
        const keyId = existing?.keyId ?? Math.max(...creds.all().map((r) => r.keyId)) + 1;
        const v: CredentialVersion = { ...ver(existing ? Math.max(0, ...existing.versions.map((x) => x.versionIndex)) + 1 : 1, "DRAFT"), source: "GENERATED", displayPrefix: `sk_${env}_`, fingerprint: `fp${secret.slice(8, 16)}` };
        if (existing) await creds.update(String(keyId), { versions: [v, ...existing.versions], pendingVersions: existing.pendingVersions + 1 });
        else await creds.insert({ keyId, credentialType: type, ownerType: "PARTNER_APP", ownerRef: no, name: `${no} ${type}`, counterparty: null, purpose: "APP_SIGN", algorithm: type === "APP_SECRET" ? "HMAC-SHA256" : "AES-256-GCM", materialType: "SYMMETRIC_SECRET", env: app.environment === "LIVE" ? "PRODUCTION" : "SANDBOX", status: "ACTIVE", currentVersion: 0, currentFingerprint: null, expiresAt: null, expiryState: "NONE", pendingVersions: 1, lastUsedAt: null, versions: [v], version: 1 });
        if (app.credentialStatus !== "ACTIVE") await apps.update(no, { credentialStatus: "PENDING" });
        return { keyId, versionIndex: v.versionIndex, serial: `${no}-${v.versionIndex}`, secret, fingerprint: v.fingerprint, status: "DRAFT" };
      }),

      keyChallenge: (no) => run(() => {
        const a = apps.get(no); need(a.signType === "RSA", "只有 RSA 应用上传商家公钥"); need(a.status === "ACTIVE", "应用启用后才能上传公钥");
        const challenge = `CH${Date.now().toString(36)}${++keySeq}`; const expiresAt = Date.now() + 5 * 60_000; challenges.set(challenge, { appNo: no, expiresAt });
        return { appNo: no, challenge, expiresAt, stringToSignPrefix: `pop|${no}|${challenge}|` };
      }),
      uploadPublicKey: (no, b) => run(async () => {
        reasonOf(b.reason); const ch = challenges.get(b.challenge); need(!!ch && ch.appNo === no && ch.expiresAt > Date.now(), "挑战不存在、已过期或已使用"); challenges.delete(b.challenge);
        need(b.publicKeyPem.includes("-----BEGIN PUBLIC KEY-----") && !b.publicKeyPem.includes("PRIVATE"), "公钥 PEM 不合法");
        need(Math.abs(Date.now() - Number(b.timestamp)) <= 5 * 60_000, "时间戳不在 5 分钟内");
        need(await rsaVerifyBase64(b.publicKeyPem, `pop|${no}|${b.challenge}|${b.timestamp}`, b.signature), "持有证明签名不符");
        const serial = `K${new Date().toISOString().replace(/\D/g, "").slice(0, 14)}${String(keySeq).padStart(2, "0")}`;
        const keyId = 4300 + creds.all().length;
        const v = { ...ver(1, "VERIFIED", "admin"), externalKeyId: serial, displayPrefix: serial, verifyEvidence: `POP:${b.challenge}` };
        await creds.insert({ keyId, credentialType: "APP_PUBLIC_KEY", ownerType: "PARTNER_APP", ownerRef: no, name: `${no} 商家公钥`, counterparty: null, purpose: "APP_SIGN", algorithm: "RSA_SHA256", materialType: "PUBLIC_KEY", env: "PRODUCTION", status: "ACTIVE", currentVersion: 0, currentFingerprint: null, expiresAt: null, expiryState: "NONE", pendingVersions: 1, lastUsedAt: null, versions: [v], version: 1 });
        publicPem.set(`${keyId}:1`, b.publicKeyPem);
        return { keyId, version: v };
      }),
      emergencyLock: (no, reason) => run(async () => {
        reasonOf(reason); const a = apps.get(no); need(a.status !== "REVOKED", "已吊销的应用不需要锁定");
        const revoked: string[] = [];
        for (const r of creds.all().filter((x) => x.ownerType === "PARTNER_APP" && x.ownerRef === no && ["APP_SECRET", "APP_PUBLIC_KEY"].includes(x.credentialType))) {
          const vs = r.versions.map((v) => { if (v.status === "REVOKED") return v; revoked.push(`${r.keyId}:v${v.versionIndex}`); return { ...v, status: "REVOKED", revokedBy: "admin", revokedAt: Date.now(), revokeReason: reason }; });
          await creds.update(String(r.keyId), { versions: vs, currentVersion: 0, currentFingerprint: null, pendingVersions: 0 });
        }
        const next = await apps.update(no, { dailyRefundLimitMinor: 0, credentialStatus: "REVOKED" });
        return { appNo: no, revokedVersions: revoked, refundLimitBefore: a.dailyRefundLimitMinor, refundLimitAfter: 0, appVersion: next.version };
      }),
      keyUsage: (no) => run(() => (apps.get(no).lastAccessAt ? [{ keyVersion: 2, calls: 1284, lastAt: T0 - H }, { keyVersion: 1, calls: 3, lastAt: T0 - 5 * 24 * H }] : [])),    },
    appApplications: {
      list: (q) => pageOf([...applications.all()].sort((a, b) => (b.createdAt ?? 0) - (a.createdAt ?? 0)), q, ["applicationNo", "appName", "merchantNo"]),
      detail: (no) => run(() => { const a = applications.get(no); return wrap(a, a.version, appActions(a)); }),
      submit: (b) => run(async () => {
        need(!!b.merchantNo.trim() && merchants.all().some((m) => m.merchantNo === b.merchantNo.trim()), "平台商户不存在或未启用");
        need(!!b.appName.trim() && b.appName.length <= 128, "请填写应用名称（≤128）");
        need(b.scopes.length > 0 && b.scopes.every((x) => ["READ", "COLLECT", "REFUND"].includes(x)), "接口权限为 READ / COLLECT / REFUND 的非空子集");
        need(Number.isInteger(b.rateLimitQps) && b.rateLimitQps >= 1 && b.rateLimitQps <= 1000 && b.dailyRefundLimitMinor >= 0, "QPS 1–1000，退款上限不能为负");
        need(!b.contactEmail || /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(b.contactEmail), "联系人邮箱格式不正确");
        const domains = [...new Set(b.callbackDomains.map((d) => d.trim().toLowerCase()).filter(Boolean))].sort();
        if (b.notifyUrl) { let u: URL | null = null; try { u = new URL(b.notifyUrl); } catch { /* 下面判 */ } need(u?.protocol === "https:", "回调须为 HTTPS 地址"); need(domains.some((d) => u!.hostname === d || u!.hostname.endsWith(`.${d}`)), "回调域名不在白名单"); }
        const a: AppApplicationRow = { ...b, applicationNo: `AAP${Date.now().toString().slice(-11)}`, merchantNo: b.merchantNo.trim(), appName: b.appName.trim(), environment: "LIVE", callbackDomains: domains, status: "SUBMITTED", submittedBy: "admin", reviewedBy: null, reviewedAt: null, reviewReason: null, appNo: null, createdAt: Date.now(), version: 0 };
        await applications.insert(a); return wrap(a, 0, appActions(a));
      }),
      decide: (no, action, c) => run(async () => {
        cmdCheck(c); const a = applications.get(no); need(a.status === "SUBMITTED", `申请已处理：${a.status}`);
        if (action === "withdraw") need(a.submittedBy === "admin", "仅提交人可撤回");
        else need(a.submittedBy !== "admin", "提交人不得审核本申请（SELF_APPROVAL）");
        let appNo: string | null = null;
        if (action === "approve") {
          appNo = `APP${9001 + apps.all().length}`;
          await apps.insert({ appNo, name: a.appName, purpose: a.purpose, ownerKind: "MERCHANT", merchantNo: a.merchantNo, environment: a.environment === "SANDBOX" ? "SANDBOX" : "LIVE", scopes: a.scopes, notifyUrl: a.notifyUrl, callbackDomains: a.callbackDomains, callbackStatus: "UNVERIFIED", ipWhitelist: a.ipWhitelist, rateLimitQps: a.rateLimitQps, dailyRefundLimitMinor: a.dailyRefundLimitMinor, credentialStatus: "NONE", grantCount: 0, status: "DRAFT", lastAccessAt: null, lastAccessOutcome: null, version: 1, grants: [], signType: a.signType });
        }
        const status = { approve: "APPROVED", reject: "REJECTED", withdraw: "WITHDRAWN" }[action];
        const r = await applications.update(no, { status, appNo, reviewedBy: "admin", reviewedAt: Date.now(), reviewReason: c.reason.trim() }, c.expectedVersion);
        return ok(no, status, r.version);
      }),
    },
    platformKeys: {
      list: () => run(() => creds.all().filter((r) => r.ownerType === "PLATFORM_OPEN").flatMap((r) => r.versions
        .filter((v) => ["VERIFIED", "ACTIVE", "ROTATING"].includes(v.status) && v.externalKeyId)
        .map((v): PlatformKeyRow => ({ usage: usageOf(r.credentialType), keyId: r.keyId, versionIndex: v.versionIndex, serial: v.externalKeyId!, status: v.status, fingerprint: v.fingerprint, publicKeyPem: publicPem.get(`${r.keyId}:${v.versionIndex}`) ?? PEM(v.externalKeyId!), enabledAt: v.enabledAt, overlapUntil: v.overlapUntil })))
        // 与 near-pay 同序：按类型（签名在前）、再按凭证与版本
        .sort((a, b) => a.usage.localeCompare(b.usage) * -1 || a.keyId - b.keyId || a.versionIndex - b.versionIndex)),
      generate: (usage, reason) => run(async () => {
        reasonOf(reason); need(usage === "SIGN" || usage === "ENCRYPT", "usage 为 SIGN / ENCRYPT");
        const serial = `${usage === "SIGN" ? "P" : "Q"}${new Date().toISOString().replace(/\D/g, "").slice(0, 14)}01`;
        const keyId = 4200 + creds.all().length + 10;
        const v = { ...ver(1, "DRAFT", "admin"), externalKeyId: serial, source: "GENERATED", displayPrefix: serial };
        await creds.insert(platformKey(keyId, usage, v)); publicPem.set(`${keyId}:1`, PEM(serial));
        return { usage, keyId, version: v };
      }),
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
      metrics: () => run(() => [
        { metric: "CHANNEL_SUCCESS_RATE", title: "渠道成功率", unit: "PERCENT", scopeTypes: ["INSTANCE", "CHANNEL"], defaultWindowSeconds: 300, eventDriven: false },
        { metric: "CHANNEL_LATENCY", title: "渠道耗时", unit: "MILLISECOND", scopeTypes: ["INSTANCE", "CHANNEL"], defaultWindowSeconds: 300, eventDriven: false },
        { metric: "CALLBACK_FAILURE", title: "回调失败", unit: "COUNT", scopeTypes: ["INSTANCE", "CHANNEL"], defaultWindowSeconds: 300, eventDriven: false },
        { metric: "NOTIFY_FAILURE", title: "通知失败", unit: "COUNT", scopeTypes: ["INSTANCE"], defaultWindowSeconds: 900, eventDriven: false },
        { metric: "REVERSAL_PENDING", title: "冲正待确认", unit: "MINUTE", scopeTypes: ["INSTANCE", "CHANNEL"], defaultWindowSeconds: 1800, eventDriven: true },
      ]),
      recheckReversal: (no) => run(() => { const ev = alertEvents.get(no); need(ev.metric === "REVERSAL_PENDING" && ["OPEN", "CLAIMED"].includes(ev.status), "只有未关闭的冲正待确认告警可回查"); return "PENDING" as const; }),
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
    credentials: {
      types: () => run(() => [
        { credentialType: "NEWEBPAY_MERCHANT_HASH", ownerType: "PAY_MERCHANT", counterparty: "NEWEBPAY", purpose: "MERCHANT_HASH", algorithm: "AES-256", materialType: "COMPOSITE" as const, materialFields: ["hashKey", "hashIv"], generated: false },
        { credentialType: "APP_SECRET", ownerType: "PARTNER_APP", counterparty: null, purpose: "APP_SIGN", algorithm: "HMAC-SHA256", materialType: "SYMMETRIC_SECRET" as const, materialFields: ["secret"], generated: true },
        { credentialType: "JKOPAY_PLATFORM_VERIFY", ownerType: "CHANNEL_PLATFORM", counterparty: "JKOPAY", purpose: "PLATFORM_VERIFY", algorithm: "RSA", materialType: "PUBLIC_KEY" as const, materialFields: ["publicMaterial"], generated: false },
      ]),
      policies: () => run(() => credPolicies),
      savePolicy: (owner, b) => run(() => { reasonOf(b.reason); const i = credPolicies.findIndex((p) => p.ownerType === owner); need(i >= 0, "没有这个归属类型"); if (credPolicies[i].version !== b.expectedVersion) throw new MockPayError("VERSION_CONFLICT", "策略已被他人修改，请刷新后重试"); credPolicies[i] = { ...credPolicies[i], ...b, dualControl: true, revealOnIssueOnly: true, version: credPolicies[i].version + 1 }; return credPolicies[i]; }),
      list: (q) => pageOf(creds.all().map(({ versions: _v, version: _x, ...r }) => r), q, ["name", "ownerRef", "credentialType"]),
      detail: (id) => run(() => { const r = creds.get(String(id)); const { versions, version: _x, ...rec } = r; return { record: rec, versions, policy: credPolicies.find((p) => p.ownerType === r.ownerType)!, events: [], allowedActions: credActions(r) }; }),
      accessLogs: (id) => run(() => { creds.get(String(id)); return [{ accessType: "SIGN", accessorNo: "pay-svc", accessorRole: "SERVICE", reason: "收款签名", approvalRef: null, accessedAt: T0 - H }]; }),
      register: (b) => run(async () => { reasonOf(b.reason); need(!b.credentialType.startsWith("TERMINAL"), "终端密钥只能由激活流程登记"); const row = { keyId: 4100 + creds.all().length + 1, credentialType: b.credentialType, ownerType: "PAY_MERCHANT", ownerRef: b.ownerRef, name: b.credentialType, counterparty: null, purpose: b.credentialType, algorithm: "—", materialType: "COMPOSITE", env: "SANDBOX", status: "ACTIVE", currentVersion: 0, currentFingerprint: null, expiresAt: null, expiryState: "NONE", pendingVersions: 0, lastUsedAt: null, versions: [], version: 1 }; await creds.insert(row); const { versions: _v, version: _x, ...r } = row; return r; }),
      addVersion: (id, b) => run(async () => { reasonOf(b.reason); const r = creds.get(String(id)); need(!!(b.secret || b.hashKey || b.publicMaterial), "请填写密钥材料"); const v = ver(Math.max(0, ...r.versions.map((x) => x.versionIndex)) + 1, "DRAFT", "admin"); await creds.update(String(id), { versions: [v, ...r.versions], pendingVersions: r.pendingVersions + 1 }); return v; }),
      generate: (id, reason) => run(async () => { reasonOf(reason); const r = creds.get(String(id)); const v = ver(Math.max(0, ...r.versions.map((x) => x.versionIndex)) + 1, "DRAFT", "admin"); await creds.update(String(id), { versions: [v, ...r.versions], pendingVersions: r.pendingVersions + 1 }); return { keyId: id, versionIndex: v.versionIndex, serial: `ext-${v.versionIndex}`, secret: `sk_test_mock_${Date.now()}`, fingerprint: v.fingerprint, status: "DRAFT" }; }),
      verify: (id, vi, b) => run(async () => { reasonOf(b.reason); const r = creds.get(String(id)); const v = r.versions.find((x) => x.versionIndex === vi); need(v?.status === "DRAFT", "只有草稿版本可核验"); const next = { ...v!, status: "VERIFIED", verifiedAt: Date.now(), verifyEvidence: b.evidenceRef ? `MANUAL:${b.evidenceRef}` : null }; await creds.update(String(id), { versions: r.versions.map((x) => (x.versionIndex === vi ? next : x)) }); return next; }),
      enable: (id, vi, b) => run(async () => { reasonOf(b.reason); return enableCredVersion(id, vi, b.approvalRef); }),
      revoke: (id, vi, reason) => run(async () => { reasonOf(reason); const r = creds.get(String(id)); const v = r.versions.find((x) => x.versionIndex === vi); need(!!v && v.status !== "REVOKED", "版本已撤销"); const next = { ...v!, status: "REVOKED", revokedBy: "admin", revokedAt: Date.now(), revokeReason: reason }; await creds.update(String(id), { versions: r.versions.map((x) => (x.versionIndex === vi ? next : x)) }); return next; }),
      bindings: () => run(() => [{ keyId: 4101, source: "SEC" as const, consumerType: "CHANNEL_ACCOUNT", consumerRef: "NWP7000", consumerName: "蓝新商户 7000", field: "pay_merchant.sec_key_id", channel: "NEWEBPAY", status: "ACTIVE", environment: "PRODUCTION", transactions: 128, lastFrozenAt: T0 - H }]),
    },
    trials: {
      preparation: (no, ch) => run(() => { const a = accounts.get(no); return { payMerchantNo: no, accountVersion: a.version, products: ch === "newebpay" ? ["NEWEBPAY_TEST_CARD_ONETIME"] : ["LINEPAY_TEST_ONLINE"], enablementEvidence: `approval:AP-${no}` }; }),
      create: (no, ch, b) => run(() => {
        reasonOf(b.reason); const a = accounts.get(no); if (a.version !== b.expectedVersion) throw new MockPayError("VERSION_CONFLICT", "渠道账户已变更，请刷新后重试");
        need(/^[A-Z0-9_-]{1,32}$/.test(b.evidenceNo), "证据号格式不对"); need(["H5", "API"].includes(b.entry), "入口只能是 H5 或 API");
        const v: QualificationVersion = { ref: `${b.evidenceNo}:${b.revision}`, revision: b.revision, channel: ch === "newebpay" ? "NEWEBPAY" : "LINEPAY", purpose: "SANDBOX_TRIAL", coordinates: { payMerchantNo: no, product: b.productCode, entry: b.entry }, coordinateKey: null, payloadJson: JSON.stringify({ returnUrl: b.returnUrl, notifyUrl: b.notifyUrl }), payloadSha256: "mock", status: "DRAFT", maker: "admin", approvalNo: null, validUntil: b.validUntil, version: 1 };
        qualifications.push(v); return v;
      }),
    },
    testSessions: {
      sandbox: (no, b) => run(() => { need(b.expiresAt > Date.now() && b.expiresAt <= Date.now() + 30 * 60_000, "测试单有效期须在 30 分钟内"); return { payOrderNo: `SBH${no.slice(-6)}${String(b.expiresAt).slice(-4)}`, checkoutUrl: `https://pay.example/cashier/SBH-${no}`, expireAt: b.expiresAt }; }),
      payby: (no, b) => run(() => { need(b.expiresAt > Date.now() && b.expiresAt <= Date.now() + 30 * 60_000, "测试单有效期须在 30 分钟内"); return { payOrderNo: `PBA${no.slice(-6)}${String(b.expiresAt).slice(-4)}`, checkoutUrl: `https://pay.example/cashier/PBA-${no}`, expireAt: b.expiresAt }; }),
    },
    pos: {
      list: (t) => run(() => posConfigs.filter((p) => p.terminalNo === t).slice(0, 30)),
      detail: (t, id) => run(() => posOf(t, id)),
      save: (t, b) => run(() => {
        reasonOf(b.reason); const term = terminals.get(t); need(term.payChannel === "PAYBY", "需要 PayBy · 阿联酋终端（PAYBY_AE_AED_TERMINAL_REQUIRED）");
        need(b.settings.allowedModes.includes(b.settings.defaultMode), "默认模式必须在允许模式里");
        need(!b.binding.deviceKeyRef || /^(vault|secret):/.test(b.binding.deviceKeyRef), "凭据只收 vault: / secret: 引用");
        const binding = { bindingId: 1, bindingVersion: 1, status: "PENDING", physicalSn: term.serialNo, deviceId: b.binding.deviceId, channelMerchantNo: b.binding.channelMerchantNo, channelStoreNo: b.binding.channelStoreNo, channelTerminalNo: b.binding.channelTerminalNo, issuerCode: b.binding.issuerCode, issuerType: b.binding.issuerType, payeeMemberId: b.binding.payeeMemberId, deviceKeyVersion: b.binding.deviceKeyVersion, queryKeyVersion: b.binding.queryKeyVersion, deviceCredentialLinked: !!b.binding.deviceKeyRef, queryCredentialLinked: !!b.binding.queryCredentialRef, activationRef: b.binding.activationRef, authorizationRef: b.binding.authorizationRef };
        if (b.configurationId == null) {
          need(b.expectedVersion === 0, "新草稿的版本必须是 0（POS_DRAFT_VERSION_CONFLICT）"); need(!posConfigs.some((p) => p.terminalNo === t && ["DRAFT", "VERIFIED"].includes(p.state)), "已有未发布的草稿，请修改它（EDIT_EXISTING_POS_DRAFT）");
          const x: PosConfigView = { id: 9000 + posConfigs.length + 1, terminalNo: t, state: "DRAFT", version: 1, baseTerminalVersion: b.expectedTerminalVersion, targetTerminalVersion: b.expectedTerminalVersion + 1, settings: b.settings, binding, configuredBy: "admin", verifiedBy: null, reviewEvidenceRef: null, stale: false, canPublish: false, modes: Object.fromEntries(b.settings.allowedModes.map((md) => [md, { mode: md, ready: true, checks: { contract: true, credential: !!b.binding.deviceKeyRef }, reasons: [] }])), runtime: { ready: false, canReceivePayments: false, reasons: ["POS_NOT_PUBLISHED"] }, audit: [] };
          posConfigs.unshift(x); return x;
        }
        const x = posOf(t, b.configurationId); need(["DRAFT", "VERIFIED"].includes(x.state), "只有草稿或已核验的配置可修改（POS_DRAFT_NOT_EDITABLE）");
        return posBump(x, { state: "DRAFT", verifiedBy: null, settings: b.settings, binding: { ...binding, deviceCredentialLinked: binding.deviceCredentialLinked || !!x.binding?.deviceCredentialLinked, queryCredentialLinked: binding.queryCredentialLinked || !!x.binding?.queryCredentialLinked }, canPublish: false }, b.expectedVersion);
      }),
      verify: (t, id, b) => run(() => { reasonOf(b.reason); const x = posOf(t, id); need(x.state === "DRAFT", "只有草稿可核验"); need(x.configuredBy !== "admin", "制单人不能核验自己的草稿（INDEPENDENT_CURRENT_MAKER_REVIEW_REQUIRED）"); const r = posBump(x, { state: "VERIFIED", verifiedBy: "admin", reviewEvidenceRef: b.evidenceRef }, b.expectedVersion); r.canPublish = posReady(r); return r; }),
      publish: (t, id, c) => run(() => { cmdCheck(c); const x = posOf(t, id); need(x.canPublish, "默认模式或配置未就绪（POS_DEFAULT_MODE_OR_CONFIGURATION_NOT_READY）"); posConfigs.filter((p) => p.terminalNo === t && p.state === "PUBLISHED").forEach((p) => { p.state = "SUPERSEDED"; }); return posBump(x, { state: "PUBLISHED", canPublish: false, runtime: { ready: true, canReceivePayments: true, reasons: [] } }, c.expectedVersion); }),
      disable: (t, id, c) => run(() => { cmdCheck(c); const x = posOf(t, id); need(x.state === "PUBLISHED", "只有已发布的配置可停用（PUBLISHED_POS_REVISION_REQUIRED）"); return posBump(x, { state: "DISABLED", runtime: { ready: false, canReceivePayments: false, reasons: ["POS_DISABLED"] } }, c.expectedVersion); }),
    },
    posUat: {
      runs: (t, id) => run(() => uatRuns.filter((r) => r.identity.terminalNo === t && r.identity.configurationId === id)),
      start: (t, id, b) => run(() => { reasonOf(b.reason); const x = posOf(t, id); need(x.state === "PUBLISHED" && !x.stale && x.settings.environment === "SANDBOX" && b.mode === x.settings.defaultMode, "需要当前已发布的沙箱默认模式配置（CURRENT_PUBLISHED_SANDBOX_DEFAULT_MODE_REQUIRED）"); if (x.version !== b.expectedVersion) throw new MockPayError("VERSION_CONFLICT", "配置已变更"); const r: UatRun = { runNo: `POS-UAT-${uatRuns.length + 1}`, version: 1, state: "COLLECTING", mode: b.mode, createdBy: "admin", reviewedBy: null, reviewEvidenceRef: null, stale: false, identity: { terminalNo: t, configurationId: id, mode: b.mode }, identityHash: "mock", cases: [], blockers: ["UAT_EVIDENCE_VERIFIER_UNAVAILABLE"], reviewerEligible: false }; uatRuns.unshift(r); return r; }),
      run: (_t, rn) => run(() => { const r = uatRuns.find((x) => x.runNo === rn); if (!r) throw new MockPayError("NOT_FOUND", "没有这个批次"); return r; }),
      recordCase: (_t, rn, b) => run(() => { reasonOf(b.reason); const r = uatRuns.find((x) => x.runNo === rn)!; need(r.state === "COLLECTING", "已签核的批次不能再记录（ACCEPTED_UAT_IMMUTABLE）"); need(/^file:/.test(b.evidenceRef), "证据须是 file: 引用"); if (r.version !== b.expectedVersion) throw new MockPayError("VERSION_CONFLICT", "批次已变更"); r.cases = [{ caseCode: b.caseCode, result: b.result, evidenceRef: b.evidenceRef, executionNo: b.executionNo, recordedBy: "admin", recordedAt: new Date().toISOString() }, ...r.cases.filter((c) => c.caseCode !== b.caseCode)]; r.version += 1; return r; }),
      review: (_t, rn, b) => run(() => { reasonOf(b.reason); const r = uatRuns.find((x) => x.runNo === rn)!; need(r.reviewerEligible, "签核人不能是批次创建人或用例记录人（INDEPENDENT_UAT_REVIEWER_REQUIRED）"); need(r.blockers.length === 0, "还有阻塞项（INDEPENDENT_COMPLETE_UAT_EVIDENCE_REQUIRED）"); r.state = "ACCEPTED"; r.version += 1; return r; }),
      manifest: (_t, rn) => run(() => { const r = uatRuns.find((x) => x.runNo === rn)!; return { schemaVersion: 1, kind: "PAYBY_POS_UAT_EVIDENCE", runNo: r.runNo, state: r.state, identity: r.identity, identityHash: r.identityHash, requiredCases: Array.from({ length: 20 }, (_, i) => `D${String(i + 1).padStart(2, "0")}`), cases: r.cases }; }),
    },
    softpos: {
      register: (t, b) => run(() => { reasonOf(b.reason); const term = terminals.get(t); need(/^[a-f0-9]{64}$/.test(b.signatureSha256), "签名须是 64 位小写十六进制"); const x: SoftPosBinding = { id: 7700 + softposBindings.length + 1, terminal: t, store: term.storeNo, accountId: 1, physicalDevice: term.serialNo, epoch: "1", providerMid: term.payMerchantNo ?? "", providerTid: term.channelTerminalNo ?? "", packageName: b.packageName, signatureSha256: b.signatureSha256, appVersion: b.appVersion, sdkVersion: b.sdkVersion, configVersion: term.version, status: "DRAFT", validUntil: b.validUntil }; softposBindings.unshift(x); return x; }),
      detail: (t, id) => run(() => { const x = softposBindings.find((b) => b.terminal === t && b.id === id); if (!x) throw new MockPayError("NOT_FOUND", "没有这个绑定"); return x; }),
      revoke: (t, id, reason) => run(() => { reasonOf(reason); const x = softposBindings.find((b) => b.terminal === t && b.id === id); need(!!x, "没有这个绑定"); x!.status = "REVOKED"; return x!; }),
    },
    audits: {
      list: (q) => pageOf(audits, q, ["auditNo", "resourceNo", "operatorNo"]),
      detail: (no) => run(() => { const a = audits.find((x) => x.auditNo === no); if (!a) throw new MockPayError("NOT_FOUND", "没有这条审计"); return wrap(a, 0, []); }),
    },
    merchantMembers: {
      list: (q) => pageOf(members.all().map(({ version: _v, ...r }) => r), q, ["membershipNo", "merchantNo", "subjectRef"]),
      detail: (no) => run(() => { const x = members.get(no); const { version, ...r } = x; return wrap<MerchantMemberRow>(r, version, memberActions(x)); }),
      create: (b) => run(async () => {
        reasonOf(b.reason); need(["MCH_ACCOUNT", "USER"].includes(b.subjectType), "主体类型只能是 MCH_ACCOUNT 或 USER");
        need(!members.all().some((x) => x.merchantNo === b.merchantNo && x.role === "MERCHANT_ADMIN" && x.status === "ACTIVE"), "商户已有生效管理员");
        const row: MemberRec = { membershipNo: `MM${9100 + members.all().length}`, merchantNo: b.merchantNo, sourceSystem: "NEAR_PAY", subjectType: b.subjectType, subjectRef: b.subjectRef, role: "MERCHANT_ADMIN", storeScope: "ALL", storeNos: [], actions: ["payment.create", "refund.create"], status: "ACTIVE", validUntil: null, grantedBy: "admin", revokeReason: null, version: 1 };
        await members.insert(row); const { version: _v, ...r } = row; return wrap(r, 1, memberActions(row));
      }),
      command: (no, action, c) => run(async () => {
        cmdCheck(c); const x = members.get(no);
        const next = action === "suspend" ? (need(x.status === "ACTIVE", "只有生效中的成员可暂停"), "SUSPENDED") : action === "resume" ? (need(x.status === "SUSPENDED", "只有已暂停的成员可恢复"), "ACTIVE") : (need(x.status !== "REVOKED", "成员已撤销"), "REVOKED");
        if (x.role === "MERCHANT_ADMIN" && x.status === "ACTIVE" && action !== "resume") need(members.all().some((m) => m.merchantNo === x.merchantNo && m.membershipNo !== no && m.role === "MERCHANT_ADMIN" && m.status === "ACTIVE"), "至少保留一位生效管理员");
        const r = await members.update(no, { status: next, revokeReason: action === "revoke" ? c.reason : x.revokeReason }, c.expectedVersion); return ok(no, next, r.version);
      }),
    },
    repairs: {
      list: (q) => pageOf(repairs.all(), q, ["repairNo", "merchantNo", "requestNo"]),
      detail: (no) => run(() => { const x = repairs.get(no); return wrap(x, x.version, repairActions(x)); }),
      create: (b) => run(async () => {
        need(!!b.evidence?.trim(), "请填写依据"); need(b.fromOperatingEntityId !== b.toOperatingEntityId, "原主体与目标主体不能相同"); need(b.storeNos.length <= 100 && new Set(b.storeNos).size === b.storeNos.length, "门店不能重复且最多 100 个");
        need(!repairs.all().some((r) => r.merchantNo === b.merchantNo && ["PENDING_APPROVAL", "APPROVED"].includes(r.status)), "这条关联已有进行中的修复");
        const row: RepairRow = { repairNo: `SR${7100 + repairs.all().length}`, requestNo: `ops-${Date.now()}`, merchantNo: b.merchantNo, linkNo: `LK-${b.merchantNo}`, sourceSystem: "AI_SHOP", sourceScope: "MAIN", fromOperatingEntityId: b.fromOperatingEntityId, toOperatingEntityId: b.toOperatingEntityId, storeNos: b.storeNos, evidence: b.evidence, expectedVersion: b.expectedVersion, status: "PENDING_APPROVAL", approvalNo: `AP${8100 + repairs.all().length}`, approvalStatus: "PENDING_FIRST", approvalVersion: 1, requestedBy: "admin", executedBy: null, executedAt: null, beforeSnapshot: null, afterSnapshot: null, createdAt: Date.now(), version: 1 };
        await repairs.insert(row); return wrap(row, 1, repairActions(row));
      }),
      decide: (no, action, c) => run(async () => {
        cmdCheck(c); const x = repairs.get(no);
        if (action === "execute") { need(x.status === "APPROVED", "只有已批准的修复可执行"); const r = await repairs.update(no, { status: "EXECUTED", executedBy: "admin", executedAt: Date.now() }, c.expectedVersion); return ok(no, "EXECUTED", r.version, x.approvalNo); }
        need(x.status === "PENDING_APPROVAL", "只有待审批的修复可审批");
        if (x.approvalVersion !== c.expectedVersion) throw new MockPayError("VERSION_CONFLICT", "审批已被他人处理，请刷新后重试");
        const status = action === "approve" ? "APPROVED" : "REJECTED";
        const r = await repairs.update(no, { status, approvalStatus: status, approvalVersion: (x.approvalVersion ?? 0) + 1 }); return ok(no, status, r.version, x.approvalNo);
      }),
    },
    approvalFlows: {
      list: (q) => pageOf(flows, q, ["flowNo", "name"]),
      detail: (no) => run(() => { const f = flows.find((x) => x.flowNo === no); if (!f) throw new MockPayError("NOT_FOUND", "流程不存在"); return { record: f, revisions: [f] }; }),
    },
    settings: {
      get: () => run(() => settings),
      save: (b) => run(() => { reasonOf(b.reason); if (b.expectedVersion !== settings.version) throw new MockPayError("VERSION_CONFLICT", "记录已被他人修改，请刷新后重试"); settings = { ...settings, defaultChannelCode: b.defaultChannelCode, channelOrder: b.channelOrder, version: settings.version + 1, updatedAt: Date.now(), updatedBy: "admin" }; return settings; }),
    },
    attempts: {
      list: (q) => pageOf(attempts, q, ["outTradeNo", "paymentNo", "payOrderNo"]),
      detail: (no) => run(() => { const a = attempts.find((x) => x.outTradeNo === no); if (!a) throw new MockPayError("NOT_FOUND", "流水不存在"); return wrap(a, a.version, a.payOrderNo && ["INIT", "PENDING"].includes(a.status) ? ["refresh"] : []); }),
      refresh: (no, c) => run(() => { cmdCheck(c); const a = attempts.find((x) => x.outTradeNo === no); if (!a) throw new MockPayError("NOT_FOUND", "流水不存在"); need(["INIT", "PENDING"].includes(a.status), "终态流水不需要查渠道结果"); return { outTradeNo: no, payOrderNo: a.payOrderNo, orderStatus: "AWAITING_CONFIRM", accepted: true }; }),
    },
    ledger: {
      list: (q) => pageOf(ledger, q, ["voucherNo", "payOrderNo", "account"]),
      detail: (no) => run(() => { const entry = ledger.find((l) => l.entryNo === no) ?? null; const vno = entry?.voucherNo ?? no; const lines = ledger.filter((l) => l.voucherNo === vno); if (!lines.length) throw new MockPayError("NOT_FOUND", "凭证不存在"); return wrap({ entry, voucher: { voucherNo: vno, voucherType: lines[0].voucherType, refType: "PAY_ORDER", sourceType: "PAYMENT", market: "TW", currency: "TWD", totalMinor: lines.filter((l) => l.direction === "D").reduce((n, l) => n + l.amountMinor, 0), bizDate: lines[0].bizDate, fundsPath: lines[0].fundsPath, operatorNo: null, memo: null, reversalOfVoucherNo: null, reversedByVoucherNos: [], createdAt: lines[0].createdAt, lines } }, 0); }),
    },
    settlements: {
      list: (q) => pageOf(settlements, q, ["settlementNo", "merchantNo"]),
      detail: (no) => run(() => { const st = settlements.find((x) => x.settlementNo === no); if (!st) throw new MockPayError("NOT_FOUND", "结算批次不存在"); return wrap({ settlement: st, payouts: payouts.filter((p) => p.batchNo === no) }, st.version); }),
    },
    withdrawals: {
      list: (q) => pageOf(withdrawals, q, ["withdrawalNo", "merchantNo", "merchantName"]),
      detail: (no) => run(() => { const w = withdrawals.find((x) => x.withdrawalNo === no); if (!w) throw new MockPayError("NOT_FOUND", "提现申请不存在"); return wrap(w, w.version, w.status === "PENDING" ? ["review", "cancel"] : w.status === "FAILED" ? ["review"] : []); }),
      review: (no, b) => run(() => { cmdCheck(b); const w = withdrawals.find((x) => x.withdrawalNo === no)!; need(["PENDING", "FAILED"].includes(w.status), "当前状态不能审批"); if (w.version !== b.expectedVersion) throw new MockPayError("VERSION_CONFLICT", "记录已被他人修改，请刷新后重试"); if (b.decision === "APPROVE" && w.amountMinor > w.availableBalanceMinor) throw new MockPayError("WITHDRAW_OVER_BALANCE", "提现金额超过可提余额"); const st = b.decision === "APPROVE" ? "APPROVED" : "REJECTED"; withdrawals = withdrawals.map((x) => (x.withdrawalNo === no ? { ...x, status: st, decidedAt: Date.now(), decidedBy: "admin", remark: b.reason, version: x.version + 1 } : x)); return ok(no, st, w.version + 1); }),
      cancel: (no, c) => run(() => { cmdCheck(c); const w = withdrawals.find((x) => x.withdrawalNo === no)!; need(w.status === "PENDING", "只能撤销待审批的申请"); withdrawals = withdrawals.map((x) => (x.withdrawalNo === no ? { ...x, status: "CANCELLED", remark: c.reason, version: x.version + 1 } : x)); return ok(no, "CANCELLED", w.version + 1); }),
    },
    payouts: {
      list: (q) => pageOf(payouts, q, ["payoutNo", "batchNo", "merchantNo"]),
      detail: (no) => run(() => { const p = payouts.find((x) => x.payoutNo === no); if (!p) throw new MockPayError("NOT_FOUND", "出款记录不存在"); return wrap({ payout: p, settleNos: [`ST-${p.batchNo}-1`], settleDocumentNos: [`SD-${p.batchNo}`] }, p.version, ["PENDING", "EXPORTED"].includes(p.status) ? ["receipt"] : []); }),
      receipt: (no, b) => run(() => { cmdCheck(b); const p = payouts.find((x) => x.payoutNo === no)!; need(["PENDING", "EXPORTED"].includes(p.status), "当前状态不能登记回执"); if (b.result === "PAID") need(!!b.paymentRef, "已付须填付款凭证号"); const st = b.result; payouts = payouts.map((x) => (x.payoutNo === no ? { ...x, status: st, paymentRef: b.paymentRef, paidAt: st === "PAID" ? Date.now() : null, paidBy: st === "PAID" ? "admin" : null, failReason: st === "FAILED" ? b.reason : null, version: x.version + 1 } : x)); return ok(no, st, p.version + 1); }),
    },
    reconDiffs: {
      list: (q) => pageOf(reconDiffs.map(({ claimedBy: _c, ...r }) => r), q, ["differenceNo", "paymentNo", "orderNo"]),
      coverage: () => run(() => ({ channelBillConnected: false, note: "渠道账单尚未接入：当前只有平台自查，零差异不代表与渠道账平。" })),
      detail: (no) => run(() => { const d = reconDiffs.find((x) => x.differenceNo === no); if (!d) throw new MockPayError("NOT_FOUND", "差异不存在"); const { claimedBy, ...difference } = d; const acts = d.status !== "PENDING" ? [] : !claimedBy ? ["claim", "close"] : claimedBy === "admin" ? ["close"] : []; return wrap({ difference, claimedBy, claimedAt: claimedBy ? T0 : null }, d.version, acts); }),
      claim: (no, c) => run(() => { cmdCheck(c); const d = reconDiffs.find((x) => x.differenceNo === no)!; need(d.status === "PENDING", "差异已关闭"); need(!d.claimedBy || d.claimedBy === "admin", `已被 ${d.claimedBy} 认领`); reconDiffs = reconDiffs.map((x) => (x.differenceNo === no ? { ...x, claimedBy: "admin" } : x)); return ok(no, "PENDING", d.version); }),
      close: (no, b) => run(() => { cmdCheck(b); const d = reconDiffs.find((x) => x.differenceNo === no)!; need(d.status === "PENDING", "差异已关闭"); need(!d.claimedBy || d.claimedBy === "admin", "被他人认领的差异只有认领人能关闭"); reconDiffs = reconDiffs.map((x) => (x.differenceNo === no ? { ...x, status: b.outcome, resolution: b.reason, resolvedAt: Date.now(), resolvedBy: "admin", version: x.version + 1 } : x)); return ok(no, b.outcome, d.version + 1); }),
    },
    storedValue: {
      accounts: (q) => pageOf(svAccounts, q, ["accountNo", "customerNo"]),
      account: (no) => run(() => { const a = svAccounts.find((x) => x.accountNo === no); if (!a) throw new MockPayError("NOT_FOUND", "账户不存在"); return wrap({ account: a, recentTransactions: svTx.filter((t) => t.accountNo === no).slice(0, 20) }, a.version); }),
      transactions: (q) => pageOf(svTx, q, ["transactionNo", "accountNo", "bizNo"]),
      transaction: (no) => run(() => { const t = svTx.find((x) => x.transactionNo === no); if (!t) throw new MockPayError("NOT_FOUND", "没有这笔储值交易"); return wrap(t, 0, []); }),
    },
    channelCosts: {
      list: (q) => pageOf(costStatus(costs).sort((a, b) => b.effectiveFrom - a.effectiveFrom), q, ["payChannel", "payMethod"]),
      detail: (no) => run(() => { const r = costStatus(costs).find((x) => x.costNo === no); if (!r) throw new MockPayError("NOT_FOUND", "版本不存在"); return wrap(r, r.version ?? 1, r.status === "DISABLED" ? [] : ["revise"]); }),
      versions: (no) => run(() => { const r = costs.find((x) => x.costNo === no)!; return costStatus(costs).filter((x) => x.payChannel === r.payChannel && x.payMethod === r.payMethod && x.legalForm === r.legalForm && x.market === r.market).sort((a, b) => b.effectiveFrom - a.effectiveFrom); }),
      create: (b) => run(() => { reasonOf(b.reason); need(b.rateBps >= 0 && b.rateBps <= 10000, "费率须在 0–10000 万分比之间"); need(b.effectiveFrom >= Date.now() - 60_000, "生效时间不能早于现在"); const row: ChannelCostRow = { costNo: `CR${9001 + costs.length}`, payChannel: b.payChannel, payMethod: b.payMethod || "*", legalForm: b.legalForm || "*", market: b.market || "*", rateBps: b.rateBps, minFeeMinor: b.minFeeMinor ?? 0, effectiveFrom: b.effectiveFrom, status: "SCHEDULED", remark: b.reason, createdAt: Date.now(), createdBy: "admin", version: 1 }; costs = [...costs, row]; const r = costStatus(costs).find((x) => x.costNo === row.costNo)!; return wrap(r, 1, ["revise"]); }),
      revise: (no, b) => run(() => { reasonOf(b.reason); const base = costs.find((x) => x.costNo === no); if (!base) throw new MockPayError("NOT_FOUND", "版本不存在"); if ((base.version ?? 1) !== b.expectedVersion) throw new MockPayError("VERSION_CONFLICT", "记录已被他人修改，请刷新后重试"); need(b.effectiveFrom >= Date.now() - 60_000, "生效时间不能早于现在"); const row: ChannelCostRow = { ...base, costNo: `CR${9001 + costs.length}`, rateBps: b.rateBps, minFeeMinor: b.minFeeMinor ?? base.minFeeMinor, effectiveFrom: b.effectiveFrom, remark: b.reason, createdAt: Date.now(), createdBy: "admin", version: 1 }; costs = [...costs, row]; const r = costStatus(costs).find((x) => x.costNo === row.costNo)!; return wrap(r, 1, ["revise"]); }),
    },
    commissionRules: {
      list: (q) => pageOf(commissions, q, ["businessMode", "trafficSource"]),
      detail: (no) => run(() => { const r = commissions.find((x) => x.ruleNo === no); if (!r) throw new MockPayError("NOT_FOUND", "规则不存在"); return wrap(r, 0); }),
      create: (b) => run(() => { reasonOf(b.reason); need(b.rateBps >= 0 && b.rateBps <= 10000, "费率须在 0–10000 万分比之间"); const row: CommissionRuleRow = { ruleNo: `FR${7001 + commissions.length}`, businessMode: b.businessMode, trafficSource: b.trafficSource, rateBps: b.rateBps, effectiveFrom: b.effectiveFrom, status: b.effectiveFrom > Date.now() ? "SCHEDULED" : "ACTIVE", remark: b.reason, createdAt: Date.now(), createdBy: "admin" }; commissions = [...commissions, row]; return wrap(row, 0); }),
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
