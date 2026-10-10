// 覆盖范围：分账结算（P-12.1）与提现·发票·个税（P-12.2）。
import type { PayChannelSetting, PayChannelRateVersion, SettleBatch, MerchantDebt,
  PurchaseInvoice,
  BuyerInvoiceRequest,
  ClientPointsPolicy, PointsOverview, AfterSale, BusinessMode, EffectiveFeeRates, FeeRuleVersion, FeeTrafficSource, InvoiceRequest, InvoiceTitle, Page, Payout, PayoutAccount, PayoutList, SettleStatRow, Settlement, SplitLog, TaxRule,
  BankFlowImportResult,
} from "@/lib/types";
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

  // ── 供应商收款账户（V358，ADR-011）。**审核 = 资金重定向**：
  // 通过之后这个主体下一期的货款就打到这张卡，所以它与登记付款同一个权限码。
  /** @param status 空 = 全部；审核队列传 PENDING */
  listPayoutAccounts(q?: { status?: string; entityNo?: string; page?: number; size?: number }):
    Promise<Page<PayoutAccount>>;
  /** 审核。**驳回必须写 remark** —— 后端拒空，且原文回商家 */
  auditPayoutAccount(accountNo: string, pass: boolean, remark?: string): Promise<PayoutAccount>;

  // ── 结算口径的经营统计（P-12.1）。三维可切，按成交日区间聚合。
  /**
   * 付款清单（P2）。**响应里带明文账号**，拿到即写进导出文件，别存进 state。
   * 每次调用后端都写 critical 审计 —— 它不是普通读操作。
   * @param entityNo 只导某一家，空则全部
   */
  payoutList(entityNo?: string): Promise<PayoutList>;

  /**
   * 导入银行流水（TDD §10）。出款对账 B 侧的**数据入口** ——
   * 在它之前，「银行到底有没有划出这笔」在系统里看不见。
   *
   * **传的是文件内容的文本，不是 multipart**：解析在服务端（判据不能放在浏览器里，
   * 而且银企直连接上时换的是取数那一段）。页面读文件时要兜底 GBK ——
   * 网银导出的 CSV 常常不是 UTF-8。
   *
   * **重复上传不是错误**：已存在的流水号计入 `skipped`，照常 200。
   */
  importBankFlows(fileName: string, csv: string): Promise<BankFlowImportResult>;

  /** @param dim STORE / ENTITY / PAY_MERCHANT；`from`/`to` 是 yyyy-MM-dd，含两端 */
  listSettleStats(q: { dim: string; from: string; to: string; businessMode?: string }):
    Promise<SettleStatRow[]>;

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

  // ── 账期批次（P-12.1）。**批次管「能不能放」，单据管「放得成不成」**

  /**
   * 账期批次列表。
   *
   * @param status 空 = 全部；`BLOCKED` 就是**待处置队列** —— 这一页最常用的筛选
   */
  listSettleBatches(q?: { status?: string; entityNo?: string }): Promise<SettleBatch[]>;

  /**
   * 人工放行一批。
   *
   * ⚠️ `remark` **必填**：事后要能回答「当时凭什么放的」。
   * 与超时自动放行（`decidedBy = SYSTEM_TIMEOUT`）分开统计 ——
   * 那个数持续大于零说明挂起时限比处置能力短，要调的是时限不是任务。
   */
  approveSettleBatch(batchNo: string, remark: string): Promise<SettleBatch>;

  /** 继续挂起。同样必须写原因 */
  holdSettleBatch(batchNo: string, remark: string): Promise<SettleBatch>;

  /**
   * **放款**（V391）：RECONCILED → RELEASED，按收款号生成放款记录。
   * 三道闸（票、账户、状态）在后端。这是运营端唯一让钱出去的动作。
   *
   * ⚠️ 2026-10-09 之前 `releaseSettleBatch` 调的是上面那个「挂起处置通过」，从来不放钱。
   */
  releaseSettleBatch(batchNo: string): Promise<Payout[]>;

  // ── 放款记录（V391）：一笔网银转账一条，凭证与银行流水挂在它上面

  /** @param status 空 = 全部；PENDING 待导出、EXPORTED 已导出待登记 */
  listPayouts(q?: { status?: string; entityNo?: string }): Promise<Payout[]>;
  /** 登记凭证。凭证号必填 */
  payPayout(payoutNo: string, paymentRef: string): Promise<Payout>;
  /** 打款失败或退回。原因必填；结算单回待对账、批次回可放款 */
  failPayout(payoutNo: string, reason: string): Promise<Payout>;

  // ── 商家欠款（Z4 追偿第二层）

  /** 某商家的欠款余额与流水 */
  merchantDebt(entityNo: string): Promise<MerchantDebt>;

  /**
   * 用保证金抵掉一部分欠款。**人工动作，不自动**（ADR-022 §3.3）——
   * 动的是商家的**本金**，而未经同意扣款的合规边界还没定。
   *
   * 实际抵扣两头封顶：不超过欠款余额，也不超过保证金**可用**额
   * （冻结中的那部分正被别的争议占着）。
   */
  /**
   * @param requestNo **必填**的幂等键，跟着运营的这一次意图走、不跟着每一次点击走。
   *                  这个动作不是自然幂等的：它算 min(欠款, 请求额, 保证金可用)，
   *                  点第二次时三个数都变小了，于是会接着扣，而每次单看都「算得对」。
   */
  offsetDebtByDeposit(entityNo: string, amountMinor: number, reason: string,
    requestNo: string): Promise<MerchantDebt>;

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
