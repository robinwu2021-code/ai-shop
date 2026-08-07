// 覆盖范围：分账结算（P-12.1）与提现·发票·个税（P-12.2）。
import type { AfterSale, FeeRule, InvoiceRequest, Page, Settlement, SplitRecord, TaxRule, Withdrawal } from "@/lib/types";
import type { PageQ, SettlementQ } from "../query";

export interface FinanceApi {
  listSettlements(q?: SettlementQ): Promise<Page<Settlement>>;
  /**
   * 下发分账指令（P-12.1.3）。
   * 前置：商家**已报备分账接收方**（读商家档案 `settleAccountReady`，ADR-002）；
   * 重试有上限，超过转 FAILED 等人工介入。
   */
  executeSplit(settleNo: string): Promise<Settlement>;
  /** 超时兜底（P-12.1.4）：冻结超过 freezeDays 仍未成功的，解冻回平台。 */
  freezeBackSettlement(settleNo: string): Promise<Settlement>;
  listSplitRecords(q?: PageQ & { settleNo?: string }): Promise<Page<SplitRecord>>;

  /** 待回退分账的售后单（P-12.1.5 / E4）：售后裁决打的 `refundSplitPending` 标记。 */
  listRefundSplitBacks(): Promise<AfterSale[]>;
  /** 执行退款回退分账，**执行后清除该售后单的标记**，否则队列永远消不掉。 */
  executeRefundSplitBack(asNo: string): Promise<AfterSale>;

  getFeeRule(): Promise<FeeRule>;
  /** 费率配置（P-12.1.7 / 12.1.8 / 12.1.4）。 */
  saveFeeRule(v: Pick<FeeRule, "byTrafficSource" | "pickupServiceFeeRate" | "freezeDays">): Promise<FeeRule>;

  // ── 提现审批（P-12.2.1）──────────────────────────────────────

  listWithdrawals(q?: PageQ & { status?: string }): Promise<Page<Withdrawal>>;

  /**
   * 审批一笔提现。这是**运营端唯一会把钱打出去**的动作，校验最密：
   *
   * - 只有 `PENDING` / `FAILED` 能审批（状态机）；
   * - 金额不得超过申请时的可提余额快照 —— 用快照而不是实时值，
   *   因为审批看的是"申请那一刻他能提多少"，实时值会因为期间的新订单而漂移；
   * - 商家**未报备分账接收方**不能通过：没有收款账户，批了钱也打不出去（ADR-002）；
   * - 商家**已封禁**不能通过：要先解封，那是另一条链路上的决定（P-11.1.4）；
   * - 低于单笔下限不能通过：渠道手续费比本金还贵；
   * - 超过复核阈值必须写复核说明；驳回必须写原因。
   *
   * ⚠️ 通过后落 `APPROVED` 而不是 `PAID` —— 打款结果来自渠道回执，
   * 让人手动置为"已打款"就等于允许在钱没到账时把单子做平。
   */
  decideWithdrawal(v: { withdrawNo: string; pass: boolean; remark?: string }): Promise<Withdrawal>;

  // ── 发票与个税（P-12.2.2 / 12.2.3）──────────────────────────

  listInvoiceRequests(q?: PageQ & { status?: string }): Promise<Page<InvoiceRequest>>;

  /**
   * 开票。
   *
   * - 企业抬头**必须有税号**；
   * - 开票金额不得超过该周期已结算金额 —— 超出部分就是虚开；
   * - **已开票的不能再开**：重复开票就是重复虚开。
   */
  issueInvoice(v: { invoiceNo: string; serialNo: string }): Promise<InvoiceRequest>;
  rejectInvoice(v: { invoiceNo: string; reason: string }): Promise<InvoiceRequest>;

  getTaxRule(): Promise<TaxRule>;
  /** 个税代扣规则。只对个人主体生效；税率上限与起征点见 lib/constants.ts。 */
  saveTaxRule(v: Pick<TaxRule, "threshold" | "rate">): Promise<TaxRule>;
}
