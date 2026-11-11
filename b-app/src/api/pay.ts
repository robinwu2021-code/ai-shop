// 商家端支付（/biz/pay，TDD-支付服务-I5接入层 S8）。金额一律最小单位；币种由服务端给。
import type { OfflineRecordCreateReq, OfflineRecordListQuery, OfflineRecordSummaryQuery } from "./requests";

/** near-pay 来源主体的资料审核结果；不代表门店可收款。 */
export interface PayServiceStatus {
  /** NONE / PENDING / NEED_MORE / REJECTED / APPROVED，原样转发 PaySourceLinkApi.Status */
  status: string;
  /** 支付平台商户引用，尚未建立时为空 */
  merchantRef: string | null;
  /** 来源关联状态，尚未建立时为空 */
  linkStatus: string | null;
  /** 商户资料状态，尚无资料时为空 */
  profileStatus: string | null;
  /** 补充资料或驳回原因，支付平台未提供时为空 */
  reviewReason: string | null;
}

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

// ---------------------------------------------------------------- App 外收款记录（TDD-商户App外部记账）
// 商家在 App 外收到的钱（现金、外部刷卡机、银行转账）记一笔账：平台不经手、不进拨款、不进通道对账、不能退款、不改订单状态。

/** 收款方式。不用 POS：那个词在收款订单的 source 里已表示平台自己的 POS 收款 */
export type OfflineRecordMethod = "CASH" | "BANK_TRANSFER" | "EXTERNAL_POS" | "OTHER";
/** 已记 / 已作废（作废是终态，行不删） */
export type OfflineRecordStatus = "RECORDED" | "VOIDED";
/** 留痕动作 */
export type OfflineRecordAction = "CREATE" | "CORRECT" | "VOID";

/** 一步留痕；端上按 action 与前后值自己拼文字 */
export interface OfflineRecordHistory {
  /** 发生时刻，ISO 8601 带门店市场偏移 */
  at: string;
  /** 操作人号 */
  by: string;
  /** 操作人名字；取不到为 null，端上退回显示 by */
  byName: string | null;
  /** 这一步做了什么 */
  action: OfflineRecordAction;
  /** 更正 / 作废的原因；CREATE 为 null */
  reason: string | null;
  /** 改前金额（最小单位）；CREATE / VOID 为 null */
  fromAmountMinor: number | null;
  /** 改后金额（最小单位）；VOID 为 null */
  toAmountMinor: number | null;
  /** 改前方式；CREATE / VOID 为 null */
  fromMethod: OfflineRecordMethod | null;
  /** 改后方式；VOID 为 null */
  toMethod: OfflineRecordMethod | null;
  /** 改前实收时间；更正改了实收时间时才有 */
  fromReceivedAt: string | null;
  /** 改后实收时间；更正改了实收时间时才有 */
  toReceivedAt: string | null;
}

/** 外部收款记录（四个接口共用） */
export interface OfflineRecord {
  /** 记录号（OFR…），与支付单号空间不重叠 */
  recordNo: string;
  /** 金额（最小单位；按门店市场 currencyScale 解释，TWD 必为 100 的倍数） */
  amountMinor: number;
  /** 门店市场币种 */
  currency: string;
  /** 收款方式 */
  method: OfflineRecordMethod;
  /** 引用的本店子单号；只是引用，不推订单状态 */
  orderNo: string | null;
  /** 备注 */
  note: string | null;
  /** 实际收款时刻，ISO 8601 带门店市场偏移 */
  receivedAt: string;
  /** 记账人号 */
  recordedBy: string;
  /** 记账人名字；取不到为 null，端上退回显示 recordedBy */
  recordedByName: string | null;
  /** 记账时刻，ISO 8601 带门店市场偏移 */
  recordedAt: string;
  /** 已记 / 已作废 */
  status: OfflineRecordStatus;
  /** 是否被更正过 */
  corrected: boolean;
  /** 每次更正 / 作废 +1；更正、作废时作为 expectedVersion 带回 */
  version: number;
  /** 留痕，按时间正序 */
  history: OfflineRecordHistory[];
}

export interface OfflineRecordPage {
  /** 按实收时间倒序 */
  items: OfflineRecord[];
  /** 下一页游标；null = 没有了 */
  nextCursor: string | null;
}

/** 区间内未作废记录的合计：报表单列「外部記帳（商家自報）」，不并入渠道营业额 */
export interface OfflineRecordSummary {
  /** 门店市场的 yyyy-MM-dd，含 */
  from: string;
  /** 门店市场的 yyyy-MM-dd，含 */
  to: string;
  /** 门店市场币种 */
  currency: string;
  /** 未作废笔数 */
  count: number;
  /** 未作废合计（最小单位） */
  amountMinor: number;
}

export interface PayApi {
  /** 当前所选门店所属主体的支付服务资料审核状态，仅属主可查 */
  mPayServiceStatus(): Promise<PayServiceStatus>;
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
  /** 记一笔 App 外收款；key 为打开记账表单时生成的幂等键，重送沿用 */
  mPayRecordOffline(body: OfflineRecordCreateReq, key: string): Promise<OfflineRecord>;
  mPayOfflineRecords(query?: OfflineRecordListQuery): Promise<OfflineRecordPage>;
  mPayOfflineSummary(query?: OfflineRecordSummaryQuery): Promise<OfflineRecordSummary>;
}
