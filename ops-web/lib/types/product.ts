// 商品与类目域（矩阵 P-3）。商家能入驻但没有商品池时，整条交易链路是空的 —— 这块把已交付的域串起来。
import type { Archivable } from "./common";

/** 品类属性模板（P-3.1.2）：五品类的字段结构不同，模板决定商家录入时看到哪些字段。 */
export type CategoryTemplate = "STANDARD" | "FRESH" | "SERVICE" | "VIRTUAL" | "VOUCHER";

/** 多市场（B6：必须分别定价，不做汇率换算）。 */
export type Market = "CN" | "SG";
export const MARKETS: Market[] = ["CN", "SG"];

/** 三语文案。zh 是基准，en/ar 缺失时按 R9 回落规则展示 zh。 */
export interface I18nText {
  /** 中文，**基准语言，必填** */
  zh: string;
  /** 英文。缺失时按 R9 回落规则展示 zh */
  en?: string;
  /** 阿拉伯语。缺失时回落 zh */
  ar?: string;
}

export const MAX_CATEGORY_LEVEL = 3;

export interface Category extends Archivable {
  /** 类目单号 */
  categoryNo: string;
  /** 类目名（运营侧展示用中文名） */
  name: string;
  /** 顶级为空 */
  parentNo?: string;
  /** 1–3，见 MAX_CATEGORY_LEVEL */
  level: number;
  /** 品类属性模板。**决定商家录入时看到哪些字段** */
  template: CategoryTemplate;
  /**
   * 类目资质要求（P-3.1.4）：人读的资质名称，展示给运营与商家看。
   * ⚠️ 它**不是**校验依据 —— 真正校验用下面的 `requiredCode`。
   */
  qualifications: string[];
  /**
   * 经营该类目所需的**经营类目编码**，对应商家档案的 `categoryCodes`（入驻时申请、平台授权）。
   * 空 = 无门槛。
   *
   * 为什么单列一个字段而不是拿 `qualifications` 的文案去匹配：文案是给人看的，
   * 拿它做判据会写成「类目号以 CAT1 开头就认为需要生鲜资质」这类前缀魔法 ——
   * 看起来在校验，实际上几乎总是通过。
   *
   * ⚠️ 当前校验的是**入驻时申请的经营类目**，不是资质证件本身；
   * 证件校验要等 B-11.1.2 资质上传落地后再收紧。
   */
  requiredCode?: string;
  /** 类目名的三语文案，下发给 C 端展示 */
  i18n: I18nText;
  /** 该类目下的在售商品数（归档校验要用） */
  skuCount: number;
}

/** REJECTED 不是终态：商家改完可以重新提审。 */
export type SkuStatus = "DRAFT" | "PENDING" | "ON_SALE" | "OFF_SALE" | "REJECTED";

export const SKU_TRANSITIONS: Record<SkuStatus, SkuStatus[]> = {
  DRAFT: ["PENDING"],
  PENDING: ["ON_SALE", "REJECTED"],
  ON_SALE: ["OFF_SALE"],
  OFF_SALE: ["ON_SALE", "PENDING"],
  REJECTED: ["PENDING"],
};

export interface Sku {
  /** 商品单号 */
  skuNo: string;
  /** 商品标题（三语） */
  title: I18nText;
  /** 归属商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 归属类目 */
  categoryNo: string;
  /** 类目名快照 */
  categoryName: string;
  /** 商品状态。`REJECTED` 不是终态，改完可重新提审，见 `SKU_TRANSITIONS` */
  status: SkuStatus;
  /** 各市场价格（分）。**缺任一市场价格不予通过**（B6） */
  prices: Partial<Record<Market, number>>;
  /** 现货库存 */
  stock: number;
  /** 预售额度（P-3.3.1）。0 = 不做预售 */
  presaleQuota: number;
  /** 已售（预售期内） */
  soldCount: number;
  /** 截单时间（P-3.3.2）。必须早于到货时间，否则货到了还能下单 */
  cutoffAt?: string;
  /** 到货时间（与履约批次对齐） */
  arriveAt?: string;
  /** 创建时间 */
  createdAt: string;
  /** 驳回/强制下架原因，原样进商家 B 端 */
  reason?: string;
}

/**
 * 商品池里的一行（goods 粒度，SKU 收在 `skus` 里）——**不是** {@link Sku} 的复数形式。
 *
 * <p>后端 `prd_goods`/`prd_sku` 本来就是一对多：标题、图、类目、审核状态都在 goods 上，
 * 价格/库存/规格才是 sku 的。商品池按 goods 展示、审核/强制下架/预售这几个动作
 * 仍然打在具体某个 sku 上（见 `skus[].skuNo`）——两者granularity 不同，别混用。
 */
export interface ProductGoods {
  /** 商品单号 */
  goodsNo: string;
  /** 标题（三语） */
  title: I18nText;
  /** 封面图 */
  cover?: string;
  /** 归属商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 归属类目 */
  categoryNo?: string;
  /** 类目名快照 */
  categoryName?: string;
  /** 商品状态：AUDITING / ON_SALE / OFF_SALE / REJECTED */
  status: string;
  /** 这件商品下的所有规格 */
  skus: GoodsSkuRow[];
}

/**
 * 商品池里一个商品下的某个规格——**故意不是** {@link Sku}。
 *
 * <p>{@code Sku} 那份（预售额度/已售/截单时间）是「库存与预售」tab 的形状，
 * 而那几个字段在真实后端压根不存在（`prd_sku` 没有这些列，见该 tab 的说明）。
 * 商品池要的是"这个规格在每个市场卖多少钱、还有多少库存"，直接照实定义，
 * 不要为了复用一个类型就在这里塞几个"这次数据源永远给不出来"的字段。
 */
export interface GoodsSkuRow {
  skuNo: string;
  optionValues: string[];
  spec?: string;
  /** 按市场分别定价（分）。缺某个市场 = 没这个市场的价，不是 0 元 */
  prices: Partial<Record<Market, number>>;
  stock: number;
}

/**
 * 待审商品（后端 `prd_goods`，**goods 粒度不是 sku 粒度**）。
 *
 * <p>与本文件里 `Sku` 的差别：审核判的是「这件商品能不能卖」——
 * 标题、图、类目、资质都在 goods 上；sku 只是规格与价格。
 * 拿 sku 粒度去审，同一件商品会被审好几遍。
 */
export interface GoodsAudit {
  /** 商品单号。审核动作打在它上面 */
  goodsNo: string;
  /** 标题。审核先看它 —— 违规多半从标题就能看出来 */
  title: string;
  /** 副标题/卖点 */
  subtitle?: string;
  /** 封面图。图文不符是驳回的主因之一，所以要能看到图 */
  cover?: string;
  /** 商品形态 NORMAL/FRESH/SERVICE/VIRTUAL/CARD */
  type: string;
  /** 平台类目。**当前恒为空** —— 商品编辑页还没有选类目这一步 */
  categoryNo?: string;
  /**
   * 归属商家（后端下发的是一个 brief 对象，不是裸的 merchantNo）——
   * 审核时要看得到是谁上的架：同一个商家反复交同类违规品是有信号的。
   */
  merchant?: { merchantNo: string; name: string };
  /**
   * 商品状态。**字段名是 `status` 不是 `auditStatus`** ——
   * 后端 `GoodsVO` 里它同时承载审核态与上下架态：AUDITING / ON_SALE / OFF_SALE / REJECTED。
   */
  status?: string;
}
