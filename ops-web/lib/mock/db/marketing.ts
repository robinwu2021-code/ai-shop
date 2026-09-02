// 营销域 mock（P-7.1 / P-7.2 / P-7.3）。
// 券刻意留了「预算快用完」「已停用」「草稿」三种，否则预算校验与状态机在页面上验不到。
import type { MerchantCampaign, PlatformSlot, ContentSlot, Coupon, CouponIssue } from "@/lib/types";

export const coupons: Coupon[] = [
  {
    couponNo: "CP9001", name: "新人首单立减 5 元", type: "NEWCOMER", status: "ACTIVE",
    value: 500, threshold: 1900, validFrom: Date.parse("2026-08-01T00:00:00Z"), validTo: Date.parse("2026-08-31T16:00:00Z"),
    budget: 500_000, issuedAmount: 214_000, issued: 428, redeemed: 301, createdAt: Date.parse("2026-07-28T02:00:00Z"),
    totalCount: 1000, perUserLimit: 1, maxDiscountMinor: 0,
  },
  {
    couponNo: "CP9002", name: "满 39 减 8（生鲜）", type: "FULL_CUT", status: "ACTIVE",
    value: 800, threshold: 3900, validFrom: Date.parse("2026-08-04T00:00:00Z"), validTo: Date.parse("2026-08-10T16:00:00Z"),
    // 预算快用完：用来验「超预算发券被拒」
    budget: 120_000, issuedAmount: 116_800, issued: 146, redeemed: 88, createdAt: Date.parse("2026-08-03T06:00:00Z"),
    totalCount: 150, perUserLimit: 1, maxDiscountMinor: 0,
  },
  {
    couponNo: "CP9003", name: "邻家便利 85 折（店铺定向）", type: "DISCOUNT", status: "PAUSED",
    value: 8500, threshold: 0, validFrom: Date.parse("2026-07-20T00:00:00Z"), validTo: Date.parse("2026-08-20T16:00:00Z"),
    budget: 200_000, issuedAmount: 63_500, issued: 127, redeemed: 61, createdAt: Date.parse("2026-07-19T03:00:00Z"),
    totalCount: 200, perUserLimit: 1, maxDiscountMinor: 2000,
  },
  {
    couponNo: "CP9004", name: "老客回归 10 元券", type: "TARGETED", status: "DRAFT",
    value: 1000, threshold: 4900, validFrom: Date.parse("2026-08-10T00:00:00Z"), validTo: Date.parse("2026-09-10T16:00:00Z"),
    budget: 300_000, issuedAmount: 0, issued: 0, redeemed: 0, createdAt: Date.parse("2026-08-05T08:00:00Z"),
    totalCount: 500, perUserLimit: 1, maxDiscountMinor: 0,
  },
  {
    couponNo: "CP9005", name: "七月满 50 减 12（已结束）", type: "FULL_CUT", status: "ENDED",
    value: 1200, threshold: 5000, validFrom: Date.parse("2026-07-01T00:00:00Z"), validTo: Date.parse("2026-07-31T16:00:00Z"),
    budget: 400_000, issuedAmount: 386_400, issued: 322, redeemed: 258, createdAt: Date.parse("2026-06-28T02:00:00Z"),
    totalCount: 400, perUserLimit: 1, maxDiscountMinor: 0,
  },
];

export const couponIssues: CouponIssue[] = [
  { issueNo: "CI9101", couponNo: "CP9001", couponName: "新人首单立减 5 元", target: "NEW_USER", targetDesc: "近 7 日注册新客", count: 200, amount: 100_000, operator: "campaign01", createdAt: "2026-08-04T02:00:00Z" },
  { issueNo: "CI9102", couponNo: "CP9002", couponName: "满 39 减 8（生鲜）", target: "COMMUNITY", targetDesc: "锦绣花园", count: 80, amount: 64_000, operator: "campaign01", createdAt: "2026-08-04T09:30:00Z" },
  { issueNo: "CI9103", couponNo: "CP9002", couponName: "满 39 减 8（生鲜）", target: "SINGLE_USER", targetDesc: "海棠（售后补偿）", count: 1, amount: 800, operator: "cs02", createdAt: "2026-08-05T01:10:00Z" },
  { issueNo: "CI9104", couponNo: "CP9003", couponName: "邻家便利 85 折（店铺定向）", target: "COMMUNITY", targetDesc: "阳光里", count: 127, amount: 63_500, operator: "campaign01", createdAt: "2026-07-21T02:00:00Z" },
];

/**
 * **商家自建的店铺活动**（`/ops/campaigns` 返回的东西）。
 *
 * 此前这里造的是平台投放场次的数据（SECKILL / position / skuCount）——
 * 一个后端并不存在的对象。**mock 比后端好看**，于是页面在 mock 下一切正常，
 * 接上真后端才露出类型列打原始枚举码。
 */
export const merchantCampaigns: MerchantCampaign[] = [
  { campaignNo: "CM9001", merchantNo: "M0001", name: "开业满 50 减 8", type: "FULL_CUT", status: "RUNNING", startAt: Date.parse("2026-08-04T00:00:00Z"), endAt: Date.parse("2026-08-10T16:00:00Z"), goodsNos: ["G001", "G002"] },
  { campaignNo: "CM9002", merchantNo: "M0001", name: "挂面尝鲜价", type: "FLASH", status: "RUNNING", startAt: Date.parse("2026-08-06T00:00:00Z"), endAt: Date.parse("2026-08-13T16:00:00Z"), goodsNos: ["G003"] },
  { campaignNo: "CM9003", merchantNo: "M0002", name: "买二送一（抽纸）", type: "BUY_GIFT", status: "PAUSED", startAt: Date.parse("2026-08-01T00:00:00Z"), endAt: Date.parse("2026-08-31T16:00:00Z"), goodsNos: ["G010", "G011", "G012"] },
  { campaignNo: "CM9004", merchantNo: "M0002", name: "店铺券·满 20 减 3", type: "COUPON", status: "ENDED", startAt: Date.parse("2026-07-01T00:00:00Z"), endAt: Date.parse("2026-07-31T16:00:00Z"), goodsNos: [] },
];

/**
 * 平台投放场次的 mock。**后端还没有这个对象** —— 保留它是为了那块 UI
 * （位置、秒杀场次重叠校验）在 mock 模式下仍能演示，以及把那几条产品规则
 * 记在测试里：首尾相接不算重叠、跨位置可并行。
 *
 * **单独一个数组**，不与 merchantCampaigns 混：两个领域对象共用一个数组
 * 正是「类型列一半中文一半原始枚举码」的来源。
 */
export const platformSlots: PlatformSlot[] = [
  { campaignNo: "AC9001", name: "早市秒杀 07:00 场", type: "SECKILL", status: "RUNNING", startAt: "2026-08-06T23:00:00Z", endAt: "2026-08-07T00:00:00Z", position: "首页秒杀位", skuCount: 12, createdAt: "2026-08-01T02:00:00Z" },
  { campaignNo: "AC9002", name: "晚市秒杀 18:00 场", type: "SECKILL", status: "SCHEDULED", startAt: "2026-08-07T10:00:00Z", endAt: "2026-08-07T11:00:00Z", position: "首页秒杀位", skuCount: 8, createdAt: "2026-08-01T02:10:00Z" },
  { campaignNo: "AC9003", name: "生鲜满 39 减 8", type: "FULL_REDUCE", status: "RUNNING", startAt: "2026-08-04T00:00:00Z", endAt: "2026-08-10T16:00:00Z", position: "生鲜频道", skuCount: 46, createdAt: "2026-08-03T06:00:00Z" },
];

export const contentSlots: ContentSlot[] = [
  { slotNo: "SL9001", title: "今日团（首页第一屏）", kind: "HOME_FLOOR", sort: 1, communityNos: [], goodsNos: ["G0004", "G0002", "G0001"], onlineAt: "2026-08-01T00:00:00Z", offlineAt: "2026-12-31T16:00:00Z", enabled: true },
  { slotNo: "SL9002", title: "生鲜大促 Banner", kind: "BANNER", sort: 1, communityNos: ["C001", "C002"], goodsNos: [], onlineAt: "2026-08-04T00:00:00Z", offlineAt: "2026-08-10T16:00:00Z", enabled: true },
  { slotNo: "SL9003", title: "新人专区入口", kind: "HOME_FLOOR", sort: 2, communityNos: [], goodsNos: ["G0003"], onlineAt: "2026-08-01T00:00:00Z", offlineAt: "2026-08-31T16:00:00Z", enabled: true },
  { slotNo: "SL9004", title: "梧桐苑开城 Banner", kind: "BANNER", sort: 2, communityNos: ["C003"], goodsNos: [], onlineAt: "2026-08-08T00:00:00Z", offlineAt: "2026-08-20T16:00:00Z", enabled: false },
];

/**
 * 会员卡（P-7.4）。
 *
 * 三张样本分别对应"能不能改"的三种情形：
 * 有 1200 人持卡的在售卡（权益改不了）、还在草稿的新卡（随便改）、已停售的旧卡（终态）。
 */
export const memberCards: import("@/lib/types").MemberCard[] = [
  {
    cardNo: "MC901", name: "邻里卡", level: 1, priceMonthly: 900,
    benefits: [
      { kind: "DISCOUNT", value: 9500 },
      { kind: "FREE_SHIPPING", value: 2 },
      { kind: "POINTS_BOOST", value: 12000 },
    ],
    status: "ACTIVE", holderCount: 1200,
    createdAt: "2026-03-01T00:00:00Z", updatedAt: "2026-06-10T02:00:00Z", updatedBy: "ops01",
  },
  {
    cardNo: "MC902", name: "邻里卡 PLUS（草稿）", level: 2, priceMonthly: 2900,
    benefits: [
      { kind: "DISCOUNT", value: 9000 },
      { kind: "COUPON_PACK", value: 4, couponNo: "CP9002" },
    ],
    status: "DRAFT", holderCount: 0,
    createdAt: "2026-07-20T03:00:00Z", updatedAt: "2026-07-20T03:00:00Z", updatedBy: "ops01",
  },
  {
    cardNo: "MC903", name: "尝鲜卡（已停售）", level: 1, priceMonthly: 500,
    benefits: [{ kind: "FREE_SHIPPING", value: 1 }],
    status: "ENDED", holderCount: 86,
    createdAt: "2026-01-05T00:00:00Z", updatedAt: "2026-05-30T08:00:00Z", updatedBy: "ops01",
  },
];
