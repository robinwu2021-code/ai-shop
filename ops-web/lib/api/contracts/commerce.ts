import type {CashRefundApi} from "./offline-cash";
// 覆盖范围：套餐商城（commerce）—— 目录、商城订单、门店权益来源、线下银行转账核款。
//
// 端点与字段逐字对应 docs/api/commerce-mall-openapi.json（task8-v1，已冻结）。
// 后端业务错误的 msg 是**机器原因码**（VERSION_CONFLICT、OFFLINE_RECEIVER_MISMATCH…），
// 页面经 app/mall/shared.tsx 的 reasonText 翻成人话；mock 抛同样的原因码，两边同构。
//
// ⚠️ 门店维度的接口都要显式 storeNo（后端从查询参数取，再经 facade 校验），
// 不从登录态推断 —— 运营不属于任何门店。
import type {
  MallApproveRequest, MallBusinessEndView, MallBusinessTypeDraft, MallBusinessTypeView, MallCatalogOverview,
  MallCategoryDraft, MallCategoryUpdate, MallCategoryView,
  MallDeploymentOption, MallDraftSaveResult, MallEndRequest, MallEvidencePurpose, MallGrantRequest,
  MallIndustryPublish, MallOfflineActionView, MallOfflineClaimView, MallOfflineCollectionTerms, MallOfflineCollectionProfile,
  MallOfflineConfirmRequest, MallOfflineEvidenceTicket, MallOfflineEvidenceUploaded, MallOfflineInstructionsView,
  MallOfflinePayoutRequest, MallOfflinePendingClaim, MallOfflineProfileCommand, MallOfflineSignedRead,
  MallOfflineVoidRequest, MallOrderDetailView, MallOrderSummary, MallOrderView, MallPlaceRequest, MallPlanDefinitionView,
  MallPlanRequest, MallProductDraft, MallProductUpdate, MallProductView, MallPublicationResult, MallPublishSku,
  MallQuoteRequest, MallQuoteView, MallRefundReviewQuoteView, MallRefundView, MallRejectRequest,
  MallRevokeRequest, MallShipmentRequest, MallSkuRequest, MallSkuVersionView, MallStop, MallSubscriptionView,
  MallTypeUpdate,
} from "@/lib/types";

/** 线下待审队列的游标：按 (orderNo, claimNo) 翻页 */
export type OfflineClaimCursor = { afterOrderNo?: string; afterClaimNo?: string; limit?: number };

export interface CommerceApi extends Pick<CashRefundApi, "mallCashReceipts"|"mallCashRefunds"|"requestMallCashRefund"|"payoutMallCashRefund"|"recoverMallCashRefund"> {
  // ── 目录（读 commerce:catalog:read；写 :edit；发布/停售/启用 :publish）──
  /** 目录总览。空目录也返回结构（各数组为空），不是 404 */
  mallCatalog(): Promise<MallCatalogOverview>;
  /** 经营类型列表 */
  mallBusinessTypes(): Promise<MallBusinessTypeView[]>;
  /** 新建经营类型：expectedVersion=0、enabled=false；后端同时生成 FREE/PRO/CHAIN 三条 Plan 定义 */
  createMallBusinessType(v: MallBusinessTypeDraft): Promise<MallDraftSaveResult>;
  /** 改经营类型；切换 enabled 属于发布权，且必须先发布过行业目录 */
  updateMallBusinessType(typeCode: string, v: MallTypeUpdate): Promise<MallDraftSaveResult>;
  /** 业务端列表 */
  mallBusinessEnds(): Promise<MallBusinessEndView[]>;
  /** 部署验收清单（运营从这里选端，**不手填** client/route/acceptance） */
  mallDeploymentOptions(): Promise<MallDeploymentOption[]>;
  /** 保存业务端；VERIFIED 必须带 deploymentNo */
  saveMallBusinessEnd(v: MallEndRequest): Promise<MallDraftSaveResult>;
  /** Plan 定义（每个经营类型三条） */
  mallPlanDefinitions(): Promise<MallPlanDefinitionView[]>;
  /** 新建/编辑 Plan 草稿版本；已发布版本只读（IMMUTABLE_VERSION） */
  saveMallPlanVersion(planNo: string, v: MallPlanRequest): Promise<MallDraftSaveResult>;
  /** 行业发布：三档 + 候选 SKU 一起冻结 */
  publishMallIndustry(typeCode: string, v: MallIndustryPublish): Promise<MallPublicationResult>;
  /** 商品分类（扁平，按 level、sortOrder、编码） */
  mallCategories(): Promise<MallCategoryView[]>;
  /** 新建分类（expectedVersion=0） */
  createMallCategory(v: MallCategoryDraft): Promise<MallDraftSaveResult>;
  /** 改分类：上级 / 名称 / 图标 / 排序 / 启停 */
  updateMallCategory(categoryCode: string, v: MallCategoryUpdate): Promise<MallDraftSaveResult>;
  /** 商品列表 */
  mallProducts(): Promise<MallProductView[]>;
  /** 新建商品（expectedVersion=0） */
  createMallProduct(v: MallProductDraft): Promise<MallDraftSaveResult>;
  /** 改商品 */
  updateMallProduct(productNo: string, v: MallProductUpdate): Promise<MallDraftSaveResult>;
  /** 新建/编辑 SKU 草稿版本 */
  saveMallSkuVersion(productNo: string, v: MallSkuRequest): Promise<MallDraftSaveResult>;
  /** 读一个 SKU 版本 */
  mallSkuVersion(versionNo: string): Promise<MallSkuVersionView>;
  /** 发布单个 SKU 版本 */
  publishMallSkuVersion(versionNo: string, v: MallPublishSku): Promise<MallPublicationResult>;
  /** Plan 停售（不改写已落单的快照） */
  stopMallPlanSale(planNo: string, v: MallStop): Promise<MallDraftSaveResult>;
  /** SKU 停售（不改写已落单的快照） */
  stopMallSkuSale(skuNo: string, v: MallStop): Promise<MallDraftSaveResult>;

  // ── 商城订单（commerce:mall:order:read / :shipment:write / commerce:refund:review）──
  /** 某门店的订单，按 orderNo 游标翻页 */
  mallOrders(storeNo: string, q?: { afterOrderNo?: string; limit?: number }): Promise<MallOrderSummary[]>;
  /** 订单详情：paymentStatus / fulfillmentStatus / refundStatus 三维独立 */
  mallOrder(storeNo: string, orderNo: string): Promise<MallOrderDetailView>;
  /** 硬件发货（operationNo 幂等，组件/数量/承运/单号不可变） */
  shipMallOrder(storeNo: string, orderNo: string, v: MallShipmentRequest): Promise<MallOrderDetailView>;
  /** 退款审核报价：基于买家原请求摘要生成独立的 reviewQuoteNo */
  reviewMallRefund(storeNo: string, orderNo: string, refundNo: string, v: { requestDigest: string; reviewQuoteNo: string | null }): Promise<MallRefundReviewQuoteView>;
  /** 批准退款：绑定 reviewQuoteNo + approvalDigest，金额不超预览上限 */
  approveMallRefund(storeNo: string, orderNo: string, refundNo: string, v: MallApproveRequest): Promise<MallRefundView>;
  /** 拒绝退款：只改变审核请求；已发出/未知的退款不能靠拒绝解冻 */
  rejectMallRefund(storeNo: string, orderNo: string, refundNo: string, v: MallRejectRequest): Promise<MallRefundView>;

  // ── 门店权益来源（commerce:subscription:read / :grant / :revoke）──
  /** 门店权益来源与可访问时段 */
  mallSubscription(storeNo: string, entitlementCode?: string): Promise<MallSubscriptionView>;
  /** 零金额独立端赠送（有期限；FREE 受保护；不提供假完整 Plan 赠送） */
  grantMallEnd(storeNo: string, v: MallGrantRequest): Promise<MallSubscriptionView>;
  /** 撤销零金额来源（付费来源必须走退款） */
  revokeMallSource(storeNo: string, sourceNo: string, v: MallRevokeRequest): Promise<MallSubscriptionView>;

  // ── 线下银行转账（Task8）──
  /** 收款档案（按币种）；从未配置返回 null */
  offlineProfile(currency: string, collectionMethod?: "BANK_TRANSFER" | "SALES_COLLECTION"): Promise<MallOfflineCollectionProfile | null>;
  offlineCollectors(storeNo: string): Promise<Array<{ collectorNo: string; displayName: string }>>;
  /** 配置收款档案：每次修改生成新版本，已发出的指引按原版本 */
  configureOfflineProfile(v: MallOfflineProfileCommand): Promise<MallOfflineCollectionTerms>;
  /** 待审申报队列 */
  offlinePendingClaims(q?: OfflineClaimCursor): Promise<MallOfflinePendingClaim[]>;
  /** 代客报价：买方为 STORE，发起人为真实运营 */
  offlineAssistedQuote(storeNo: string, v: MallQuoteRequest): Promise<MallQuoteView>;
  /** 代客下单 */
  offlineAssistedPlace(storeNo: string, v: MallPlaceRequest): Promise<MallOrderView>;
  /** 代客发出付款指引（幂等） */
  issueOfflineInstructions(storeNo: string, orderNo: string): Promise<MallOfflineInstructionsView>;
  /** 读已发出的指引；未发出时报 OFFLINE_INSTRUCTIONS_NOT_ISSUED */
  offlineInstructions(storeNo: string, orderNo: string): Promise<MallOfflineInstructionsView>;
  /** 某订单的买家申报 */
  offlineClaims(storeNo: string, orderNo: string): Promise<MallOfflineClaimView[]>;
  /** 预留一个运营凭证资产键 */
  reserveOfflineEvidence(storeNo: string, orderNo: string, purpose: MallEvidencePurpose): Promise<MallOfflineEvidenceTicket>;
  /** 上传凭证字节（image/jpeg 或 image/png，≤10MB；每个键只能传一次） */
  uploadOfflineEvidence(storeNo: string, orderNo: string, assetKey: string, file: Blob): Promise<MallOfflineEvidenceUploaded>;
  /** 读凭证（5 分钟签名地址） */
  offlineEvidenceUrl(storeNo: string, orderNo: string, assetKey: string): Promise<MallOfflineSignedRead>;
  /** 拒绝申报：不等于无款，不释放占用 */
  declineOfflineClaim(storeNo: string, orderNo: string, claimNo: string, reason: string): Promise<MallOfflineClaimView>;
  /** 核款：录入实际到账；金额不符 → UNMATCHED，不发权益 */
  /** Read the durable original outcome without replaying the command. */
  offlineAction(storeNo: string, orderNo: string, actionNo: string): Promise<MallOfflineActionView | null>;
  confirmOfflinePayment(storeNo: string, orderNo: string, v: MallOfflineConfirmRequest): Promise<MallOfflineActionView>;
  /** 对账作废：之后来款仍入账并走退款 */
  voidOfflineOrder(storeNo: string, orderNo: string, v: MallOfflineVoidRequest): Promise<MallOfflineActionView>;
  /** Recover the original offline order workflow without replaying bank/release commands. */
  recoverOfflineOrder(storeNo: string, orderNo: string): Promise<{recovered:boolean}>;
  /** 线下退款实际出款确认 */
  confirmOfflineRefundPayout(storeNo: string, orderNo: string, refundNo: string, v: MallOfflinePayoutRequest): Promise<MallRefundView>;
}
