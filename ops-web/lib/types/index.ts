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

/**
 * 运营侧看到的一条会员（P8）。
 *
 * @remarks `phoneTail` **只有后四位**。「跨商家可见」与「手机号脱敏」是并列的两句 ——
 * 不是前者的例外。要看完整号得走 `revealMemberPhone`：单独权限码、必填理由、每次留痕。
 */
export interface OpsMember {
  memberNo: string;
  personNo: string;
  phoneTail: string | null;
  entityNo: string;
  entityName: string;
  status: string;
  source: string;
  level: string | null;
  orderCount: number;
  totalSpentMinor: number;
  reachOptOut: boolean;
  joinedAt: number;
}

/** 人档：一份人档串起几家商家的会员关系 —— 这正是它存在的理由 */
export interface OpsPerson {
  personNo: string;
  phoneTail: string | null;
  userNo: string | null;
  memberships: OpsMember[];
  merges: string[];
}

/**
 * 触达健康度。
 *
 * @remarks `optOutRate` 是这条线唯一的健康指标 —— 发得多不是成绩，
 * 发到有人关掉才是问题。列表按它倒序。
 */
export interface ReachStat {
  entityNo: string;
  entityName: string;
  sent: number;
  members: number;
  optOut: number;
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
  couponNo: string;
  entityNo: string;
  entityName: string;
  title: string;
  benefitMode: string;
  benefitValue: number;
  benefitCapMinor: number | null;
  totalCount: number | null;
  receivedCount: number;
  budgetMinor: number | null;
  maxExposureMinor: number | null;
  status: string;
  flags: string[];
}

/** 运营看到的一场活动（新模型）。`audienceCount === 0` 表示对所有人生效 */
export interface OpsPromoActivity {
  activityNo: string;
  entityNo: string;
  entityName: string;
  name: string;
  triggerType: string;
  benefitType: string;
  scheduleType: string;
  quota: number | null;
  quotaUsed: number;
  budgetMinor: number | null;
  budgetUsedMinor: number;
  audienceCount: number;
  status: string;
  endedReason: string | null;
  flags: string[];
}
