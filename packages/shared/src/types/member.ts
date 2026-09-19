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
  /** 他身上的商家标签名（名单卡片第二行）。只在名单接口里有 */
  tagNames?: string[];
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
  /**
   * 上一次按口径每日重算分层的时刻（毫秒）。从没跑过为 null。
   * 会员页据此写「09-19 03:00 按口径重算」—— 商家看到昨天的常客今天变沉睡，知道不是数据错了
   */
  levelComputedAt?: number | null;
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
  /** 最近一次触达（原型 m04）。没发过为空 —— 商家打电话前能先看到上周已经发过一次唤回、而且他来了 */
  lastReach?: MemberLastReach | null;
}
/** 一个人身上最近的一次触达 */
export interface MemberLastReach {
  /** 那一次的批次号，点进去是效果页 */
  taskNo: string;
  /** `NOTICE` 公告 / `WAKEUP` 唤回 / `COUPON` 发券通知 */
  scene: string;
  /** 发出时刻 */
  sentAt: number;
  /** 点推送进店的时刻。没来为空 */
  openedAt?: number | null;
  /** 归到这次的下单时刻。没下为空 */
  orderedAt?: number | null;
}
/**
 * 一次触达（原型 m19 / m20）：发出 · 来了 · 成单。
 *
 * @remarks `settled` 为假时界面写「统计中」—— 归因窗口（默认 7 天）还没关，
 * 数字还会涨；不写的话商家第二天看到 2 单就判定这次失败了。
 */
export interface ReachTask {
  /** 批次号 */
  taskNo: string;
  /** `NOTICE` / `WAKEUP` / `COUPON` */
  scene: string;
  /** 消息标题 */
  title: string;
  /** 消息正文 */
  body?: string | null;
  /** 发给了谁（发送时选人面板上的那句话） */
  audienceDesc: string;
  /** 发出时刻 */
  sentAt: number;
  /** 归因窗口关闭时刻 */
  statsUntil: number;
  /** 窗口已关，数字不会再变 */
  settled: boolean;
  /** 条件命中多少人 */
  matched: number;
  /** 发出多少人 */
  sent: number;
  /** 跳过多少人 */
  skipped: number;
  /** 跳过的原因分布 */
  skips: Array<{ reason: string; count: number }>;
  /** 点推送进了店的人数 */
  opened: number;
  /** 窗口内下单的人数（每人只算触达后的第一单） */
  ordered: number;
  /** 这些单的实付合计（分） */
  orderedAmountMinor: number;
  /** 下单的人，最多 50 个。列表接口里为空 */
  orderedMembers: ReachOrderedMember[];
  /** 没来的人数。「没来的存人群」存进去的就是这些人 */
  notOpened: number;
}
/** 效果页上下单的一个人 */
export interface ReachOrderedMember {
  /** 会员号 */
  memberNo: string;
  /** 商家给他记的备注名；没记为空，界面用手机尾号 */
  name?: string | null;
  /** 手机尾号 */
  phoneTail?: string | null;
  /** 这一单的实付（分） */
  amountMinor: number;
  /** 下单时刻 */
  orderedAt: number;
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
  /** 分层口径（平台统一，商家只读）：超过这么多天没下单算沉睡 */
  sleepDays: number;
  /** 近 90 天至少这么多单算熟客 */
  loyalD90Orders: number;
  /** 近 90 天至少这么多单算常客；再少是新客 */
  regularD90Orders: number;
  /** 上一次按口径重算的时刻；从没跑过为 null */
  levelComputedAt?: number | null;
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
  /** 限定某一次触达里的人（效果页「没来的存人群 / 下单的打标签」） */
  reachTaskNo?: string | null;
  /** 与 `reachTaskNo` 配用：`ORDERED` 下了单的 / `OPENED` 来了的 / `NOT_OPENED` 没来的；空 = 那次发到的所有人 */
  reachOutcome?: string | null;
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
 * 一个受众项：活动、发券、发消息「发给谁」的最小单位。
 *
 * @remarks **多项之间取或**（「沉睡的 + 爱囤货的都给」）；与筛选里「同时含以下标签」的取且相反，
 * 界面上两处要分别写清。要「既沉睡又爱囤货」，先在筛选里存成人群，再选这个人群。
 */
export interface AudienceItem {
  /** `ALL` 全部会员 · `LEVEL` 分层 · `TAG` 标签 · `SEGMENT` 人群 · `SOURCE` 首次来源 · `NON_MEMBER` 非本店会员（只在活动里） */
  type: string;
  /** 分层码 / 标签号 / 人群号 / 来源码；`ALL`、`NON_MEMBER` 为 `*`。**存号不存文本** —— 标签改名不该动到这里 */
  value: string;
}
/**
 * 选人面板的试算：命中多少、收得到多少、收不到的为什么。
 *
 * @remarks 活动场景只有 `matched`（活动不推送，没有「收得到」一说）；
 * 含「非本店会员」时两个数都为 null —— 「所有不是本店会员的人」数不出来。
 */
export interface AudiencePreview {
  /** 命中多少人（含收不到的）；数不出来时为 null */
  matched: number | null;
  /** 其中收得到消息的；活动场景为 null */
  reachable: number | null;
  /** 收不到的按原因分档：`LEAD` 手录未认领 · `BLOCKED` 已拉黑 · `OPT_OUT` 关了本店消息 · `NO_ACCOUNT` 还没注册 · `TOO_SOON` 最近刚收到过 */
  skips: Array<{ reason: string; count: number }>;
}
/**
 * 批量打标的试算 / 结果。**确认框上写的就是 `willChange`** ——「其中 5 人已有，实际新增 32 人」。
 */
export interface BatchTagResult {
  /** 圈到的人（只算本店的） */
  matched: number;
  /** 已经是目标状态的（打：本来就有；去：本来就没有），不重复计 */
  alreadyInState: number;
  /** 实际会改的人数 */
  willChange: number;
  /** 标签已满（每人上限）而跳过的 */
  skippedFull: number;
  /** false = 这只是试算，没有落库 */
  applied: boolean;
}
/** 标签 / 人群被谁引用着：一个活动，或一批发券 */
export interface AudienceRef {
  /** `ACTIVITY` 活动 / `COUPON_ISSUE` 发券批次 */
  kind: string;
  /** 活动号 / 发放批次号 */
  refNo: string;
  /** 活动名 / 券名 */
  name?: string | null;
  /** 活动状态（`RUNNING` / `PAUSED` …）；发券批次为空 */
  status?: string | null;
  /** 发放时刻；活动为空 */
  at?: number | null;
}
/**
 * 一个标签用在哪。**停用或合并前先给商家看** —— 引用它的活动会跟着受影响。
 */
export interface MemberTagUsage {
  /** 标签本身（含人数） */
  tag: MemberTag;
  /** 本月新打上的人数 */
  newThisMonth: number;
  /** 受众里引用它的未结束活动 */
  activities: AudienceRef[];
  /** 条件里含它的人群 */
  segments: MemberSegment[];
}
/**
 * 人群详情：条件 + 此刻人数（当场算）+ 用在哪。
 *
 * @remarks 进行中的活动按**发布那一刻**的人群条件生效 —— 改这里的条件不影响它们。
 */
export interface MemberSegmentDetail {
  /** 人群本身 */
  segment: MemberSegment;
  /** 此刻命中（当场算，不是 `lastCount`） */
  matched: number;
  /** 其中收得到消息的 */
  reachable: number;
  /** 引用它的未结束活动 */
  activities: AudienceRef[];
  /** 按它发过的券（最近 20 批） */
  couponIssues: AudienceRef[];
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
