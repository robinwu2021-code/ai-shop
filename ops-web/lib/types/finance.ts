// 结算与资金域（矩阵 P-12）。它是「按商家拆单 + 分账」链路的收口：
// 把商家的 settleAccountReady、自提点的 serviceFeeRate、订单的 trafficSource、
// 售后的 refundSplitPending 这四个**已存在的字段**接起来。
import type { TrafficSource } from "./order";

export type SettleStatus =
  | "PENDING"      // 已生成，待下发分账指令
  | "SPLITTING"    // 指令已下发，等回执
  | "SPLIT"        // 分账成功（终态）
  | "FAILED"       // 失败，等人工介入
  | "FROZEN_BACK"; // 超时兜底：解冻回平台（终态，12.1.4）

export const SETTLE_TRANSITIONS: Record<SettleStatus, SettleStatus[]> = {
  PENDING: ["SPLITTING", "FROZEN_BACK"],
  SPLITTING: ["SPLIT", "FAILED"],
  // 失败可重试（回到下发中），也可能被超时兜底收走
  FAILED: ["SPLITTING", "FROZEN_BACK"],
  SPLIT: [],
  FROZEN_BACK: [],
};

/**
 * 结算单：一个商家一个周期一张。
 * ⚠️ **对账恒等式**：gross = platformFee + serviceFee + net。
 * 这三个数分别来自三处（费率表、自提点配置、余数），不校验就会出现"分完了还差几分钱"。
 */
export interface Settlement {
  /** 结算单号 */
  settleNo: string;
  /** 结算对象商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 结算周期，如 2026-08-上 */
  period: string;
  /** 本期结算的子订单笔数 */
  orderCount: number;
  /** 应结总额（分）= 子订单实付合计 */
  grossAmount: number;
  /** 平台佣金（分）。按「分账内扣」实现（12.1.6 口径待定） */
  platformFee: number;
  /** 自提点履约服务费（分，R15） */
  serviceFee: number;
  /** 实付商家（分） */
  netAmount: number;
  /** 结算状态。允许的流转见 `SETTLE_TRANSITIONS` */
  status: SettleStatus;
  /** 分账指令重试次数（上限见 lib/constants.ts） */
  retryCount: number;
  /** 失败原因。`status=FAILED` 时有值，人工介入据此判断 */
  failReason?: string;
  /** 冻结开始时间：超过 freezeDays 未成功就解冻回平台 */
  frozenAt: string;
  /** 结算单生成时间 */
  createdAt: string;
}

/** 分账明细：一条 = 一个子订单。费率按 trafficSource 分档（R16）。 */
export interface SplitRecord {
  /** 分账明细单号 */
  splitNo: string;
  /** 所属结算单 */
  settleNo: string;
  /** 对应的子订单。**一条明细 = 一个子订单** */
  orderNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 该订单的流量来源，决定适用哪一档费率（R16） */
  trafficSource: TrafficSource;
  /** 该订单实付金额（分） */
  grossAmount: number;
  /** 本条实际适用的平台佣金费率（万分比），来自费率表 */
  feeRate: number;
  /** 本条的平台佣金（分） */
  platformFee: number;
  /** 履约自提点。非自提单为空 */
  pickupNo?: string;
  /** 自提点履约服务费（分）；非自提单为 0 */
  serviceFee: number;
  /** 实付商家（分）。**恒等式**：grossAmount = platformFee + serviceFee + netAmount */
  netAmount: number;
}

/** 费率配置（P-12.1.7 / 12.1.8 / 12.1.4）。全部万分比。 */
export interface FeeRule {
  /**
   * 按流量来源分档的平台佣金费率（R16）。
   * ⚠️ `MERCHANT_OWNED`（商家自带客流）**建议 0** —— 商家自己把客人带来的单还抽佣，
   * 商家就会把客人带去别处成交（ADR-004 的增长模型立不住）。口径未定，故可配。
   */
  byTrafficSource: Record<TrafficSource, number>;
  /** 自提点履约服务费默认费率（R15）；自提点自己配了就用它自己的 */
  pickupServiceFeeRate: number;
  /** 超时兜底天数（12.1.4）：冻结超过它仍未分账成功，解冻回平台 */
  freezeDays: number;
  /** 最后修改时间。**改费率不影响已生成的结算单** */
  updatedAt: string;
  /** 最后修改人（STAFF 账号） */
  updatedBy: string;
}

// ── 提现审批（P-12.2.1）───────────────────────────────────────────

/**
 * 提现状态。
 *
 * ⚠️ `APPROVED → PAID` **不由运营点** —— 打款结果来自渠道回执。
 * 让人手动置为"已打款"，就等于允许在钱没到账时把单子做平。
 */
export type WithdrawStatus = "PENDING" | "APPROVED" | "REJECTED" | "PAID" | "FAILED";

export const WITHDRAW_TRANSITIONS: Record<WithdrawStatus, WithdrawStatus[]> = {
  PENDING: ["APPROVED", "REJECTED"],
  // 打款成功/失败都由回执驱动，运营在界面上没有这两个动作
  APPROVED: ["PAID", "FAILED"],
  // 打款失败可以重新审批（多半是账户信息要改）
  FAILED: ["APPROVED", "REJECTED"],
  REJECTED: [],
  PAID: [],
};

export interface Withdrawal {
  /** 提现单号 */
  withdrawNo: string;
  /** 申请商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 申请金额（分） */
  amount: number;
  /** 申请时的可提余额（分）。快照，不是实时值 —— 审批看的是申请那一刻的口径 */
  availableBalance: number;
  /** 收款账户，展示一律脱敏 */
  bankAccountMasked: string;
  /** 提现状态。**`APPROVED → PAID` 由渠道回执驱动，运营点不了** */
  status: WithdrawStatus;
  /** 申请时间 */
  appliedAt: string;
  /** 审批时间。未审为 null */
  decidedAt?: string | null;
  /** 审批人（STAFF 账号）。未审为 null */
  decidedBy?: string | null;
  /** 驳回原因 / 大额复核说明。原样回商家 B 端 */
  remark?: string | null;
}

// ── 发票与个税（P-12.2.2 / 12.2.3）────────────────────────────────

/** 抬头类型。企业抬头必须有税号，个人抬头没有 —— 这是两条不同的校验路径。 */
export type InvoiceTitleType = "COMPANY" | "PERSONAL";

export type InvoiceStatus = "PENDING" | "ISSUED" | "REJECTED";

export interface InvoiceRequest {
  /** 开票申请单号 */
  invoiceNo: string;
  /** 申请商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 开票周期，与结算周期同口径 */
  period: string;
  /** 申请开票金额（分） */
  amount: number;
  /** 该周期已结算金额（分）。开票金额不能超过它 —— 超了就是虚开 */
  settledAmount: number;
  /** 抬头类型。企业抬头必须有税号，个人抬头没有 —— 两条不同的校验路径 */
  titleType: InvoiceTitleType;
  /** 发票抬头（公司全称或个人姓名） */
  title: string;
  /** 纳税人识别号。企业抬头必填 */
  taxNo?: string | null;
  /** 开票状态 */
  status: InvoiceStatus;
  /** 开票后的发票流水号 */
  serialNo?: string | null;
  /** 申请时间 */
  appliedAt: string;
  /** 处理时间。未处理为 null */
  decidedAt?: string | null;
  /** 驳回原因。原样回商家 B 端 */
  remark?: string | null;
}

/**
 * 个税代扣规则（P-12.2.3）。
 *
 * 只对**个人主体**商家生效：个体户与企业自行申报，平台不代扣。
 * 起征点以下不扣 —— 不设起征点会给每一笔几块钱的提现都产生一条扣税记录。
 */
export interface TaxRule {
  /** 起征点（分）：单期收入低于它不代扣 */
  threshold: number;
  /** 代扣税率（万分比） */
  rate: number;
  /** 最后修改时间 */
  updatedAt: string;
  /** 最后修改人（STAFF 账号） */
  updatedBy: string;
}
