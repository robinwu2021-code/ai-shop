// 会员与人档（P8 · O1–O7）。
import type { PageCopy } from "@/lib/use-copy";

const zh = {
  title: "会员与人档",
  tabMembers: "会员名单",
  tabPersons: "人档",
  tabReach: "触达健康度",
  tabCoupons: "券（敞口）",
  tabActivities: "活动（敞口）",

  searchTail: "手机号后四位",
  searchTailHint: "只接受四位。后四位会撞是有意的：先看到几个候选，再按商家、下单时间确认是哪一个",
  entityNo: "商家",
  allEntities: "全部商家",

  colMember: "会员",
  colEntity: "归属商家",
  colLevel: "分层",
  colOrders: "单数",
  colSpent: "累计",
  colReach: "消息",
  colJoined: "成为会员",
  reachOn: "可发",
  reachOff: "已关",
  lead: "线索",

  personTitle: "人档 {no}",
  personHint: "一份人档串起他在几家店的会员身份 —— 这正是人档存在的理由",
  noAccount: "还没注册账号",
  reveal: "查看完整手机号",
  revealReason: "查看理由（至少四个字，会写进审计）",
  revealDone: "完整号：{phone}",
  revealHint: "这是唯一能把后四位还原成真实号码的地方。谁在什么时候看了谁的号，都会留下记录",

  reachHint: "按退订率倒序 —— 发得多不是成绩，发到有人关掉才是问题",
  colSent: "近 30 天发送",
  colMembers: "会员数",
  colOptOut: "已退订",
  colOptOutRate: "退订率",

  couponHint: "这一页看的是敞口，不是券本身：谁家的券没设预算、不限量、单张优惠过大",
  colTitle: "券名",
  colIssued: "已领 / 发行",
  colBudget: "预算",
  colExposure: "最大敞口",
  colFlags: "风险",
  unlimited: "不限",
  none: "未设",

  activityHint: "长期又不限量的活动没有停下来的那一天 —— 这一列就是为了让它跑不掉",
  colActivity: "活动",
  colSchedule: "排期",
  colQuota: "限量",
  colAudience: "受众",
  audienceAll: "所有人",
  audienceN: "{n} 条",
  stop: "强制停止",
  stopReason: "停止原因（商家看得见）",
  stopDone: "已停止",

    // 风险标记平铺成 flag_* —— useCopy 的口径是扁平 Record<string,string>，
    // 嵌套一层会让整份文案的类型对不上（而报错指向的是 useCopy 那一行，看不出根因）
  flag_NO_BUDGET: "没设预算",
  flag_UNLIMITED: "不限量",
  flag_HIGH_VALUE: "单张优惠大",
  flag_NEARLY_OUT: "快发完",
  flag_ALWAYS_ON_UNCAPPED: "长期且无上限",
  flag_QUOTA_NEARLY_OUT: "限量快用完",
  flag_ENDED_BY_QUOTA: "已到量结束",
};

const en: typeof zh = {
  title: "Members & persons",
  tabMembers: "Members",
  tabPersons: "Person",
  tabReach: "Messaging health",
  tabCoupons: "Coupons (exposure)",
  tabActivities: "Activities (exposure)",

  searchTail: "Last 4 digits",
  searchTailHint: "Exactly four. Collisions are intentional: you see candidates and confirm by store or order time",
  entityNo: "Merchant",
  allEntities: "All merchants",

  colMember: "Member",
  colEntity: "Merchant",
  colLevel: "Tier",
  colOrders: "Orders",
  colSpent: "Spent",
  colReach: "Messages",
  colJoined: "Member since",
  reachOn: "On",
  reachOff: "Off",
  lead: "Lead",

  personTitle: "Person {no}",
  personHint: "One person record ties together their memberships across stores",
  noAccount: "Not registered yet",
  reveal: "Reveal full phone",
  revealReason: "Reason (at least four characters, written to the audit log)",
  revealDone: "Full number: {phone}",
  revealHint: "This is the only place that turns the last four digits back into a real number. Every view is recorded",

  reachHint: "Sorted by opt-out rate — sending a lot is not an achievement; being switched off is a problem",
  colSent: "Sent (30d)",
  colMembers: "Members",
  colOptOut: "Opted out",
  colOptOutRate: "Opt-out rate",

  couponHint: "This page is about exposure, not coupons: who has no budget, no cap, or an unusually large discount",
  colTitle: "Coupon",
  colIssued: "Claimed / issued",
  colBudget: "Budget",
  colExposure: "Max exposure",
  colFlags: "Risk",
  unlimited: "No limit",
  none: "None",

  activityHint: "An always-on activity with no cap never stops — this column exists to catch it",
  colActivity: "Activity",
  colSchedule: "Schedule",
  colQuota: "Quota",
  colAudience: "Audience",
  audienceAll: "Everyone",
  audienceN: "{n} rules",
  stop: "Force stop",
  stopReason: "Reason (visible to the merchant)",
  stopDone: "Stopped",

  flag_NO_BUDGET: "No budget",
  flag_UNLIMITED: "Unlimited",
  flag_HIGH_VALUE: "Large discount",
  flag_NEARLY_OUT: "Nearly out",
  flag_ALWAYS_ON_UNCAPPED: "Always-on, uncapped",
  flag_QUOTA_NEARLY_OUT: "Quota nearly used",
  flag_ENDED_BY_QUOTA: "Ended on quota",
};

export const MEMBERS_COPY: PageCopy<typeof zh> = { zh, en };

/** 拆出去的组件靠它接文案（与 marketing/copy.ts 同一写法） */
export type MembersCopy = typeof zh;
