// 平台商城（商家向平台采购套餐 / 硬件，/biz/stores/{storeNo}/mall）。首期只走线下银行转账：
// 报价 → 下单 → 发出付款指引 → 商家转账后上传凭证并申报 → 运营端核款。
//
// 字段逐字镜像 docs/api/commerce-mall-openapi.json（与 ops-web/lib/types/commerce.ts 同源），不改名。
// 金额是 `MallMoney`：`minor` 为**整数字符串**，前端只做展示，不做浮点运算。
// 「商城订单」是本店向平台买东西，与顾客在本店下的销售订单是两回事。

/** 金额：币种 + 最小单位整数字符串 */
export interface MallMoney {
  /** ISO 4217 三位大写币种（TWD / CNY …） */
  currency: string;
  /** 最小货币单位的十进制整数文本（TWD 699000 = NT$6,990.00）；不是 number，避免超过 2^53 失真 */
  minor: string;
}

/** 门店引用；租户与门店都由后端按登录态与路径决定，页面只读 */
export interface MallStoreRef {
  /** 租户号（固定 MAIN） */
  tenantNo?: string;
  /** 门店号 */
  storeNo?: string;
}

/** 组件种类：套餐 / 业务端包 / 硬件 */
export type MallComponentKind = "PLAN" | "END_PACKAGE" | "HARDWARE";
/** 商品种类：套餐 / 业务端 / 硬件 / 组合套装 */
export type MallProductKind = "PLAN" | "END_ACCESS" | "HARDWARE" | "BUNDLE";

/** SKU 组件：一件 SKU 交付哪些东西 */
export interface MallSkuComponent {
  /** 组件号 */
  componentNo?: string | null;
  /** 种类；有 HARDWARE 的单要收货地址 */
  kind?: MallComponentKind;
  /** PLAN 组件引用的 Plan 版本 */
  planVersionNo?: string | null;
  /** END_PACKAGE 组件的业务端权益编码 */
  entitlementCode?: string | null;
  /** HARDWARE 组件的硬件 SKU 号 */
  hardwareSkuNo?: string | null;
  /** 数字组件的周期天数；硬件为空 */
  periodDays?: number | null;
  /** 数量 */
  unitCount?: number;
  /** 赠送组件（不参与金额分摊） */
  gift?: boolean;
}

/** 可售 SKU 的已发布版本 */
export interface MallSku {
  /** 所属商品号 */
  productNo?: string;
  /** SKU 号 */
  skuNo?: string;
  /** SKU 版本号；报价按这个版本冻结 */
  skuVersionNo?: string;
  /** 标题 */
  title?: string;
  /** 划线原价 */
  listPrice?: MallMoney;
  /** 售价 */
  price?: MallMoney;
  /** 退款政策快照（发布时冻结） */
  refundPolicySnapshot?: string;
  /** 交付组件 */
  components?: MallSkuComponent[];
  /** 每单最多买几件 */
  maxPerOrder?: number;
}

/** 商品基本信息 */
export interface MallProductInfo {
  /** 商品号 */
  productNo?: string;
  /** 商品种类 */
  kind?: MallProductKind;
  /** 名称 */
  name?: string;
  /** 描述 */
  description?: string | null;
  /** 类目编码 */
  categoryCode?: string;
}

/** 在线支付口径的可购买判断（线下转账另有判断，以报价结果为准） */
export interface MallReadiness {
  /** 在线支付是否可买 */
  available?: boolean;
  /** 不可买的原因码 */
  reasons?: string[];
}

/** 列表里的一个可售 SKU */
export interface MallStorefrontSku {
  /** SKU 版本内容 */
  sku?: MallSku;
  /** 在线支付口径的可购买判断 */
  readiness?: MallReadiness;
}

/** 列表里的一件商品及其可售 SKU */
export interface MallStorefrontProduct {
  /** 商品信息 */
  product?: MallProductInfo;
  /** 可售 SKU */
  skus?: MallStorefrontSku[];
}

/** 本店能买的商品（按门店经营类型过滤） */
export interface MallStorefront {
  /** 门店 */
  store?: MallStoreRef;
  /** 门店经营类型 */
  businessTypeCode?: string;
  /** 本店结算币种；报价按它 */
  currency?: string;
  /** 目录版本 */
  catalogVersion?: number;
  /** 当前账号能不能为本店采购 */
  canProcure?: boolean;
  /** 商品 */
  products?: MallStorefrontProduct[];
}

/** 报价 / 订单里冻结的组件与金额 */
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

/** 报价（服务器冻结，默认 15 分钟有效） */
export interface MallQuote {
  /** 报价号；下单时带上 */
  quoteNo?: string;
  /** 门店 */
  store?: MallStoreRef;
  /** 商品总额 */
  total?: MallMoney;
  /** 抵扣 */
  credit?: MallMoney;
  /** 应付现金 */
  payable?: MallMoney;
  /** 组件 */
  components?: MallQuoteComponent[];
  /** 过期时间（ISO） */
  expiresAt?: string;
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
  /** 报价行；首期一单一个 SKU */
  lines: MallQuoteLine[];
  /** 币种，取本店结算币种 */
  currency: string;
}

/** 收货地址（含硬件时必填） */
export interface MallShippingAddress {
  /** 收件人 */
  recipient?: string;
  /** 电话 */
  phone?: string;
  /** 国家 / 地区码（TW / CN / AE） */
  countryCode?: string;
  /** 省 / 州 */
  region?: string | null;
  /** 城市 */
  city?: string | null;
  /** 详细地址 */
  addressLine?: string;
  /** 邮编 */
  postalCode?: string | null;
}

/** 下单请求 */
export interface MallPlaceRequest {
  /** 报价号 */
  quoteNo: string;
  /** 幂等键；同一次确认重复提交只下一单 */
  idempotencyKey: string;
  /** 收货地址；纯数字订单为 null */
  shippingAddress: MallShippingAddress | null;
}

/** 订单总状态（概览用；付款以资金轴 paymentStatus 为准） */
export type MallOrderStatus =
  | "PENDING_INVENTORY" | "AWAITING_PAYMENT" | "PAYMENT_UNKNOWN" | "PAID" | "FULFILLING"
  | "COMPLETED" | "CANCEL_PENDING" | "CLOSED" | "REFUNDING" | "PARTIALLY_REFUNDED" | "REFUNDED";

/** 订单 */
export interface MallOrder {
  /** 订单号 */
  orderNo?: string;
  /** 门店 */
  store?: MallStoreRef;
  /** 总状态 */
  status?: MallOrderStatus;
  /** 应付 */
  payable?: MallMoney;
  /** 组件 */
  components?: MallQuoteComponent[];
  /** 下单时间（ISO） */
  createdAt?: string;
}

/** 订单概览：总状态 + 资金 / 交付 / 退款三个独立维度 */
export interface MallOrderSummary {
  /** 订单 */
  order?: MallOrder;
  /** 资金维度：UNPAID / PAYING / PAYMENT_UNKNOWN / CANCEL_PENDING / PAID / CLOSED */
  paymentStatus?: string;
  /** 权益 / 交付维度 */
  fulfillmentStatus?: string;
  /** 退款维度 */
  refundStatus?: string;
}

/** 硬件发货 */
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
  status?: string;
}

/** 订单详情 */
export interface MallOrderDetail {
  /** 概览与三维状态 */
  summary?: MallOrderSummary;
  /** 冻结报价 */
  quote?: MallQuote;
  /** 收货地址 */
  shippingAddress?: MallShippingAddress | null;
  /** 硬件发货 */
  shipments?: MallShipment[];
}

/** 已发出的付款指引（收款账户按发出时的档案版本冻结） */
export interface MallOfflineInstructions {
  /** 订单号 */
  orderNo: string;
  /** 应转账金额 */
  payable: MallMoney;
  /** 付款截止（ISO 时间） */
  paymentDueAt: string;
  /** 收款档案号 */
  profileNo: string;
  /** 收款档案版本 */
  profileVersion: number;
  /** 收款信息：bankCode / bankAccount / accountName */
  instructions: Record<string, string>;
}

/** 申报状态。**申报不是到账**：CONFIRMED 才表示运营已核到款 */
export type MallOfflineClaimStatus = "SUBMITTED" | "REVIEWING" | "CONFIRMED" | "MISMATCHED" | "DECLINED";

/** 商家的付款申报 */
export interface MallOfflineClaim {
  /** 申报号 */
  claimNo: string;
  /** 申报状态 */
  status: MallOfflineClaimStatus;
  /** 申报金额 */
  declaredAmount: MallMoney;
  /** 申报的转账时间（ISO） */
  declaredPaidAt: string;
  /** 提交时间（ISO） */
  submittedAt: string;
  /** 申报时填的转账备注 */
  bankReference: string;
  /** 申报时附的凭证 assetKey */
  evidenceKeys: string[];
}

/** 付款申报请求 */
export interface MallOfflineClaimRequest {
  /** 申报号（端上生成，同号重放幂等；被拒后用新号重报） */
  claimNo: string;
  /** 申报金额，取指引里的应转账金额 */
  declaredAmount: MallMoney;
  /** 转账时间（ISO） */
  declaredPaidAt: string;
  /** 转账备注 / 流水号 / 账号末几位 —— 运营对账用 */
  bankReference: string;
  /** 1–10 张已上传凭证的 assetKey */
  evidenceKeys: string[];
}

/** 凭证预留回执 */
export interface MallEvidenceTicket {
  /** 资产键；随后按它上传字节、在申报里引用 */
  assetKey: string;
  /** 用途（商家侧固定 BUYER_RECEIPT） */
  purpose: string;
}

/** 凭证上传回执 */
export interface MallEvidenceUploaded {
  /** 资产键 */
  assetKey: string;
}

export interface MallApi {
  /** 本店能买的商品 */
  mMallStorefront(storeNo: string): Promise<MallStorefront>;
  /** 线下转账报价：只取部署配置的收款档案 */
  mMallOfflineQuote(storeNo: string, body: MallQuoteRequest): Promise<MallQuote>;
  /** 按报价下单 */
  mMallPlaceOrder(storeNo: string, body: MallPlaceRequest): Promise<MallOrder>;
  /** 本店的商城订单 */
  mMallOrders(storeNo: string): Promise<MallOrderSummary[]>;
  /** 订单详情 */
  mMallOrder(storeNo: string, orderNo: string): Promise<MallOrderDetail>;
  /** 发出付款指引（幂等） */
  mMallIssueInstructions(storeNo: string, orderNo: string): Promise<MallOfflineInstructions>;
  /** 读已发出的指引；未发出时后端报 OFFLINE_INSTRUCTIONS_NOT_ISSUED */
  mMallInstructions(storeNo: string, orderNo: string): Promise<MallOfflineInstructions>;
  /** 预留一个凭证位，拿 assetKey */
  mMallReserveEvidence(storeNo: string, orderNo: string): Promise<MallEvidenceTicket>;
  /** 上传凭证图片原始字节（image/jpeg | image/png，≤10MB；每个 key 只能传一次） */
  mMallUploadEvidence(storeNo: string, orderNo: string, assetKey: string, bytes: ArrayBuffer, contentType: string): Promise<MallEvidenceUploaded>;
  /** 申报已转账（不是到账） */
  mMallSubmitClaim(storeNo: string, orderNo: string, body: MallOfflineClaimRequest): Promise<MallOfflineClaim>;
  /** 本单的付款申报 */
  mMallClaims(storeNo: string, orderNo: string): Promise<MallOfflineClaim[]>;
}
