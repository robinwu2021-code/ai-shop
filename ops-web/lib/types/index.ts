// 类型总出口。域类型按文件切片，页面统一 `import type { X } from "@/lib/types"`。
export * from "./common";
export * from "./merchant";
export * from "./order";
export * from "./payment";
export * from "./dashboard";
export * from "./community";
export * from "./fulfillment";
export * from "./store";
export * from "./marketing";
export * from "./review";
export * from "./aftersale";
export * from "./group";
export * from "./product";
export * from "./inventory";
export * from "./finance";
export * from "./iam";
export * from "./growth";
export * from "./risk";
export * from "./message";
export * from "./content";
export * from "./system";
export * from "./job";

/**
 * 运营侧看到的一条会员（P8）。
 *
 * @remarks `phoneTail` **只有后四位**。「跨商家可见」与「手机号脱敏」是并列的两句 ——
 * 不是前者的例外。要看完整号得走 `revealMemberPhone`：单独权限码、必填理由、每次留痕。
 */
export interface OpsMember {
  /** 会员号（某商家下的一条会员关系） */
  memberNo: string;
  /** 平台人档号。**一份人档串起几家商家的会员关系** —— 跨商家查同一个人靠它 */
  personNo: string;
  /** 手机号后四位。**只有后四位** —— 要完整号得走 revealMemberPhone：单独权限码、必填理由、每次留痕 */
  phoneTail: string | null;
  /** 所属商家 */
  entityNo: string;
  /** 商家名 */
  entityName: string;
  /** 状态 */
  status: string;
  /** 这个会员是怎么来的 */
  source: string;
  /** 会员等级。空 = 商家没开分层 */
  level: string | null;
  /** 累计下单数 */
  orderCount: number;
  /** 累计消费（分） */
  totalSpentMinor: number;
  /** 已退订。**退订的人不进任何受众** —— 运营排查「怎么没收到」第一个看它 */
  reachOptOut: boolean;
  /** 成为会员的时刻 */
  joinedAt: number;
}

/** 人档：一份人档串起几家商家的会员关系 —— 这正是它存在的理由 */
export interface OpsPerson {
  /** 平台人档号 */
  personNo: string;
  /** 手机号后四位。**永远不给完整号** */
  phoneTail: string | null;
  /** 用户号 */
  userNo: string | null;
  /** 他在各商家的会员关系。**一份人档串起几家** —— 这正是人档存在的理由 */
  memberships: OpsMember[];
  /** 合并过的人档号。合并不可逆，留痕是唯一的回溯手段 */
  merges: string[];
}

/**
 * 触达健康度。
 *
 * @remarks `optOutRate` 是这条线唯一的健康指标 —— 发得多不是成绩，
 * 发到有人关掉才是问题。列表按它倒序。
 */
export interface ReachStat {
  /** 所属商家 */
  entityNo: string;
  /** 商家名 */
  entityName: string;
  /** 发出多少条 */
  sent: number;
  /** 覆盖多少会员 */
  members: number;
  /** 其中退订多少人 */
  optOut: number;
  /** 退订率。**这条线唯一的健康指标** —— 发得多不是成绩，发到有人关掉才是问题 */
  optOutRate: number;
}

/**
 * 运营看到的一张券（新模型）。
 *
 * @remarks `flags` 是这一页的价值所在：`NO_BUDGET` 没设预算、`UNLIMITED` 不限量、
 * `HIGH_VALUE` 单张优惠过大、`NEARLY_OUT` 快发完。商家自己看不出来 ——
 * 他只看得到他那一张；跨商家排在一起才看得见。
 */
export interface OpsPromoCoupon {
  /** 券模板号 */
  couponNo: string;
  /** 所属商家 */
  entityNo: string;
  /** 商家名 */
  entityName: string;
  /** 券名 */
  title: string;
  /** `CASH` 减固定金额 / `PERCENT` 打折 / `GIFT` 换赠品 / `TIMES` 次卡 */
  benefitMode: string;
  /** 优惠力度。含义**跟着 benefitMode 变**：CASH 是分、PERCENT 是万分比、TIMES 是次数 */
  benefitValue: number;
  /** 折扣券封顶（分）。空 = 不封顶 —— 与 UNLIMITED 一起出现时敞口无上限 */
  benefitCapMinor: number | null;
  /** 总发行量。空 = 不限量 */
  totalCount: number | null;
  /** 已领取数 */
  receivedCount: number;
  /** 预算上限（分）。空 = 不限 */
  budgetMinor: number | null;
  /** 最大敞口 = 限量 × 单张优惠。**这一页真正要看的数** —— 不限量时它算不出来 */
  maxExposureMinor: number | null;
  /** 状态 */
  status: string;
  /** 风险标记。商家自己看不出来 —— 他只看得到他那一张，跨商家排在一起才看得见 */
  flags: string[];
}

/** 运营看到的一场活动（新模型）。`audienceCount === 0` 表示对所有人生效 */
export interface OpsPromoActivity {
  /** 活动号 */
  activityNo: string;
  /** 所属商家 */
  entityNo: string;
  /** 商家名 */
  entityName: string;
  /** 活动名 */
  name: string;
  /** 触发条件：满额 / 满件 / 命中商品 / 无条件 */
  triggerType: string;
  /** 优惠方式：减钱 / 改单价 / 送商品 / 发券 */
  benefitType: string;
  /** 排期：短期 / 长期 / 周期 */
  scheduleType: string;
  /** 限量。空 = 不限量 */
  quota: number | null;
  /** 已用掉的限量 */
  quotaUsed: number;
  /** 预算上限（分）。空 = 不限 */
  budgetMinor: number | null;
  /** 已花掉的预算（分） */
  budgetUsedMinor: number;
  /** 定向人数。**0 表示对所有人生效**，不是「谁也不发」 */
  audienceCount: number;
  /** 状态 */
  status: string;
  /** 为什么停的：到期 / 限量用尽 / 预算用尽 / 人工停。商家问「怎么停了」要有答案 */
  endedReason: string | null;
  /** 风险标记。商家自己看不出来 —— 他只看得到他那一张，跨商家排在一起才看得见 */
  flags: string[];
}
