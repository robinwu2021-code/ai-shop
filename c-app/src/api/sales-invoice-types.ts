// 台湾电子发票（买方视角）：常用载具、给自己的订单选开法、「我的发票」。
//
// 与 contract 里的 `InvoiceRequest`（`/mp/invoice/*`，线上订单事后申请平台开票）是两件事，路径刻意不同。
// 买家身份一律取自登录态 —— 这里的入参**没有用户号，也没有会员载具号码**（会员载具的号码就是账号，服务端填）。

/** 载具类别（财政部代码）：3J0002 手机条码 / CQ0001 自然人凭证。会员载具不在这里存。 */
export type InvoiceCarrierType = "3J0002" | "CQ0001";

/** 给一张订单选的开法：一般（印证明联）/ 会员载具 / 我的载具 / 打统编 / 捐赠 */
export type InvoiceChoiceKind = "CONSUMER" | "MEMBER" | "CARRIER" | "TAX_ID" | "DONATE";

/**
 * 每张子单各自的结果。SAVED 已记下（付款后按它开）/ ISSUED 已按新选择开立 /
 * ALREADY_ISSUED 早开过了（要改须找商家作废重开）/ NOT_ENABLED 该商家没开通电子发票 /
 * MEMBER_CARRIER_UNAVAILABLE 该商家没有会员载具
 */
export type InvoiceChoiceOutcome = "SAVED" | "ISSUED" | "ALREADY_ISSUED" | "NOT_ENABLED" | "MEMBER_CARRIER_UNAVAILABLE";

/** 发票状态：已开立 / 已作废 */
/** REVOKING 注销中 / REVOKED 已注销（商家注销后可同号重开一张新票） */
export type BuyerInvoiceStatus = "ISSUED" | "VOIDED" | "REVOKING" | "REVOKED";

export interface InvoiceCarrier {
  /** 载具记录号（删除、设默认用它） */
  carrierNo: string;
  /** 载具类别：3J0002 手机条码 / CQ0001 自然人凭证 */
  carrierType: InvoiceCarrierType;
  /** 手机条码 `/ABC+123`，自然人凭证 `AB12345678901234` */
  carrierId: string;
  /** 买家给的备注名，如「我的手机」；没给为空 */
  label?: string | null;
  /** 是否默认载具（结账时预先选中） */
  isDefault: boolean;
}

export interface SaveInvoiceCarrierReq {
  /** 载具类别：3J0002 手机条码 / CQ0001 自然人凭证 */
  carrierType: InvoiceCarrierType;
  /** 载具号码：手机条码 `/` 加 7 码，自然人凭证 2 个英文字母加 14 位数字 */
  carrierId: string;
  /** 备注名；不传为空 */
  label?: string;
  /** 设为默认载具；不传 = 否 */
  makeDefault?: boolean;
}

export interface InvoiceChoiceReq {
  /** C 端主单号；一张主单可能拆成多家商家的子单，各自返回一条结果 */
  orderNo: string;
  /** 开法：CONSUMER 一般 / MEMBER 会员载具 / CARRIER 我的载具 / TAX_ID 打统编 / DONATE 捐赠 */
  kind: InvoiceChoiceKind;
  /** kind=CARRIER 时必填：载具类别 */
  carrierType?: InvoiceCarrierType;
  /** kind=CARRIER 时必填：载具号码 */
  carrierId?: string;
  /** kind=TAX_ID 时必填：买方统一编号（8 位） */
  taxId?: string;
  /** kind=TAX_ID 时必填：买方名称 */
  name?: string;
  /** kind=DONATE 时必填：爱心码（3–7 位） */
  donateCode?: string;
}

export interface InvoiceChoiceResult {
  /** 子单号 */
  sourceNo: string;
  /** 这张子单的结果，见 InvoiceChoiceOutcome */
  outcome: InvoiceChoiceOutcome;
  /** ISSUED / ALREADY_ISSUED 时的发票号码 */
  fiscalNumber?: string | null;
}

export interface BuyerInvoiceLine {
  /** 行号，从 1 起 */
  lineNo: number;
  /** 品名（开立当下冻结） */
  name: string;
  /** 数量（保留小数精度） */
  quantity: number | string;
  /** 单位；没有为空 */
  unit?: string | null;
  /** 含税单价（最小货币单位） */
  unitPriceMinor: number;
  /** 含税金额（最小货币单位） */
  amountMinor: number;
  /** 课税别：TAXABLE 应税 / ZERO 零税率 / EXEMPT 免税 */
  taxType?: string | null;
}

export interface BuyerInvoiceAllowance {
  /** 折让单号 */
  allowanceNo: string;
  /** 折让金额（含税，最小货币单位） */
  amountMinor: number;
  /** 其中税额（最小货币单位） */
  taxMinor: number;
  /** 折让原因 */
  reason: string;
  /** 开立时间（ISO 8601） */
  createdAt: string;
  /** 折让证明单号码；没有为空 */
  fiscalAllowanceNumber?: string | null;
  /** ACTIVE / VOIDED（作废的折让不再计入累计折让） */
  status?: string | null;
}

export interface BuyerInvoice {
  /** 系统发票号（查详情用；票面号码见 fiscalNumber） */
  invoiceNo: string;
  /** 营业人名称（开立当下冻结） */
  sellerName?: string | null;
  /** 营业人统一编号（开立当下冻结） */
  sellerTaxId?: string | null;
  /** 字轨 + 8 码，如 AB12345678 */
  fiscalNumber: string;
  /** 期别：民国年 3 码 + 双月末月 2 码，如 11512 = 115 年 11-12 月 */
  periodCode: string;
  /** 4 位随机码（对奖、查询用） */
  randomCode: string;
  /** 开立时间（ISO 8601） */
  issuedAt: string;
  /** 买受人类型：B2C 一般消费者 / B2B 打统编 */
  buyerType: string;
  /** 打统编时的买方统一编号 */
  buyerTaxId?: string | null;
  /** 存载具时的载具类别 */
  carrierType?: string | null;
  /** 存载具时的载具号码 */
  carrierId?: string | null;
  /** 捐赠时的爱心码 */
  donateCode?: string | null;
  /** 是否印证明联（存载具 / 捐赠时为 false） */
  printMark: boolean;
  /** 币种，台湾为 TWD */
  currency: string;
  /** 应税销售额（最小货币单位；B2C 为含税） */
  salesMinor: number;
  /** 零税率销售额 */
  zeroTaxSalesMinor?: number | null;
  /** 免税销售额 */
  freeTaxSalesMinor?: number | null;
  /** 税额（B2C 为 0，税额含在销售额里） */
  taxMinor: number;
  /** 总计（最小货币单位） */
  totalMinor: number;
  /** 累计折让 */
  allowedMinor: number;
  /** 发票状态：ISSUED 已开立 / VOIDED 已作废 */
  status: BuyerInvoiceStatus;
  /** 明细（列表不带，详情带） */
  lines?: BuyerInvoiceLine[] | null;
  /** 折让（列表不带，详情带） */
  allowances?: BuyerInvoiceAllowance[] | null;
}

export interface BuyerInvoicePage {
  /** 本页发票，开立时间倒序 */
  records: BuyerInvoice[];
  /** 总条数 */
  total: number;
  /** 页码，从 1 起 */
  page: number;
  /** 每页条数 */
  size: number;
}
