// 运营侧的会员与新模型营销（P8）种子。
//
// **手机号只种后四位**：mock 里也不放完整号 —— 演示数据里出现一串真号，
// 迟早会有人截图发出去，而那正是这一页最不该发生的事。
import type { OpsMember, OpsPromoActivity, OpsPromoCoupon, ReachStat } from "@/lib/types";

export const opsMembers: OpsMember[] = [
  {
    memberNo: "MB-1001", personNo: "PS-1001", phoneTail: "1148",
    entityNo: "M0001", entityName: "张记生鲜", status: "ACTIVE", source: "ORDER",
    level: "LOYAL", orderCount: 6, totalSpentMinor: 27270, reachOptOut: false,
    joinedAt: Date.now() - 60 * 86400_000,
  },
  {
    // 同一个人档在两家店 —— 人档页要把这件事串起来
    memberNo: "MB-1002", personNo: "PS-1001", phoneTail: "1148",
    entityNo: "M0002", entityName: "老张粮油店", status: "ACTIVE", source: "SHARE",
    level: "NEW", orderCount: 1, totalSpentMinor: 1280, reachOptOut: true,
    joinedAt: Date.now() - 9 * 86400_000,
  },
  {
    // 线索：商家录进来的号，本人还没在平台出现 —— 不可触达
    memberNo: "MB-1003", personNo: "PS-1002", phoneTail: "1176",
    entityNo: "M0001", entityName: "张记生鲜", status: "LEAD", source: "MANUAL",
    level: "NEW", orderCount: 0, totalSpentMinor: 0, reachOptOut: false,
    joinedAt: Date.now() - 3 * 86400_000,
  },
];

export const reachStats: ReachStat[] = [
  // 按退订率倒序 —— 发得多不是成绩，发到有人关掉才是问题
  { entityNo: "M0002", entityName: "老张粮油店", sent: 12, members: 4, optOut: 2, optOutRate: 50 },
  { entityNo: "M0001", entityName: "张记生鲜", sent: 86, members: 52, optOut: 3, optOutRate: 5.77 },
];

export const opsPromoCoupons: OpsPromoCoupon[] = [
  {
    couponNo: "PC-DEMO-1", entityNo: "M0001", entityName: "张记生鲜",
    title: "老客回归 · 满 30 减 5", benefitMode: "CASH", benefitValue: 500,
    benefitCapMinor: null, totalCount: 200, receivedCount: 37, budgetMinor: 100000,
    maxExposureMinor: 100000, status: "ACTIVE", flags: [],
  },
  {
    // 没设预算 + 不限量：两个标记都要亮起来，这正是这一页的用途
    couponNo: "PC-DEMO-9", entityNo: "M0002", entityName: "老张粮油店",
    title: "开业无门槛 10 元", benefitMode: "CASH", benefitValue: 1000,
    benefitCapMinor: null, totalCount: null, receivedCount: 128, budgetMinor: null,
    maxExposureMinor: null, status: "ACTIVE", flags: ["NO_BUDGET", "UNLIMITED", "HIGH_VALUE"],
  },
];

export const opsPromoActivities: OpsPromoActivity[] = [
  {
    activityNo: "PT-DEMO-1", entityNo: "M0001", entityName: "张记生鲜",
    name: "满 50 减 5", triggerType: "AMOUNT", benefitType: "CUT", scheduleType: "ALWAYS_ON",
    quota: 200, quotaUsed: 190, budgetMinor: null, budgetUsedMinor: 95000,
    audienceCount: 0, status: "RUNNING", endedReason: null, flags: ["QUOTA_NEARLY_OUT"],
  },
  {
    activityNo: "PT-DEMO-7", entityNo: "M0002", entityName: "老张粮油店",
    name: "长期九折", triggerType: "AMOUNT", benefitType: "CUT", scheduleType: "ALWAYS_ON",
    quota: null, quotaUsed: 0, budgetMinor: null, budgetUsedMinor: 0,
    audienceCount: 0, status: "RUNNING", endedReason: null, flags: ["ALWAYS_ON_UNCAPPED"],
  },
];
