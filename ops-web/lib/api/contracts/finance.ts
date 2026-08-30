// 覆盖范围：分账结算（P-12.1）与提现·发票·个税（P-12.2）。
import type { PayChannelSetting, PayChannelRateVersion,
  PurchaseInvoice,
  BuyerInvoiceRequest,
  ClientPointsPolicy, PointsOverview, AfterSale, BusinessMode, EffectiveFeeRates, FeeRuleVersion, FeeTrafficSource, InvoiceRequest, InvoiceTitle, Page, Settlement, SplitLog, TaxRule, Withdrawal } from "@/lib/types";
import type { PageQ, SettlementQ } from "../query";

export interface FinanceApi {
  /**
   * 积分资金总览。**只读** —— 池子的钱是靠流水推出来的，不是靠人改的。
   * 开一个「手工调整余额」的入口，等于允许在没有业务事件的情况下改账，
   * 而那之后恒等式失衡就再也说不清是哪一笔。
   */
  pointsOverview(market?: string): Promise<PointsOverview>;

  /**
   * 积分的**端策略**：哪个端不发放、哪个端不核销、当面付能不能抵扣。
   *
   * ⚠️ 存的是**禁用名单**（`X-Client` 还没全量在发，允许名单会静默关掉全站积分），
   * 而且**不是合规硬闸** —— 端标识来自客户端、可伪造。
   */
  pointsClientPolicy(): Promise<ClientPointsPolicy>;
  savePointsClientPolicy(v: ClientPointsPolicy): Promise<ClientPointsPolicy>;

  /** 结算单列表。**自营与第三方都在这里** —— 不该因经营模式分成两个入口。 */
  /**
   * 结算单列表。**返回分页包**，不是裸数组 ——
   * 后端 `GET /ops/settlements` 返的是 `{records,total,page,size}`（`PageData.ofAll`）。
   * 此前这里声明成 `Settlement[]`，真接口下 `rows.filter is not a function` 整页崩，
   * 而 mock 里是数组，所以本地怎么点都正常。
   */
  // ── 自营应付账款（P-12.1）。**今天唯一真能把钱付出去的路** ——
  // 第三方走分账而分账网关是桩。后端十个端点早已实现，此前运营端零入口。
  /** @param status 空 = 全部；常用 PENDING_RECON（待对账）/ CONFIRMED（待付款） */
  listPayables(q?: { status?: string; entityNo?: string }): Promise<Settlement[]>;
  /** 确认对账：双方认了这个数。**之后才能收票、付款** */
  confirmPayable(settleNo: string): Promise<Settlement>;
  /** 登记已付款。`paymentRef` 是网银流水号 —— 没有它，之后对账差额永远说不清 */
  payPayable(settleNo: string, paymentRef: string): Promise<Settlement>;
  /** 标记无票供应商：**不进发票流程，但要在应付列表上标出来** —— 让财务付款前就看见 */
  markNoInvoice(settleNo: string, reason: string): Promise<Settlement>;

  // ── 进项票（供应商开给平台）
  listPurchaseInvoices(q?: { status?: string }): Promise<PurchaseInvoice[]>;
  verifyPurchaseInvoice(invoiceNo: string): Promise<PurchaseInvoice>;
  rejectPurchaseInvoice(invoiceNo: string, reason: string): Promise<PurchaseInvoice>;

  // ── 买家的开票申请（/ops/invoice-requests）。
  // ⚠️ 与既有的 `listSettleInvoices`（**商家**开票申请，/ops/finance/invoices）
  // 是两回事：那个按主体与账期走，这个按订单走。三张票的区别见 BuyerInvoiceRequest 的注释
  listBuyerInvoiceRequests(q?: { status?: string }): Promise<BuyerInvoiceRequest[]>;
  markBuyerInvoiceIssued(requestNo: string, invoiceNo: string): Promise<BuyerInvoiceRequest>;
  rejectBuyerInvoiceRequest(requestNo: string, reason: string): Promise<BuyerInvoiceRequest>;

  listSettlements(q?: { status?: string; merchantNo?: string; businessMode?: string }): Promise<Page<Settlement>>;
  /*
   * **运营端不下发分账、不解冻**：分账的下发与回退有它们自己的触发路径
   * （结算生成、售后退款）。在运营台放一个「立即分账」按钮，
   * 等于给人一个绕过状态机的口子 —— 而这条链路动的是真钱。
   * 所以这里只留读。
   */
  listSplitRecords(q?: { settleNo?: string; action?: string }): Promise<Page<SplitLog>>;

  /** 待回退分账的售后单（P-12.1.5 / E4）：售后裁决打的 `refundSplitPending` 标记。 */
  listRefundSplitBacks(): Promise<AfterSale[]>;
  /** 执行退款回退分账，**执行后清除该售后单的标记**，否则队列永远消不掉。 */
  executeRefundSplitBack(asNo: string): Promise<AfterSale>;

  // ── 费率（后端 stl_fee_rule）────────────────────────────────

  /**
   * 全部费率版本，含历史。
   *
   * <b>不提供「改」和「删」</b>：调费率是插一个新版本，旧版本永久保留。
   * 原地改只能回答「现在是多少」，而真正会被问到的是
   * 「上个月那批单当时按什么费率算的」。
   */
  listFeeRules(): Promise<FeeRuleVersion[]>;

  /**
   * 某时刻实际生效的四格费率。
   *
   * 单独一个接口而不是让页面从版本列表里自己推：
   * 「哪一版此刻在生效」牵涉停用回退的语义，前端推错了不会报错，只会显示错。
   */
  effectiveFeeRates(at?: number): Promise<EffectiveFeeRates>;

  /** 新增一个费率版本。`effectiveFrom` 留空 = 立即生效，填未来时刻 = 预约生效。 */
  addFeeRule(v: {
    businessMode: BusinessMode;
    trafficSource: FeeTrafficSource;
    rateBp: number;
    effectiveFrom?: number;
    remark?: string;
  }): Promise<FeeRuleVersion>;

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

  /**
   * 平台开票抬头。后端一直有这两个端点（P0-11），**运营端此前没有入口** ——
   * 于是「供应商开不出票」这件事在界面上无从处置。
   */
  getInvoiceTitle(): Promise<InvoiceTitle>;
  /** 公司全称与税号必填 —— 缺了供应商开不出票，存下去只会让人以为已经配好了。 */
  saveInvoiceTitle(v: InvoiceTitle): Promise<InvoiceTitle>;

  getTaxRule(): Promise<TaxRule>;
  /** 个税代扣规则。只对个人主体生效；税率上限与起征点见 lib/constants.ts。 */
  saveTaxRule(v: Pick<TaxRule, "threshold" | "rate">): Promise<TaxRule>;
  /** 支付通道设置 + 每个通道的费率版本。 */
  listPayChannels(): Promise<PayChannelSetting[]>;

  /**
   * 改通道的开关与结算属性。
   *
   * <b>能力位不在这里改</b> —— 支不支持补差、分账上限多少是通道自己的事实，
   * 不是运营的选择；改错会让积分抵扣在一个做不到补差的通道上开出来，那是资金差错。
   */
  updatePayChannel(channel: string, v: {
    enabled?: boolean;
    markets?: string;
    currency?: string;
    settleCycle?: string;
  }): Promise<PayChannelSetting>;

  /** 加一版通道费率。**不改旧行**，与 `addFeeRule` 同一条规矩。 */
  addPayChannelRate(channel: string, v: {
    payMethod?: string;
    legalForm?: string;
    rateBp: number;
    minFeeMinor?: number;
    effectiveFrom?: number;
    remark?: string;
  }): Promise<PayChannelRateVersion>;

}
