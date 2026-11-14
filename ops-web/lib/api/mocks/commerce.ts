import { ApiError } from "../error";
// 覆盖范围：套餐商城（commerce）。规则全部在 lib/mock/db/commerce.ts 强制，这里只做形状转换与延迟。
import * as m from "@/lib/mock/db/commerce";
import type { CommerceApi } from "../contracts/commerce";
import type {
  MallBusinessTypeView, MallCatalogOverview, MallOrderDetailView, MallOrderSummary, MallSubscriptionView,
} from "@/lib/types";
import { wait } from "./_wait";

const clone = <T>(v: T): T => structuredClone(v);

function catalog(): MallCatalogOverview {
  const s = m.mall.s;
  return clone({
    businessTypes: Object.values(s.types).map((t) => ({ draft: t.draft, version: t.version, catalogVersion: t.catalogVersion })),
    businessEnds: Object.values(s.ends).map((e) => ({ draft: e.draft, version: e.version })),
    planDefinitions: Object.values(s.planDefs),
    planVersions: Object.values(s.planVersions),
    products: Object.values(s.products),
    skuDefinitions: Object.values(s.skuDefs),
    skuVersions: Object.values(s.skuVersions),
    categories: m.categoryList(),
  });
}

function summary(o: m.MallOrderRow): MallOrderSummary {
  return clone({
    order: { orderNo: o.orderNo, store: { tenantNo: "MAIN", storeNo: o.storeNo }, status: o.status, payable: o.payable,
      components: o.components, createdAt: new Date(o.createdAt).toISOString() },
    paymentStatus: o.payment, fulfillmentStatus: o.fulfillment, refundStatus: o.refund,
  });
}

function detail(o: m.MallOrderRow): MallOrderDetailView {
  return clone({
    summary: summary(o), quote: o.quote, shippingAddress: o.shippingAddress, paymentAttempts: o.attempts,
    shipments: o.shipments.map(({ fingerprint: _f, ...x }) => x), refunds: o.refunds.map(m.publicRefund),
  });
}

function sub(storeNo: string, s: ReturnType<typeof m.subscriptionOf>, entitlementCode?: string): MallSubscriptionView {
  const keep = (code?: string) => !entitlementCode || code === entitlementCode;
  // All seed stores belong to RETAIL/TWD. Derive the current bound Plan from seed sources,
  // rather than treating the newest FREE catalog version as an existing store's subscription.
  const now = Date.now();
  const activeSourceNos = new Set(s.segments.filter((x) => Date.parse(x.startsAt!) <= now && (!x.endsAt || now < Date.parse(x.endsAt))).map((x) => x.sourceNo));
  const current = s.sources.find((x) => x.planVersionNo && !x.permanent && x.lifecycle !== "REVOKED" && activeSourceNos.has(x.sourceNo))
    ?? s.sources.find((x) => x.planVersionNo && x.permanent && x.lifecycle !== "REVOKED");
  return clone({
    store: { tenantNo: "MAIN", storeNo }, entitlementVersion: s.entitlementVersion,
    businessTypeCode: "RETAIL", currency: current?.valuationBasis?.currency ?? "TWD", serverNow: now,
    currentPlanVersionNo: current?.planVersionNo ?? m.mall.s.planDefs["RETAIL:FREE"]?.currentVersionNo ?? undefined,
    sources: s.sources.filter((x) => keep(x.entitlementCode)), segments: s.segments.filter((x) => keep(x.entitlementCode)),
  });
}

/** 把 Blob 落成可回读的地址：浏览器里是 object URL，Node（单测）里退到 data URL */
async function blobUrl(file: Blob): Promise<string> {
  if (typeof URL.createObjectURL === "function" && typeof window !== "undefined") return URL.createObjectURL(file);
  const buf = Buffer.from(await file.arrayBuffer());
  return `data:${file.type};base64,${buf.toString("base64")}`;
}

export const commerceMock: CommerceApi = {
  platformMallOrders: async () => { throw new Error("Platform order reporting requires the real backend"); },
  platformMallClaims: async () => { throw new Error("Platform verification reporting requires the real backend"); },
  platformMallCash: async () => { throw new Error("Platform cash reporting requires the real backend"); },
  mallCashReceipts: async () => { throw new ApiError(10400,"Offline cash refunds require a real bank ledger"); },
  mallCashRefunds: async () => { throw new ApiError(10400,"Offline cash refunds require a real bank ledger"); },
  requestMallCashRefund: async () => { throw new ApiError(10400,"Offline cash refunds require a real bank ledger"); },
  payoutMallCashRefund: async () => { throw new ApiError(10400,"Offline cash refunds require a real bank ledger"); },
  recoverMallCashRefund: async () => { throw new ApiError(10400,"Offline cash refunds require a real bank ledger"); },

  mallCatalog: async () => wait(catalog()),
  mallBusinessTypes: async () => wait(catalog().businessTypes as MallBusinessTypeView[]),
  createMallBusinessType: async (v) => wait(m.createType(v), 350),
  updateMallBusinessType: async (code, v) => wait(m.updateType(code, v), 350),
  mallBusinessEnds: async () => wait(catalog().businessEnds ?? []),
  mallDeploymentOptions: async () => wait(clone(m.mall.s.deployments)),
  saveMallBusinessEnd: async (v) => wait(m.saveEnd(v), 350),
  mallPlanDefinitions: async () => wait(catalog().planDefinitions ?? []),
  saveMallPlanVersion: async (planNo, v) => wait(m.savePlanVersion(planNo, v), 350),
  publishMallIndustry: async (type, v) => wait(clone(m.publishIndustry(type, v)), 400),
  mallCategories: async () => wait(clone(m.categoryList())),
  createMallCategory: async (v) => wait(m.saveCategory(v, true), 350),
  updateMallCategory: async (code, v) => wait(m.saveCategory({ ...v, categoryCode: code }, false), 350),
  mallProducts: async () => wait(catalog().products ?? []),
  createMallProduct: async (v) => wait(m.saveProduct(v, true), 350),
  updateMallProduct: async (no, v) => wait(m.saveProduct({ ...v, productNo: no }, false), 350),
  saveMallSkuVersion: async (no, v) => wait(m.saveSkuVersion(no, v), 350),
  mallSkuVersion: async (no) => {
    const v = m.mall.s.skuVersions[no];
    if (!v) m.mallFail("NOT_FOUND");
    return wait(clone(v));
  },
  publishMallSkuVersion: async (no, v) => wait(clone(m.publishSku(no, v)), 400),
  stopMallPlanSale: async (no, v) => wait(clone(m.stopSale("PLAN", no, v)), 400),
  stopMallSkuSale: async (no, v) => wait(clone(m.stopSale("SKU", no, v)), 400),

  mallOrders: async (storeNo, q) => {
    if (!m.mall.s.stores.has(storeNo)) m.mallFail("STORE_UNAVAILABLE");
    const limit = q?.limit ?? 20;
    if (limit < 1 || limit > 100) m.mallFail("INVALID_REQUEST");
    const rows = m.mall.s.orders.filter((o) => o.storeNo === storeNo)
      .sort((a, b) => a.orderNo.localeCompare(b.orderNo))
      .filter((o) => !q?.afterOrderNo || o.orderNo > q.afterOrderNo)
      .slice(0, limit);
    return wait(rows.map(summary));
  },
  mallOrder: async (storeNo, no) => wait(detail(m.findOrder(storeNo, no))),
  shipMallOrder: async (storeNo, no, v) => wait(detail(m.ship(storeNo, no, v)), 400),
  reviewMallRefund: async (storeNo, no, r, v) => wait(clone(m.reviewRefund(storeNo, no, r, v)), 350),
  approveMallRefund: async (storeNo, no, r, v) => wait(m.approveRefund(storeNo, no, r, v), 400),
  rejectMallRefund: async (storeNo, no, r, v) => wait(m.rejectRefund(storeNo, no, r, v), 400),

  mallSubscription: async (storeNo, code) => wait(sub(storeNo, m.subscriptionOf(storeNo), code)),
  grantMallEnd: async (storeNo, v) => wait(sub(storeNo, m.grant(storeNo, v)), 400),
  revokeMallSource: async (storeNo, no, v) => wait(sub(storeNo, m.revoke(storeNo, no, v)), 400),

  offlineProfile: async (currency) => {
    const p = m.mall.s.profiles[currency];
    return wait(p ? clone({ ...p.terms, enabled: p.enabled }) : null);
  },
  configureOfflineProfile: async (v) => wait(clone(m.configureProfile(v)), 350),
  offlinePendingClaims: async (q) => wait(clone(m.pendingClaims(q ?? {}))),
  offlineAssistedQuote: async (storeNo, v) => wait(clone(m.assistedQuote(storeNo, v)), 350),
  offlineAssistedPlace: async (storeNo, v) => wait(summary(m.assistedPlace(storeNo, v)).order!, 400),
  issueOfflineInstructions: async (storeNo, no) => wait(clone(m.publicInstructions(m.issueInstructions(storeNo, no))), 350),
  offlineInstructions: async (storeNo, no) => wait(clone(m.publicInstructions(m.instructionsOf(storeNo, no)))),
  offlineClaims: async (storeNo, no) => wait(clone(m.claimsOf(storeNo, no))),
  reserveOfflineEvidence: async (storeNo, no, purpose) => wait(m.reserveEvidence(storeNo, no, purpose), 200),
  uploadOfflineEvidence: async (storeNo, no, key, file) => {
    // 先校验再落字节：键不存在/已上传/类型不对都要在读文件之前拒掉
    m.findOrder(storeNo, no);
    const url = await blobUrl(file);
    return wait(m.storeEvidence(storeNo, no, key, file.type, file.size, url), 400);
  },
  offlineEvidenceUrl: async (storeNo, no, key) => wait(m.evidenceUrl(storeNo, no, key)),
  declineOfflineClaim: async (storeNo, no, claimNo, reason) => wait(clone(m.declineClaim(storeNo, no, claimNo, reason)), 400),
  offlineAction: async (storeNo, no, actionNo) => wait(clone(m.actionOf(storeNo, no, actionNo))),
  confirmOfflinePayment: async (storeNo, no, v) => wait(m.confirmPayment(storeNo, no, v), 400),
  voidOfflineOrder: async (storeNo, no, v) => wait(m.voidOrder(storeNo, no, v), 400),
  recoverOfflineOrder: async () => { throw new ApiError(10400,"Offline order recovery requires a real bank ledger"); },
  confirmOfflineRefundPayout: async (storeNo, no, r, v) => wait(m.payout(storeNo, no, r, v), 400),
};
