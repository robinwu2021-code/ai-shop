// 系统配置域（矩阵 P-17.1）。
import type { ThemeKey } from "@/lib/stores/theme";

/** 全局外观与语言（P-17.1.1 / 17.1.2）。 */
export interface AppearanceConfig {
  /** C 端默认皮肤下发（C-TH-05）。取值必须是 `C_END_THEMES` 之一（不含运营端专有的 business），与 packages/shared 的 SKINS 同源 */
  defaultSkin: ThemeKey;
  /** 节日皮肤：留空表示不启用 */
  festivalSkin?: ThemeKey;
  /** 节日皮肤生效开始时间。启用节日皮肤时必填 */
  festivalFrom?: string;
  /** 节日皮肤生效结束时间 */
  festivalTo?: string;
  /** 语言回落规则（R9）：缺译时回落到哪个语言 */
  fallbackLang: string;
  /** 最后修改时间 */
  updatedAt: string;
  /** 最后修改人（STAFF 账号） */
  updatedBy: string;
}

/** 市场与货币（P-17.1.3）。 */
export interface MarketConfig {
  /** 市场编码，如 `CN` / `SG` */
  code: string;
  /** 市场展示名 */
  name: string;
  /** 结算与展示货币，如 `CNY` */
  currency: string;
  /** 时区标识，如 `Asia/Shanghai`。截单时间按它切分自然日 */
  timezone: string;
  /**
   * 对基准货币的汇率。
   * ⚠️ 基准货币（CNY）恒为 1 且**不可改** —— 改了整套价格换算的原点就没了。
   */
  rate: number;
  /** 是否开放该市场。关掉后该市场的商品不再售卖 */
  enabled: boolean;
}

export const BASE_CURRENCY = "CNY";

/** 规则文案（P-17.1.4）。这三条是 C 端要展示给用户看的，不能为空。 */
export interface RuleTexts {
  /** 退款规则文案，C 端售后页展示 */
  refund: string;
  /** 自提规则文案，C 端下单与取货页展示 */
  pickup: string;
  /** 称重差价规则文案，生鲜订单展示 */
  weighDiff: string;
  /** 最后修改时间 */
  updatedAt: string;
  /** 最后修改人（STAFF 账号） */
  updatedBy: string;
}

/** 开关与灰度（P-17.1.5）。 */
export interface FeatureFlag {
  /** 开关标识，代码里读的就是它 */
  key: string;
  /** 开关展示名 */
  name: string;
  /** 总开关。关掉时 `rolloutPercent` 不生效 */
  enabled: boolean;
  /** 灰度比例 0–100 */
  rolloutPercent: number;
  /** 最后修改时间 */
  updatedAt: string;
}
