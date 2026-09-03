// 会员与人档（商家视角的顾客）· 触达
//
// 三端共用的契约镜像，按域切开的一份 —— 口径与切开之前逐字相同，见 `index.ts`。

import type { TrafficSource } from "./core";

/**
 * 商家的客户（B-11.2.8）。
 *
 * 这是「商家自带客流」定位下最该给店主看的东西：**谁在买、谁不来了**。
 * 平台电商给商家看的是流量与转化；小店老板要的是「张阿姨上个月每周都来，这半个月没来」。
 */
/**
 * 会员：一个人与这家商家的关系（P1）。
 *
 * <p>与 {@link MerchantCustomer} 的分工：那个是按订单实时聚合出来的「谁来过」，
 * 这个是**沉淀下来的关系** —— 有来源、有分层、能挂标签、能被筛出来做活动。
 * 客户页升级为会员页之后，前者只剩跨店总览还在用。
 */
export interface Member {
  /** 会员号 */
  memberNo: string;
  /** 平台人档号。会员挂人不挂账号 —— 商家看不到，但详情页要用它取来源轨迹 */
  personNo: string;
  /** 手机号后四位。**永远不会有完整号** —— 需要它的只有平台申诉处置 */
  phoneTail?: string | null;
  /** `LEAD` 线索（商家录的、本人还没注册，不可触达）/ `ACTIVE` / `BLOCKED` */
  status: string;
  /** 首次来源 `ORDER`/`SHARE`/`SCAN`/`MANUAL`/`FAVORITE`/`SEARCH` */
  source: string;
  /** `NEW`/`REGULAR`/`LOYAL`/`SLEEPING`。按主体还是按门店算，取决于主体的经营口径 */
  level?: string | null;
  /** 他从哪家门店进来的 */
  firstStoreNo?: string | null;
  /** 累计下单数 */
  orderCount: number;
  /** 累计消费（分） */
  totalSpentMinor: number;
  /** 近 90 天下单数。**分层判据用它而不是累计** —— 三年前买过十次的人今天是沉睡客 */
  d90OrderCount: number;
  /** 上次下单时刻。空 = 从没下过单（线索会员） */
  lastOrderAt?: number | null;
  /** 距上次下单多少天。按 lastOrderAt 与今天实时算 */
  daysSinceLast?: number | null;
  /** 买家关掉了这家店的消息。商家看得到状态，看不到原因 */
  reachOptOut: boolean;
  /** 商家写的备注。**只有商家自己看得到** */
  remark?: string | null;
  /** 成为会员的时刻 */
  joinedAt: number;
}
/**
 * 会员四层人数 + 两个提醒数。
 *
 * @remarks `unlinkedBuyers` 要显示在页面顶部：商家一定会拿订单数与会员数对，
 * 对不上时他的第一反应是数据丢了。**先说，比等他问强。**
 */
export interface MemberStats {
  /** 新客数 */
  newCount: number;
  /** 回头客数 */
  regularCount: number;
  /** 忠实客数 */
  loyalCount: number;
  /** 沉睡客数 */
  sleepingCount: number;
  /** 可触达人数（排除线索、拉黑、已退订） */
  reachable: number;
  /** 本月新增会员 */
  newThisMonth: number;
  /** 未绑手机号、因此没计进会员的买家数 */
  unlinkedBuyers: number;
}
/** 他在某一家门店的往来。单店主体没有这一段 */
export interface MemberStoreStat {
  /** 发生在哪家店 */
  storeNo: string;
  /** 累计下单数 */
  orderCount: number;
  /** 累计消费（分） */
  totalSpentMinor: number;
  /** 上次下单时刻。空 = 从没下过单（线索会员） */
  lastOrderAt?: number | null;
  /** 他是从这家店进来的 */
  isFirstStore: boolean;
}
/** 一次来源。**谁发的链接**要写出来，否则分享激励没法结算，商家也不知道该谢谁 */
export interface MemberSourceItem {
  /** 这一次是怎么来的（扫码 / 分享 / 商家录入 / 活动） */
  sourceType: string;
  /** 发生在哪家店 */
  storeNo?: string | null;
  /** 分享链接号 */
  linkNo?: string | null;
  /** **谁发的链接**。不写出来的话分享激励没法结算，商家也不知道该谢谁 */
  inviterUserNo?: string | null;
  /** 分享人的身份（顾客 / 店员）—— 结算口径不同 */
  inviterRole?: string | null;
  /** 商家录入时的经手人 */
  operatorNo?: string | null;
  /** 来自哪个活动 */
  activityNo?: string | null;
  /** 是不是首次进店。首次那一条决定了这个会员算谁带来的 */
  isFirst: boolean;
  /** 发生时刻 */
  occurredAt: number;
}
export interface MemberDetail {
  /** 会员本身 */
  member: Member;
  /** 他在各门店的往来。单店主体没有这一段 */
  stores: MemberStoreStat[];
  /** 来源轨迹：他是怎么来的 */
  sources: MemberSourceItem[];
  /** 身上的标签 */
  tags: MemberTag[];
}
/**
 * 会员标签。
 *
 * @remarks `tagType` 为 `SYS` 时**只读**：系统标签的名字就是口径（「沉睡」= 60 天没来），
 * 允许改名之后两个商家对同一个词会有两种理解，按它筛出来的人群从此不可比。
 */
export interface MemberTag {
  /** 标签号 */
  tagNo: string;
  /** 人群名 */
  name: string;
  /** `SYS` 系统算的（只读）/ `MCH` 商家自己的 */
  tagType: string;
  /** `ACTIVE` / `DISABLED` 停用（老的还在、新的打不上）/ `MERGED` 已并入别的标签 */
  status: string;
  /** 打了多少人。服务端 COUNT 出来的，不是冗余列 */
  count: number;
}
/**
 * 群发试算结果（P7）。
 *
 * @remarks `skips` 必须显示出来：商家选了 30 个人实发 8 个，
 * 只说「发送成功」的话他会以为 30 个人都收到了。
 * 原因码：`TOO_SOON` 最近发过 / `OPT_OUT` 已退订 / `LEAD` 线索会员 / `NO_ACCOUNT` 还没注册。
 */
export interface ReachPlan {
  /** 条件命中多少人 */
  matched: number;
  /** 其中**能真正收到东西**的有多少（线索会员与已退订的人进不了受众）。只显示 count 的话，商家在人群页看到 120、发放页发出 96，会以为发漏了 */
  reachable: number;
  /** 发不出去的人按原因分类。**必须显示** —— 商家选了 30 个人实发 8 个，只说「发送成功」他会以为 30 个都收到了 */
  skips: Array<{ reason: string; count: number }>;
}
export interface ReachResult {
  /** 这次群发的任务号 */
  taskNo: string;
  /** 实际发出多少条 */
  sent: number;
  /** 跳过多少人 */
  skipped: number;
  /** 发不出去的人按原因分类。**必须显示** —— 商家选了 30 个人实发 8 个，只说「发送成功」他会以为 30 个都收到了 */
  skips: Array<{ reason: string; count: number }>;
}
/**
 * 「我是这家店的会员」（C 端，P7）。
 *
 * @remarks 这一页是发消息功能的前提：顾客要能看到**谁在给他发消息**并且能关掉。
 */
export interface MyMembership {
  /** 哪家商家 */
  entityNo: string;
  /** 商家名 */
  entityName: string;
  /** 会员等级 */
  level?: string | null;
  /** 累计下单数 */
  orderCount: number;
  /** 累计消费（分） */
  totalSpentMinor: number;
  /** 我关掉了这家店的消息没有。**只有本人能改** */
  reachOptOut: boolean;
  /** 成为会员的时刻 */
  joinedAt: number;
}
/**
 * 会员经营口径（P3）。
 *
 * @remarks 切换 `memberScope` 会**改变「新客」的含义**：按门店时，
 * 在别的店买过的人在这家店仍算新客。这句话必须写在开关旁边 ——
 * 不写的话，商家会以为自己把数据弄丢了。
 * 实际上两份指标一直都在算，**切回来一个数都不少**。
 */
export interface MemberSetting {
  /** `ENTITY` 按主体（默认）/ `STORE` 按门店 */
  memberScope: string;
  /** 支付成功自动入会。关掉之后只剩手工录入与本人主动加入 */
  autoJoinOnOrder: boolean;
}
/**
 * 人群：一组筛选条件，可命名保存、反复用。
 *
 * @remarks **存的是条件不是名单**。名单每天都在变（有人昨天刚下单就不再沉睡），
 * `lastCount` 只是「上次算于 countedAt 时」的展示值 —— 发券与触达前会当场重算。
 */
export interface MemberSegment {
  /** 人群号 */
  segmentNo: string;
  /** 人群名 */
  name: string;
  /** 限定门店。空 = 全主体 */
  scopeStoreNo?: string | null;
  /** 筛选条件。存的是 JSON —— 条件会长，拆成列的话每加一个维度都要改表 */
  rule: MemberSegmentRule;
  /** 上次算出来命中多少人 */
  lastCount: number;
  /** 上次算的时刻。**人群是快照不是实时** —— 中间新来的人不在里面 */
  countedAt?: number | null;
}
/** 人群条件。**只存号**（标签号/门店号）—— 标签改名之后条件还得成立 */
export interface MemberSegmentRule {
  /** 会员等级 */
  level?: string | null;
  /** 来源类型 */
  source?: string | null;
  /** 状态 */
  status?: string | null;
  /** **取交集**：选两个标签是「都要满足」。界面上写「同时含以下标签」 */
  tagNos?: string[];
  /** 上次下单**早于**这个时刻。用来筛沉睡客 */
  lastOrderBefore?: number | null;
  /** 上次下单**晚于**这个时刻。用来筛活跃客 */
  lastOrderAfter?: number | null;
  /** 累计消费下限（分）。空 = 不限 */
  spentMin?: number | null;
  /** 累计消费上限（分）。空 = 不限 */
  spentMax?: number | null;
}
/**
 * 人群试算。
 *
 * @remarks 两个数都要显示：`count` 是条件命中多少人，`reachable` 是其中
 * **能真正收到东西**的有多少（线索会员与退订的人进不了受众）。
 * 只显示 count 的话，商家在人群页看到 120、发放页发出 96，他会以为发漏了。
 */
export interface MemberSegmentPreview {
  /** 条件命中多少人 */
  count: number;
  /** 其中**能真正收到东西**的有多少（线索会员与已退订的人进不了受众）。只显示 count 的话，商家在人群页看到 120、发放页发出 96，会以为发漏了 */
  reachable: number;
}
/**
 * 合并标签的影响面。**先给商家看这几个数，再让他按** —— 合并不可逆。
 */
export interface MemberMergePreview {
  /** 会被改到的会员数 */
  affectedMembers: number;
  /** 两个标签都有的人。合并后只保留一条 */
  bothTagged: number;
  /** 引用了这个标签的活动数。**合并前要看** —— 合并不可逆，而活动的受众条件会跟着变 */
  referencedActivities: number;
  /** false = 这只是试算，没有落库 */
  applied: boolean;
}
export interface MerchantCustomer {
  /** 脱敏昵称，不给完整手机号（B12） */
  /** 客户昵称 */
  nickname: string;
  /** 客户头像 */
  avatar: string;
  /** 在本店的累计下单次数 */
  orderCount: number;
  /** 在本店的累计消费额（最小货币单位） */
  totalSpentMinor: number;
  /** 最近一次下单时间 */
  lastOrderAt: number;
  /** 距上次下单天数 */
  daysSinceLast: number;
  /** 沉默客户：曾经常来、最近没来。**这是店主唯一能立刻行动的信号** */
  silent: boolean;
  /** 客流来源：他是你自己带来的，还是平台分配的 */
  source: TrafficSource;
}
