// 套餐商城 mock 数据集 + 状态机。
//
// **规则全在这里强制**，页面写不出违规操作：乐观锁、已发布只读、核款收款主体、银行流水唯一、
// 作废必须逐笔处置 MALL_UNMATCHED、出款金额必须等于退款意图……
// 报错与真实后端同构：`ApiError(10000+HTTP状态, 机器原因码)`，页面按原因码翻译。
//
// 数据是**开发测试样例**：价格、银行、收款主体都带「DEV」字样或是文档里的示例值，
// 不是正式售价，也不是真实账户。三家台湾线上通道（NewebPay / LINE Pay / JKOPay）
// 在这里**根本没有**状态 —— 它们未接通，界面写死「未接通」，不从数据里读一个可以被改成 true 的布尔。
import { ApiError } from "@/lib/api/error";
import type {
  MallBusinessEndDraft, MallBusinessTypeDraft, MallDeploymentOption, MallEntitlementSource, MallAccessSegment,
  MallMoney, MallOfflineClaimView, MallOfflineCollectionTerms, MallOfflineInstructionsView, MallPaymentAttempt,
  MallPlanDraft, MallPlanTier, MallProductDraft, MallQuoteComponent, MallQuoteView, MallRefundView,
  MallShipment, MallShippingAddress, MallSkuDraft, MallOrderStatus, MallComponentKind,
} from "@/lib/types";

// ── 错误 ────────────────────────────────────────────────────────────────

const STATUS: Record<string, number> = {
  NOT_FOUND: 404, FORBIDDEN: 403, VERSION_CONFLICT: 409, IMMUTABLE_VERSION: 409, IDEMPOTENCY_CONFLICT: 409,
  QUOTE_EXPIRED: 409, QUOTE_USED: 409, CLAIM_ALREADY_REVIEWED: 409, OFFLINE_ALREADY_PAID: 409,
  OFFLINE_BANK_REFERENCE_CONFLICT: 409, EVIDENCE_ALREADY_STORED: 409, OFFLINE_RECONCILIATION_CONFLICT: 409,
};

/** 抛后端同款错误：code=10000+HTTP 状态，msg=原因码 */
export function mallFail(reason: string): never {
  const http = STATUS[reason] ?? (reason.endsWith("_UNAVAILABLE") ? 503 : 400);
  throw new ApiError(10000 + http, reason);
}

const need = (ok: unknown, reason: string): void => { if (!ok) mallFail(reason); };
const expected = (want: number | undefined, actual: number) => need(want === actual, "VERSION_CONFLICT");
const text = (s: unknown, max: number) => typeof s === "string" && s.trim().length > 0 && s.length <= max;

// ── 时间与编号 ──────────────────────────────────────────────────────────

let seq = 1000;
const nextNo = (prefix: string) => `${prefix}-${++seq}`;
const iso = (ms: number) => new Date(ms).toISOString();
const DAY = 86_400_000;
const T0 = Date.UTC(2026, 10, 1, 2, 0, 0); // 2026-11-01 10:00 台北

const twd = (minor: string): MallMoney => ({ currency: "TWD", minor });
const zero = (currency: string): MallMoney => ({ currency, minor: "0" });
const sum = (ms: MallMoney[], currency: string): MallMoney =>
  ({ currency, minor: ms.reduce((a, m) => a + BigInt(m.minor), BigInt(0)).toString() });

// ── 目录 ────────────────────────────────────────────────────────────────

interface TypeRow { draft: MallBusinessTypeDraft; catalogVersion: number; version: number }
interface EndRow { draft: MallBusinessEndDraft; version: number }
interface PlanDefRow { planNo: string; typeCode: string; tier: MallPlanTier; currentVersionNo: string | null; saleStatus: string; version: number }
interface PlanVerRow { draft: MallPlanDraft; status: "DRAFT" | "PUBLISHED"; revision: number; version: number }
interface ProductRow { draft: MallProductDraft; saleStatus: string; version: number }
interface SkuDefRow { skuNo: string; productNo: string; title: string; maxPerOrder: number; currentVersionNo: string | null; saleStatus: string; version: number }
interface SkuVerRow { draft: MallSkuDraft; status: "DRAFT" | "PUBLISHED"; revision: number; version: number }

// ── 订单 ────────────────────────────────────────────────────────────────

export interface MallRefundRow extends MallRefundView {
  /** 买家原请求的上限（审核报价的 cashLimit / creditLimit） */
  requestedCash: MallMoney;
  requestedCredit: MallMoney;
  reviewQuotes: Record<string, { approvalDigest: string; expiresAt: number }>;
}

export interface OfflineState {
  instructions: (MallOfflineInstructionsView & { platformMerchantNo: string }) | null;
  claims: MallOfflineClaimView[];
  /** assetKey → 用途与内容（url 为空表示只预留未上传） */
  evidence: Record<string, { purpose: string; url: string | null }>;
  /** 动作幂等：actionNo → 指纹与结果 */
  actions: Record<string, { fingerprint: string; status: "APPLIED" | "UNMATCHED"; paymentNo: string | null }>;
}

export interface MallOrderRow {
  storeNo: string;
  orderNo: string;
  status: MallOrderStatus;
  payment: string;
  fulfillment: string;
  refund: string;
  payable: MallMoney;
  components: MallQuoteComponent[];
  createdAt: number;
  quote: MallQuoteView;
  shippingAddress: MallShippingAddress | null;
  attempts: MallPaymentAttempt[];
  shipments: (MallShipment & { fingerprint: string })[];
  refunds: MallRefundRow[];
  /** 线下购买的订单才有 */
  offline: OfflineState | null;
  /** 代客单的下单幂等键 */
  idempotencyKey?: string;
}

interface Subscription { entitlementVersion: number; sources: MallEntitlementSource[]; segments: MallAccessSegment[]; grantOps: Record<string, string> }

interface ProfileRow { terms: MallOfflineCollectionTerms; enabled: boolean }

interface QuoteRow { view: MallQuoteView; usedBy: string | null; currency: string; lines: { skuVersionNo: string; quantity: number }[] }

export interface MallState {
  deployments: MallDeploymentOption[];
  types: Record<string, TypeRow>;
  ends: Record<string, EndRow>;
  planDefs: Record<string, PlanDefRow>;
  planVersions: Record<string, PlanVerRow>;
  products: Record<string, ProductRow>;
  skuDefs: Record<string, SkuDefRow>;
  skuVersions: Record<string, SkuVerRow>;
  /** 发布操作号幂等 */
  publications: Record<string, unknown>;
  orders: MallOrderRow[];
  subscriptions: Record<string, Subscription>;
  profiles: Record<string, ProfileRow>;
  quotes: Record<string, QuoteRow>;
  /** 全局已用银行流水（核款与出款共用一个号段） */
  bankRefs: Set<string>;
  /** 已知门店（mock 的门店存在性校验） */
  stores: Set<string>;
}

// ── 种子 ────────────────────────────────────────────────────────────────

const DEV_DEPLOYMENTS: MallDeploymentOption[] = [
  {
    deploymentNo: "DEP-DEV-BIZ", name: "B 端 App（开发验收样例）", code: "END_BIZ_APP", entitlementCode: "END_BIZ_APP",
    allowedBusinessTypes: ["RETAIL", "FNB"], clientId: "dev-biz-app", routePolicyRef: "commerce-route-v1:BIZ",
    acceptanceRef: "DEV-ACCEPT-BIZ-01",
  },
  {
    deploymentNo: "DEP-DEV-POS", name: "收银 POS（开发验收样例）", code: "END_APP_POS", entitlementCode: "END_APP_POS",
    allowedBusinessTypes: ["RETAIL", "FNB"], clientId: "dev-pos-app", routePolicyRef: "commerce-route-v1:POS",
    acceptanceRef: "DEV-ACCEPT-POS-01",
  },
];

const planComponent = (periodDays: number): MallQuoteComponent & { kind: MallComponentKind } =>
  ({ componentNo: nextNo("OC"), kind: "PLAN", listAmount: twd("0"), dealAmount: twd("0"), creditAmount: twd("0"),
     cashAmount: twd("0"), cycleDays: periodDays, quantity: 1 });

function priced(c: MallQuoteComponent, minor: string): MallQuoteComponent {
  return { ...c, listAmount: twd(minor), dealAmount: twd(minor), cashAmount: twd(minor) };
}

function quoteOf(storeNo: string, components: MallQuoteComponent[], at: number): MallQuoteView {
  const total = sum(components.map((c) => c.dealAmount ?? twd("0")), "TWD");
  return {
    quoteNo: nextNo("Q"), store: { tenantNo: "MAIN", storeNo }, total, credit: twd("0"), payable: total,
    components, entitlementVersion: 1, balanceVersion: 1, expiresAt: iso(at + 15 * 60_000), creditBreakdown: null,
  };
}

function seedOrder(
  storeNo: string, orderNo: string, at: number, components: MallQuoteComponent[],
  axes: { status: MallOrderStatus; payment: string; fulfillment: string; refund?: string },
  offline: OfflineState | null, shippingAddress: MallShippingAddress | null = null,
): MallOrderRow {
  const quote = quoteOf(storeNo, components, at);
  return {
    storeNo, orderNo, createdAt: at, components, payable: quote.payable!, quote, shippingAddress,
    status: axes.status, payment: axes.payment, fulfillment: axes.fulfillment, refund: axes.refund ?? "NONE",
    attempts: [], shipments: [], refunds: [], offline,
  };
}

function seedInstructions(orderNo: string, payable: MallMoney, issuedAt: number): OfflineState["instructions"] {
  return {
    orderNo, payable, paymentDueAt: iso(issuedAt + DAY), profileNo: "PRF-TWD-DEV", profileVersion: 1,
    instructions: { bankCode: "DEV-000", bankAccount: "DEV-0000-0000-0001", accountName: "平台收款（开发样例）" },
    platformMerchantNo: "PLT-DEV-TW",
  };
}

const placeholderReceipt =
  "data:image/svg+xml;utf8," + encodeURIComponent(
    "<svg xmlns='http://www.w3.org/2000/svg' width='320' height='200'><rect width='100%' height='100%' fill='#eef2f7'/>"
    + "<text x='50%' y='50%' text-anchor='middle' font-family='sans-serif' font-size='16' fill='#475569'>DEV receipt sample</text></svg>");

export function seedMall(): MallState {
  seq = 1000;
  const s: MallState = {
    deployments: DEV_DEPLOYMENTS.map((d) => ({ ...d })),
    types: {}, ends: {}, planDefs: {}, planVersions: {}, products: {}, skuDefs: {}, skuVersions: {},
    publications: {}, orders: [], subscriptions: {}, profiles: {}, quotes: {}, bankRefs: new Set(),
    stores: new Set(["TW-STORE-001", "TW-STORE-002", "TW-STORE-003", "TW-STORE-004"]),
  };

  // 零售：已发布、已启用
  s.types.RETAIL = { draft: { typeCode: "RETAIL", name: "零售", sortOrder: 1, enabled: true }, catalogVersion: 1, version: 3 };
  // 餐饮：只有类型，三档还没起草 —— 「目录草稿 → 发布」从这里走
  s.types.FNB = { draft: { typeCode: "FNB", name: "餐饮", sortOrder: 2, enabled: false }, catalogVersion: 0, version: 1 };

  s.ends.END_BIZ_APP = { version: 2, draft: {
    code: "END_BIZ_APP", entitlementCode: "END_BIZ_APP", name: "B 端 App", scope: "STORE",
    allowedBusinessTypes: ["RETAIL", "FNB"], releaseStatus: "VERIFIED", saleEnabled: false, bootstrapRequired: true,
    enabled: true, clientId: "dev-biz-app", routePolicyRef: "commerce-route-v1:BIZ", acceptanceRef: "DEV-ACCEPT-BIZ-01" } };
  s.ends.END_APP_POS = { version: 2, draft: {
    code: "END_APP_POS", entitlementCode: "END_APP_POS", name: "收银 POS", scope: "STORE",
    allowedBusinessTypes: ["RETAIL", "FNB"], releaseStatus: "VERIFIED", saleEnabled: true, bootstrapRequired: false,
    enabled: true, clientId: "dev-pos-app", routePolicyRef: "commerce-route-v1:POS", acceptanceRef: "DEV-ACCEPT-POS-01" } };
  s.ends.END_KDS = { version: 1, draft: {
    code: "END_KDS", entitlementCode: "END_KDS", name: "后厨屏", scope: "STORE",
    allowedBusinessTypes: ["FNB"], releaseStatus: "PLANNED", saleEnabled: false, bootstrapRequired: false,
    enabled: false, clientId: null, routePolicyRef: null, acceptanceRef: null } };

  for (const type of ["RETAIL", "FNB"]) {
    for (const tier of ["FREE", "PRO", "CHAIN"] as MallPlanTier[]) {
      const planNo = `${type}:${tier}`;
      s.planDefs[planNo] = { planNo, typeCode: type, tier, currentVersionNo: null, saleStatus: "DRAFT", version: 1 };
    }
  }
  const retailCodes: Record<MallPlanTier, string[]> = {
    FREE: ["END_BIZ_APP"], PRO: ["END_BIZ_APP", "END_APP_POS"], CHAIN: ["END_BIZ_APP", "END_APP_POS"],
  };
  for (const tier of ["FREE", "PRO", "CHAIN"] as MallPlanTier[]) {
    const planNo = `RETAIL:${tier}`;
    const vno = `PV-RETAIL-${tier}-1`;
    s.planVersions[vno] = { status: "PUBLISHED", revision: 1, version: 2, draft: {
      planNo, planVersionNo: vno, typeCode: "RETAIL", tier, displayName: `零售 ${tier}（开发测试）`,
      summary: null, entitlementCodes: retailCodes[tier], policyVersion: "dev-2026-11" } };
    s.planDefs[planNo] = { ...s.planDefs[planNo]!, currentVersionNo: vno, saleStatus: "ON_SALE", version: 2 };
  }

  s.products["MP-RETAIL-PLAN"] = { saleStatus: "ON_SALE", version: 1, draft: {
    productNo: "MP-RETAIL-PLAN", categoryCode: "PLAN", kind: "PLAN", name: "零售套餐（开发测试）",
    description: "开发测试目录，价格为文档示例值，非正式售价", allowedBusinessTypes: ["RETAIL"], sortOrder: 1 } };
  s.products["MP-POS-HW"] = { saleStatus: "DRAFT", version: 1, draft: {
    productNo: "MP-POS-HW", categoryCode: "HARDWARE", kind: "HARDWARE", name: "收银机（开发测试）",
    description: null, allowedBusinessTypes: ["RETAIL", "FNB"], sortOrder: 2 } };
  s.skuDefs["SKU-RETAIL-PRO-Y"] = { skuNo: "SKU-RETAIL-PRO-Y", productNo: "MP-RETAIL-PLAN", title: "零售 PRO 年付（开发测试）",
    maxPerOrder: 1, currentVersionNo: "SV-RETAIL-PRO-Y-1", saleStatus: "ON_SALE", version: 2 };
  s.skuVersions["SV-RETAIL-PRO-Y-1"] = { status: "PUBLISHED", revision: 1, version: 2, draft: {
    productNo: "MP-RETAIL-PLAN", skuNo: "SKU-RETAIL-PRO-Y", skuVersionNo: "SV-RETAIL-PRO-Y-1", title: "零售 PRO 年付（开发测试）",
    listPrice: twd("69900"), price: twd("69900"), allowedBusinessTypes: ["RETAIL"],
    refundPolicySnapshot: '{"schema":"commerce-refund-v1","unusedDigital":"FULL_ORIGINAL_ALLOCATION","usedDigital":"MANUAL_ONLY"}',
    components: [{ componentNo: "SC-1", kind: "PLAN", planVersionNo: "PV-RETAIL-PRO-1", entitlementCode: null, hardwareSkuNo: null,
      periodDays: 365, unitCount: 1, allocationWeight: 1, gift: false, activationPolicy: "IMMEDIATE" }],
    maxPerOrder: 1 } };
  // 一个草稿 SKU：用来走「发布单个 SKU」
  s.skuDefs["SKU-POS-HW-1"] = { skuNo: "SKU-POS-HW-1", productNo: "MP-POS-HW", title: "收银机一台（开发测试）",
    maxPerOrder: 5, currentVersionNo: null, saleStatus: "DRAFT", version: 1 };
  s.skuVersions["SV-POS-HW-1-1"] = { status: "DRAFT", revision: 1, version: 1, draft: {
    productNo: "MP-POS-HW", skuNo: "SKU-POS-HW-1", skuVersionNo: "SV-POS-HW-1-1", title: "收银机一台（开发测试）",
    listPrice: twd("1200000"), price: twd("990000"), allowedBusinessTypes: ["RETAIL", "FNB"],
    refundPolicySnapshot: '{"schema":"commerce-refund-v1","unusedDigital":"MANUAL_ONLY","usedDigital":"MANUAL_ONLY"}',
    components: [{ componentNo: "SC-2", kind: "HARDWARE", planVersionNo: null, entitlementCode: null, hardwareSkuNo: "HW-POS-01",
      periodDays: null, unitCount: 1, allocationWeight: 1, gift: false, activationPolicy: "ON_SHIPMENT" }],
    maxPerOrder: 5 } };

  // 收款档案：TWD v1 已启用（开发样例账户）
  s.profiles.TWD = { enabled: true, terms: {
    profileNo: "PRF-TWD-DEV", profileVersion: 1, platformMerchantNo: "PLT-DEV-TW", currency: "TWD",
    receivingAccountKey: "acct:dev:9f2c", paymentWindowVersion: "dev-24h", paymentWindowMs: DAY,
    instructions: { bankCode: "DEV-000", bankAccount: "DEV-0000-0000-0001", accountName: "平台收款（开发样例）" } } };

  // ── 订单 ──
  const pro = () => priced(planComponent(365), "69900");

  // 1. 已付已开通，买家提了退款待审
  const o1 = seedOrder("TW-STORE-001", "MO-1001", T0 - 20 * DAY, [pro()], { status: "COMPLETED", payment: "PAID", fulfillment: "COMPLETED" }, null);
  o1.attempts.push({ attemptNo: "PA-1001", channel: "INTERNAL", payMethod: "DEV", amount: o1.payable, status: "SUCCEEDED",
    paymentNo: "PAY-1001", paidAt: iso(T0 - 20 * DAY + 60_000), errorCode: null, paidAtPrecisionMs: 1 });
  o1.refunds.push({ refundNo: "RF-1001", orderNo: "MO-1001", status: "PENDING", cashAmount: twd("0"), restoredCredit: twd("0"),
    reviewStatus: "REQUESTED", requestDigest: "RD-1001", reviewQuoteNo: null, requestedCash: twd("69900"), requestedCredit: twd("0"), reviewQuotes: {} });
  o1.refund = "NONE";

  // 2. 代客线下单：已发指引，买家申报待审
  const o2 = seedOrder("TW-STORE-001", "MO-1002", T0 - DAY, [pro()], { status: "AWAITING_PAYMENT", payment: "UNPAID", fulfillment: "PENDING" },
    { instructions: null, claims: [], evidence: {}, actions: {} });
  o2.offline!.instructions = seedInstructions("MO-1002", o2.payable, T0 - DAY);
  o2.offline!.claims.push({ claimNo: "CL-1002-1", status: "SUBMITTED", declaredAmount: twd("69900"),
    declaredPaidAt: iso(T0 - DAY + 3 * 3_600_000), submittedAt: iso(T0 - DAY + 4 * 3_600_000) });

  // 3. 含硬件的已付单：待发货
  const hw: MallQuoteComponent = { componentNo: "OC-HW-1003", kind: "HARDWARE", listAmount: twd("1980000"), dealAmount: twd("1980000"),
    creditAmount: twd("0"), cashAmount: twd("1980000"), cycleDays: 0, quantity: 2 };
  const o3 = seedOrder("TW-STORE-001", "MO-1003", T0 - 3 * DAY, [hw], { status: "FULFILLING", payment: "PAID", fulfillment: "PENDING" }, null,
    { recipient: "王小明（样例）", phone: "0900-000-000", countryCode: "TW", region: null, city: "台北市", addressLine: "信义路样例 1 号", postalCode: "110" });
  o3.attempts.push({ attemptNo: "PA-1003", channel: "INTERNAL", payMethod: "DEV", amount: o3.payable, status: "SUCCEEDED",
    paymentNo: "PAY-1003", paidAt: iso(T0 - 3 * DAY + 60_000), errorCode: null, paidAtPrecisionMs: 1 });

  // 4. 线下单：来了一笔金额不符的款（MALL_UNMATCHED），申报被判不符 —— 作废时必须逐笔处置
  const o4 = seedOrder("TW-STORE-003", "MO-3001", T0 - 2 * DAY, [pro()], { status: "AWAITING_PAYMENT", payment: "UNPAID", fulfillment: "PENDING" },
    { instructions: null, claims: [], evidence: {}, actions: {} });
  o4.offline!.instructions = seedInstructions("MO-3001", o4.payable, T0 - 2 * DAY);
  o4.offline!.claims.push({ claimNo: "CL-3001-1", status: "MISMATCHED", declaredAmount: twd("60000"),
    declaredPaidAt: iso(T0 - 2 * DAY + 3_600_000), submittedAt: iso(T0 - 2 * DAY + 2 * 3_600_000) });
  o4.attempts.push({ attemptNo: "OA-3001", channel: "OFFLINE", payMethod: "BANK_TRANSFER", amount: twd("60000"), status: "SUCCEEDED",
    paymentNo: "U-3001-1", paidAt: iso(T0 - 2 * DAY + 3_600_000), errorCode: "MALL_UNMATCHED", paidAtPrecisionMs: 1 });
  s.bankRefs.add("DEV-BANK-REF-3001");

  // 5. 线下已付、退款已批准，等运营录入实际出款
  const o5 = seedOrder("TW-STORE-001", "MO-1005", T0 - 10 * DAY, [pro()], { status: "REFUNDING", payment: "PAID", fulfillment: "COMPLETED", refund: "REFUNDING" },
    { instructions: null, claims: [], evidence: {}, actions: {} });
  o5.offline!.instructions = seedInstructions("MO-1005", o5.payable, T0 - 10 * DAY);
  o5.attempts.push({ attemptNo: "OA-1005", channel: "OFFLINE", payMethod: "BANK_TRANSFER", amount: o5.payable, status: "SUCCEEDED",
    paymentNo: "P-1005", paidAt: iso(T0 - 10 * DAY + 3_600_000), errorCode: null, paidAtPrecisionMs: 1 });
  o5.refunds.push({ refundNo: "RF-1005", orderNo: "MO-1005", status: "PENDING", cashAmount: twd("69900"), restoredCredit: twd("0"),
    reviewStatus: "APPROVED", requestDigest: "RD-1005", reviewQuoteNo: "RQ-1005", requestedCash: twd("69900"), requestedCredit: twd("0"), reviewQuotes: {} });
  s.bankRefs.add("DEV-BANK-REF-1005");

  // 6. 另一家店：申报审核中
  const o6 = seedOrder("TW-STORE-002", "MO-2001", T0 - DAY / 2, [pro()], { status: "AWAITING_PAYMENT", payment: "UNPAID", fulfillment: "PENDING" },
    { instructions: null, claims: [], evidence: {}, actions: {} });
  o6.offline!.instructions = seedInstructions("MO-2001", o6.payable, T0 - DAY / 2);
  o6.offline!.claims.push({ claimNo: "CL-2001-1", status: "REVIEWING", declaredAmount: twd("69900"),
    declaredPaidAt: iso(T0 - DAY / 2 + 3_600_000), submittedAt: iso(T0 - DAY / 2 + 2 * 3_600_000) });

  s.orders.push(o1, o2, o3, o4, o5, o6);

  // 门店权益来源
  s.subscriptions["TW-STORE-001"] = { entitlementVersion: 4, grantOps: {}, sources: [
    { sourceNo: "SRC-001-FREE", entitlementCode: "END_BIZ_APP", planVersionNo: "PV-RETAIL-FREE-1", originNo: "BOOTSTRAP",
      durationMillis: 0, consumedMillis: 0, valuationBasis: twd("0"), permanent: true, lifecycle: "FREE", revokedDurationMillis: 0 },
    { sourceNo: "SRC-001-PRO", entitlementCode: "END_APP_POS", planVersionNo: "PV-RETAIL-PRO-1", originNo: "MO-1001",
      durationMillis: 365 * DAY, consumedMillis: 20 * DAY, valuationBasis: twd("69900"), permanent: false, lifecycle: "PURCHASE", revokedDurationMillis: 0 },
    { sourceNo: "SRC-001-GIFT", entitlementCode: "END_APP_POS", planVersionNo: null, originNo: "GRANT-DEV-1",
      durationMillis: 30 * DAY, consumedMillis: 2 * DAY, valuationBasis: twd("0"), permanent: false, lifecycle: "GRANT", revokedDurationMillis: 0 },
  ], segments: [
    { sourceNo: "SRC-001-PRO", entitlementCode: "END_APP_POS", startsAt: iso(T0 - 20 * DAY), endsAt: iso(T0 + 345 * DAY) },
    { sourceNo: "SRC-001-GIFT", entitlementCode: "END_APP_POS", startsAt: iso(T0 + 345 * DAY), endsAt: iso(T0 + 373 * DAY) },
  ] };
  s.subscriptions["TW-STORE-002"] = { entitlementVersion: 1, grantOps: {}, sources: [
    { sourceNo: "SRC-002-FREE", entitlementCode: "END_BIZ_APP", planVersionNo: "PV-RETAIL-FREE-1", originNo: "BOOTSTRAP",
      durationMillis: 0, consumedMillis: 0, valuationBasis: twd("0"), permanent: true, lifecycle: "FREE", revokedDurationMillis: 0 },
  ], segments: [] };
  return s;
}

export const mall: { s: MallState } = { s: seedMall() };
/** 测试用：回到种子状态 */
export const resetMall = () => { mall.s = seedMall(); };
export const SAMPLE_RECEIPT_URL = placeholderReceipt;

// ── 目录写操作 ──────────────────────────────────────────────────────────

const st = () => mall.s;

export function createType(d: MallBusinessTypeDraft) {
  need(d.typeCode && /^[A-Z][A-Z0-9_]{1,31}$/.test(d.typeCode) && text(d.name, 64) && text(d.reason, 1024), "INVALID_ARGUMENT");
  const s = st();
  need(!s.types[d.typeCode!], "VERSION_CONFLICT");
  expected(d.expectedVersion, 0);
  need(!d.enabled, "INVALID_ARGUMENT"); // 新建必须停用：发布行业目录后才能启用
  s.types[d.typeCode!] = { draft: { ...d }, catalogVersion: 0, version: 1 };
  for (const tier of ["FREE", "PRO", "CHAIN"] as MallPlanTier[]) {
    const planNo = `${d.typeCode}:${tier}`;
    s.planDefs[planNo] = { planNo, typeCode: d.typeCode!, tier, currentVersionNo: null, saleStatus: "DRAFT", version: 1 };
  }
  return { entityNo: d.typeCode!, draftVersionNo: null, version: 1 };
}

export function updateType(code: string, v: { name: string; sortOrder: number; enabled: boolean; expectedVersion: number; reason: string }) {
  const s = st();
  const row = s.types[code] ?? mallFail("NOT_FOUND");
  expected(v.expectedVersion, row.version);
  need(text(v.name, 64) && text(v.reason, 1024), "INVALID_ARGUMENT");
  need(!v.enabled || row.catalogVersion > 0, "INVALID_ARGUMENT"); // 没发布过不能启用
  row.draft = { ...row.draft, name: v.name, sortOrder: v.sortOrder, enabled: v.enabled, reason: v.reason };
  row.version += 1;
  return { entityNo: code, draftVersionNo: null, version: row.version };
}

export function saveEnd(v: {
  code: string; entitlementCode: string; name: string; scope: "STORE" | "SYSTEM"; allowedBusinessTypes: string[];
  releaseStatus: "PLANNED" | "VERIFIED" | "RETIRED"; saleEnabled: boolean; bootstrapRequired: boolean; enabled: boolean;
  deploymentNo: string | null; expectedVersion: number; reason: string;
}) {
  const s = st();
  need(/^[A-Z][A-Z0-9_]{1,31}$/.test(v.code) && /^[A-Z][A-Z0-9_]{1,63}$/.test(v.entitlementCode)
    && text(v.name, 64) && text(v.reason, 1024) && v.allowedBusinessTypes.length > 0, "INVALID_ARGUMENT");
  let binding: MallDeploymentOption | null = null;
  if (v.deploymentNo != null) {
    binding = s.deployments.find((d) => d.deploymentNo === v.deploymentNo) ?? mallFail("DEPLOYMENT_UNAVAILABLE");
    need(binding.code === v.code && binding.entitlementCode === v.entitlementCode
      && v.allowedBusinessTypes.every((t) => binding!.allowedBusinessTypes!.includes(t)), "INVALID_REQUEST");
  }
  if (v.releaseStatus === "VERIFIED") need(binding, "DEPLOYMENT_UNAVAILABLE");
  for (const t of v.allowedBusinessTypes) need(s.types[t], "INVALID_ARGUMENT");
  const old = s.ends[v.code];
  expected(v.expectedVersion, old?.version ?? 0);
  const version = (old?.version ?? 0) + 1;
  s.ends[v.code] = { version, draft: {
    code: v.code, entitlementCode: v.entitlementCode, name: v.name, scope: v.scope, allowedBusinessTypes: [...v.allowedBusinessTypes],
    releaseStatus: v.releaseStatus, saleEnabled: v.saleEnabled, bootstrapRequired: v.bootstrapRequired, enabled: v.enabled,
    clientId: binding?.clientId ?? null, routePolicyRef: binding?.routePolicyRef ?? null, acceptanceRef: binding?.acceptanceRef ?? null,
    reason: v.reason } };
  return { entityNo: v.code, draftVersionNo: null, version };
}

export function savePlanVersion(planNo: string, v: {
  planVersionNo: string | null; typeCode: string; tier: MallPlanTier; displayName: string; summary: string | null;
  entitlementCodes: string[]; policyVersion: string; expectedVersion: number; reason: string;
}) {
  const s = st();
  const def = s.planDefs[planNo] ?? mallFail("NOT_FOUND");
  need(def.typeCode === v.typeCode && def.tier === v.tier, "INVALID_ARGUMENT");
  need(text(v.displayName, 64) && text(v.policyVersion, 64) && text(v.reason, 1024)
    && (v.summary == null || v.summary.length <= 512), "INVALID_ARGUMENT");
  need(new Set(v.entitlementCodes).size === v.entitlementCodes.length, "INVALID_ARGUMENT");
  for (const c of v.entitlementCodes) need(Object.values(s.ends).some((e) => e.draft.entitlementCode === c), "INVALID_ARGUMENT");
  const creating = v.planVersionNo == null;
  const old = creating ? undefined : s.planVersions[v.planVersionNo!];
  if (!creating) need(old, "INVALID_ARGUMENT");
  if (old && old.status !== "DRAFT") mallFail("IMMUTABLE_VERSION");
  expected(v.expectedVersion, old?.version ?? 0);
  if (old) need(old.draft.planNo === planNo, "INVALID_ARGUMENT");
  const revision = old?.revision ?? Math.max(0, ...Object.values(s.planVersions).filter((x) => x.draft.planNo === planNo).map((x) => x.revision)) + 1;
  const vno = v.planVersionNo ?? `PV-${planNo.replace(":", "-")}-${revision}`;
  const version = (old?.version ?? 0) + 1;
  s.planVersions[vno] = { status: "DRAFT", revision, version, draft: {
    planNo, planVersionNo: vno, typeCode: v.typeCode, tier: v.tier, displayName: v.displayName, summary: v.summary,
    entitlementCodes: [...v.entitlementCodes], policyVersion: v.policyVersion, reason: v.reason } };
  return { entityNo: planNo, draftVersionNo: vno, version };
}

export function publishIndustry(typeCode: string, d: {
  operationNo: string; freePlanVersionNo: string; proPlanVersionNo: string; chainPlanVersionNo: string;
  candidateSkuVersionNos: string[]; expectedCatalogVersion: number; expectedPlanVersions: Record<string, number>;
  expectedSkuVersions: Record<string, number>; expectedPlanDefinitionVersions: Record<string, number>;
  expectedSkuDefinitionVersions: Record<string, number>; reason: string;
}) {
  const s = st();
  need(text(d.operationNo, 64) && text(d.reason, 1024), "INVALID_ARGUMENT");
  const key = `IND:${d.operationNo}`;
  if (s.publications[key]) return s.publications[key] as { publicationNo: string; catalogVersion: number; publishedVersionNos: string[] };
  const type = s.types[typeCode] ?? mallFail("NOT_FOUND");
  expected(d.expectedCatalogVersion, type.catalogVersion);
  const versions = [d.freePlanVersionNo, d.proPlanVersionNo, d.chainPlanVersionNo];
  need(new Set(versions).size === 3, "INVALID_ARGUMENT");
  for (const def of Object.values(s.planDefs).filter((p) => p.typeCode === typeCode)) {
    need(def.planNo in d.expectedPlanDefinitionVersions, "INVALID_ARGUMENT");
    expected(d.expectedPlanDefinitionVersions[def.planNo], def.version);
  }
  const tiers: MallPlanTier[] = ["FREE", "PRO", "CHAIN"];
  let lower = new Set<string>();
  versions.forEach((no, i) => {
    const v = s.planVersions[no] ?? mallFail("NOT_FOUND");
    need(no in d.expectedPlanVersions, "INVALID_ARGUMENT");
    expected(d.expectedPlanVersions[no], v.version);
    need(v.draft.typeCode === typeCode && v.draft.tier === tiers[i], "INVALID_ARGUMENT");
    const codes = new Set(v.draft.entitlementCodes);
    need([...lower].every((c) => codes.has(c)), "INVALID_ARGUMENT"); // 高档必须包含低档
    if (i === 0) {
      for (const e of Object.values(s.ends)) {
        if (e.draft.bootstrapRequired && e.draft.allowedBusinessTypes?.includes(typeCode)) need(codes.has(e.draft.entitlementCode!), "INVALID_ARGUMENT");
      }
      need(codes.size > 0, "INVALID_ARGUMENT");
    }
    lower = codes;
  });
  const skuNos = new Set<string>();
  let currency: string | null = null;
  for (const no of d.candidateSkuVersionNos) {
    const sv = s.skuVersions[no] ?? mallFail("NOT_FOUND");
    const def = s.skuDefs[sv.draft.skuNo!]!;
    need(no in d.expectedSkuVersions && def.skuNo in d.expectedSkuDefinitionVersions, "INVALID_ARGUMENT");
    expected(d.expectedSkuVersions[no], sv.version);
    expected(d.expectedSkuDefinitionVersions[def.skuNo], def.version);
    need(!skuNos.has(def.skuNo), "INVALID_ARGUMENT");
    skuNos.add(def.skuNo);
    need((sv.draft.components ?? []).filter((c) => c.kind === "PLAN").every((c) => versions.includes(c.planVersionNo!)), "INVALID_ARGUMENT");
    need(sv.draft.allowedBusinessTypes?.includes(typeCode), "INVALID_ARGUMENT");
    const cur: string = sv.draft.price!.currency;
    need(currency == null || currency === cur, "INVALID_ARGUMENT");
    currency = cur;
  }
  for (const no of versions) {
    const v = s.planVersions[no]!;
    if (v.status === "DRAFT") { v.status = "PUBLISHED"; v.version += 1; }
    const def = s.planDefs[v.draft.planNo!]!;
    def.currentVersionNo = no; def.saleStatus = "ON_SALE"; def.version += 1;
  }
  // 引用了本行业旧 Plan 版本的在售 SKU 一律停售：新目录下不能再买到旧 Plan
  for (const def of Object.values(s.skuDefs)) {
    if (def.saleStatus !== "ON_SALE" || !def.currentVersionNo) continue;
    const sv = s.skuVersions[def.currentVersionNo]!;
    const obsolete = (sv.draft.components ?? []).some((c) => c.kind === "PLAN"
      && s.planVersions[c.planVersionNo!]?.draft.typeCode === typeCode && !versions.includes(c.planVersionNo!));
    if (obsolete) { def.saleStatus = "OFF_SALE"; def.version += 1; }
  }
  for (const no of d.candidateSkuVersionNos) publishSkuRow(no);
  type.catalogVersion += 1;
  type.version += 1;
  const result = { publicationNo: nextNo("PUB"), catalogVersion: type.catalogVersion, publishedVersionNos: [...versions, ...d.candidateSkuVersionNos] };
  s.publications[key] = result;
  return result;
}

function publishSkuRow(no: string) {
  const s = st();
  const sv = s.skuVersions[no]!;
  if (sv.status === "DRAFT") { sv.status = "PUBLISHED"; sv.version += 1; }
  const def = s.skuDefs[sv.draft.skuNo!]!;
  def.currentVersionNo = no; def.saleStatus = "ON_SALE"; def.title = sv.draft.title!; def.maxPerOrder = sv.draft.maxPerOrder!; def.version += 1;
  const product = s.products[def.productNo];
  if (product && product.saleStatus !== "ON_SALE") { product.saleStatus = "ON_SALE"; product.version += 1; }
}

export function saveProduct(d: MallProductDraft, creating: boolean) {
  const s = st();
  need(d.productNo && /^[A-Z][A-Z0-9_-]{1,63}$/.test(d.productNo) && text(d.name, 128) && text(d.reason, 1024)
    && text(d.categoryCode, 32) && d.kind && (d.allowedBusinessTypes ?? []).length > 0, "INVALID_ARGUMENT");
  for (const t of d.allowedBusinessTypes!) need(s.types[t], "INVALID_ARGUMENT");
  const old = s.products[d.productNo!];
  if (creating) need(!old, "VERSION_CONFLICT");
  else need(old, "NOT_FOUND");
  expected(d.expectedVersion, old?.version ?? 0);
  const version = (old?.version ?? 0) + 1;
  s.products[d.productNo!] = { draft: { ...d }, saleStatus: old?.saleStatus ?? "DRAFT", version };
  return { entityNo: d.productNo!, draftVersionNo: null, version };
}

export function saveSkuVersion(productNo: string, v: {
  skuNo: string; skuVersionNo: string | null; title: string; listPrice: MallMoney; price: MallMoney;
  allowedBusinessTypes: string[]; refundPolicy: { unusedDigital: string; usedDigital: string };
  components: MallSkuDraft["components"]; maxPerOrder: number; expectedVersion: number; reason: string;
}) {
  const s = st();
  const product = s.products[productNo] ?? mallFail("NOT_FOUND");
  const p = v.refundPolicy;
  need(p && ["FULL_ORIGINAL_ALLOCATION", "MANUAL_ONLY"].includes(p.unusedDigital) && p.usedDigital === "MANUAL_ONLY", "INVALID_REFUND_POLICY");
  need(/^[A-Z][A-Z0-9_-]{1,63}$/.test(v.skuNo) && text(v.title, 128) && text(v.reason, 1024)
    && v.maxPerOrder >= 1 && v.maxPerOrder <= 100, "INVALID_ARGUMENT");
  for (const m of [v.listPrice, v.price]) need(m && /^[A-Z]{3}$/.test(m.currency) && /^(0|[1-9][0-9]{0,18})$/.test(String(m.minor)), "INVALID_ARGUMENT");
  need(v.listPrice.currency === v.price.currency && BigInt(v.price.minor) <= BigInt(v.listPrice.minor), "INVALID_ARGUMENT");
  need((v.components ?? []).length > 0, "INVALID_ARGUMENT");
  for (const c of v.components!) {
    if (c.kind === "PLAN") need(c.planVersionNo && s.planVersions[c.planVersionNo] && (c.periodDays ?? 0) > 0, "INVALID_ARGUMENT");
    else if (c.kind === "END_PACKAGE") need(c.entitlementCode && Object.values(s.ends).some((e) => e.draft.entitlementCode === c.entitlementCode) && (c.periodDays ?? 0) > 0, "INVALID_ARGUMENT");
    else if (c.kind === "HARDWARE") need(text(c.hardwareSkuNo, 64), "INVALID_ARGUMENT");
    else mallFail("INVALID_ARGUMENT");
    need((c.unitCount ?? 0) >= 1 && (c.allocationWeight ?? -1) >= 0, "INVALID_ARGUMENT");
  }
  for (const t of v.allowedBusinessTypes) need(product.draft.allowedBusinessTypes?.includes(t), "INVALID_ARGUMENT");
  const def = s.skuDefs[v.skuNo];
  if (def) need(def.productNo === productNo, "INVALID_ARGUMENT");
  expected(v.expectedVersion, def?.version ?? 0);
  const creating = v.skuVersionNo == null;
  const old = creating ? undefined : s.skuVersions[v.skuVersionNo!];
  if (!creating) need(old && old.draft.skuNo === v.skuNo, "INVALID_ARGUMENT");
  if (old && old.status !== "DRAFT") mallFail("IMMUTABLE_VERSION");
  const revision = old?.revision ?? Math.max(0, ...Object.values(s.skuVersions).filter((x) => x.draft.skuNo === v.skuNo).map((x) => x.revision)) + 1;
  const vno = v.skuVersionNo ?? `SV-${v.skuNo.replace(/^SKU-/, "")}-${revision}`;
  const defVersion = (def?.version ?? 0) + 1;
  s.skuDefs[v.skuNo] = def
    ? { ...def, version: defVersion }
    : { skuNo: v.skuNo, productNo, title: v.title, maxPerOrder: v.maxPerOrder, currentVersionNo: null, saleStatus: "DRAFT", version: defVersion };
  s.skuVersions[vno] = { status: "DRAFT", revision, version: (old?.version ?? 0) + 1, draft: {
    productNo, skuNo: v.skuNo, skuVersionNo: vno, title: v.title, listPrice: { ...v.listPrice, minor: String(v.listPrice.minor) },
    price: { ...v.price, minor: String(v.price.minor) }, allowedBusinessTypes: [...v.allowedBusinessTypes],
    refundPolicySnapshot: JSON.stringify({ schema: "commerce-refund-v1", unusedDigital: p.unusedDigital, usedDigital: "MANUAL_ONLY" }),
    components: v.components!.map((c, i) => ({ ...c, componentNo: c.componentNo ?? `SC-${vno}-${i + 1}` })),
    maxPerOrder: v.maxPerOrder, reason: v.reason } };
  return { entityNo: v.skuNo, draftVersionNo: vno, version: defVersion };
}

export function publishSku(no: string, d: { operationNo: string; expectedVersion: number; expectedSkuVersion: number; reason: string }) {
  const s = st();
  need(text(d.operationNo, 64) && text(d.reason, 1024), "INVALID_ARGUMENT");
  const key = `SKU:${d.operationNo}`;
  if (s.publications[key]) return s.publications[key] as { publicationNo: string; catalogVersion: number; publishedVersionNos: string[] };
  const sv = s.skuVersions[no] ?? mallFail("NOT_FOUND");
  const def = s.skuDefs[sv.draft.skuNo!]!;
  expected(d.expectedVersion, def.version);
  expected(d.expectedSkuVersion, sv.version);
  // 套餐 SKU 只能引用**当前已发布**的 Plan 版本：否则要走行业发布一起冻结
  for (const c of sv.draft.components ?? []) {
    if (c.kind === "PLAN") {
      const pv = s.planVersions[c.planVersionNo!];
      need(pv && pv.status === "PUBLISHED" && s.planDefs[pv.draft.planNo!]?.currentVersionNo === c.planVersionNo, "INVALID_ARGUMENT");
    }
  }
  publishSkuRow(no);
  const result = { publicationNo: nextNo("PUB"), catalogVersion: 0, publishedVersionNos: [no] };
  s.publications[key] = result;
  return result;
}

export function stopSale(target: "PLAN" | "SKU", no: string, d: { operationNo: string; expectedVersion: number; reason: string }) {
  const s = st();
  need(text(d.operationNo, 64) && text(d.reason, 1024), "INVALID_ARGUMENT");
  const row = target === "PLAN" ? s.planDefs[no] : s.skuDefs[no];
  if (!row) mallFail("NOT_FOUND");
  const key = `STOP:${target}:${d.operationNo}`;
  if (s.publications[key]) return s.publications[key] as { entityNo: string; draftVersionNo: null; version: number };
  expected(d.expectedVersion, row.version);
  need(row.saleStatus === "ON_SALE", "INVALID_ARGUMENT");
  row.saleStatus = "OFF_SALE";
  row.version += 1;
  // 已落单订单的冻结快照（quote.components）**不动** —— 停售只影响以后的报价
  const result = { entityNo: no, draftVersionNo: null, version: row.version };
  s.publications[key] = result;
  return result;
}

// ── 订单动作 ────────────────────────────────────────────────────────────

export function findOrder(storeNo: string, orderNo: string): MallOrderRow {
  need(st().stores.has(storeNo), "STORE_UNAVAILABLE");
  return st().orders.find((o) => o.orderNo === orderNo && o.storeNo === storeNo) ?? mallFail("NOT_FOUND");
}

function digitalOnly(o: MallOrderRow) {
  return o.components.every((c) => c.kind !== "HARDWARE");
}

export function ship(storeNo: string, orderNo: string, v: { orderComponentNo: string; operationNo: string; carrierCode: string; trackingNo: string; quantity: number }) {
  const o = findOrder(storeNo, orderNo);
  need(text(v.operationNo, 64) && text(v.carrierCode, 32) && text(v.trackingNo, 64), "INVALID_REQUEST");
  const fp = JSON.stringify([v.orderComponentNo, v.carrierCode, v.trackingNo, v.quantity]);
  const prior = o.shipments.find((x) => x.operationNo === v.operationNo);
  if (prior) { need(prior.fingerprint === fp, "IDEMPOTENCY_CONFLICT"); return o; }
  need(o.payment === "PAID", "FULFILLMENT_UNAVAILABLE");
  const c = o.components.find((x) => x.componentNo === v.orderComponentNo);
  need(c && c.kind === "HARDWARE", "INVALID_REQUEST");
  const shipped = o.shipments.filter((x) => x.orderComponentNo === v.orderComponentNo && x.status !== "FAILED")
    .reduce((a, x) => a + (x.quantity ?? 0), 0);
  need(Number.isInteger(v.quantity) && v.quantity >= 1 && v.quantity <= (c!.quantity ?? 0) - shipped, "INVALID_REQUEST");
  o.shipments.push({ operationNo: v.operationNo, orderComponentNo: v.orderComponentNo, carrierCode: v.carrierCode,
    trackingNo: v.trackingNo, quantity: v.quantity, status: "SUCCEEDED", errorCode: null, fingerprint: fp });
  const done = o.components.filter((x) => x.kind === "HARDWARE").every((h) =>
    o.shipments.filter((x) => x.orderComponentNo === h.componentNo && x.status === "SUCCEEDED").reduce((a, x) => a + (x.quantity ?? 0), 0) >= (h.quantity ?? 0));
  o.fulfillment = done ? "COMPLETED" : "PARTIAL";
  if (done && o.refund === "NONE") o.status = "COMPLETED";
  return o;
}

function refundOf(o: MallOrderRow, refundNo: string) {
  return o.refunds.find((r) => r.refundNo === refundNo) ?? mallFail("NOT_FOUND");
}

export function reviewRefund(storeNo: string, orderNo: string, refundNo: string, v: { requestDigest: string; reviewQuoteNo: string | null }) {
  const o = findOrder(storeNo, orderNo);
  const r = refundOf(o, refundNo);
  need(r.reviewStatus === "REQUESTED", "VERSION_CONFLICT");
  need(v.requestDigest === r.requestDigest, "INVALID_REQUEST");
  const reviewQuoteNo = v.reviewQuoteNo ?? nextNo("RQ");
  const prior = r.reviewQuotes[reviewQuoteNo];
  const approvalDigest = prior?.approvalDigest ?? `AD-${reviewQuoteNo}`;
  const expiresAt = Date.now() + 15 * 60_000;
  r.reviewQuotes[reviewQuoteNo] = { approvalDigest, expiresAt };
  return {
    reviewQuoteNo, requestDigest: r.requestDigest!,
    preview: { refundNo, orderNo, cashLimit: r.requestedCash, creditLimit: r.requestedCredit, requiresManualReview: true,
      sourcePeriodNos: [], expiresAt: iso(expiresAt), approvalDigest },
  };
}

export function approveRefund(storeNo: string, orderNo: string, refundNo: string, v: {
  approvalDigest: string; reviewQuoteNo: string | null; cashAmount: MallMoney; restoreCredit: MallMoney; reviewEvidence: string;
}): MallRefundView {
  const o = findOrder(storeNo, orderNo);
  const r = refundOf(o, refundNo);
  need(r.reviewStatus === "REQUESTED", "VERSION_CONFLICT");
  need(text(v.reviewEvidence, 1024), "INVALID_REQUEST");
  const q = v.reviewQuoteNo ? r.reviewQuotes[v.reviewQuoteNo] : undefined;
  need(q && q.approvalDigest === v.approvalDigest, "INVALID_REQUEST");
  need(q!.expiresAt > Date.now(), "QUOTE_EXPIRED");
  need(v.cashAmount.currency === r.requestedCash.currency && v.restoreCredit.currency === r.requestedCredit.currency, "INVALID_REQUEST");
  need(BigInt(v.cashAmount.minor) <= BigInt(r.requestedCash.minor) && BigInt(v.restoreCredit.minor) <= BigInt(r.requestedCredit.minor), "INVALID_REQUEST");
  need(BigInt(v.cashAmount.minor) + BigInt(v.restoreCredit.minor) > BigInt(0), "INVALID_REQUEST");
  r.reviewStatus = "APPROVED";
  r.reviewQuoteNo = v.reviewQuoteNo;
  r.cashAmount = { ...v.cashAmount, minor: String(v.cashAmount.minor) };
  r.restoredCredit = { ...v.restoreCredit, minor: String(v.restoreCredit.minor) };
  r.status = "PENDING"; // 资金还没动：线上走通道，线下等运营录入实际出款
  o.refund = "REFUNDING";
  o.status = "REFUNDING";
  return publicRefund(r);
}

export function rejectRefund(storeNo: string, orderNo: string, refundNo: string, v: { requestDigest: string; reviewEvidence: string }): MallRefundView {
  const o = findOrder(storeNo, orderNo);
  const r = refundOf(o, refundNo);
  need(r.reviewStatus === "REQUESTED", "VERSION_CONFLICT");
  need(v.requestDigest === r.requestDigest && text(v.reviewEvidence, 1024), "INVALID_REQUEST");
  r.reviewStatus = "DECLINED";
  r.status = "FAILED";
  return publicRefund(r);
}

export function publicRefund(r: MallRefundRow): MallRefundView {
  return { refundNo: r.refundNo, orderNo: r.orderNo, status: r.status, cashAmount: r.cashAmount, restoredCredit: r.restoredCredit,
    reviewStatus: r.reviewStatus, requestDigest: r.requestDigest, reviewQuoteNo: r.reviewQuoteNo };
}

// ── 权益来源 ────────────────────────────────────────────────────────────

export function subscriptionOf(storeNo: string): Subscription {
  need(st().stores.has(storeNo), "STORE_UNAVAILABLE");
  return st().subscriptions[storeNo] ?? (st().subscriptions[storeNo] = { entitlementVersion: 0, sources: [], segments: [], grantOps: {} });
}

export function grant(storeNo: string, v: { operationNo: string; entitlementCode: string; durationDays: number; reason: string }) {
  const sub = subscriptionOf(storeNo);
  need(text(v.operationNo, 64) && text(v.reason, 1024), "INVALID_REQUEST");
  const fp = JSON.stringify([v.entitlementCode, v.durationDays]);
  if (sub.grantOps[v.operationNo]) { need(sub.grantOps[v.operationNo] === fp, "IDEMPOTENCY_CONFLICT"); return sub; }
  const end = Object.values(st().ends).find((e) => e.draft.entitlementCode === v.entitlementCode);
  // 只有可单独售卖的独立端能赠送；FREE 必需端受保护
  need(end && end.draft.saleEnabled && !end.draft.bootstrapRequired, "INVALID_REQUEST");
  need(Number.isInteger(v.durationDays) && v.durationDays >= 1 && v.durationDays <= 3650, "INVALID_REQUEST");
  const now = Date.now();
  const sourceNo = nextNo("SRC");
  sub.sources.push({ sourceNo, entitlementCode: v.entitlementCode, planVersionNo: null, originNo: v.operationNo,
    durationMillis: v.durationDays * DAY, consumedMillis: 0, valuationBasis: zero("TWD"), permanent: false, lifecycle: "GRANT", revokedDurationMillis: 0 });
  sub.segments.push({ sourceNo, entitlementCode: v.entitlementCode, startsAt: iso(now), endsAt: iso(now + v.durationDays * DAY) });
  sub.grantOps[v.operationNo] = fp;
  sub.entitlementVersion += 1;
  return sub;
}

export function revoke(storeNo: string, sourceNo: string, v: { operationNo: string; reason: string; expectedVersion: number }) {
  const sub = subscriptionOf(storeNo);
  need(text(v.operationNo, 64) && text(v.reason, 1024), "INVALID_REQUEST");
  expected(v.expectedVersion, sub.entitlementVersion);
  const src = sub.sources.find((x) => x.sourceNo === sourceNo) ?? mallFail("NOT_FOUND");
  // 只能撤销原始价值全零的赠送；付费来源（哪怕过期）必须走退款
  need(src.lifecycle === "GRANT" && src.valuationBasis?.minor === "0", "INVALID_REQUEST");
  const remaining = Math.max(0, (src.durationMillis ?? 0) - (src.consumedMillis ?? 0));
  src.lifecycle = "REVOKED";
  src.revokedDurationMillis = remaining;
  const now = iso(Date.now());
  for (const seg of sub.segments) if (seg.sourceNo === sourceNo && (seg.endsAt ?? "") > now) seg.endsAt = now;
  sub.entitlementVersion += 1;
  return sub;
}

// ── 线下收款 ────────────────────────────────────────────────────────────

export function profileOf(currency: string): MallOfflineCollectionTerms | null {
  return st().profiles[currency]?.terms ?? null;
}

export function configureProfile(v: {
  profileNo: string; expectedVersion: number; platformMerchantNo: string; currency: string; bankCode: string;
  bankAccount: string; accountName: string; paymentWindowVersion: string; paymentWindowMs: number; enabled: boolean; reason: string;
}): MallOfflineCollectionTerms {
  need(/^[A-Z]{3}$/.test(v.currency) && text(v.profileNo, 64) && text(v.platformMerchantNo, 64) && text(v.bankCode, 32)
    && text(v.bankAccount, 128) && text(v.accountName, 128) && text(v.paymentWindowVersion, 64) && text(v.reason, 1024)
    && Number.isInteger(v.paymentWindowMs) && v.paymentWindowMs >= 1000 && v.paymentWindowMs <= 90 * DAY, "OFFLINE_PROFILE_INVALID");
  const s = st();
  const old = s.profiles[v.currency];
  expected(v.expectedVersion, old?.terms.profileVersion ?? 0);
  if (old) need(old.terms.profileNo === v.profileNo, "OFFLINE_PROFILE_INVALID");
  const terms: MallOfflineCollectionTerms = {
    profileNo: v.profileNo, profileVersion: v.expectedVersion + 1, platformMerchantNo: v.platformMerchantNo, currency: v.currency,
    receivingAccountKey: `acct:${v.bankCode.trim().toUpperCase()}:${v.bankAccount.replace(/[\s-]/g, "").slice(-4)}`,
    paymentWindowVersion: v.paymentWindowVersion, paymentWindowMs: v.paymentWindowMs,
    instructions: { bankCode: v.bankCode, bankAccount: v.bankAccount, accountName: v.accountName },
  };
  s.profiles[v.currency] = { terms, enabled: v.enabled };
  return terms;
}

export function pendingClaims(q: { afterOrderNo?: string; afterClaimNo?: string; limit?: number }) {
  const limit = q.limit ?? 20;
  need(limit >= 1 && limit <= 100, "INVALID_REQUEST");
  const rows = st().orders.flatMap((o) => (o.offline?.claims ?? [])
    .filter((c) => c.status === "SUBMITTED" || c.status === "REVIEWING")
    .map((claim) => ({ store: { tenantNo: "MAIN", storeNo: o.storeNo }, orderNo: o.orderNo, claim })))
    .sort((a, b) => a.orderNo.localeCompare(b.orderNo) || a.claim.claimNo.localeCompare(b.claim.claimNo));
  const after = q.afterOrderNo
    ? rows.filter((r) => r.orderNo > q.afterOrderNo! || (r.orderNo === q.afterOrderNo && r.claim.claimNo > (q.afterClaimNo ?? "")))
    : rows;
  return after.slice(0, limit);
}

export function assistedQuote(storeNo: string, v: { lines: { skuVersionNo: string; quantity: number }[]; currency: string }): MallQuoteView {
  const s = st();
  need(s.stores.has(storeNo), "STORE_UNAVAILABLE");
  need(v.lines?.length > 0 && /^[A-Z]{3}$/.test(v.currency), "INVALID_REQUEST");
  const profile = s.profiles[v.currency];
  need(profile && profile.enabled, "OFFLINE_UNAVAILABLE");
  const components: MallQuoteComponent[] = [];
  const seen = new Set<string>();
  for (const line of v.lines) {
    need(!seen.has(line.skuVersionNo), "INVALID_REQUEST");
    seen.add(line.skuVersionNo);
    const sv = s.skuVersions[line.skuVersionNo] ?? mallFail("NOT_FOUND");
    const def = s.skuDefs[sv.draft.skuNo!]!;
    // 只报当前在售、已发布的版本 —— 草稿和停售的报出来就是假价
    need(sv.status === "PUBLISHED" && def.saleStatus === "ON_SALE" && def.currentVersionNo === line.skuVersionNo, "CHECKOUT_UNAVAILABLE");
    need(sv.draft.price!.currency === v.currency, "CHECKOUT_UNAVAILABLE");
    need(Number.isInteger(line.quantity) && line.quantity >= 1 && line.quantity <= (sv.draft.maxPerOrder ?? 1), "INVALID_REQUEST");
    const each = sv.draft.price!;
    const total = (BigInt(each.minor) * BigInt(line.quantity)).toString();
    const comps = sv.draft.components ?? [];
    // 分摊：按权重切成交价，尾差落在最后一个组件上（整数，守恒）
    const weights = comps.map((c) => BigInt(c.gift ? 0 : (c.allocationWeight ?? 0)));
    const wsum = weights.reduce((a, b) => a + b, BigInt(0));
    let left = BigInt(total);
    comps.forEach((c, i) => {
      const share = i === comps.length - 1 || wsum === BigInt(0) ? left : (BigInt(total) * weights[i]!) / wsum;
      left -= share;
      const m = { currency: v.currency, minor: share.toString() };
      components.push({ componentNo: nextNo("QC"), kind: c.kind, listAmount: m, dealAmount: m, creditAmount: zero(v.currency),
        cashAmount: m, cycleDays: c.periodDays ?? 0, quantity: line.quantity * (c.unitCount ?? 1) });
    });
  }
  const total = sum(components.map((c) => c.dealAmount!), v.currency);
  const view: MallQuoteView = { quoteNo: nextNo("Q"), store: { tenantNo: "MAIN", storeNo }, total, credit: zero(v.currency), payable: total,
    components, entitlementVersion: subscriptionOf(storeNo).entitlementVersion, balanceVersion: 1,
    expiresAt: iso(Date.now() + 15 * 60_000), creditBreakdown: null };
  s.quotes[view.quoteNo!] = { view, usedBy: null, currency: v.currency, lines: v.lines };
  return view;
}

export function assistedPlace(storeNo: string, v: { quoteNo: string; idempotencyKey: string; shippingAddress: MallShippingAddress | null }) {
  const s = st();
  need(text(v.idempotencyKey, 64), "INVALID_REQUEST");
  const q = s.quotes[v.quoteNo] ?? mallFail("NOT_FOUND");
  need(q.view.store?.storeNo === storeNo, "NOT_FOUND");
  const replay = s.orders.find((o) => o.storeNo === storeNo && o.idempotencyKey === v.idempotencyKey);
  if (replay) { need(q.usedBy === replay.orderNo, "IDEMPOTENCY_CONFLICT"); return replay; }
  need(!q.usedBy, "QUOTE_USED");
  need(Date.parse(q.view.expiresAt!) > Date.now(), "QUOTE_EXPIRED");
  const hasHardware = (q.view.components ?? []).some((c) => c.kind === "HARDWARE");
  if (hasHardware) need(v.shippingAddress && text(v.shippingAddress.recipient, 64) && text(v.shippingAddress.addressLine, 256), "INVALID_REQUEST");
  // 同门店同时只能有一笔数字变更单在途（后端「唯一占用」）
  if ((q.view.components ?? []).some((c) => c.kind !== "HARDWARE")) {
    need(!s.orders.some((o) => o.storeNo === storeNo && o.status === "AWAITING_PAYMENT" && o.components.some((c) => c.kind !== "HARDWARE")), "DIGITAL_CHANGE_PENDING");
  }
  const orderNo = nextNo("MO");
  const order: MallOrderRow = {
    storeNo, orderNo, status: "AWAITING_PAYMENT", payment: "UNPAID", fulfillment: hasHardware ? "WAIT_INVENTORY" : "PENDING", refund: "NONE",
    payable: q.view.payable!, components: q.view.components!, createdAt: Date.now(), quote: q.view,
    shippingAddress: hasHardware ? v.shippingAddress : null, attempts: [], shipments: [], refunds: [],
    offline: { instructions: null, claims: [], evidence: {}, actions: {} }, idempotencyKey: v.idempotencyKey,
  };
  if (hasHardware) order.fulfillment = "PENDING";
  q.usedBy = orderNo;
  s.orders.push(order);
  return order;
}

function offlineOf(o: MallOrderRow): OfflineState {
  return o.offline ?? mallFail("OFFLINE_INTENT_REQUIRED");
}

export function issueInstructions(storeNo: string, orderNo: string) {
  const o = findOrder(storeNo, orderNo);
  const off = offlineOf(o);
  if (off.instructions) return off.instructions; // 幂等：再发一次还是原来那份
  need(o.payment === "UNPAID" && o.status === "AWAITING_PAYMENT", "OFFLINE_UNAVAILABLE");
  const p = st().profiles[o.payable.currency];
  need(p && p.enabled, "OFFLINE_UNAVAILABLE");
  const now = Date.now();
  off.instructions = {
    orderNo, payable: o.payable, paymentDueAt: iso(now + p!.terms.paymentWindowMs), profileNo: p!.terms.profileNo,
    profileVersion: p!.terms.profileVersion, instructions: { ...p!.terms.instructions }, platformMerchantNo: p!.terms.platformMerchantNo,
  };
  return off.instructions;
}

export function instructionsOf(storeNo: string, orderNo: string) {
  const off = offlineOf(findOrder(storeNo, orderNo));
  return off.instructions ?? mallFail("OFFLINE_INSTRUCTIONS_NOT_ISSUED");
}

export const publicInstructions = (i: NonNullable<OfflineState["instructions"]>): MallOfflineInstructionsView =>
  ({ orderNo: i.orderNo, payable: i.payable, paymentDueAt: i.paymentDueAt, profileNo: i.profileNo, profileVersion: i.profileVersion, instructions: i.instructions });

export function claimsOf(storeNo: string, orderNo: string) {
  return offlineOf(findOrder(storeNo, orderNo)).claims;
}

export function reserveEvidence(storeNo: string, orderNo: string, purpose: string) {
  const off = offlineOf(findOrder(storeNo, orderNo));
  need(["PLATFORM_RECEIPT", "RECONCILIATION", "REFUND_PROOF"].includes(purpose), "INVALID_REQUEST");
  const assetKey = nextNo("EVK");
  off.evidence[assetKey] = { purpose, url: null };
  return { assetKey, purpose };
}

export function storeEvidence(storeNo: string, orderNo: string, assetKey: string, type: string, size: number, url: string) {
  const off = offlineOf(findOrder(storeNo, orderNo));
  const e = off.evidence[assetKey] ?? mallFail("EVIDENCE_FORBIDDEN");
  need(!e.url, "EVIDENCE_ALREADY_STORED");
  need(type === "image/jpeg" || type === "image/png", "INVALID_REQUEST");
  need(size > 0 && size <= 10 * 1024 * 1024, "INVALID_REQUEST");
  e.url = url;
  return { assetKey };
}

export function evidenceUrl(storeNo: string, orderNo: string, assetKey: string) {
  const off = offlineOf(findOrder(storeNo, orderNo));
  const e = off.evidence[assetKey] ?? mallFail("EVIDENCE_FORBIDDEN");
  return { url: e.url ?? mallFail("EVIDENCE_UNAVAILABLE") };
}

function uploaded(off: OfflineState, key: string) {
  need(off.evidence[key], "EVIDENCE_FORBIDDEN");
  need(off.evidence[key]!.url, "EVIDENCE_UNAVAILABLE");
}

export function declineClaim(storeNo: string, orderNo: string, claimNo: string, reason: string) {
  const off = offlineOf(findOrder(storeNo, orderNo));
  need(text(reason, 1024), "INVALID_REQUEST");
  const c = off.claims.find((x) => x.claimNo === claimNo) ?? mallFail("NOT_FOUND");
  need(c.status === "SUBMITTED" || c.status === "REVIEWING", "CLAIM_ALREADY_REVIEWED");
  // 拒绝的只是这份申报：订单、占用、付款窗口都不动，买家可以用新的 claimNo 重报
  c.status = "DECLINED";
  return c;
}

export function confirmPayment(storeNo: string, orderNo: string, v: {
  actionNo: string; claimNo: string | null; actualAmount: MallMoney; actualPaidAt: string; actualReceiver: string;
  bankReference: string; evidenceKey: string; historical: boolean; reason: string;
}) {
  const s = st();
  const o = findOrder(storeNo, orderNo);
  const off = offlineOf(o);
  need(text(v.actionNo, 64) && text(v.bankReference, 128) && text(v.reason, 1024) && text(v.actualReceiver, 64), "INVALID_REQUEST");
  const amountMinor = String(v.actualAmount?.minor ?? "");
  need(/^[A-Z]{3}$/.test(v.actualAmount?.currency ?? "") && /^(0|[1-9][0-9]{0,18})$/.test(amountMinor), "INVALID_REQUEST");
  const fp = JSON.stringify([v.claimNo, v.actualAmount.currency, amountMinor, v.actualPaidAt, v.actualReceiver, v.bankReference, v.evidenceKey, v.historical]);
  const prior = off.actions[v.actionNo];
  if (prior) { need(prior.fingerprint === fp, "IDEMPOTENCY_CONFLICT"); return { actionNo: v.actionNo, status: prior.status, paymentNo: prior.paymentNo }; }
  const ins = off.instructions ?? mallFail("OFFLINE_INSTRUCTIONS_NOT_ISSUED");
  uploaded(off, v.evidenceKey);
  const paidAt = Date.parse(v.actualPaidAt);
  need(Number.isFinite(paidAt) && paidAt <= Date.now(), "INVALID_REQUEST");
  // 早于下单的到账只能以「历史补录」口径录入 —— 否则一笔旧流水会被当成这张单的款
  if (paidAt < o.createdAt) need(v.historical, "OFFLINE_HISTORICAL_MODE_REQUIRED");
  need(v.actualReceiver === ins.platformMerchantNo, "OFFLINE_RECEIVER_MISMATCH");
  need(!s.bankRefs.has(v.bankReference), "OFFLINE_BANK_REFERENCE_CONFLICT");
  let claim: MallOfflineClaimView | undefined;
  if (v.claimNo) {
    claim = off.claims.find((c) => c.claimNo === v.claimNo) ?? mallFail("NOT_FOUND");
    need(claim.status === "SUBMITTED" || claim.status === "REVIEWING", "CLAIM_ALREADY_REVIEWED");
  }
  const matches = o.payment === "UNPAID" && o.status === "AWAITING_PAYMENT"
    && v.actualAmount.currency === o.payable.currency && amountMinor === o.payable.minor;
  if (o.payment === "PAID" && matches) mallFail("OFFLINE_ALREADY_PAID");
  s.bankRefs.add(v.bankReference);
  const attemptNo = nextNo("OA");
  if (matches) {
    const paymentNo = nextNo("P");
    o.attempts.push({ attemptNo, channel: "OFFLINE", payMethod: "BANK_TRANSFER", amount: { currency: v.actualAmount.currency, minor: amountMinor },
      status: "SUCCEEDED", paymentNo, paidAt: iso(paidAt), errorCode: null, paidAtPrecisionMs: 1 });
    o.payment = "PAID";
    // 数字权益在到账确认后才发；含硬件的单进入待发货
    o.fulfillment = digitalOnly(o) ? "COMPLETED" : "PENDING";
    o.status = digitalOnly(o) ? "COMPLETED" : "FULFILLING";
    if (claim) claim.status = "CONFIRMED";
    off.actions[v.actionNo] = { fingerprint: fp, status: "APPLIED", paymentNo };
    return { actionNo: v.actionNo, status: "APPLIED" as const, paymentNo };
  }
  // 金额不符 / 已作废后的迟到款：真实收到的钱照记，但只记 MALL_UNMATCHED，不发权益、不补差
  const paymentNo = nextNo("U");
  o.attempts.push({ attemptNo, channel: "OFFLINE", payMethod: "BANK_TRANSFER", amount: { currency: v.actualAmount.currency, minor: amountMinor },
    status: "SUCCEEDED", paymentNo, paidAt: iso(paidAt), errorCode: "MALL_UNMATCHED", paidAtPrecisionMs: 1 });
  if (claim) claim.status = "MISMATCHED";
  off.actions[v.actionNo] = { fingerprint: fp, status: "UNMATCHED", paymentNo };
  return { actionNo: v.actionNo, status: "UNMATCHED" as const, paymentNo };
}

export const unmatchedPaymentNos = (o: MallOrderRow) =>
  o.attempts.filter((a) => a.errorCode === "MALL_UNMATCHED" && a.paymentNo).map((a) => a.paymentNo!);

export function voidOrder(storeNo: string, orderNo: string, v: {
  actionNo: string; reconciliationReference: string; reason: string; evidenceKey: string; unmatchedDispositions: Record<string, string>;
}) {
  const o = findOrder(storeNo, orderNo);
  const off = offlineOf(o);
  need(text(v.actionNo, 64) && text(v.reconciliationReference, 128) && text(v.reason, 1024) && v.unmatchedDispositions, "INVALID_REQUEST");
  const fp = JSON.stringify(["VOID", v.reconciliationReference, v.evidenceKey, v.unmatchedDispositions]);
  const prior = off.actions[v.actionNo];
  if (prior) { need(prior.fingerprint === fp, "OFFLINE_RECONCILIATION_CONFLICT"); return { actionNo: v.actionNo, status: prior.status, paymentNo: prior.paymentNo }; }
  need(off.instructions, "OFFLINE_INTENT_REQUIRED");
  uploaded(off, v.evidenceKey);
  need(o.payment !== "PAID", "OFFLINE_ALREADY_PAID");
  need(o.payment !== "CLOSED", "OFFLINE_RECONCILIATION_CONFLICT");
  const unmatched = unmatchedPaymentNos(o);
  const keys = Object.keys(v.unmatchedDispositions);
  need(keys.length === unmatched.length && unmatched.every((p) => keys.includes(p)), "OFFLINE_UNMATCHED_DISPOSITION_REQUIRED");
  for (const k of keys) need(text(v.unmatchedDispositions[k], 1024), "OFFLINE_UNMATCHED_DISPOSITION_REQUIRED");
  o.payment = "CLOSED";
  o.status = "CLOSED";
  off.actions[v.actionNo] = { fingerprint: fp, status: "APPLIED", paymentNo: null };
  return { actionNo: v.actionNo, status: "APPLIED" as const, paymentNo: null };
}

export function payout(storeNo: string, orderNo: string, refundNo: string, v: {
  actualAmount: MallMoney; paidOutAt: string; payeeReference: string; bankReference: string; evidenceKey: string;
}) {
  const s = st();
  const o = findOrder(storeNo, orderNo);
  const off = offlineOf(o);
  const r = refundOf(o, refundNo);
  need(text(v.payeeReference, 128) && text(v.bankReference, 128), "INVALID_REQUEST");
  need(r.reviewStatus === "APPROVED" && r.status === "PENDING", "REFUND_UNAVAILABLE");
  uploaded(off, v.evidenceKey);
  const at = Date.parse(v.paidOutAt);
  need(Number.isFinite(at) && at <= Date.now(), "INVALID_REQUEST");
  need(v.actualAmount.currency === r.cashAmount!.currency && String(v.actualAmount.minor) === r.cashAmount!.minor, "OFFLINE_PAYOUT_AMOUNT_MISMATCH");
  need(!s.bankRefs.has(v.bankReference), "OFFLINE_BANK_REFERENCE_CONFLICT");
  s.bankRefs.add(v.bankReference);
  r.status = "SUCCEEDED";
  const refunded = o.refunds.filter((x) => x.status === "SUCCEEDED").reduce((a, x) => a + BigInt(x.cashAmount!.minor), BigInt(0));
  const full = refunded >= BigInt(o.payable.minor);
  o.refund = full ? "REFUNDED" : "PARTIAL";
  o.status = full ? "REFUNDED" : "PARTIALLY_REFUNDED";
  return publicRefund(r);
}
