// 覆盖范围：系统配置（P-17.1）。
import type { AppearanceConfig, FeatureFlag, Industry, MarketConfig, RuleTexts } from "@/lib/types";

export interface SystemApi {
  // ── 行业主数据（P-17.1 / ADR-010）—— **已接真后端** `/ops/industries/**`

  listIndustries(): Promise<Industry[]>;
  /**
   * 改某通道的小微白名单。
   *
   * @param remark 为什么改。**建议必填** —— 改白名单会被商家追问，
   *   而「谁什么时候为什么改的」只有这里记得住
   */
  setIndustryMicroAllowed(industry: string, payChannel: string, allowed: boolean, remark?: string): Promise<Industry>;
  /** 停用后入驻表单里不再出现这个行业；**不影响已入驻的商家** */
  setIndustryEnabled(industry: string, enabled: boolean): Promise<Industry>;
  /** 强制开启积分：商家不可自行关闭 */
  setIndustryPointsForced(industry: string, forced: boolean): Promise<Industry>;

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
