// 套餐商城（commerce）运营端契约类型。
//
// **逐字镜像** docs/api/commerce-mall-openapi.json（task8-v1）的 schema，字段名不改写、
// 不做「前端友好」的重命名 —— 这批接口已经冻结，改名只会让对账多一层翻译。
// 统一加 `Mall` 前缀：本仓库已有 `Sku` / `Quote` / `ProductGoods` 等同名旧概念，
// 它们是社区团购那套商品与报价，与平台向门店卖套餐不是一回事。
//
// 金额一律 `MallMoney`：`minor` 是**整数字符串**（TWD 69900 = NT$699.00），
// 前端不转 number、不做浮点运算（见 lib/commerce-money.ts）。

/** 金额：币种 + 最小单位整数字符串（`^(0|[1-9][0-9]{0,18})$`） */
export interface MallMoney {
  /** ISO 4217 三位大写币种 */
  currency: string;
  /** 最小货币单位的十进制整数文本；**不是** number，避免超过 2^53 时失真 */
  minor: string;
}

/** 门店引用；tenant 由后端可信上下文决定，页面只读 */
export interface MallStoreRef {
  /** 租户号（固定 MAIN） */
  tenantNo?: string;
  /** 门店号 */
  storeNo?: string;
}

/** 写目录的回执 */
export interface MallDraftSaveResult {
  /** 被保存的实体号（typeCode / planNo / productNo / skuNo） */
  entityNo?: string;
  /** 新建或修改的草稿版本号；不涉及版本的实体为 null */
  draftVersionNo?: string | null;
  /** 保存后的乐观锁版本 —— 下一次写要带它 */
  version?: number;
}

/** 发布回执 */
export interface MallPublicationResult {
  /** 发布单号（审计用） */
  publicationNo?: string;
  /** 发布后的行业目录版本。**运营目录接口不回读它**，只在这里出现一次 */
  catalogVersion?: number;
  /** 本次被冻结为已发布的版本号 */
  publishedVersionNos?: string[];
}

// ── 经营类型 ───────────────────────────────────────────────────────────

/** 经营类型草稿（新建时 enabled 必须为 false，发布过行业目录才能启用） */
export interface MallBusinessTypeDraft {
  /** 类型编码，新建后不可改 */
  typeCode?: string;
  /** 显示名 */
  name?: string;
  /** 排序 */
  sortOrder?: number;
  /** 是否对门店开放（启用属于发布权） */
  enabled?: boolean;
  /** 乐观锁：新建传 0 */
  expectedVersion?: number;
  /** 审计原因 */
  reason?: string;
}

/** 经营类型行 */
export interface MallBusinessTypeView {
  /** 当前草稿 */
  draft?: MallBusinessTypeDraft;
  /** 乐观锁版本 */
  version?: number;
}

/** 修改经营类型 */
export interface MallTypeUpdate {
  /** 显示名 */
  name: string;
  /** 排序 */
  sortOrder: number;
  /** 是否启用（属于发布权） */
  enabled: boolean;
  /** 乐观锁 */
  expectedVersion: number;
  /** 审计原因 */
  reason: string;
}

// ── 业务端 ─────────────────────────────────────────────────────────────

/** 业务端作用域 */
export type MallEndScope = "STORE" | "SYSTEM";
/** 业务端验收状态；VERIFIED 必须绑定部署验收清单里的一项 */
export type MallReleaseStatus = "PLANNED" | "VERIFIED" | "RETIRED";

/** 部署所有者提供的已验收端（运营只能从这里选，不手填 client/route/acceptance） */
export interface MallDeploymentOption {
  /** 部署号 */
  deploymentNo?: string;
  /** 显示名 */
  name?: string;
  /** 业务端编码 */
  code?: string;
  /** 对应的权益编码 */
  entitlementCode?: string;
  /** 允许的经营类型 */
  allowedBusinessTypes?: string[];
  /** 客户端 id（只读展示） */
  clientId?: string;
  /** 路由策略引用（只读展示） */
  routePolicyRef?: string;
  /** 验收证明引用（只读展示） */
  acceptanceRef?: string;
}

/** 业务端草稿（后端保存的形态，含绑定后的 client/route/acceptance） */
export interface MallBusinessEndDraft {
  /** 业务端编码 */
  code?: string;
  /** 权益编码 */
  entitlementCode?: string;
  /** 显示名 */
  name?: string;
  /** 作用域 */
  scope?: MallEndScope;
  /** 允许的经营类型 */
  allowedBusinessTypes?: string[];
  /** 验收状态 */
  releaseStatus?: MallReleaseStatus;
  /** 是否可单独售卖 */
  saleEnabled?: boolean;
  /** 门店初始化必需（FREE 必须包含） */
  bootstrapRequired?: boolean;
  /** 是否启用 */
  enabled?: boolean;
  /** 绑定后的客户端 id */
  clientId?: string | null;
  /** 绑定后的路由策略 */
  routePolicyRef?: string | null;
  /** 绑定后的验收引用 */
  acceptanceRef?: string | null;
  /** 乐观锁 */
  expectedVersion?: number;
  /** 审计原因 */
  reason?: string;
}

/** 业务端行 */
export interface MallBusinessEndView {
  /** 当前草稿 */
  draft?: MallBusinessEndDraft;
  /** 乐观锁版本 */
  version?: number;
}

/** 保存业务端：client/route/acceptance 由 deploymentNo 在后端解析 */
export interface MallEndRequest {
  /** 业务端编码 */
  code: string;
  /** 权益编码 */
  entitlementCode: string;
  /** 显示名 */
  name: string;
  /** 作用域 */
  scope: MallEndScope;
  /** 允许的经营类型 */
  allowedBusinessTypes: string[];
  /** 验收状态 */
  releaseStatus: MallReleaseStatus;
  /** 是否可单独售卖 */
  saleEnabled: boolean;
  /** 门店初始化必需 */
  bootstrapRequired: boolean;
  /** 是否启用 */
  enabled: boolean;
  /** 选中的部署号；PLANNED 可为 null */
  deploymentNo: string | null;
  /** 乐观锁：新建传 0 */
  expectedVersion: number;
  /** 审计原因 */
  reason: string;
}

// ── 三档 Plan ─────────────────────────────────────────────────────────

/** 三档 */
export type MallPlanTier = "FREE" | "PRO" | "CHAIN";

/** Plan 定义（每个经营类型固定三条，新建类型时由后端生成） */
export interface MallPlanDefinitionView {
  /** Plan 号（形如 `RETAIL:PRO`） */
  planNo?: string;
  /** 所属经营类型 */
  typeCode?: string;
  /** 档位 */
  tier?: MallPlanTier;
  /** 当前在售版本；未发布为 null */
  currentVersionNo?: string | null;
  /** 售卖状态（DRAFT / ON_SALE / OFF_SALE） */
  saleStatus?: string;
  /** 乐观锁版本 */
  version?: number;
}

/** Plan 版本内容 */
export interface MallPlanDraft {
  /** Plan 号 */
  planNo?: string;
  /** 版本号 */
  planVersionNo?: string;
  /** 经营类型 */
  typeCode?: string;
  /** 档位 */
  tier?: MallPlanTier;
  /** 显示名 */
  displayName?: string;
  /** 摘要 */
  summary?: string | null;
  /** 包含的权益编码（高档必须是低档的超集） */
  entitlementCodes?: string[];
  /** 政策版本 */
  policyVersion?: string;
  /** 乐观锁 */
  expectedVersion?: number;
  /** 审计原因 */
  reason?: string;
}

/** Plan 版本行；status=PUBLISHED 之后只读 */
export interface MallPlanVersionView {
  /** 内容 */
  draft?: MallPlanDraft;
  /** DRAFT / PUBLISHED */
  status?: string;
  /** 同一 Plan 下的修订序号 */
  revision?: number;
  /** 乐观锁版本 */
  version?: number;
}

/** 保存 Plan 版本：planVersionNo 为 null 表示新建 */
export interface MallPlanRequest {
  /** 编辑既有草稿时传版本号；新建传 null */
  planVersionNo: string | null;
  /** 经营类型（必须与 Plan 定义一致） */
  typeCode: string;
  /** 档位（必须与 Plan 定义一致） */
  tier: MallPlanTier;
  /** 显示名 */
  displayName: string;
  /** 摘要 */
  summary: string | null;
  /** 权益编码 */
  entitlementCodes: string[];
  /** 政策版本 */
  policyVersion: string;
  /** 乐观锁：新建传 0 */
  expectedVersion: number;
  /** 审计原因 */
  reason: string;
}

/** 行业发布：三档版本 + 候选 SKU 版本 + 全部相关行的乐观锁 */
export interface MallIndustryPublish {
  /** 幂等操作号 */
  operationNo: string;
  /** FREE 版本号 */
  freePlanVersionNo: string;
  /** PRO 版本号 */
  proPlanVersionNo: string;
  /** CHAIN 版本号 */
  chainPlanVersionNo: string;
  /** 同时发布的套餐 SKU 版本 */
  candidateSkuVersionNos: string[];
  /** 行业目录当前版本（首次发布为 0） */
  expectedCatalogVersion: number;
  /** 选中 Plan 版本号 → 行版本 */
  expectedPlanVersions: Record<string, number>;
  /** 选中 SKU 版本号 → 行版本 */
  expectedSkuVersions: Record<string, number>;
  /** 该类型三条 Plan 定义 → 行版本 */
  expectedPlanDefinitionVersions: Record<string, number>;
  /** 候选 SKU 定义 → 行版本 */
  expectedSkuDefinitionVersions: Record<string, number>;
  /** 审计原因 */
  reason: string;
}

// ── 商品与 SKU ────────────────────────────────────────────────────────

/** 商品种类 */
export type MallProductKind = "PLAN" | "END_ACCESS" | "HARDWARE" | "BUNDLE";
/** 组件种类 */
export type MallComponentKind = "PLAN" | "END_PACKAGE" | "HARDWARE";

/** 商品草稿 */
export interface MallProductDraft {
  /** 商品号 */
  productNo?: string;
  /** 类目编码 */
  categoryCode?: string;
  /** 种类 */
  kind?: MallProductKind;
  /** 名称 */
  name?: string;
  /** 描述 */
  description?: string | null;
  /** 允许的经营类型 */
  allowedBusinessTypes?: string[];
  /** 排序 */
  sortOrder?: number;
  /** 乐观锁：新建传 0 */
  expectedVersion?: number;
  /** 审计原因 */
  reason?: string;
}

/** 商品行 */
export interface MallProductView {
  /** 当前草稿 */
  draft?: MallProductDraft;
  /** 售卖状态 */
  saleStatus?: string;
  /** 乐观锁版本 */
  version?: number;
}

/** 修改商品 */
export type MallProductUpdate = Omit<MallProductDraft, "productNo">;

/** SKU 组件：PLAN 引用 Plan 版本，END_PACKAGE 引用权益编码，HARDWARE 引用硬件 SKU */
export interface MallSkuComponent {
  /** 组件号（保存后由后端给） */
  componentNo?: string | null;
  /** 种类 */
  kind?: MallComponentKind;
  /** PLAN 组件的 Plan 版本 */
  planVersionNo?: string | null;
  /** END_PACKAGE 组件的权益编码 */
  entitlementCode?: string | null;
  /** HARDWARE 组件的硬件 SKU 号 */
  hardwareSkuNo?: string | null;
  /** 周期天数（数字组件） */
  periodDays?: number | null;
  /** 数量 */
  unitCount?: number;
  /** 分摊权重 */
  allocationWeight?: number;
  /** 赠送（分摊基数为 0） */
  gift?: boolean;
  /** 生效策略 */
  activationPolicy?: string;
}

/** SKU 定义行 */
export interface MallSkuDefinitionView {
  /** SKU 号 */
  skuNo?: string;
  /** 所属商品 */
  productNo?: string;
  /** 标题 */
  title?: string;
  /** 每单上限 */
  maxPerOrder?: number;
  /** 当前在售版本 */
  currentVersionNo?: string | null;
  /** 售卖状态 */
  saleStatus?: string;
  /** 乐观锁版本 */
  version?: number;
}

/** SKU 版本内容 */
export interface MallSkuDraft {
  /** 商品号 */
  productNo?: string;
  /** SKU 号 */
  skuNo?: string;
  /** 版本号 */
  skuVersionNo?: string;
  /** 标题 */
  title?: string;
  /** 划线价 */
  listPrice?: MallMoney;
  /** 售价 */
  price?: MallMoney;
  /** 允许的经营类型 */
  allowedBusinessTypes?: string[];
  /** 退款政策快照（后端生成的 JSON 文本） */
  refundPolicySnapshot?: string;
  /** 组件 */
  components?: MallSkuComponent[];
  /** 每单上限 */
  maxPerOrder?: number;
  /** 乐观锁 */
  expectedVersion?: number;
  /** 审计原因 */
  reason?: string;
}

/** SKU 版本行；PUBLISHED 之后只读 */
export interface MallSkuVersionView {
  /** 内容 */
  draft?: MallSkuDraft;
  /** DRAFT / PUBLISHED */
  status?: string;
  /** 修订序号 */
  revision?: number;
  /** 乐观锁版本 */
  version?: number;
}

/** 未使用数字权益的退款口径：按原分摊全退 / 一律人工 */
export type MallUnusedRefundPolicy = "FULL_ORIGINAL_ALLOCATION" | "MANUAL_ONLY";

/** 退款政策输入：unusedDigital 二选一，usedDigital 只能 MANUAL_ONLY */
export interface MallRefundPolicyInput {
  /** 未使用数字权益 */
  unusedDigital: MallUnusedRefundPolicy;
  /** 已使用数字权益：只能人工 */
  usedDigital: "MANUAL_ONLY";
}

/** 保存 SKU 版本 */
export interface MallSkuRequest {
  /** SKU 号（新 SKU 由运营给定） */
  skuNo: string;
  /** 编辑草稿时传版本号；新建传 null */
  skuVersionNo: string | null;
  /** 标题 */
  title: string;
  /** 划线价 */
  listPrice: MallMoney;
  /** 售价 */
  price: MallMoney;
  /** 允许的经营类型 */
  allowedBusinessTypes: string[];
  /** 退款政策 */
  refundPolicy: MallRefundPolicyInput;
  /** 组件 */
  components: MallSkuComponent[];
  /** 每单上限 */
  maxPerOrder: number;
  /** 乐观锁：新 SKU 传 0；否则带 SKU 定义的 version */
  expectedVersion: number;
  /** 审计原因 */
  reason: string;
}

/** 发布 SKU 版本 */
export interface MallPublishSku {
  /** 幂等操作号 */
  operationNo: string;
  /** SKU 定义行版本 */
  expectedVersion: number;
  /** SKU 版本行版本 */
  expectedSkuVersion: number;
  /** 审计原因 */
  reason: string;
}

/** 停售 */
export interface MallStop {
  /** 幂等操作号 */
  operationNo: string;
  /** 被停售对象（Plan 定义 / SKU 定义）的行版本 */
  expectedVersion: number;
  /** 审计原因 */
  reason: string;
}

/** 目录总览（运营端一次读全量） */
export interface MallCatalogOverview {
  /** 经营类型 */
  businessTypes?: MallBusinessTypeView[];
  /** 业务端 */
  businessEnds?: MallBusinessEndView[];
  /** Plan 定义 */
  planDefinitions?: MallPlanDefinitionView[];
  /** Plan 版本 */
  planVersions?: MallPlanVersionView[];
  /** 商品 */
  products?: MallProductView[];
  /** SKU 定义 */
  skuDefinitions?: MallSkuDefinitionView[];
  /** SKU 版本 */
  skuVersions?: MallSkuVersionView[];
}

// ── 订单 ──────────────────────────────────────────────────────────────

/** 订单总状态（**只作概览**：付款/履约/退款三个维度各看各的） */
export type MallOrderStatus =
  | "PENDING_INVENTORY" | "AWAITING_PAYMENT" | "PAYMENT_UNKNOWN" | "PAID" | "FULFILLING"
  | "COMPLETED" | "CANCEL_PENDING" | "CLOSED" | "REFUNDING" | "PARTIALLY_REFUNDED" | "REFUNDED";
/** 外部异步动作状态 */
export type MallAsyncStatus = "PENDING" | "SUCCEEDED" | "FAILED" | "UNKNOWN";

/** 报价/订单组件（冻结） */
export interface MallQuoteComponent {
  /** 组件号 */
  componentNo?: string;
  /** 种类 */
  kind?: MallComponentKind;
  /** 原价 */
  listAmount?: MallMoney;
  /** 成交价 */
  dealAmount?: MallMoney;
  /** 抵扣部分 */
  creditAmount?: MallMoney;
  /** 现金部分 */
  cashAmount?: MallMoney;
  /** 周期天数 */
  cycleDays?: number;
  /** 数量 */
  quantity?: number;
}

/** 升级抵扣明细 */
export interface MallQuoteCreditView {
  /** 本次旧 Plan 转换出的价值 */
  newConversion?: MallMoney;
  /** 历史可用余额 */
  historicalAvailable?: MallMoney;
  /** 本单实际抵扣 */
  applied?: MallMoney;
  /** 预留的历史余额 */
  reservedHistorical?: MallMoney;
  /** 抵扣后余额 */
  balanceAfter?: MallMoney;
  /** 估值时间 */
  valuationAt?: string;
}

/** 报价（服务器冻结） */
export interface MallQuoteView {
  /** 报价号 */
  quoteNo?: string;
  /** 门店 */
  store?: MallStoreRef;
  /** 总额 */
  total?: MallMoney;
  /** 抵扣 */
  credit?: MallMoney;
  /** 应付 */
  payable?: MallMoney;
  /** 组件 */
  components?: MallQuoteComponent[];
  /** 权益版本 */
  entitlementVersion?: number;
  /** 余额版本 */
  balanceVersion?: number;
  /** 过期时间（默认 15 分钟） */
  expiresAt?: string;
  /** 升级抵扣明细；普通报价为 null */
  creditBreakdown?: MallQuoteCreditView | null;
}

/** 报价行 */
export interface MallQuoteLine {
  /** SKU 版本号 */
  skuVersionNo: string;
  /** 数量 */
  quantity: number;
}

/** 报价请求 */
export interface MallQuoteRequest {
  /** 行 */
  lines: MallQuoteLine[];
  /** 币种 */
  currency: string;
}

/** 收货地址（含硬件时必填） */
export interface MallShippingAddress {
  /** 收件人 */
  recipient?: string;
  /** 电话 */
  phone?: string;
  /** 国家/地区码 */
  countryCode?: string;
  /** 省/州 */
  region?: string | null;
  /** 城市 */
  city?: string | null;
  /** 地址 */
  addressLine?: string;
  /** 邮编 */
  postalCode?: string | null;
}

/** 下单请求 */
export interface MallPlaceRequest {
  /** 报价号 */
  quoteNo: string;
  /** 幂等键 */
  idempotencyKey: string;
  /** 收货地址；纯数字订单为 null */
  shippingAddress: MallShippingAddress | null;
}

/** 订单 */
export interface MallOrderView {
  /** 订单号 */
  orderNo?: string;
  /** 门店 */
  store?: MallStoreRef;
  /** 总状态（概览用） */
  status?: MallOrderStatus;
  /** 应付 */
  payable?: MallMoney;
  /** 组件 */
  components?: MallQuoteComponent[];
  /** 创建时间 */
  createdAt?: string;
}

/** 付款尝试 */
export interface MallPaymentAttempt {
  /** 尝试号 */
  attemptNo?: string;
  /** 通道 */
  channel?: string;
  /** 方式 */
  payMethod?: string;
  /** 金额 */
  amount?: MallMoney;
  /** 状态 */
  status?: MallAsyncStatus;
  /** 支付号 */
  paymentNo?: string | null;
  /** 付款时间 */
  paidAt?: string | null;
  /** 错误码 */
  errorCode?: string | null;
  /** 付款时间精度（毫秒） */
  paidAtPrecisionMs?: 1 | 1000 | null;
}

/** 发货 */
export interface MallShipment {
  /** 操作号 */
  operationNo?: string;
  /** 订单组件号 */
  orderComponentNo?: string;
  /** 承运商 */
  carrierCode?: string;
  /** 运单号 */
  trackingNo?: string;
  /** 数量 */
  quantity?: number;
  /** 状态 */
  status?: MallAsyncStatus;
  /** 错误码 */
  errorCode?: string | null;
}

/** 退款 */
export interface MallRefundView {
  /** 退款号 */
  refundNo?: string;
  /** 订单号 */
  orderNo?: string;
  /** 资金动作状态 */
  status?: MallAsyncStatus;
  /** 现金退款 */
  cashAmount?: MallMoney;
  /** 恢复的抵扣余额 */
  restoredCredit?: MallMoney;
  /** 审核状态（REQUESTED / APPROVED / DECLINED） */
  reviewStatus?: string | null;
  /** 买家原始请求摘要 */
  requestDigest?: string | null;
  /** 绑定的审核报价 */
  reviewQuoteNo?: string | null;
}

/** 资金维度（后端 OrderState.Payment） */
export type MallPaymentAxis = "UNPAID" | "PAYING" | "PAYMENT_UNKNOWN" | "CANCEL_PENDING" | "PAID" | "CLOSED";
/** 权益/履约维度（后端 OrderState.Fulfillment） */
export type MallFulfillmentAxis = "WAIT_INVENTORY" | "PENDING" | "PROCESSING" | "PARTIAL" | "COMPLETED" | "FAILED";
/** 退款维度（后端 OrderState.Refund） */
export type MallRefundAxis = "NONE" | "REFUNDING" | "PARTIAL" | "REFUNDED";

/** 订单概览：总状态 + 三个独立维度（列表返回的就是它） */
export interface MallOrderSummary {
  /** 订单 */
  order?: MallOrderView;
  /** 资金维度 */
  paymentStatus?: string;
  /** 权益/履约维度 */
  fulfillmentStatus?: string;
  /** 退款维度 */
  refundStatus?: string;
}

/** 订单详情：三个独立维度 + 冻结报价 + 实际动作 */
export interface MallOrderDetailView {
  /** 概览与三维状态 */
  summary?: MallOrderSummary;
  /** 冻结报价 */
  quote?: MallQuoteView;
  /** 收货地址 */
  shippingAddress?: MallShippingAddress | null;
  /** 付款尝试 */
  paymentAttempts?: MallPaymentAttempt[];
  /** 发货 */
  shipments?: MallShipment[];
  /** 退款 */
  refunds?: MallRefundView[];
}

/** 硬件发货 */
export interface MallShipmentRequest {
  /** 订单组件号（HARDWARE） */
  orderComponentNo: string;
  /** 幂等操作号 */
  operationNo: string;
  /** 承运商 */
  carrierCode: string;
  /** 运单号 */
  trackingNo: string;
  /** 数量 */
  quantity: number;
}

/** 退款预览 */
export interface MallRefundPreviewView {
  /** 退款号 */
  refundNo?: string;
  /** 订单号 */
  orderNo?: string;
  /** 现金上限 */
  cashLimit?: MallMoney;
  /** 抵扣恢复上限 */
  creditLimit?: MallMoney;
  /** 需要人工审核 */
  requiresManualReview?: boolean;
  /** 涉及的权益周期 */
  sourcePeriodNos?: string[];
  /** 过期时间 */
  expiresAt?: string;
  /** 批准摘要 */
  approvalDigest?: string;
}

/** 退款审核报价 */
export interface MallRefundReviewQuoteView {
  /** 审核报价号 */
  reviewQuoteNo?: string;
  /** 原请求摘要 */
  requestDigest?: string;
  /** 预览 */
  preview?: MallRefundPreviewView;
}

/** 批准退款 */
export interface MallApproveRequest {
  /** 预览给出的批准摘要 */
  approvalDigest: string;
  /** 审核报价号 */
  reviewQuoteNo: string | null;
  /** 现金退款 */
  cashAmount: MallMoney;
  /** 恢复抵扣 */
  restoreCredit: MallMoney;
  /** 审核证据（≤1024 字符） */
  reviewEvidence: string;
}

/** 拒绝退款 */
export interface MallRejectRequest {
  /** 原请求摘要 */
  requestDigest: string;
  /** 审核证据 */
  reviewEvidence: string;
}

// ── 门店权益来源 ──────────────────────────────────────────────────────

/** 权益来源 */
export interface MallEntitlementSource {
  /** 来源号 */
  sourceNo?: string;
  /** 权益编码 */
  entitlementCode?: string;
  /** 来自的 Plan 版本 */
  planVersionNo?: string | null;
  /** 来源单号（订单/赠送操作号） */
  originNo?: string;
  /** 总时长（毫秒） */
  durationMillis?: number;
  /** 已消耗（毫秒） */
  consumedMillis?: number;
  /** 估值基数（赠送为 0） */
  valuationBasis?: MallMoney;
  /** 永久 */
  permanent?: boolean;
  /** 生命周期（PURCHASE / GRANT / FREE / REVOKED …） */
  lifecycle?: string;
  /** 撤销截断的时长 */
  revokedDurationMillis?: number;
}

/** 可访问时段 */
export interface MallAccessSegment {
  /** 来源号 */
  sourceNo?: string;
  /** 权益编码 */
  entitlementCode?: string;
  /** 起 */
  startsAt?: string;
  /** 止 */
  endsAt?: string;
}

/** 门店订阅视图 */
export interface MallSubscriptionView {
  /** 门店 */
  store?: MallStoreRef;
  /** 权益版本（撤销时作乐观锁） */
  entitlementVersion?: number;
  /** 来源 */
  sources?: MallEntitlementSource[];
  /** 时段 */
  segments?: MallAccessSegment[];
}

/** 零金额独立端赠送 */
export interface MallGrantRequest {
  /** 幂等操作号 */
  operationNo: string;
  /** 独立端权益编码 */
  entitlementCode: string;
  /** 天数 */
  durationDays: number;
  /** 审计原因 */
  reason: string;
}

/** 撤销零金额来源 */
export interface MallRevokeRequest {
  /** 幂等操作号 */
  operationNo: string;
  /** 审计原因 */
  reason: string;
  /** 门店权益版本 */
  expectedVersion: number;
}

// ── 线下银行转账（Task8）───────────────────────────────────────────────

/** 收款档案（按币种；每次修改生成新版本） */
export interface MallOfflineCollectionTerms {
  /** 档案号 */
  profileNo: string;
  /** 档案版本 */
  profileVersion: number;
  /** 平台收款主体 */
  platformMerchantNo: string;
  /** 币种 */
  currency: string;
  /** 收款账户指纹（后端哈希，不是账号本身） */
  receivingAccountKey: string;
  /** 付款窗口版本 */
  paymentWindowVersion: string;
  /** 付款窗口（毫秒） */
  paymentWindowMs: number;
  /** 指引内容（bankCode / bankAccount / accountName） */
  instructions: Record<string, string>;
}

/** 配置收款档案 */
export interface MallOfflineProfileCommand {
  /** 档案号 */
  profileNo: string;
  /** 乐观锁：首次传 0，之后传当前 profileVersion */
  expectedVersion: number;
  /** 平台收款主体 */
  platformMerchantNo: string;
  /** 币种 */
  currency: string;
  /** 银行代码 */
  bankCode: string;
  /** 账号 */
  bankAccount: string;
  /** 户名 */
  accountName: string;
  /** 付款窗口版本 */
  paymentWindowVersion: string;
  /** 付款窗口（毫秒，1000 ~ 90 天） */
  paymentWindowMs: number;
  /** 是否启用 */
  enabled: boolean;
  /** 审计原因 */
  reason: string;
}

/** 申报状态（申报 ≠ 到账） */
export type MallOfflineClaimStatus = "SUBMITTED" | "REVIEWING" | "CONFIRMED" | "MISMATCHED" | "DECLINED";

/** 买家付款申报 */
export interface MallOfflineClaimView {
  /** 申报号 */
  claimNo: string;
  /** 申报状态 */
  status: MallOfflineClaimStatus;
  /** 申报金额 */
  declaredAmount: MallMoney;
  /** 申报的付款时间 */
  declaredPaidAt: string;
  /** 提交时间 */
  submittedAt: string;
}

/** 待审申报队列行 */
export interface MallOfflinePendingClaim {
  /** 门店 */
  store: MallStoreRef;
  /** 订单号 */
  orderNo: string;
  /** 申报 */
  claim: MallOfflineClaimView;
}

/** 已发出的付款指引（冻结档案版本） */
export interface MallOfflineInstructionsView {
  /** 订单号 */
  orderNo: string;
  /** 应付 */
  payable: MallMoney;
  /** 付款截止 */
  paymentDueAt: string;
  /** 档案号 */
  profileNo: string;
  /** 档案版本 */
  profileVersion: number;
  /** 指引内容 */
  instructions: Record<string, string>;
}

/** 运营凭证用途 */
export type MallEvidencePurpose = "PLATFORM_RECEIPT" | "RECONCILIATION" | "REFUND_PROOF";

/** 凭证预留回执 */
export interface MallOfflineEvidenceTicket {
  /** 资产键 */
  assetKey: string;
  /** 用途 */
  purpose: string;
}

/** 凭证上传回执 */
export interface MallOfflineEvidenceUploaded {
  /** 资产键 */
  assetKey: string;
}

/** 凭证签名读地址（5 分钟） */
export interface MallOfflineSignedRead {
  /** 地址 */
  url: string;
}

/** 核款：录入实际到账 */
export interface MallOfflineConfirmRequest {
  /** 幂等动作号 */
  actionNo: string;
  /** 对应的申报；运营直接录款为 null */
  claimNo: string | null;
  /** 实际到账金额 */
  actualAmount: MallMoney;
  /** 实际到账时间 */
  actualPaidAt: string;
  /** 实际收款主体（必须是档案里的平台主体） */
  actualReceiver: string;
  /** 银行流水号（全局唯一） */
  bankReference: string;
  /** 凭证资产键 */
  evidenceKey: string;
  /** 补录早于下单的历史到账 */
  historical: boolean;
  /** 审计原因 */
  reason: string;
}

/** 对账作废 */
export interface MallOfflineVoidRequest {
  /** 幂等动作号 */
  actionNo: string;
  /** 对账参考号 */
  reconciliationReference: string;
  /** 审计原因 */
  reason: string;
  /** 凭证资产键 */
  evidenceKey: string;
  /** 每一笔 MALL_UNMATCHED 实收的处置说明：paymentNo → 说明（必须一笔不漏） */
  unmatchedDispositions: Record<string, string>;
}

/** 线下动作结果状态：ACCEPTED 只是受理，资金端未确认 */
export type MallOfflineActionStatus = "ACCEPTED" | "APPLIED" | "UNMATCHED" | "REJECTED";

/** 线下动作结果：ACCEPTED 只是受理，**不等于到账** */
export interface MallOfflineActionView {
  /** 动作号 */
  actionNo: string;
  /** 结果 */
  status: MallOfflineActionStatus;
  /** 产生的支付号 */
  paymentNo: string | null;
}

/** 线下退款出款确认 */
export interface MallOfflinePayoutRequest {
  /** 实际出款金额（必须等于退款意图） */
  actualAmount: MallMoney;
  /** 出款时间 */
  paidOutAt: string;
  /** 收款方参考 */
  payeeReference: string;
  /** 银行流水号 */
  bankReference: string;
  /** 出款凭证资产键 */
  evidenceKey: string;
}
