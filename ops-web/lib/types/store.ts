// 门店主页治理域（矩阵 P-10.1）。一期的**主获客路径**：店主分享店铺码 → 用户进店 → 首单。
// 平台侧管三件事：合规审核、店铺码供给、效果度量。

export type StoreAuditKind = "BANNER" | "NOTICE";
export type StoreAuditStatus = "PENDING" | "PASSED" | "REJECTED";

export interface StorePageAudit {
  /** 审核单号 */
  auditNo: string;
  /** 提审商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 待审内容类型：店招图 / 公告文本 */
  kind: StoreAuditKind;
  /** 待审内容：店招图 URL 或公告文本 */
  content: string;
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
  layout: "GRID" | "LIST" | "FEATURE";
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
