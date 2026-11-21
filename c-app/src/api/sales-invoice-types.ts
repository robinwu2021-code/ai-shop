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
export type BuyerInvoiceStatus = "ISSUED" | "VOIDED";

export interface InvoiceCarrier {
  carrierNo: string;
  carrierType: InvoiceCarrierType;
  /** 手机条码 `/ABC+123`，自然人凭证 `AB12345678901234` */
  carrierId: string;
  label?: string | null;
  isDefault: boolean;
}

export interface SaveInvoiceCarrierReq {
  carrierType: InvoiceCarrierType;
  carrierId: string;
  label?: string;
  makeDefault?: boolean;
}

export interface InvoiceChoiceReq {
  /** C 端主单号；一张主单可能拆成多家商家的子单，各自返回一条结果 */
  orderNo: string;
  kind: InvoiceChoiceKind;
  carrierType?: InvoiceCarrierType;
  carrierId?: string;
  taxId?: string;
  name?: string;
  donateCode?: string;
}

export interface InvoiceChoiceResult {
  /** 子单号 */
  sourceNo: string;
  outcome: InvoiceChoiceOutcome;
  /** ISSUED / ALREADY_ISSUED 时的发票号码 */
  fiscalNumber?: string | null;
}

export interface BuyerInvoiceLine {
  lineNo: number;
  name: string;
  /** 数量（保留小数精度） */
  quantity: number | string;
  unit?: string | null;
  unitPriceMinor: number;
  amountMinor: number;
  taxType?: string | null;
}

export interface BuyerInvoiceAllowance {
  allowanceNo: string;
  amountMinor: number;
  taxMinor: number;
  reason: string;
  createdAt: string;
}

export interface BuyerInvoice {
  invoiceNo: string;
  /** 营业人名称与统编（开立当下冻结） */
  sellerName?: string | null;
  sellerTaxId?: string | null;
  /** 字轨 + 8 码，如 AB12345678 */
  fiscalNumber: string;
  /** 期别：民国年 3 码 + 双月末月 2 码，如 11512 = 115 年 11-12 月 */
  periodCode: string;
  randomCode: string;
  issuedAt: string;
  buyerType: string;
  buyerTaxId?: string | null;
  carrierType?: string | null;
  carrierId?: string | null;
  donateCode?: string | null;
  /** 是否印证明联（存载具 / 捐赠时为 false） */
  printMark: boolean;
  currency: string;
  salesMinor: number;
  zeroTaxSalesMinor?: number | null;
  freeTaxSalesMinor?: number | null;
  taxMinor: number;
  totalMinor: number;
  /** 累计折让 */
  allowedMinor: number;
  status: BuyerInvoiceStatus;
  lines?: BuyerInvoiceLine[] | null;
  allowances?: BuyerInvoiceAllowance[] | null;
}

export interface BuyerInvoicePage {
  records: BuyerInvoice[];
  total: number;
  page: number;
  size: number;
}
