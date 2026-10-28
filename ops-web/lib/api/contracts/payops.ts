// 覆盖范围：支付运营端（TDD-支付服务-I5接入层 S9 · F50 F51 F53 F57；开发计划 M5b 接入与密钥）。
// 金额一律最小单位；时间一律 UTC 毫秒。运营**只复核不发起**退款。

export type PayOrderStatus = "CREATED" | "AWAITING_PAYMENT" | "SUCCEEDED" | "PARTIALLY_REFUNDED" | "REFUNDED" | "CLOSED";

export interface PayOpsOrderRow {
  payOrderNo: string;
  outOrderNo: string;
  bizType: string;
  storeId: number | null;
  storeName: string | null;
  market: string | null;
  currency: string;
  amountMinor: number;
  paidMinor: number;
  refundedMinor: number;
  status: PayOrderStatus;
  payChannel: string | null;
  payMethod: string | null;
  fundsPath: string | null;
  sourceType: string | null;
  createdAt: number | null;
  succeededAt: number | null;
}

export interface PayOpsAttemptRow {
  paymentNo: string;
  /** PAY / REFUND */
  direction: string;
  outTradeNo: string | null;
  tradeNo: string | null;
  payChannel: string | null;
  amountMinor: number;
  currency: string;
  status: string;
  payMerchantId: number | null;
  fundsPath: string | null;
  at: number | null;
}

export interface PayOpsRefundRow {
  payRefundNo: string;
  payOrderNo: string | null;
  amountMinor: number;
  currency: string;
  status: string;
  reason: string | null;
  requestedBy: string | null;
  sourceType: string | null;
  /** 命中的复核规则（NO_ACTIVE_RULE、AMOUNT_OVER…） */
  hitReasons: string | null;
  createdAt: number | null;
}

export interface PayOpsVoucherRow {
  voucherNo: string;
  voucherType: string;
  totalMinor: number;
  currency: string;
  fundsPath: string | null;
  bizDate: string | null;
  memo: string | null;
}

export interface PayOpsNotifyRow {
  id: number;
  eventType: string;
  eventKey: string;
  targetType: string;
  targetUrl: string | null;
  status: string;
  attempts: number;
  nextAt: number | null;
  deliveredAt: number | null;
  createdAt: number | null;
}

export interface PayOpsExceptionRow {
  id: number;
  /** LATE_PAYMENT / DUPLICATE_PAYMENT / AMOUNT_MISMATCH / CURRENCY_MISMATCH */
  kind: string;
  payOrderNo: string | null;
  amountMinor: number;
  currency: string;
  /** OPEN / AUTO_REFUNDING / RESOLVED / IGNORED */
  status: string;
  resolution: string | null;
  note: string | null;
  handledBy: string | null;
  createdAt: number | null;
}

export interface PayOpsOrderDetail {
  order: PayOpsOrderRow;
  attempts: PayOpsAttemptRow[];
  refunds: PayOpsRefundRow[];
  vouchers: PayOpsVoucherRow[];
  notifies: PayOpsNotifyRow[];
  exceptions: PayOpsExceptionRow[];
}

export type PayOpsOrderQ = { status?: string; payOrderNo?: string; outOrderNo?: string; storeId?: number; bizType?: string;
  page?: number; size?: number };

// ---- 接入与密钥（开发计划 M5b）：密钥只有指纹 / 序列号 / 状态，明文不出现 ----

export interface PayOpsAppRow {
  appNo: string;
  name: string;
  /** PARTNER / INTERNAL */
  ownerType: string;
  status: string;
  scopes: string | null;
  sandbox: boolean;
  signType: string | null;
  webhookUrl: string | null;
  rateLimitQps: number | null;
  dailyRefundLimitMinor: number | null;
  activeGrants: number;
}

export interface PayOpsGrantRow {
  storeId: number | null;
  actions: string | null;
  status: string;
  grantedBy: string | null;
  grantedAt: number | null;
  revokedAt: number | null;
}

export interface PayOpsCredentialRow {
  /** APP_SECRET / ENCRYPT_KEY */
  kind: string;
  version: number;
  /** DRAFT / VERIFIED / ACTIVE / ROTATING / RETIRED / REVOKED */
  status: string;
  fingerprint: string;
  serial: string | null;
  enteredBy: string | null;
}

export interface PayOpsRequestLogRow {
  id: number;
  traceId: string;
  /** OPEN_API / TERMINAL_API / INTERNAL_HTTP / BIZ_API / OPS_API / CHECKOUT / CALLBACK … */
  entry: string;
  api: string;
  payAction: string | null;
  appNo: string | null;
  operatorNo: string | null;
  sourceService: string | null;
  sourceModule: string | null;
  sourceInstance: string | null;
  sourceIp: string | null;
  originClient: string | null;
  originApi: string | null;
  clientIp: string | null;
  httpStatus: number | null;
  resultCode: string | null;
  /** ACCEPTED / REJECTED_AUTH / REJECTED_PARAM / REJECTED_FORBIDDEN / DUPLICATE / ERROR */
  outcome: string;
  elapsedMs: number | null;
  signKeyVersion: number | null;
  receivedAt: number | null;
}

/** 写入时就已脱敏的报文，原样给 */
export interface PayOpsRequestLogDetail {
  row: PayOpsRequestLogRow;
  requestMasked: string | null;
  responseMasked: string | null;
}

export interface PayOpsQrRow {
  qrCode: string;
  storeId: number | null;
  /** STORE / TABLE */
  kind: string;
  tableRef: string | null;
  currency: string;
  singleLimitMinor: number;
  dailyLimitMinor: number;
  status: string;
  createdByType: string | null;
  createdAt: number | null;
}

export interface PayOpsPlatformKeyRow {
  keyId: number;
  ownerType: string;
  ownerId: number;
  direction: string;
  usage: string;
  name: string;
  counterparty: string | null;
  status: string;
  currentVersion: number;
  expiresAt: number | null;
}

export interface PayOpsKeyVersionRow {
  version: number;
  status: string;
  fingerprint: string;
  serial: string | null;
  enteredBy: string | null;
}

export type PayOpsRequestLogQ = { entry?: string; appNo?: string; sourceService?: string; traceId?: string; outcome?: string;
  fromMs?: number; toMs?: number; page?: number; size?: number };

export interface PayOpsApi {
  payOpsOrders(q?: PayOpsOrderQ): Promise<PayOpsOrderRow[]>;
  payOpsOrder(payOrderNo: string): Promise<PayOpsOrderDetail>;
  payOpsRefunds(q?: { status?: string; limit?: number }): Promise<PayOpsRefundRow[]>;
  /** 两人复核：同一人不能通过两次；申请人不能复核自己 */
  payOpsReviewRefund(payRefundNo: string, v: { approve: boolean; reason?: string }): Promise<PayOpsRefundRow>;
  payOpsNotifies(q?: { status?: string; limit?: number }): Promise<PayOpsNotifyRow[]>;
  payOpsResendNotify(id: number): Promise<void>;
  payOpsExceptions(q?: { status?: string; limit?: number }): Promise<PayOpsExceptionRow[]>;
  /** 原路退回那一笔多收的钱（不碰已关支付单的余额） */
  payOpsRefundException(id: number): Promise<void>;
  payOpsIgnoreException(id: number, note: string): Promise<void>;
  payOpsDisableQrCode(qrCode: string): Promise<void>;
  payOpsQrCodes(q?: { storeId?: number; status?: string; page?: number; size?: number }): Promise<PayOpsQrRow[]>;
  payOpsApps(): Promise<PayOpsAppRow[]>;
  payOpsAppGrants(appNo: string): Promise<PayOpsGrantRow[]>;
  payOpsAppCredentials(appNo: string): Promise<PayOpsCredentialRow[]>;
  /** 吊销即时生效：下一次用这一版签的请求就 401 */
  payOpsRevokeAppCredential(appNo: string, kind: string, version: number, reason: string): Promise<void>;
  payOpsRequestLogs(q?: PayOpsRequestLogQ): Promise<PayOpsRequestLogRow[]>;
  payOpsRequestLog(id: number): Promise<PayOpsRequestLogDetail>;
  payOpsPlatformKeys(): Promise<PayOpsPlatformKeyRow[]>;
  payOpsPlatformKeyVersions(keyId: number): Promise<PayOpsKeyVersionRow[]>;
  /** 两人：启用人不能是录入人（后端 70088） */
  payOpsEnablePlatformKey(keyId: number, version: number): Promise<void>;
  payOpsRevokePlatformKey(keyId: number, version: number, reason: string): Promise<void>;
}
