// 覆盖范围：系统配置（P-17.1）。
import type { AppearanceConfig, FeatureFlag, MarketConfig, RuleTexts } from "@/lib/types";

export interface SystemApi {
  getAppearance(): Promise<AppearanceConfig>;
  /** 皮肤下发（P-17.1.1 / C-TH-05）。取值必须是四套皮肤之一。 */
  saveAppearance(v: Pick<AppearanceConfig, "defaultSkin" | "festivalSkin" | "festivalFrom" | "festivalTo" | "fallbackLang">): Promise<AppearanceConfig>;

  listMarkets(): Promise<MarketConfig[]>;
  /** 市场与汇率（P-17.1.3）。汇率 > 0；**基准货币不可改**。 */
  saveMarketRate(code: string, rate: number, enabled: boolean): Promise<MarketConfig>;

  getRuleTexts(): Promise<RuleTexts>;
  /** 规则文案（P-17.1.4）。三条都不能为空 —— C 端要展示给用户看。 */
  saveRuleTexts(v: Pick<RuleTexts, "refund" | "pickup" | "weighDiff">): Promise<RuleTexts>;

  listFeatureFlags(): Promise<FeatureFlag[]>;
  /** 开关与灰度（P-17.1.5）。灰度比例 0–100。 */
  saveFeatureFlag(key: string, enabled: boolean, rolloutPercent: number): Promise<FeatureFlag>;
}
