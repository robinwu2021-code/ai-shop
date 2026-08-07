// 系统配置 mock（P-17.1）。
import type { AppearanceConfig, FeatureFlag, MarketConfig, RuleTexts } from "@/lib/types";

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
