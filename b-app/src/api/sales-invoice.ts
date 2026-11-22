import type { FactRef } from "./print";
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
/** 向服务商查单结果。providerState / expectedState：ISSUED / VOIDED / PARTIALLY_ALLOWED / FULLY_ALLOWED / NOT_FOUND */
export interface SiProviderCheck {
  providerState: string;
  /** 服务商原文（鲸跃 0 / 1 / 2 / 3 / nodata） */
  providerCode: string;
  expectedState: string;
  /** CONSISTENT 一致 / PENDING_UPLOAD 还有未送达的上传 / MISMATCH 不一致 */
  verdict: string;
  /** 本次补记为已送达的上传（ISSUE / VOID） */
  reconciled: string[];
  checkedAt: string;
}
/** 门店上传积压：pending 含待发 / 发送中 / 退避中；dueSoon 已过预警时点；overdue 已过法定期限仍未送达 */
export interface SiUploadBacklog {
  pending: number;
  held: number;
  needsReview: number;
  dueSoon: number;
  overdue: number;
  oldestDeadlineAt?: string | null;
}
export interface SiAllowance {
  allowanceNo: string;
  /** 含税折让 */
  amountMinor: number;
  /** 其中税额 */
  taxMinor: number;
  reason: string;
  createdAt: string;
  /** 对外折让单号（发票号码 + 两位序号） */
  fiscalAllowanceNumber?: string | null;
  status?: SiAllowanceStatus;
  voidReason?: string | null;
  voidedAt?: string | null;
}
export type SiStatus = "ISSUED" | "VOIDED";
/** 折让单状态：作废后累计折让回退、证明单不再出纸 */
export type SiAllowanceStatus = "ACTIVE" | "VOIDED";
/** HELD = 账号问题暂停（核验账号后自动恢复）；NEEDS_REVIEW = 服务商拒收，待人工处理后重传 */
export type SiUploadStatus = "PENDING" | "SENDING" | "SENT" | "FAILED" | "HELD" | "NEEDS_REVIEW";
/** 开票通道：本地桩 / 财政部 Turnkey（与后端 InvoiceGateway#code 一致）。 */
/** 号段来源：MANUAL 商家登记 / PROVIDER 加值中心配给 */
export type SiRangeOwner = "MANUAL" | "PROVIDER";
export type SiGatewayCode = "STUB" | "TURNKEY" | "CETUSTEK" | "UNIVERSALEC";
/** 生效供应商的来源：MERCHANT 商家自选 / DEFAULT 跟随市场默认 */
export type SiGatewaySource = "MERCHANT" | "DEFAULT";
/** 本市场商家可选的一家供应商（能力决定页面出现哪些项） */
export interface SiProviderOption {
  code: string;
  numbering: string;
  reporting: string;
  /** 要填商家在供应商的账号 */
  requiresAccount: boolean;
  /** 能核验账号 */
  verifiable: boolean;
  /** 号段由供应商配给（能「领号段」） */
  rangeProvided: boolean;
  /** 能向供应商查单 */
  queryable: boolean;
  isDefault: boolean;
}
export interface SiProviderOptions {
  regimeCode: string;
  /** 不选时跟随的市场默认供应商 */
  defaultGatewayCode: string;
  /** 默认的排第一 */
  options: SiProviderOption[];
}
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
  /** 开立报文的法定上报期限（台湾 B2C 48 小时、B2B 7 日） */
  uploadDeadlineAt?: string | null;
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
  /** 当前生效的供应商 */
  gatewayCode: string;
  gatewaySource?: SiGatewaySource;
  /** QR 密钥是否已配置（密钥本身只写不读） */
  qrKeyConfigured: boolean;
  memberCarrierType?: string | null;
  /** ACTIVE / SUSPENDED */
  status: string;
  /** 付款成功后按预存买受人自动开立 */
  autoIssue?: boolean | null;
  /** 加值中心分给商家的代号（鲸跃 officeid） */
  providerAccountCode?: string | null;
  /** 加值中心账号密码是否已保存（本身只写不读） */
  providerCredentialConfigured?: boolean;
  /** 上传暂停原因（账号认证失败时由系统写入，核验通过后清空）；空 = 正常 */
  providerHoldReason?: string | null;
  providerHeldAt?: string | null;
  /** 本期剩余号码提醒值 */
  rangeLowWater?: number;
  /** 余量不足 / 期末前自动向加值中心领号 */
  autoFetchRanges?: boolean;
  /** 本期期别与可用号码数 */
  currentPeriod?: string | null;
  currentRemaining?: number;
  /** 下一期期别与已备号码数 */
  nextPeriod?: string | null;
  nextRemaining?: number;
}
export interface SiProfileReq {
  regimeCode: "TW_EGUI";
  sellerTaxId: string;
  sellerName: string;
  /** 商家自选的供应商，须在本市场可选列表里；空串 = 跟随市场默认 */
  gatewayCode: string;
  /** 32 位十六进制；不填表示不改 */
  qrAesKeyHex?: string;
  memberCarrierType?: string;
  /** 不传 = 关 */
  autoIssue?: boolean;
  /** 不传 = 清空 */
  providerAccountCode?: string;
  /** 账号与密码两项同传才更新；都不传 = 不改 */
  providerUser?: string;
  providerPassword?: string;
  /** 不传 = 不改 */
  rangeLowWater?: number;
  /** 不传 = 不改 */
  autoFetchRanges?: boolean;
}
export interface SiProviderVerification {
  taxId: string;
  name?: string | null;
  /** 服务商回传的 QR 密钥已存起来 */
  qrKeyStored: boolean;
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
  /** MANUAL 手工登记 / PROVIDER 服务商配给 */
  allocationOwner?: SiRangeOwner;
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

/** 一张发票可模拟打印的打印件 */
export interface SiPrintFact {
  /** INVOICE_PROOF 证明联 / INVOICE_DETAIL 交易明细 / INVOICE_ALLOWANCE 折让证明单 */
  scene: string;
  /** 发票号或折让单号 */
  docNo: string;
  /** 打印中心认的事实引用，原样作为模拟的 source.factRef */
  factRef: FactRef;
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
  /** 作废本期折让（跨期拒绝） */
  mSiVoidAllowance(invoiceNo: string, allowanceNo: string, body: { reason: string }): Promise<SalesInvoice>;
  /** 向服务商查单并对账（会把服务商已收到的上传补记为已送达） */
  mSiProviderCheck(invoiceNo: string): Promise<SiProviderCheck>;
  /** 本张发票可模拟打印的打印件（作废的发票没有证明联与明细） */
  mSiPrintFacts(invoiceNo: string): Promise<SiPrintFact[]>;
  /** 本门店上传积压概览 */
  mSiUploadBacklog(): Promise<SiUploadBacklog>;
  /** 重传待人工处理的上传任务（开立 / 作废 / 折让），报文不变 */
  mSiRetransmit(invoiceNo: string): Promise<SalesInvoice>;
  /** 未开通时后端回 null */
  mSiProfile(): Promise<SiProfile | null>;
  mSiSaveProfile(body: SiProfileReq): Promise<SiProfile>;
  /** 本市场商家可选的开票供应商；regimeCode 不传 = 系统默认市场 */
  mSiProviders(q: { regimeCode?: string }): Promise<SiProviderOptions>;
  mSiRanges(q: { periodCode?: string }): Promise<SiRange[]>;
  mSiAddRange(body: SiRangeReq): Promise<SiRange>;
  /** 用已保存的加值中心账号核验；统编须一致 */
  mSiVerifyProvider(): Promise<SiProviderVerification>;
  /** 向加值中心取号段；periodCode 不传 = 当期 */
  mSiFetchProviderRange(body: { periodCode?: string }): Promise<SiRange>;
}
