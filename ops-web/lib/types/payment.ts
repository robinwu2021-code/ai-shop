// 支付管理（矩阵 P-4.2）。
//
// 这一域处理的是**平台账与渠道账对不上**的那些情况 —— 它不是"再做一张订单表"，
// 而是把「渠道那边发生了什么」与「平台这边记了什么」摆在一起，让人能判。
//
// 三件事互相咬合，不能拆开看：
//   4.2.1 对账 → 找出差异
//   4.2.2 掉单补偿 → 处置「渠道收了钱、平台没记上」那一类差异
//   4.2.3 关单策略 → 决定未支付订单多久关掉，**关得太快就是在制造掉单**

/** 支付渠道。一期只接微信支付，枚举先留出位置（改枚举比改结构便宜）。 */
export type PayChannel = "WECHAT" | "ALIPAY" | "BALANCE";

/**
 * 对账差异类型。
 *
 * ⚠️ 这三类的**处置方式完全不同**，所以必须分开而不是笼统叫"异常"：
 * - `CHANNEL_ONLY`：渠道有、平台无 —— 用户付了钱但订单没落库，要**补单或退款**
 * - `PLATFORM_ONLY`：平台有、渠道无 —— 平台记了收款但渠道查无此笔，多半是误记，要**冲正**
 * - `AMOUNT_DIFF`：两边都有但金额不符 —— 要人去查是折扣算错还是重复扣款
 */
export type ReconDiffType = "CHANNEL_ONLY" | "PLATFORM_ONLY" | "AMOUNT_DIFF";

export type ReconStatus = "PENDING" | "RESOLVED" | "IGNORED";

export interface ReconDiff {
  /** 差异单号 */
  diffNo: string;
  /** 对账日期（渠道账单的账期），YYYY-MM-DD */
  billDate: string;
  /** 支付渠道 */
  channel: PayChannel;
  /** 渠道流水号；PLATFORM_ONLY 时为空（渠道根本没有这笔） */
  channelTxnNo?: string | null;
  /** 平台订单号；CHANNEL_ONLY 时为空（平台没落库） */
  orderNo?: string | null;
  /** 差异类型。**三类的处置方式完全不同**，见上方注释 */
  type: ReconDiffType;
  /** 渠道侧金额，最小货币单位（分）。PLATFORM_ONLY 为 0 */
  channelAmount: number;
  /** 平台侧金额（分）。CHANNEL_ONLY 为 0 */
  platformAmount: number;
  /** 处置状态 */
  status: ReconStatus;
  /** 处置结论。RESOLVED / IGNORED 必填 —— 没有结论的"已处理"等于没处理 */
  resolution?: string | null;
  /** 处置产生的补单号（仅 CHANNEL_ONLY 走补单时有） */
  recoveredOrderNo?: string | null;
  /** 差异产生时间 */
  createdAt: string;
  /** 处置时间。未处置为 null */
  resolvedAt?: string | null;
  /** 处置人（STAFF 账号）。未处置为 null */
  resolvedBy?: string | null;
}

/** 掉单补偿的处置方式（P-4.2.2）。 */
export type RecoverAction = "CREATE_ORDER" | "REFUND";

/**
 * 关单策略（P-4.2.3）。
 *
 * ⚠️ 这份配置与掉单**直接因果**：关单时限设得越短，"用户正在付款、订单已被关掉"
 * 的窗口就越大，而那正是 CHANNEL_ONLY 差异的主要来源。所以两者放同一页。
 */
export interface CloseRule {
  /** 未支付订单多少分钟后自动关单 */
  unpaidMinutes: number;
  /** 关单前多少分钟提醒用户（0 = 不提醒） */
  remindBeforeMinutes: number;
  /**
   * 关单后仍收到渠道支付回调时是否自动退款。
   * 关掉它意味着这笔钱要人工处理 —— 但至少不会静默退掉一笔本可以补单的钱。
   */
  autoRefundOnLateCallback: boolean;
  /** 最后修改时间 */
  updatedAt: string;
  /** 最后修改人（STAFF 账号） */
  updatedBy: string;
}
