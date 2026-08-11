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

/**
 * 行业主数据（后端 `sys_industry`）。**已接真后端。**
 *
 * <p>它不是一张普通的字典表：**行业决定商家能不能以小微主体进件** ——
 * 微信的小微白名单按行业给，判错一次商家就是进件被拒，而那时他已经开完店、上完架。
 */
export interface Industry {
  /** 行业码，入驻申请回传的就是它 */
  industry: string;
  /** 展示名。三端都取服务端的，不各自维护翻译 */
  name: string;
  /** 排序 */
  sort: number;
  /** 是否启用。关掉后入驻表单里不再出现这个行业 */
  enabled: boolean;
  /** 微信是否允许该行业以小微进件 */
  wechatMicroAllowed: boolean;
  /** 支付宝是否允许 */
  alipayMicroAllowed: boolean;
  /**
   * 是否**强制开启积分**（商家不可自行关闭）。
   * 它是 `mch_entity.points_forced` 的来源 —— 高毛利行业平台会要求让利。
   */
  pointsForced: boolean;
  /** 备注：为什么这么配。改白名单是会被商家追问的操作 */
  remark?: string;
}

/**
 * 授权码字典（运营视图）。
 *
 * ⚠️ 与 `AuthCode`（给商家发证时的可选项，见 types/merchant.ts）**是两个口径**：
 * 那个只给启用的，这个含停用的并带影响面计数。合并的话，停用过的码就再也
 * 恢复不了 —— 页面上根本看不见它。
 */
export interface AuthCodeAdmin {
  /** 授权码，如 `FRESH_VEG`。**建成之后不可改** —— 改它等于换一张证 */
  code: string;
  /** 展示名，运营给商家发证时看到的就是它 */
  name: string;
  /** 需要的资质证件名。空 = 无证件要求（不是「漏填」） */
  requiredQualification?: string;
  /** 列表里的排序权重，小的在前。同值按 code 兜底，保证顺序稳定 */
  sort: number;
  /** 是否可发放。停用**不撤销**存量商家已持有的授权 */
  enabled: boolean;
  /** 持有该码的商家数 —— 停之前要知道影响面 */
  merchantCount: number;
  /** 引用该码的在用类目数。> 0 时停用会被拒（那些类目会变成永远拒绝所有人） */
  categoryCount: number;
}

/**
 * 经营范围档位的启用状态（ADR-009 三档）。
 *
 * 档位本身是枚举，永远是那三个；这里配的是**这一期开放哪几档**。
 * 一期自营模式关掉了 PLATFORM —— 没有虚拟商品/卡券/自营快递品支撑它。
 */
export interface ServiceScopeConfig {
  /** COMMUNITY / CITY / PLATFORM */
  scope: string;
  /** 这一期是否开放这一档。关掉**不影响已经是这一档的存量商家**，只是不能再选 */
  enabled: boolean;
  /** 当前在用的商家数。不带计数的开关是盲操作 */
  merchantCount: number;
}
