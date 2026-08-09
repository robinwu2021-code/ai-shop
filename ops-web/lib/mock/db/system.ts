// 系统配置 mock（P-17.1）。
import type { Industry, AppearanceConfig, FeatureFlag, MarketConfig, RuleTexts } from "@/lib/types";

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
  { industry: "CATERING", name: "餐饮", sort: 10, enabled: true, wechatMicroAllowed: true, alipayMicroAllowed: true, pointsForced: false, remark: "微信小微白名单内" },
  { industry: "RETAIL", name: "线下零售", sort: 20, enabled: true, wechatMicroAllowed: true, alipayMicroAllowed: true, pointsForced: false, remark: "便利店、超市、生鲜果蔬" },
  { industry: "LIFE_SERVICE", name: "居民生活服务", sort: 30, enabled: true, wechatMicroAllowed: true, alipayMicroAllowed: true, pointsForced: false, remark: "家政、维修、洗衣" },
  { industry: "ONLINE", name: "线上/虚拟", sort: 60, enabled: true, wechatMicroAllowed: false, alipayMicroAllowed: false, pointsForced: false, remark: "微信明确不支持小微：直播、游戏等" },
  { industry: "OTHER", name: "其他", sort: 99, enabled: true, wechatMicroAllowed: false, alipayMicroAllowed: false, pointsForced: false, remark: "保守兜底：宁可让商家来问，也不要让他被通道拒" },
];
