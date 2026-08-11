// 系统配置 mock（P-17.1）。
import type { Industry, AuthCodeAdmin, ServiceScopeConfig, AppearanceConfig, FeatureFlag, MarketConfig, RuleTexts } from "@/lib/types";

export const appearance: AppearanceConfig = {
  // 与 packages/shared/src/design/tokens.ts 的 SKINS 同名同色
  defaultSkin: "fresh",
  festivalSkin: "promo",
  festivalFrom: "2026-09-25T00:00:00Z",
  festivalTo: "2026-10-08T00:00:00Z",
  fallbackLang: "zh",
  updatedAt: "2026-07-20T02:00:00Z",
  updatedBy: "admin",
};

export const markets: MarketConfig[] = [
  { code: "CN", name: "中国", currency: "CNY", timezone: "Asia/Shanghai", rate: 1, enabled: true },
  { code: "SG", name: "新加坡", currency: "SGD", timezone: "Asia/Singapore", rate: 5.32, enabled: true },
];

export const ruleTexts: RuleTexts = {
  refund: "商品签收前可随时取消并全额退款；签收后 24 小时内可申请售后，生鲜类支持坏果包赔。",
  pickup: "请在取货截止时间前到自提点出示取货码；逾期按平台规则顺延或作废退款。",
  weighDiff: "生鲜按约重下单、按实称结算，多退少补，差价在取货后 24 小时内自动处理。",
  updatedAt: "2026-07-10T02:00:00Z",
  updatedBy: "admin",
};

export const featureFlags: FeatureFlag[] = [
  { key: "neighbor_pickup", name: "邻里自提（ADR-005）", enabled: true, rolloutPercent: 100, updatedAt: "2026-08-01T02:00:00Z" },
  { key: "group_demand", name: "邻里求团", enabled: true, rolloutPercent: 60, updatedAt: "2026-08-03T02:00:00Z" },
  { key: "member_card", name: "付费会员（P1）", enabled: false, rolloutPercent: 0, updatedAt: "2026-07-20T02:00:00Z" },
  { key: "service_category", name: "服务品类", enabled: true, rolloutPercent: 20, updatedAt: "2026-08-05T02:00:00Z" },
];

/**
 * 行业（mock）。与后端 V2 种子同口径 ——
 * **带一个不允许小微的行业**（线上/虚拟），否则「行业决定能否小微」这条联动
 * 在 mock 下永远看不出效果，而它正是选错主体导致进件被拒的地方。
 */
export const industries: Industry[] = [
  { industry: "CATERING", name: "餐饮", sort: 10, enabled: false, wechatMicroAllowed: true, alipayMicroAllowed: true, pointsForced: false, remark: "一期停用：平台执照无餐饮服务与热食制售" },
  { industry: "RETAIL", name: "线下零售", sort: 20, enabled: true, wechatMicroAllowed: true, alipayMicroAllowed: true, pointsForced: false, remark: "一期启用：执照含日用品、水果、蔬菜、预包装食品、茶叶" },
  { industry: "LIFE_SERVICE", name: "居民生活服务", sort: 30, enabled: true, wechatMicroAllowed: true, alipayMicroAllowed: true, pointsForced: false, remark: "一期启用，但仅家政：执照无维修/洗衣" },
  { industry: "ONLINE", name: "线上/虚拟", sort: 60, enabled: false, wechatMicroAllowed: false, alipayMicroAllowed: false, pointsForced: false, remark: "一期停用：不上虚拟商品与卡券" },
  { industry: "OTHER", name: "其他", sort: 99, enabled: false, wechatMicroAllowed: false, alipayMicroAllowed: false, pointsForced: false, remark: "一期停用：自营下「其他」等于平台不清楚自己在卖什么" },
];

/**
 * 授权码字典（mock，运营视图）。**含停用的三个** ——
 * 少了它们，「停用不是删除、运营还能恢复」这条在 mock 上就演示不出来，
 * 而那正是一期收敛之所以可逆的全部依据。
 *
 * 与后端 V5 + V22 同口径。
 */
export const authCodeAdmins: AuthCodeAdmin[] = [
  { code: "FRESH_VEG", name: "蔬菜", requiredQualification: "营业执照（食用农产品）", sort: 10, enabled: true, merchantCount: 2, categoryCount: 2 },
  { code: "FRESH_FRUIT", name: "水果", requiredQualification: "营业执照（食用农产品）", sort: 20, enabled: true, merchantCount: 1, categoryCount: 2 },
  { code: "PACKAGED_FOOD", name: "预包装食品", requiredQualification: "仅销售预包装食品备案", sort: 25, enabled: true, merchantCount: 0, categoryCount: 3 },
  { code: "FRESH_DAIRY", name: "乳制品", requiredQualification: "食品经营许可证", sort: 30, enabled: false, merchantCount: 1, categoryCount: 0 },
  { code: "FOOD", name: "熟食加工", requiredQualification: "食品经营许可证", sort: 40, enabled: false, merchantCount: 1, categoryCount: 0 },
  { code: "DAILY", name: "日用百货", sort: 50, enabled: true, merchantCount: 1, categoryCount: 0 },
  { code: "SERVICE_REPAIR", name: "维修服务", requiredQualification: "家电维修资质", sort: 60, enabled: false, merchantCount: 1, categoryCount: 0 },
  { code: "HOUSEKEEPING", name: "家政服务", sort: 65, enabled: true, merchantCount: 0, categoryCount: 1 },
];

/** 经营范围三档（mock）。一期 PLATFORM 是关的 —— 没有商品形态支撑它。 */
export const serviceScopes: ServiceScopeConfig[] = [
  { scope: "COMMUNITY", enabled: true, merchantCount: 5 },
  { scope: "CITY", enabled: true, merchantCount: 1 },
  { scope: "PLATFORM", enabled: false, merchantCount: 0 },
];
