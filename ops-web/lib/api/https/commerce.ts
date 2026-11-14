// 覆盖范围：套餐商城（commerce）。端点逐字对应 docs/api/commerce-mall-openapi.json（task8-v1）。
//
// **不回落 mock**：接口没装配（开关关着 → 404）、没权限（403）、后端拒绝（10409 等）一律原样抛 ApiError，
// 页面显示错误态。这批接口管的是钱和权益，「看起来成功了」比报错坏得多。
import { client } from "../http-client";
import type { CommerceApi } from "../contracts/commerce";
import type {
  MallBusinessEndView, MallBusinessTypeView, MallCatalogOverview, MallCategoryView, MallDeploymentOption, MallDraftSaveResult,
  MallOfflineActionView, MallOfflineClaimView, MallOfflineCollectionTerms, MallOfflineCollectionProfile, MallOfflineEvidenceTicket,
  MallOfflineEvidenceUploaded, MallOfflineInstructionsView, MallOfflinePendingClaim, MallOfflineSignedRead,
  MallOrderDetailView, MallOrderSummary, MallOrderView, MallPlanDefinitionView, MallProductView, MallPublicationResult,
  MallQuoteView, MallRefundReviewQuoteView, MallRefundView, MallSkuVersionView, MallSubscriptionView,
} from "@/lib/types";

const seg = encodeURIComponent;

export const commerceHttp: CommerceApi = {
  platformMallOrders: (filter) => client.get("/ops/platform-mall/orders", filter),
  platformMallClaims: (filter) => client.get("/ops/platform-mall/claims", filter),
  platformMallCash: (filter) => client.get("/ops/platform-mall/cash", filter),
  mallCashReceipts: (store,order) => client.get(`/ops/biz-mall/offline/orders/${encodeURIComponent(order)}/cash-receipts`, {storeNo:store}),
  mallCashRefunds: (store,order) => client.get(`/ops/biz-mall/offline/orders/${encodeURIComponent(order)}/cash-refunds`, {storeNo:store}),
  requestMallCashRefund: (store,order,v) => client.post(`/ops/biz-mall/offline/orders/${encodeURIComponent(order)}/cash-refunds`,v, {storeNo:store}),
  payoutMallCashRefund: (store,order,refund,v) => client.post(`/ops/biz-mall/offline/orders/${encodeURIComponent(order)}/cash-refunds/${encodeURIComponent(refund)}/payout`,v, {storeNo:store}),
  recoverMallCashRefund: (store,order,refund) => client.post(`/ops/biz-mall/offline/orders/${encodeURIComponent(order)}/cash-refunds/${encodeURIComponent(refund)}/recover`,{}, {storeNo:store}),

  // ── 目录 ──
  mallCatalog: () => client.get<MallCatalogOverview>("/ops/biz-mall/catalog"),
  mallBusinessTypes: () => client.get<MallBusinessTypeView[]>("/ops/business-types"),
  createMallBusinessType: (v) => client.post<MallDraftSaveResult>("/ops/business-types", v),
  updateMallBusinessType: (typeCode, v) => client.put<MallDraftSaveResult>(`/ops/business-types/${seg(typeCode)}`, v),
  mallBusinessEnds: () => client.get<MallBusinessEndView[]>("/ops/business-ends"),
  mallDeploymentOptions: () => client.get<MallDeploymentOption[]>("/ops/business-ends/deployment-options"),
  saveMallBusinessEnd: (v) => client.post<MallDraftSaveResult>("/ops/business-ends", v),
  mallPlanDefinitions: () => client.get<MallPlanDefinitionView[]>("/ops/industry-plans"),
  saveMallPlanVersion: (planNo, v) => client.post<MallDraftSaveResult>(`/ops/industry-plans/${seg(planNo)}/versions`, v),
  publishMallIndustry: (typeCode, v) =>
    client.post<MallPublicationResult>(`/ops/business-types/${seg(typeCode)}/plan-publications`, v),
  mallCategories: () => client.get<MallCategoryView[]>("/ops/biz-mall/categories"),
  createMallCategory: (v) => client.post<MallDraftSaveResult>("/ops/biz-mall/categories", v),
  updateMallCategory: (code, v) => client.put<MallDraftSaveResult>(`/ops/biz-mall/categories/${seg(code)}`, v),
  mallProducts: () => client.get<MallProductView[]>("/ops/biz-mall/products"),
  createMallProduct: (v) => client.post<MallDraftSaveResult>("/ops/biz-mall/products", v),
  updateMallProduct: (productNo, v) => client.put<MallDraftSaveResult>(`/ops/biz-mall/products/${seg(productNo)}`, v),
  saveMallSkuVersion: (productNo, v) => client.post<MallDraftSaveResult>(`/ops/biz-mall/products/${seg(productNo)}/skus`, v),
  mallSkuVersion: (versionNo) => client.get<MallSkuVersionView>(`/ops/biz-mall/sku-versions/${seg(versionNo)}`),
  publishMallSkuVersion: (versionNo, v) =>
    client.post<MallPublicationResult>(`/ops/biz-mall/sku-versions/${seg(versionNo)}/publish`, v),
  stopMallPlanSale: (planNo, v) => client.post<MallDraftSaveResult>(`/ops/industry-plans/${seg(planNo)}/off-sale`, v),
  stopMallSkuSale: (skuNo, v) => client.post<MallDraftSaveResult>(`/ops/biz-mall/skus/${seg(skuNo)}/off-sale`, v),

  // ── 订单 ──
  mallOrders: (storeNo, q) => client.get<MallOrderSummary[]>("/ops/biz-mall/orders", { storeNo, ...q }),
  mallOrder: (storeNo, orderNo) => client.get<MallOrderDetailView>(`/ops/biz-mall/orders/${seg(orderNo)}`, { storeNo }),
  shipMallOrder: (storeNo, orderNo, v) =>
    client.post<MallOrderDetailView>(`/ops/biz-mall/orders/${seg(orderNo)}/shipments`, v, { storeNo }),
  reviewMallRefund: (storeNo, orderNo, refundNo, v) =>
    client.post<MallRefundReviewQuoteView>(`/ops/biz-mall/orders/${seg(orderNo)}/refunds/${seg(refundNo)}/review-quotes`, v, { storeNo }),
  approveMallRefund: (storeNo, orderNo, refundNo, v) =>
    client.post<MallRefundView>(`/ops/biz-mall/orders/${seg(orderNo)}/refunds/${seg(refundNo)}/approve`, v, { storeNo }),
  rejectMallRefund: (storeNo, orderNo, refundNo, v) =>
    client.post<MallRefundView>(`/ops/biz-mall/orders/${seg(orderNo)}/refunds/${seg(refundNo)}/reject`, v, { storeNo }),

  // ── 权益来源 ──
  mallSubscription: (storeNo, entitlementCode) =>
    client.get<MallSubscriptionView>(`/ops/stores/${seg(storeNo)}/subscription`, { entitlementCode }),
  grantMallEnd: (storeNo, v) => client.post<MallSubscriptionView>(`/ops/stores/${seg(storeNo)}/subscription/grants`, v),
  revokeMallSource: (storeNo, sourceNo, v) =>
    client.post<MallSubscriptionView>(`/ops/stores/${seg(storeNo)}/subscription/sources/${seg(sourceNo)}/revoke`, v),

  // ── 线下银行转账 ──
  offlineProfile: (currency) => client.get<MallOfflineCollectionProfile | null>("/ops/biz-mall/offline/profile", { currency }),
  configureOfflineProfile: (v) => client.put<MallOfflineCollectionTerms>("/ops/biz-mall/offline/profile", v),
  offlinePendingClaims: (q) => client.get<MallOfflinePendingClaim[]>("/ops/biz-mall/offline/claims", q),
  offlineAssistedQuote: (storeNo, v) => client.post<MallQuoteView>("/ops/biz-mall/offline/quotes", v, { storeNo }),
  offlineAssistedPlace: (storeNo, v) => client.post<MallOrderView>("/ops/biz-mall/offline/orders", v, { storeNo }),
  issueOfflineInstructions: (storeNo, orderNo) =>
    client.post<MallOfflineInstructionsView>(`/ops/biz-mall/offline/orders/${seg(orderNo)}/instructions`, {}, { storeNo }),
  offlineInstructions: (storeNo, orderNo) =>
    client.get<MallOfflineInstructionsView>(`/ops/biz-mall/offline/orders/${seg(orderNo)}/instructions`, { storeNo }),
  offlineClaims: (storeNo, orderNo) =>
    client.get<MallOfflineClaimView[]>(`/ops/biz-mall/offline/orders/${seg(orderNo)}/claims`, { storeNo }),
  reserveOfflineEvidence: (storeNo, orderNo, purpose) =>
    client.post<MallOfflineEvidenceTicket>(`/ops/biz-mall/offline/orders/${seg(orderNo)}/evidence`, { purpose }, { storeNo }),
  uploadOfflineEvidence: (storeNo, orderNo, assetKey, file) =>
    client.post<MallOfflineEvidenceUploaded>(`/ops/biz-mall/offline/orders/${seg(orderNo)}/evidence/content`, file, { storeNo, assetKey }),
  offlineEvidenceUrl: (storeNo, orderNo, assetKey) =>
    client.get<MallOfflineSignedRead>(`/ops/biz-mall/offline/orders/${seg(orderNo)}/evidence/url`, { storeNo, assetKey }),
  declineOfflineClaim: (storeNo, orderNo, claimNo, reason) =>
    client.post<MallOfflineClaimView>(`/ops/biz-mall/offline/orders/${seg(orderNo)}/claims/${seg(claimNo)}/decline`, { reason }, { storeNo }),
  offlineAction: (storeNo, orderNo, actionNo) =>
    client.get<MallOfflineActionView | null>(`/ops/biz-mall/offline/orders/${seg(orderNo)}/actions/${seg(actionNo)}`, { storeNo }),
  confirmOfflinePayment: (storeNo, orderNo, v) =>
    client.post<MallOfflineActionView>(`/ops/biz-mall/offline/orders/${seg(orderNo)}/confirmations`, v, { storeNo }),
  voidOfflineOrder: (storeNo, orderNo, v) =>
    client.post<MallOfflineActionView>(`/ops/biz-mall/offline/orders/${seg(orderNo)}/voids`, v, { storeNo }),
  recoverOfflineOrder: (storeNo, orderNo) =>
    client.post(`/ops/biz-mall/offline/orders/${seg(orderNo)}/recover`, {}, {storeNo}),
  confirmOfflineRefundPayout: (storeNo, orderNo, refundNo, v) =>
    client.post<MallRefundView>(`/ops/biz-mall/offline/orders/${seg(orderNo)}/refunds/${seg(refundNo)}/payouts`, v, { storeNo }),
};
