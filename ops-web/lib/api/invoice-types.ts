/**
 * 运营端 · 电子发票（后端 OpsSalesInvoiceController，TDD-发票模块 §18）。
 * 与「买家开票申请」（/ops/invoice-requests，平台人工开票）不是一回事：这里是门店当场开的法定电子发票。
 */

/** 营业人一览的问题筛选 */
/** 生效供应商的来源 */
export type EInvoiceGatewaySource = "MERCHANT" | "DEFAULT";
export type EInvoiceProblem = "HELD" | "OVERDUE" | "REVIEW" | "BACKLOG" | "LOW_RANGE";
/** 上传任务状态（与后端 ivc_transmission.status 逐字一致） */
export type EInvoiceUploadStatus = "PENDING" | "SENDING" | "SENT" | "FAILED" | "HELD" | "NEEDS_REVIEW";

export interface EInvoiceSeller {
  entityNo: string;
  regimeCode: string;
  sellerTaxId: string;
  sellerName: string;
  /** 当前生效的供应商 */
  gatewayCode: string;
  /** MERCHANT 商家自选 / DEFAULT 跟随市场默认（平台改配置即切换） */
  gatewaySource: EInvoiceGatewaySource;
  status: string;
  providerAccountCode?: string | null;
  providerCredentialConfigured: boolean;
  /** 账号认证失败造成的上传暂停原因；空 = 正常 */
  providerHoldReason?: string | null;
  providerHeldAt?: string | null;
  currentPeriod?: string | null;
  currentRemaining: number;
  rangeLowWater: number;
  autoFetchRanges: boolean;
  pending: number;
  held: number;
  needsReview: number;
  dueSoon: number;
  overdue: number;
}

export interface EInvoiceSellerQuery {
  gatewayCode?: string;
  problem?: EInvoiceProblem;
  keyword?: string;
  page: number;
  size: number;
}

export interface EInvoiceTransmission {
  transmissionNo: string;
  entityNo: string;
  storeNo?: string | null;
  /** ISSUE / VOID / ALLOWANCE / ALLOWANCE_VOID */
  messageType: string;
  refNo: string;
  invoiceNo: string;
  fiscalNumber?: string | null;
  gatewayCode: string;
  status: EInvoiceUploadStatus;
  attempts: number;
  lastError?: string | null;
  nextAttemptAt?: string | null;
  reportDeadlineAt?: string | null;
  overdue: boolean;
  createdAt: string;
}

export interface EInvoiceTransmissionQuery {
  entityNo?: string;
  status?: EInvoiceUploadStatus;
  overdue?: boolean;
  page: number;
  size: number;
}

/** 发票（后端 InvoiceVO 的运营端用到的字段） */
export interface EInvoice {
  invoiceNo: string;
  storeNo: string;
  sellerName: string;
  sellerTaxId: string;
  fiscalNumber: string;
  periodCode: string;
  issuedAt: string;
  currency: string;
  totalMinor: number;
  status: string;
  uploadStatus?: EInvoiceUploadStatus | null;
  uploadDeadlineAt?: string | null;
}

export interface EInvoiceQuery {
  entityNo?: string;
  storeNo?: string;
  fiscalNumber?: string;
  periodCode?: string;
  status?: string;
  page: number;
  size: number;
}

export interface EInvoiceProviderCheck {
  providerState: string;
  providerCode: string;
  expectedState: string;
  /** CONSISTENT / PENDING_UPLOAD / MISMATCH */
  verdict: string;
  reconciled: string[];
  checkedAt: string;
}

export interface EInvoiceProviderVerification {
  taxId: string;
  name?: string | null;
  qrKeyStored: boolean;
}

export interface EInvoiceRange {
  rangeNo: string;
  periodCode: string;
  track: string;
  startNo: number;
  endNo: number;
  remaining: number;
}
