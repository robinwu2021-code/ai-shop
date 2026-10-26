// 商家端支付（/biz/pay，TDD-支付服务-I5接入层 S8）。金额一律最小单位；币种由服务端给。

/** 流水行：收款 / 退款 */
export type PayStatementKind = "PAYMENT" | "REFUND";
/** 收款码：门店码 / 桌台码 */
export type PayQrKind = "STORE" | "TABLE";
export type PayQrStatus = "ACTIVE" | "DISABLED";
/** 授权第三方的动作：代收款 / 代退款 / 查单 */
export type PayGrantAction = "COLLECT" | "REFUND" | "READ";
export type PayGrantStatus = "ACTIVE" | "REVOKED";

export interface PayCollectResult {
  payOrderNo: string;
  /** 一次性收银令牌：只在这一次响应里出现，不落任何本地存储 */
  token: string;
  /** 链接到期（UTC 毫秒） */
  expireAt: number;
  /** 顾客扫的收银台链接 —— 页面据此画二维码 */
  checkoutUrl: string;
}

export interface PayRefundItem {
  payRefundNo: string;
  payOrderId: number;
  amountMinor: number;
  currency: string;
  /** PENDING_REVIEW 待平台复核 / DISPATCHED 退款中 / SUCCEEDED / FAILED / REJECTED … */
  status: string;
  reason: string | null;
  requestedBy: string | null;
  createdAt: number | null;
  succeededAt: number | null;
}

export interface PayStatementItem {
  kind: PayStatementKind;
  no: string;
  payOrderNo: string | null;
  amountMinor: number;
  payChannel: string | null;
  payMethod: string | null;
  status: string;
  at: number | null;
}

export interface PayDailyStatement {
  /** 业务日（门店市场时区）YYYY-MM-DD */
  bizDate: string;
  currency: string;
  paidCount: number;
  paidMinor: number;
  refundCount: number;
  refundMinor: number;
  items: PayStatementItem[];
}

export interface PayQrCode {
  qrCode: string;
  kind: PayQrKind;
  tableRef: string | null;
  currency: string;
  singleLimitMinor: number;
  dailyLimitMinor: number;
  status: PayQrStatus;
  /** 顾客扫的链接 */
  url: string;
}

export interface PayAppGrant {
  id: number;
  appNo: string;
  appName: string | null;
  actions: PayGrantAction[];
  status: PayGrantStatus;
  grantedAt: number | null;
}

export interface PayApi {
  mPayCollect(v: { amountMinor: number; subject?: string; expireMinutes?: number }): Promise<PayCollectResult>;
  mPayRefunds(): Promise<PayRefundItem[]>;
  /** outRefundNo：端上防连点时同一次操作带同一个 */
  mPayRefund(v: { payOrderNo: string; amountMinor: number; reason?: string; outRefundNo?: string }): Promise<PayRefundItem>;
  mPayStatement(day?: string): Promise<PayDailyStatement>;
  mPayQrCodes(): Promise<PayQrCode[]>;
  mPayCreateQrCode(v: { kind: PayQrKind; tableRef?: string; singleLimitMinor: number; dailyLimitMinor: number }): Promise<PayQrCode>;
  mPayDisableQrCode(qrCode: string): Promise<void>;
  mPayAppGrants(): Promise<PayAppGrant[]>;
  mPayGrantApp(v: { appNo: string; actions: PayAppGrant["actions"] }): Promise<PayAppGrant>;
  mPayRevokeGrant(id: number): Promise<void>;
}
