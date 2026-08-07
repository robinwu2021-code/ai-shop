// 团购与求团域（矩阵 P-8）。C 端已实现，B/P 两端全缺 ——
// 用户能发起求团，但没人能指派商家报价，功能是断的。
// 报价规则遵循 ADR-003：**不做事前审核**，用锁价 + 改价公示 + 信用约束。

export type GroupStatus = "PENDING_AUDIT" | "RUNNING" | "SUCCESS" | "FAILED";

export const GROUP_TRANSITIONS: Record<GroupStatus, GroupStatus[]> = {
  PENDING_AUDIT: ["RUNNING", "FAILED"],
  RUNNING: ["SUCCESS", "FAILED"],
  SUCCESS: [],
  FAILED: [],
};

/** 商家团（P-8.1）。 */
export interface GroupCampaign {
  /** 团单号 */
  groupNo: string;
  /** 开团商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 商品标题快照 */
  skuTitle: string;
  /** 原价（分） */
  originPrice: number;
  /** 团购价（分）。**必须低于原价**，否则"团购"是假的 */
  groupPrice: number;
  /** 起团人数，必须 ≥ 2（1 个人不叫团） */
  minCount: number;
  /** 已参团人数 */
  joined: number;
  /** 团状态。允许的流转见 `GROUP_TRANSITIONS` */
  status: GroupStatus;
  /** 成团截止时间 */
  endAt: string;
  /** 开团时间 */
  createdAt: string;
}

export type DemandStatus = "OPEN" | "QUOTING" | "CHOSEN" | "CLOSED";

/** 邻里求团需求单（P-8.2）。发起人是 C 端用户，不是商家。 */
export interface DemandOrder {
  /** 需求单号 */
  demandNo: string;
  /** 需求标题。发起时**商品还不存在**，只有这句话 */
  title: string;
  /** 发起人昵称。**是 C 端用户，不是商家** */
  initiatorNickname: string;
  /** 归属社区 */
  communityNo: string;
  /** 社区名快照 */
  communityName: string;
  /** +1 人数（想要的人有多少） */
  plusOneCount: number;
  /** 需求单状态 */
  status: DemandStatus;
  /** 已收到的报价数 */
  quoteCount: number;
  /** 发起时间 */
  createdAt: string;
}

/** 商家对需求的报价（P-8.2.3）。同一需求同一商家只能有一条。 */
export interface Quote {
  /** 报价单号 */
  quoteNo: string;
  /** 所报的需求单。**同一需求同一商家只能有一条** */
  demandNo: string;
  /** 需求标题快照 */
  demandTitle: string;
  /** 报价商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 单价（分） */
  price: number;
  /** 起订量 */
  minQty: number;
  /** 报价有效期。过期不可被选定 —— 报价不能无限期挂着 */
  validTo: string;
  /**
   * 改价次数（P-8.2.4 改价留痕）。ADR-003：不禁止改价，但**每次都公示**，
   * 超过阈值禁止再改 —— 频繁改价本身就是信号。
   */
  priceChanges: number;
  /** 是否毁约（P-8.2.5）。毁约累计影响商家信用档案（P-11.1.5） */
  breached: boolean;
  /** 报价时间 */
  createdAt: string;
}
