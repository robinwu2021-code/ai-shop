// 门店主页治理域（矩阵 P-10.1）。一期的**主获客路径**：店主分享店铺码 → 用户进店 → 首单。
// 平台侧管三件事：合规审核、店铺码供给、效果度量。

/**
 * 待审内容的种类。
 *
 * 前两种审的是**单据自己带的 content**（店招图 / 公告文本）；
 * `SERVICE_AREA` 审的是**另一张表里的一行** —— 商家框的区、街道覆盖（ADR-013 阶段三）。
 * 它进同一个队列是有意的：运营的工作台上不该有两个长得一样、入口不同的待审列表。
 */
export type StoreAuditKind = "BANNER" | "NOTICE" | "SERVICE_AREA";
export type StoreAuditStatus = "PENDING" | "PASSED" | "REJECTED";

/** 店铺装修区块的排布方式 */
export type SectionLayout = "GRID" | "LIST" | "FEATURE";

export interface StorePageAudit {
  /** 审核单号 */
  auditNo: string;
  /** 提审商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 待审内容类型：店招图 / 公告文本 */
  kind: StoreAuditKind;
  /** 待审内容：店招图 URL、公告文本，或 `DISTRICT:330106` 这样的覆盖项定位串 */
  content: string;
  /**
   * 人话版的 content。`SERVICE_AREA` 时是「浙江省 / 杭州市 / 西湖区」，其余与 content 相同。
   *
   * **列表与详情一律显示它**：让运营对着 `DISTRICT:330106` 判断
   * 「这家菜摊该不该覆盖整个西湖区」，等于让他去别处查一次再回来。
   */
  display?: string;
  /** 审核状态 */
  status: StoreAuditStatus;
  /**
   * 机审命中的敏感词/风险项，随数据下发。
   * 人审要看到「机器为什么标它」，否则只能凭感觉判，同一类内容两个人两个结论。
   */
  hits: string[];
  /** 提审时间 */
  submittedAt: string;
  /** 驳回原因：**原样出现在商家 B 端**，所以驳回必须填 */
  reason?: string;
}

/** 店铺码批量生成与导出（P-10.1.3，供 BD 地推）。 */
export interface StoreQrcode {
  /** 归属商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 所属社区名，BD 按社区领码地推 */
  communityName: string;
  /** 码值（C 端扫码进店的深链参数），导出时给 BD 去印刷 */
  code: string;
  /** 贴纸尺寸规格，如 "10x10cm" */
  size: string;
  /** 已印数量，用于对账印刷成本 */
  printed: number;
  /** 累计扫码次数 */
  scanCount: number;
}

/** 门店获客效果（P-10.1.4）：扫码 → 进店 → 注册 → 首单。 */
export interface StoreAcquisition {
  /** 归属商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 扫码次数 */
  scan: number;
  /** 进店人数 */
  enter: number;
  /** 注册人数 */
  register: number;
  /** 首单人数 */
  firstOrder: number;
  /** 首单转化率 = firstOrder / scan，0–1 */
  convRate: number;
}

// ── 主页模板配置（P-10.1.1）──────────────────────────────────────

/**
 * 店铺主页板块。
 *
 * `BANNER`（店招）是 `required` 的：关掉之后店铺页没有头部，等于一张裸列表。
 * 其余板块商家可以按模板取舍。
 */
export type SectionKey = "BANNER" | "NOTICE" | "HOT" | "CATEGORY" | "COUPON" | "GROUP";

export interface TemplateSection {
  /** 板块标识 */
  key: SectionKey;
  /** 是否启用。`required=true` 的板块不能停用 */
  enabled: boolean;
  /** 必选板块不能停用 */
  required: boolean;
}

/**
 * 店铺主页模板。
 *
 * ⚠️ `usedByCount` 是**只读的引用计数**，不是配置项 —— 它存在的唯一理由是
 * 拦住"停用一个正在被 12 家店用着的模板"这件事。
 */
export interface StoreTemplate {
  /** 模板单号 */
  templateNo: string;
  /** 模板名 */
  name: string;
  /** 商品区排布 */
  layout: SectionLayout;
  /** 板块开关列表 */
  sections: TemplateSection[];
  /** 是否可选用。**停用前要看 `usedByCount`** —— 正在被使用的模板停不得 */
  enabled: boolean;
  /** 默认模板：新店开出来就用它，所以停用不了 */
  isDefault: boolean;
  /** 正在使用该模板的店铺数（只读） */
  usedByCount: number;
  /** 最后修改时间 */
  updatedAt: string;
  /** 最后修改人（STAFF 账号） */
  updatedBy: string;
}

// ── 门店档案（矩阵 P-11.2）────────────────────────────────────────

import type { BusinessMode } from "./finance";

/**
 * 门店经营状态。三档**不是同一个人在管**，所以不能压成一个 `enabled` 布尔：
 *
 * - `ACTIVE`    正常营业；
 * - `READONLY`  商家自助停用（自己关的，自己能开回来）；
 * - `SUSPENDED` 平台强制下线 —— **只有平台解得开**，商家侧的启停对它一律拒绝。
 *
 * 压成布尔的后果是运营看不出「这家店是自己歇业还是被我们压下去的」，
 * 而这两件事该做的动作完全相反。
 */
export type StoreGovernStatus = "ACTIVE" | "READONLY" | "SUSPENDED";

/**
 * 平台视角的门店档案（后端 `mch_store`，`GET /ops/stores`）。
 *
 * **只读为主**：门店资料、价格、库存运营一律不改 —— 平台的边界是「裁、定、兜」，
 * 不替商家运营。这份类型里唯一会被写回的是 `status`（解除强制下线）。
 *
 * 与 {@link StoreMode} 的关系：那份是「准入与保证金」页里**只关心经营模式与收款号**
 * 的窄投影，这份是门店档案的全貌。两者共用 storeNo，故意不合并 ——
 * 合并会让那一页凭空多出十个它不该关心的字段。
 */
export interface StoreGovern {
  /** 门店号 */
  storeNo: string;
  /** 门店名 */
  name: string;
  /** 门店地址 */
  address: string;
  /** 所属商家主体 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 是否主体的默认门店。默认店承接「没指定门店」的那些流量 */
  isDefault: boolean;
  /** 经营状态，见 {@link StoreGovernStatus} */
  status: StoreGovernStatus;
  /** 自营 / 第三方。决定这家店的钱怎么走、票怎么开 */
  businessMode: BusinessMode;
  /**
   * 本店专属收款商户号。
   * **`null` 不是「没配」，是「用主体默认收款号」** —— 显示成空白会被读成前者。
   */
  payMerchantNo: string | null;
  /** 门店公告（走 P-10.1 的机审 + 人审） */
  announcement: string;
  /** 营业时间，展示串 */
  openHours: string;
  /** 配送半径（米） */
  deliveryRadiusM: number;
  /** 起送价（分） */
  deliveryMinOrderMinor: number;
  /** 配送费（分） */
  deliveryFeeMinor: number;
  /** 免配送费门槛（分） */
  deliveryFreeThresholdMinor: number;
}

/**
 * 门店经营状况（`GET /ops/stores/{storeNo}/stats`）。
 *
 * 后端复用商家自己在 B 端看的那套统计，不另存计数器 ——
 * 另存的迟早出现「总览说 3 单、点进去只有 2 单」。
 *
 * 待办只有**门店维度**三项：核销与分拣是自提点维度且不限商家，
 * 摆进门店页会被读成「这家店的活」。
 */
export interface StoreStats {
  /** 门店号 */
  storeNo: string;
  /** 所属商家主体 */
  merchantNo: string;
  /** 今日订单数 */
  todayOrders: number;
  /** 今日 GMV（分） */
  todayGmvMinor: number;
  /** 本月订单数 */
  monthOrders: number;
  /** 本月 GMV（分） */
  monthGmvMinor: number;
  /** 自带客流占比，0–1。**直接对应这家店少付的佣金**（ADR-004） */
  ownedTrafficRate: number;
  /** 待发货 */
  toShip: number;
  /** 待自送 */
  toDeliver: number;
  /** 缺货待补。运营看它判断「这家店是不是没人管了」 */
  toStock: number;
}
