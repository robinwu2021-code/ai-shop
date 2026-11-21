/**
 * 销售发票（台湾电子发票，契约 docs/api/invoice.openapi.json）。
 *
 * <p>与「开票」页（`pages/invoice`，供应商结算向平台开的进项票）是两件事：这里是门店当场开给顾客的销项票。
 * 主体与门店由登录态与 `X-Store-No` 决定（http 层统一带），入参里不传门店。
 */
export interface SiLine {
  lineNo: number;
  /** 品名 */
  name: string;
  /** 数量（字符串，保留小数精度） */
  quantity: string;
  unit?: string | null;
  unitPriceMinor: number;
  /** 含税金额 */
  amountMinor: number;
  /** 课税别 */
  taxType?: "TAXABLE" | "ZERO" | "EXEMPT" | null;
}
export interface SiAllowance {
  allowanceNo: string;
  /** 含税折让 */
  amountMinor: number;
  /** 其中税额 */
  taxMinor: number;
  reason: string;
  createdAt: string;
}
export type SiStatus = "ISSUED" | "VOIDED";
export type SiUploadStatus = "PENDING" | "SENDING" | "SENT" | "FAILED";
/** 开票通道：本地桩 / 财政部 Turnkey（与后端 InvoiceGateway#code 一致）。 */
export type SiGatewayCode = "STUB" | "TURNKEY";
export interface SalesInvoice {
  invoiceNo: string;
  regimeCode: string;
  industryCode?: string | null;
  sourceType: string;
  sourceNo: string;
  storeNo: string;
  /** 法定发票号码（字轨 + 8 码） */
  fiscalNumber: string;
  /** 期别，如 11512 */
  periodCode: string;
  randomCode: string;
  issuedAt: string;
  /** B2C / B2B */
  buyerType: string;
  buyerTaxId?: string | null;
  carrierType?: string | null;
  carrierId?: string | null;
  donateCode?: string | null;
  /** 是否印证明联（存载具 / 捐赠时为 false） */
  printMark: boolean;
  currency: string;
  salesMinor: number;
  /** 零税率销售额 */
  zeroTaxSalesMinor?: number | null;
  /** 免税销售额 */
  freeTaxSalesMinor?: number | null;
  taxMinor: number;
  totalMinor: number;
  /** 累计折让 */
  allowedMinor: number;
  status: SiStatus;
  voidReason?: string | null;
  uploadStatus: SiUploadStatus;
  lines?: SiLine[];
  allowances?: SiAllowance[];
}
export interface SiPage {
  records: SalesInvoice[];
  total: number;
  page: number;
  size: number;
}
export interface SiBuyer {
  taxId?: string;
  name?: string;
  carrierType?: string;
  carrierId?: string;
  donateCode?: string;
}
export interface SiIssueReq {
  sourceType: "SUB_ORDER";
  sourceNo: string;
  buyer?: SiBuyer;
}
/** 付款前预存买受人：店铺开了自动开立时，付款成功后按它开票。 */
export interface SiBuyerPreferenceReq {
  sourceType: "SUB_ORDER";
  sourceNo: string;
  buyer: SiBuyer;
}
export interface SiListQuery {
  periodCode?: string;
  status?: SiStatus;
  page: number;
  size: number;
}
export interface SiProfile {
  regimeCode: string;
  sellerTaxId: string;
  sellerName: string;
  gatewayCode: string;
  /** QR 密钥是否已配置（密钥本身只写不读） */
  qrKeyConfigured: boolean;
  memberCarrierType?: string | null;
  /** ACTIVE / SUSPENDED */
  status: string;
  /** 付款成功后按预存买受人自动开立 */
  autoIssue?: boolean | null;
}
export interface SiProfileReq {
  regimeCode: "TW_EGUI";
  sellerTaxId: string;
  sellerName: string;
  gatewayCode: SiGatewayCode;
  /** 32 位十六进制；不填表示不改 */
  qrAesKeyHex?: string;
  memberCarrierType?: string;
  /** 不传 = 关 */
  autoIssue?: boolean;
}
export interface SiRange {
  rangeNo: string;
  regimeCode: string;
  /** `*` = 主体内各店共用 */
  storeNo: string;
  periodCode: string;
  track: string;
  startNo: number;
  endNo: number;
  nextNo: number;
  remaining: number;
  /** ACTIVE / EXHAUSTED */
  status: string;
}
export interface SiRangeReq {
  regimeCode: "TW_EGUI";
  periodCode: string;
  track: string;
  startNo: number;
  endNo: number;
  /** 空 = 主体内各店共用 */
  storeNo?: string;
}

export interface SalesInvoiceApi {
  /** 按来源单开票（同来源返回原发票） */
  mSiIssue(body: SiIssueReq): Promise<SalesInvoice>;
  /** 付款前预存买受人（同一来源以最后一次为准） */
  mSiBuyerPreference(body: SiBuyerPreferenceReq): Promise<void>;
  mSiList(q: SiListQuery): Promise<SiPage>;
  mSiDetail(invoiceNo: string): Promise<SalesInvoice>;
  /** 当期作废 */
  mSiVoid(invoiceNo: string, body: { reason: string }): Promise<SalesInvoice>;
  mSiAllowance(invoiceNo: string, body: { amountMinor: number; reason: string }): Promise<SalesInvoice>;
  /** 未开通时后端回 null */
  mSiProfile(): Promise<SiProfile | null>;
  mSiSaveProfile(body: SiProfileReq): Promise<SiProfile>;
  mSiRanges(q: { periodCode?: string }): Promise<SiRange[]>;
  mSiAddRange(body: SiRangeReq): Promise<SiRange>;
}
