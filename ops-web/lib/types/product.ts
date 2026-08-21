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

export const MAX_CATEGORY_LEVEL = 2;

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
  /**
   * 门店投影（列表查询带 `storeNo` 时才有值）：这件商品在**那家店**上不上架。
   *
   * `null`/缺失 = 未按店管理，跟随主体级 `status` —— 与「在那家店下架了」是两回事，
   * 显示成同一个「否」会让运营去催商家上架一件其实全店都在卖的商品。
   */
  storeOnSale?: boolean | null;
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
  /**
   * 门店投影（列表查询带 `storeNo` 时才有值）：该店的可用库存。
   *
   * `null`/缺失 = 该 SKU 未启用分店库存，`stock` 就是它的数；
   * 启用了但那家店没有行 = 0（**不回退总量**，与后端 V13 语义一致）。
   */
  storeStock?: number | null;
}

/**
 * 平台规格模板（P-3.4 / E27，后端 `prd_spec_template` 里 `scope=PLATFORM` 的那些）。
 *
 * <p>B-4.4 商家建品时能选它，而平台端此前**没有维护入口** —— 表里只有初始化时
 * 塞进去的几行，谁也改不了、加不了。三端联动表把这条记成「❌ 断裂：模板是死的」。
 *
 * <p>与商家自存的模板（`scope=MERCHANT`）不是同一批数据：那些归商家，
 * 平台端一条都不该列出来，更不该改 —— 改了那家店的历史规格就对不上了。
 */
export interface SpecTemplate extends Archivable {
  /** 模板单号 */
  templateNo: string;
  /** 恒为 `PLATFORM`。后端写死，请求体里传什么都忽略 */
  scope: string;
  /**
   * 按五品类预置（与 `CategoryTemplate` 同一套取值）。**空 = 不限品类**。
   * 商家建品时按这个轴筛（`GET /biz/goods/spec-templates?categoryType=`）。
   */
  categoryType?: CategoryTemplate | null;
  /** 规格维度名，如「重量」「香型」 */
  name: string;
  /** 选项。整体替换，不做逐项 diff */
  options: SpecTemplateOption[];
  createdAt?: string;
}

/** {@link SpecTemplate} 的一个选项。 */
export interface SpecTemplateOption {
  /**
   * 聚合键。**平台模板必填** —— 这是平台模板存在的唯一理由（B-4.5）：
   * 自由文本下三家店会把同一件事写成「5 斤」「五斤」「2.5kg」，
   * 聚合、比价、搜索全部对不上。没有 code 的平台模板与商家手输的没有区别。
   */
  code: string;
  /** 展示文案 */
  label: string;
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

/**
 * 商品详情（后端 `GoodsVO`，`GET /ops/goods/{goodsNo}`）。
 *
 * <p>**只声明运营端抽屉真的会读的字段** —— 后端那份 VO 是 C 端契约，
 * 有近三十个字段（评分、销量、拼团配置、称重克重…），照抄一遍等于在前端
 * 维护一份"我们从不显示"的清单，而它每次后端调整都会假性变更。
 *
 * <p>与 {@link ProductGoods} 的关系：那是**列表行**（一次给一页，字段窄），
 * 这是**单条详情**（一次一件，字段全）。两者故意不是同一个类型：
 * 列表塞进详情的字段会让分页响应大一个量级。
 */
export interface GoodsDetail {
  /** 商品单号 */
  goodsNo: string;
  /** 标题（按当前语言拍平后的那一份） */
  title: string;
  /** 副标题 / 卖点 */
  subtitle?: string;
  /** 封面图 */
  cover?: string;
  /** 详情图。后端必发（可能是空数组） */
  images: string[];
  /** 商品形态 NORMAL/FRESH/SERVICE/VIRTUAL/CARD */
  type: string;
  /** 平台类目 */
  categoryNo?: string;
  /** 归属商家 brief —— 审核要看得到是谁上的架 */
  merchant?: { merchantNo: string; name: string };
  /**
   * 三语标题原文（`prd_goods.title_i18n`）。
   * 运营审文案看的是它，而不是拍平后的 `title` —— 拍平那份看不出缺译。
   */
  titleI18n?: Partial<Record<"zh" | "en" | "ar", string>>;
  /** 三语副标题原文，同 {@link titleI18n} */
  subtitleI18n?: Partial<Record<"zh" | "en" | "ar", string>>;
  /** 规格组（如「重量」→「500g / 1kg」）。后端必发 */
  specGroups: { name: string; options: string[] }[];
  /** SKU 矩阵。后端必发 */
  skus: GoodsDetailSku[];
  /** 支持的履约方式（自提 / 配送 …）。后端必发 */
  fulfillments: string[];
  /** 展示价 = 最低 SKU 价（分） */
  price?: number;
  /** 商品状态：AUDITING / ON_SALE / OFF_SALE / REJECTED */
  status?: string;
  /**
   * 最近一次驳回 / 强制下架的原因。
   * **它是商家能看到的那半边** —— 审计日志只有运营看得到，
   * 没有它商家面对 REJECTED 只能猜要改什么。过审时清空。
   */
  auditReason?: string | null;
}

/** {@link GoodsDetail} 里的一条 SKU。价格是**单一价**（分），不按市场分列 —— 后端 `SkuVO` 就这一份。 */
export interface GoodsDetailSku {
  /** 规格单号 */
  skuNo: string;
  /** 各规格组上选中的值，顺序与 `specGroups` 一致 */
  optionValues: string[];
  /** 规格展示串（如「500g / 红」）。后端拼好下发，前端不再自己拼 */
  spec?: string;
  /** 售价（分） */
  price: number;
  /** 划线价（分）。没有 = 不划线 */
  originPrice?: number | null;
  /** 可售库存 */
  stock: number;
}

/**
 * 平台标准品（TDD-标准品库）：商家引用建品的**模子**。
 *
 * <p>**无价、无库存、无履约** —— 那些永远是商家的。标准品一旦带价，
 * 它就成了平台指导价，那是完全另一件事（且有法律含义）。
 *
 * <p>它存在的理由是 `specGroups` 里的 `optionCode`：没有标准品，
 * 三家店各自录「本地菠菜」得到三个毫无关系的商品，聚合与比价无从谈起。
 */
/**
 * 主题分类（陈列）。
 *
 * <p><b>与类目正交、与活动分开</b>：类目回答「这是什么货、要什么资质」，
 * 活动回答「打几折」，主题只回答「这周首页摆什么」。
 */
export interface Topic {
  topicNo: string;
  title: string;
  /** 一句话说明，如「7 点前送到」。空 = 不展示副标题 */
  subtitle?: string;
  cover?: string;
  /** 首页排序，小的在前 */
  sort: number;
  /** 生效起止（毫秒）。**都可空 = 常设专题** —— 填一个假的结束时间会让它某天悄悄消失 */
  startAt?: number;
  endAt?: number;
  /** ACTIVE / ARCHIVED。归档不删：分享出去的海报还指着它 */
  status?: string;
  /** 专题里有几件商品。**空专题在 C 端是一个点进去什么都没有的入口**，列表要看得见 */
  goodsCount: number;
}

export interface SpuStd extends Archivable {
  stdNo: string;
  /** 所属类目。商家取用后**改不掉**（服务端覆盖）：类目决定形态 */
  categoryNo: string;
  categoryName?: string;
  title: string;
  titleI18n?: Record<string, string>;
  subtitle?: string;
  cover?: string;
  images?: string[];
  /** 每个选项都必须带 `optionCode` —— 这是标准品存在的唯一理由 */
  specGroups: { name: string; options: string[]; optionCodes?: string[]; templateNo?: string }[];
  /** 别名/品牌/俗称，空格分隔。商家搜「洋芋」也要能命中标题是「土豆」的那条 */
  keywords?: string;
  status?: string;
  /** 被引用次数。只服务排序与去重判断，不参与任何校验 */
  refCount?: number;
}
