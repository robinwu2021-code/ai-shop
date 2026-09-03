// 跨域的通用形状：分页与结果信封、语言与货币、履约方式 —— **没有归属域**的那些
//
// 三端共用的契约镜像，按域切开的一份 —— 口径与切开之前逐字相同，见 `index.ts`。

import type {
  CATEGORY_TYPE,
  CURRENCIES,
  FULFILLMENT,
  FULFILLMENT_REACH,
  LANGS,
  MARKETS,
  SERVICE_SCOPE,
} from "@shared/utils/constants";

export type CategoryType = (typeof CATEGORY_TYPE)[keyof typeof CATEGORY_TYPE];
export type FulfillmentType = (typeof FULFILLMENT)[keyof typeof FULFILLMENT];
export type Lang = (typeof LANGS)[number]["id"];
export type CurrencyCode = keyof typeof CURRENCIES;
export type MarketId = (typeof MARKETS)[number]["id"];
export type ServiceScope = (typeof SERVICE_SCOPE)[keyof typeof SERVICE_SCOPE];
export type FulfillmentReach = (typeof FULFILLMENT_REACH)[keyof typeof FULFILLMENT_REACH];
/** 多语言文案（mock 内部用；对外契约由后端按 Accept-Language 返回已本地化的 string） */
export type I18nText = Record<Lang, string>;
/** 统一响应包 */
export interface Result<T> {
  /** 业务状态码，`0` 表示成功；非 0 时 `data` 无意义，按 `msg` 提示用户 */
  code: number;
  /** 面向用户的提示文案，已按 Accept-Language 本地化 */
  msg: string;
  /** 业务数据。成功时必定存在（无返回值的接口给 `null`） */
  data: T;
}
/** 统一分页包 */
export interface PageResult<T> {
  /** 当前页数据 */
  records: T[];
  /** 满足条件的总条数（不是总页数）——端上据此判断还有没有下一页 */
  total: number;
  /** 当前页码，从 1 起 */
  page: number;
  /** 每页条数 */
  size: number;
}
export interface PageQuery {
  /** 页码，从 1 起。不传按 1 处理 */
  page?: number;
  /** 每页条数。不传按各接口默认值（通常 10 或 20） */
  size?: number;
}
/** 流量来源。**与 ops-web 的 `TrafficSource` 同名** —— 那边多 INVITE/CHANNEL 两个值（已标 MERGE） */
export type TrafficSource = "MERCHANT_OWNED" | "PLATFORM";
