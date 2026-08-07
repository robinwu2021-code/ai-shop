// 营销域（矩阵 P-7.1 券 / P-7.2 活动 / P-7.3 内容位）。
// 金额一律最小货币单位（分），与全局契约一致。
import type { Archivable } from "./common";

export type CouponType = "FULL_CUT" | "DISCOUNT" | "NEWCOMER" | "TARGETED";

/** DRAFT 可改可删；ACTIVE ⇄ PAUSED 之间可来回；ENDED 是终态（券已发出去的仍然有效）。 */
export type CouponStatus = "DRAFT" | "ACTIVE" | "PAUSED" | "ENDED";

export const COUPON_TRANSITIONS: Record<CouponStatus, CouponStatus[]> = {
  DRAFT: ["ACTIVE"],
  ACTIVE: ["PAUSED", "ENDED"],
  PAUSED: ["ACTIVE", "ENDED"],
  ENDED: [],
};

export interface Coupon extends Archivable {
  /** 券模板单号 */
  couponNo: string;
  /** 券名，展示给用户 */
  name: string;
  /** 券类型，决定 `value` 的口径 */
  type: CouponType;
  /** 券状态。允许的流转见 `COUPON_TRANSITIONS`；**ENDED 不影响已发出的券** */
  status: CouponStatus;
  /** 面额（满减/新人/定向）或折扣万分比（DISCOUNT，如 8500 = 85 折） */
  value: number;
  /** 使用门槛，0 表示无门槛 */
  threshold: number;
  /** 生效开始时间 */
  validFrom: string;
  /** 生效结束时间 */
  validTo: string;
  /**
   * 预算（分）。**已发放金额不得超过它** —— 这是唯一挡住"发着发着超支"的地方，
   * 且必须在服务端校验：客服也持有发券权限（矩阵 §2.3 补偿券）。
   */
  budget: number;
  /** 已发放金额（分） */
  issuedAmount: number;
  /** 已发放张数 */
  issued: number;
  /** 已核销张数（P-7.1.4 效果） */
  redeemed: number;
  /** 创建时间 */
  createdAt: string;
}

/** 发放对象类型（P-7.1.2 发放留痕）。 */
export type IssueTarget = "ALL" | "NEW_USER" | "COMMUNITY" | "SINGLE_USER";

export interface CouponIssue {
  /** 发放记录单号 */
  issueNo: string;
  /** 发放的券模板 */
  couponNo: string;
  /** 券名快照 */
  couponName: string;
  /** 发放对象类型 */
  target: IssueTarget;
  /** 定向说明：社区名 / 用户昵称 / 人群名 */
  targetDesc: string;
  /** 本次发放张数 */
  count: number;
  /** 本次发放占用的预算（分） */
  amount: number;
  /** 操作人（STAFF 账号）。**客服也持有发券权限**，留痕不能省 */
  operator: string;
  /** 发放时间 */
  createdAt: string;
}

export type CampaignType = "FLASH" | "SECKILL" | "FULL_REDUCE" | "GIFT" | "NEWCOMER";
export type CampaignStatus = "DRAFT" | "SCHEDULED" | "RUNNING" | "ENDED";

export interface Campaign extends Archivable {
  /** 活动单号 */
  campaignNo: string;
  /** 活动名 */
  name: string;
  /** 活动类型 */
  type: CampaignType;
  /** 活动状态 */
  status: CampaignStatus;
  /** 开始时间 */
  startAt: string;
  /** 结束时间。须晚于 startAt */
  endAt: string;
  /** 投放位置：秒杀场次的重叠校验按位置分组（跨位置可并行） */
  position: string;
  /** 参与商品数 */
  skuCount: number;
  /** 创建时间 */
  createdAt: string;
}

export type SlotKind = "HOME_FLOOR" | "BANNER" | "CHANNEL";

export interface ContentSlot extends Archivable {
  /** 内容位单号 */
  slotNo: string;
  /** 内容位标题 */
  title: string;
  /** 内容位形态：首页楼层 / 轮播 / 频道 */
  kind: SlotKind;
  /** 同一 kind 内的展示顺序，小的在前 */
  sort: number;
  /** 投放范围：社区编号列表，空 = 全部社区（P-7.3.4） */
  communityNos: string[];
  /** 上线时间 */
  onlineAt: string;
  /** 下线时间 */
  offlineAt: string;
  /** 是否启用。关掉即刻不再展示，不等下线时间 */
  enabled: boolean;
}

// ── 会员卡与权益（P-7.4）──────────────────────────────────────────

/**
 * 权益类型。
 *
 * 四类的 `value` 口径不同，所以必须分开而不是塞一个通用数字：
 * - `DISCOUNT` 万分比（9500 = 95 折）
 * - `FREE_SHIPPING` 每月免运费次数
 * - `COUPON_PACK` 每月赠券张数（要绑 `couponNo`）
 * - `POINTS_BOOST` 积分倍率万分比（10000 = 1 倍）
 */
export type BenefitKind = "DISCOUNT" | "FREE_SHIPPING" | "COUPON_PACK" | "POINTS_BOOST";

export interface Benefit {
  /** 权益类型。**决定 `value` 的口径**，见上方注释 */
  kind: BenefitKind;
  /** 权益数值，口径随 kind 变化 */
  value: number;
  /** 仅 COUPON_PACK：绑定的券模板 */
  couponNo?: string | null;
}

/** 会员卡状态。沿用券的那套语义：停售 = ENDED，不影响已持卡人。 */
export type MemberCardStatus = "DRAFT" | "ACTIVE" | "PAUSED" | "ENDED";

export const MEMBER_CARD_TRANSITIONS: Record<MemberCardStatus, MemberCardStatus[]> = {
  DRAFT: ["ACTIVE"],
  ACTIVE: ["PAUSED", "ENDED"],
  PAUSED: ["ACTIVE", "ENDED"],
  // 停售是终态：已售出的权益要继续兑现，重新开卖得新建一张
  ENDED: [],
};

export interface MemberCard extends Archivable {
  /** 会员卡单号 */
  cardNo: string;
  /** 卡名 */
  name: string;
  /** 等级，数字越大越高 */
  level: number;
  /** 月费（分） */
  priceMonthly: number;
  /** 卡内权益列表 */
  benefits: Benefit[];
  /** 卡状态。**ENDED 是终态** —— 已售出的权益要继续兑现，重开得新建一张 */
  status: MemberCardStatus;
  /**
   * 持卡人数（只读）。
   * ⚠️ 它是"这张卡还能不能改"的唯一依据 —— 卖出去的是承诺，不是配置。
   */
  holderCount: number;
  /** 创建时间 */
  createdAt: string;
  /** 最后修改时间 */
  updatedAt: string;
  /** 最后修改人（STAFF 账号） */
  updatedBy: string;
}
