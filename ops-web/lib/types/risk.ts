// 风控域（矩阵 P-16.2）。三类事件同表用 type 区分：结构一致，
// 拆三张表会让"这个用户同时命中几类"看不出来 —— 而那恰恰最该优先处理。

export type RiskType = "FAKE_ORDER" | "ABNORMAL_FISSION" | "MALICIOUS_REFUND";
export type RiskStatus = "PENDING" | "CONFIRMED" | "DISMISSED";

export interface RiskEvent {
  /** 风险事件单号 */
  eventNo: string;
  /** 风险类型。**三类同表用 type 区分** —— 拆表就看不出「同时命中几类」 */
  type: RiskType;
  /** 主体：用户昵称 / 商家名 / 设备号 */
  subject: string;
  /** 主体类型，决定 `subject` 是昵称、店名还是设备号 */
  subjectType: SubjectType;
  /**
   * 命中的信号。**不给分值** —— 分值口径要等有真实样本后由风控定，
   * 现在编一个看起来很准的分数，只会让人照着它做决定。
   */
  signals: string[];
  /** 关联证据：订单号 / 归因链路号 */
  refs: string[];
  /** 处置状态 */
  status: RiskStatus;
  /** 事件产生时间 */
  createdAt: string;
  /** 处置结论 */
  verdict?: string;
}

export type SubjectType = "USER" | "MERCHANT" | "DEVICE";

/**
 * 黑名单申诉状态。
 * ⚠️ 刻意不叫 `AppealStatus` —— 评价域（P-13.1.3 恶意差评申诉）已经有一个同名类型，
 * 两者是完全不同的东西（一个是商家申诉差评，一个是被拉黑者申诉解禁）。
 * 同名会在 `lib/types` 的总出口上直接冲突，也会让读代码的人以为它们是一回事。
 */
export type BlacklistAppealStatus = "NONE" | "PENDING" | "UPHELD" | "REJECTED";

export interface BlacklistEntry {
  /** 黑名单单号 */
  blackNo: string;
  /** 主体类型 */
  subjectType: SubjectType;
  /** 主体标识：用户昵称 / 商家名 / 设备号 */
  subject: string;
  /** 拉黑原因，必填 */
  reason: string;
  /** 到期时间，必填 —— 无期限拉黑没有申诉出口，是产品事故不是风控严格 */
  until: string;
  /** 申诉状态。**与评价域的 `AppealStatus` 是两回事**，勿混用 */
  appealStatus: BlacklistAppealStatus;
  /** 被拉黑者提交的申诉理由 */
  appealReason?: string;
  /** 申诉裁决说明 */
  appealVerdict?: string;
  /** 是否生效中。到期或申诉通过后置 false，记录保留 */
  active: boolean;
  /** 拉黑时间 */
  createdAt: string;
}

/** 拦截规则（P-16.2.5）：各类型的触发阈值 + 是否自动拦截。 */
export interface RiskRule {
  /** 适用的风险类型，一类一条 */
  type: RiskType;
  /** 触发阈值（如同设备下单数、同 IP 邀请数、30 天退款次数），必须 > 0 */
  threshold: number;
  /** 命中后是否自动拦截；false = 只记事件等人工 */
  autoBlock: boolean;
  /** 最后修改时间 */
  updatedAt: string;
}
