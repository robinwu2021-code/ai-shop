// 增长与归因域（矩阵 P-9）。归因链路同时是风控识别异常裂变的数据源（见 lib/types/risk.ts）。

/** 归因来源。优先级是**全序**，不是三个独立开关。 */
export type AttrSource = "STORE_CODE" | "INVITER" | "CHANNEL";
export const ATTR_SOURCES: AttrSource[] = ["STORE_CODE", "INVITER", "CHANNEL"];

/**
 * 店铺码归因冲突处置（P-9.1.5，矩阵 **B1 未拍板**）：
 * 用户已归属 A 店，又扫了 B 店的码，算谁的？
 */
export type ConflictPolicy =
  | "KEEP_FIRST"   // 保留首次归因（默认）：对先把人拉来的商家公平
  | "OVERWRITE"    // 后者覆盖：对当前正在服务用户的商家公平
  | "ASK_USER";    // 问用户：最准，但多一步打断，转化会掉

/** 新客判定因子（P-9.2.3）。至少选一个 —— 一个都不选等于所有人都是新客。 */
export type NewUserFactor = "DEVICE" | "PHONE";

export const ATTR_WINDOW_MIN = 1;
export const ATTR_WINDOW_MAX = 90;

export interface AttributionRule {
  /** 全序优先级，高→低。不重不漏，否则冲突时会随机裁决 */
  priority: AttrSource[];
  /** 归因窗口期（天），1–90 */
  windowDays: number;
  /** 归因冲突处置策略（矩阵 B1 未拍板，故可配） */
  conflictPolicy: ConflictPolicy;
  /** 新客判定因子。**至少选一个** —— 一个都不选等于所有人都是新客 */
  newUserFactors: NewUserFactor[];
  /** 最后修改时间 */
  updatedAt: string;
  /** 最后修改人（STAFF 账号） */
  updatedBy: string;
}

/** 一条归因链路（P-9.1.3）。风控从这里看"这个人是怎么被带进来的"。 */
export interface AttributionTrace {
  /** 归因链路单号 */
  traceNo: string;
  /**
   * 被归因的用户号。**后端下发的是它**（`MktAttributionLog.userNo`）。
   */
  userNo: string;
  /**
   * 用户昵称。**后端目前不下发** —— 它要连 usr_account 才拿得到。
   * 页面回落显示 userNo：空着一列比显示用户号更难查。
   */
  userNickname?: string;
  /** 归因来源 */
  source: AttrSource;
  /** 归因载体：店铺码 / 邀请人昵称 / 渠道名 */
  sourceRef: string;
  /** 归因发生时间 */
  attributedAt: string;
  /** 首单订单号；还没下单则为空 */
  orderNo?: string;
  /** 与之冲突的另一次归因（B1 的现实场景） */
  conflictWith?: string;
  /**
   * 命中的风控信号。**可选：后端目前一条都不下发。**
   *
   * <p>归因链路是从 `mkt_attribution_log` 拼的，那张表没有风控信号 ——
   * 也就是说这一列在真实后端上永远是空的（mock 里有「同设备」「同 IP」这类样例，
   * 所以开发时看着是有的）。
   *
   * <p>声明成必填的代价是**整页崩**：页面 `t.riskSignals.length` 打在 undefined 上，
   * TypeError 直接把 /growth?tab=traces 变成白屏，而这一页在生产上是点得到的。
   * 改成可选只是让它不撒谎，**风控信号本身仍然是个没做的功能**。
   */
  riskSignals?: string[];
}

/**
 * 邀请有礼（P-9.2.1）。
 * ⚠️ 奖励**只能是券**：ADR-004 去团长化后不存在现金激励 ——
 * 一旦发现金，职业薅羊毛立刻回来，且归因作弊有了直接变现路径。
 */
export type RewardType = "COUPON";

export interface FissionCampaign {
  /** 活动单号 */
  fissionNo: string;
  /** 活动名 */
  name: string;
  /** 奖励类型。**只能是券** —— 发现金会让职业薅羊毛立刻回来 */
  rewardType: RewardType;
  /** 奖励券模板号（对应营销域的 Coupon） */
  couponNo: string;
  /** 邀请人得几张 */
  inviterCount: number;
  /** 被邀请人得几张 */
  inviteeCount: number;
  /** 是否启用 */
  enabled: boolean;
  /** 累计邀请人数 */
  invitedCount: number;
  /** 其中转化（完成首单）的人数 */
  convertedCount: number;
  /** 创建时间 */
  createdAt: string;
}
