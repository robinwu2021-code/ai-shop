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
  /** 这笔代客收款建出的支付单号（PYO…）；到账后在收款流水里按它对得上 */
  payOrderNo: string;
  /** 一次性收银令牌：只在这一次响应里出现，不落任何本地存储 */
  token: string;
  /** 链接到期（UTC 毫秒） */
  expireAt: number;
  /** 顾客扫的收银台链接 —— 页面据此画二维码 */
  checkoutUrl: string;
}

export interface PayRefundItem {
  /** 退款单号（PYR…） */
  payRefundNo: string;
  /** 原支付单的内部 id（只用于同一页内去重，不展示） */
  payOrderId: number;
  /** 退款金额（最小单位） */
  amountMinor: number;
  /** 币种，与原支付单一致 */
  currency: string;
  /** PENDING_REVIEW 待平台复核 / DISPATCHED 退款中 / SUCCEEDED / FAILED / REJECTED … */
  status: string;
  /** 商家填的退款原因；平台复核时唯一能看到的「为什么」 */
  reason: string | null;
  /** 发起的商家账号 */
  requestedBy: string | null;
  /** 发起时间（UTC 毫秒） */
  createdAt: number | null;
  /** 退成时间（UTC 毫秒）；未退成为空 */
  succeededAt: number | null;
}

export interface PayStatementItem {
  /** 收款或退款 */
  kind: PayStatementKind;
  /** 单号：收款行是支付单号，退款行是退款单号 */
  no: string;
  /** 关联的支付单号（退款行据此找回原收款） */
  payOrderNo: string | null;
  /** 金额（最小单位，恒为正；退款行由页面加负号） */
  amountMinor: number;
  /** 渠道码（WECHAT / ALIPAY / TEST …） */
  payChannel: string | null;
  /** 支付方式码（WECHAT_PAY / ALIPAY / TEST_PAY …），页面译成名字 */
  payMethod: string | null;
  /** 支付单或退款单状态 */
  status: string;
  /** 成功时间（UTC 毫秒） */
  at: number | null;
}

export interface PayDailyStatement {
  /** 业务日（门店市场时区）YYYY-MM-DD */
  bizDate: string;
  /** 本店市场的收款币种 */
  currency: string;
  /** 当日成功收款笔数 */
  paidCount: number;
  /** 当日成功收款合计（最小单位） */
  paidMinor: number;
  /** 当日退成笔数 */
  refundCount: number;
  /** 当日退成合计（最小单位） */
  refundMinor: number;
  /** 明细，时间倒序；一天最多 1000 行（合计照算） */
  items: PayStatementItem[];
}

export interface PayQrCode {
  /** 码值（随机、不可枚举），也是停用时的标识 */
  qrCode: string;
  /** 门店码或桌台码 */
  kind: PayQrKind;
  /** 桌号（桌台码才有） */
  tableRef: string | null;
  /** 币种（按门店市场） */
  currency: string;
  /** 单笔上限（最小单位） */
  singleLimitMinor: number;
  /** 单码每日累计上限（最小单位） */
  dailyLimitMinor: number;
  /** 使用中 / 已停用（停用不可恢复） */
  status: PayQrStatus;
  /** 顾客扫的链接 */
  url: string;
}

export interface PayAppGrant {
  /** 授权记录 id（撤销时用） */
  id: number;
  /** 第三方接入方 AppId */
  appNo: string;
  /** 第三方名称 */
  appName: string | null;
  /** 授权的动作 */
  actions: PayGrantAction[];
  /** 已授权 / 已撤销 */
  status: PayGrantStatus;
  /** 授权时间（UTC 毫秒） */
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
  mPayGrantApp(v: { appNo: string; actions: PayGrantAction[] }): Promise<PayAppGrant>;
  mPayRevokeGrant(id: number): Promise<void>;
}
