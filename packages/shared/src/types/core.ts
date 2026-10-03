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
/**
 * 流量来源。与 `ord_sub_order.traffic_source` 的库列注释逐字一致（下单时固化）。
 * ops-web 的同名类型 2026-09-10 起也是这两个值 —— 它此前多的 INVITE/CHANNEL
 * 后端从不下发，已删（见 ops-web/lib/types/order.ts 的注释）。
 */
export type TrafficSource = "MERCHANT_OWNED" | "PLATFORM";

/**
 * 冷启动配置（`GET /mp/config/bootstrap`）。
 *
 * `features` 里 **yml 与运营端那一屏已经在后端合流**，端上只认这一份 ——
 * 有它才谈得上「运营后台改一下开关」对买家侧生效（此前端上一次都没调过这条端点，
 * 拿到的只有编译期常量，改一个开关要重新发版、小程序还要重新提审）。
 */
export interface BootstrapConfig {
  /** 默认皮肤（`fresh` / `brand` …）。用户没挑过时按它渲染 */
  defaultSkin: string;
  /** 平台开关。取值见各自的使用点，例如 `merchant.apply.mp-visible` */
  features: Record<string, boolean>;
  /** 低于它要提示升级 */
  minAppVer: string;
  /** 客服在线时段，形如 `09:00-21:00`。只用于展示，不参与任何判断 */
  serviceHours: string;
  /**
   * 商家版 App 的下载地址，按平台各一条。
   *
   * **由后端下发，端上不写死域名** —— 写在端上就有两处真源（官网一份、小程序一份），
   * 而这个项目已经错过一次：商家端链接曾写死成 `shop.example.com`，印了贴纸才发现。
   *
   * **空的那一档不显示**，不是显示一个点不开的地址。iOS 版在苹果审核队列里，
   * 上架前那一档是 TestFlight 公开链接，现在是空的。
   */
  merchantApp?: {
    android: string;
    ios: string;
    /**
     * 安卓包的**最新版本号**，后端从发版脚本写的 `/dl/latest.json` 读。
     *
     * **端上不要再写一份** —— 版本号此前写死在三处（官网 site.config、
     * 服务器 env、人的记性），每处都要手工跟，于是每处都会掉队：
     * 2026-09-30 查出服务器那处停在 0.4.98，而官网已经 0.5.21，差二十多版。
     * 掉队时下载照样 200、照样装得上，只是功能旧，**没有任何信号**。
     *
     * 空串 = 后端也没读到清单，端上就不显示版本号（不显示好过显示一个猜的值）。
     */
    androidVersion?: string;
  };
  /**
   * 微信客服（企业微信那款）的接入参数，给 `wx.openCustomerServiceChat` 用。
   *
   * **两个都有才算配好**：缺一个端上就回落到小程序原生的 `open-type="contact"`。
   * 拿半截参数去调那个 API，失败是**静默**的 —— 界面上与「压根没配」一模一样，
   * 所以回落要整体判，不能一个一个判。
   *
   * **为什么随冷启动发，而不是点的时候现拉**：那个 API 在 iOS 上要求由用户手势
   * **直接**触发，先 `await` 再调会被判「并非点击触发」；Android 却能过，
   * 于是这个坑只在 iOS 真机上现形。值必须在点击之前就在端上。
   */
  customerService?: {
    /**
     * 企业微信 CorpID。**同主体还不够，必须在小程序后台绑过** ——
     * 没绑的表现是 `errCode 6`，而界面上看着仍然只是「点了没反应」。
     */
    corpId: string;
    /** 客服接入链接（企微后台 → 应用管理 → 微信客服 → 客服账号详情） */
    url: string;
  };
}
