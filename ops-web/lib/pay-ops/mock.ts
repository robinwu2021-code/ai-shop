// 支付中心 mock：内存数据 + 与 near-pay 同口径的状态机与乐观锁（版本不对 409、状态不允许 409）。
// 只在 NEXT_PUBLIC_USE_MOCK≠0 时使用；数据台湾主场景（TWD、台北门店），见记忆「TW 主场景」。
import { MockPayError, VersionedTable, mockDelay, pageOf } from "./mock-store";
import type { Cmd, PayCenterApi } from "./api";
import type {
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

  const cmdCheck = (c: Cmd) => reasonOf(c.reason);
  const setStatus = async <R extends { version: number; status: string }>(t: VersionedTable<R>, id: string, c: Cmd, from: string[], to: string) => {
    cmdCheck(c);
    need(from.includes(t.get(id).status), "当前状态不允许此操作");
    const r = await t.update(id, { status: to } as Partial<R>, c.expectedVersion);
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
