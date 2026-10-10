import type { MerchantChainStuck } from "./merchant";
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
  /**
   * 同级内的展示顺序，小的在前。**C 端类目栏就按它排** ——
   * 不下发就等于运营改不了顺序，「把生鲜挪到第一个」只能改库。
   */
  sort: number;
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
  /** SKU 号 */
  skuNo: string;
  /** 这条 SKU 的规格取值组合 */
  optionValues: string[];
  /** 规格描述，人读的 */
  spec?: string;
  /** 按市场分别定价（分）。缺某个市场 = 没这个市场的价，不是 0 元 */
  prices: Partial<Record<Market, number>>;
  /** 可售库存 */
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
  /** 创建时刻 */
  createdAt?: string;
}

/**
 * 规格项（规格库 V195）。**通用与专用是运营端的两个页面**：
 * 通用维度改一条全站生效，专用维度只影响一个类目 ——
 * 混在一张表里，改的人不知道自己动了多大范围。
 */
export interface SpecDim {
  /** 维度号 */
  dimNo: string;
  /** 语义码 COLOR / WEIGHT。值编号与 optionCode 都以它为前缀，**改码等于换一根聚合轴** */
  code: string;
  /** 维度名（「颜色」「净重」） */
  name: string;
  /** ENUM 枚举 / QUANT 数值+单位。QUANT 的值必须有归一量 */
  valueType: string;
  /** 单位。QUANT 型必填，ENUM 型为空 */
  unit?: string | null;
  /** SALE 进 SKU 笛卡尔积 / PROP 只是描述 */
  usageType: string;
  /** 通用维度：所有类目都能用 */
  universal: boolean;
  /** `PLATFORM` 平台的 / `MERCHANT` 商家自建的 */
  scope: string;
  /** 哪家商家的票 */
  entityNo?: string | null;
  /** 排序权重 */
  sort: number;
  /** 状态 */
  status: string;
  /** 这个维度下有几个取值 */
  valueCount: number;
  /** 被几个类目绑着 —— 归档前要知道自己在动多大范围 */
  inUse: number;
  /** 取值列表 */
  values: SpecValue[];
}

/** 规格值。**有编号有归一量**，才谈得上聚合、排序与比价。 */
export interface SpecValue {
  /** 取值编号 */
  valueNo: string;
  /** 维度号 */
  dimNo: string;
  /** 语义码 */
  code: string;
  /** 显示名 */
  label: string;
  /** 归一量：500g / 半斤 / 0.5kg 都是 500 */
  numericValue?: number | null;
  /** 归一量的单位。与 numericValue 一起才有意义 */
  numericUnit?: string | null;
  /** 别名：识别、搜索与自动归一用 */
  aliases: string[];
  /** PLATFORM / MERCHANT。商家自有值挂在平台维度下，仍在同一根轴上 */
  scope: string;
  /** 哪家商家的票 */
  entityNo?: string | null;
  /** 排序权重 */
  sort: number;
  /** 状态 */
  status: string;
  /** 多少个商家在用这个值 —— 停用前要知道影响面 */
  merchantCount: number;
}

/** 类目绑定的整份替换体。顺序即排序，主维度只能有一个 */
export interface CategorySpecBinding {
  /** 规格维度号 */
  dimNo: string;
  /** `SALE` 进 SKU 笛卡尔积 / `PROP` 只是描述 */
  usageType?: string | null;
  /** 主维度：建品选完类目自动预填的就是它 */
  primary: boolean;
  /** 必填：不选它就建不了品 */
  required: boolean;
  /** 这一类目开放的取值；空 = 不裁剪 */
  valueNos: string[];
  /** valueNo → 类目内换名（500g 在蔬菜下叫「约1斤」） */
  labels: Record<string, string>;
}

/**
 * 停用一个类目的影响面（`GET /ops/categories/{no}/archive-impact`）。
 *
 * **有在售商品不再是拦截**：运营停一个类目多半是政策要求（这一类这期不做、
 * 资质链路没接上），拦住他并不能让那批商品消失。界面把后果说清楚，由他决定。
 */
export interface CategoryArchiveImpact {
  /** 这个类目下有几件商品 */
  goodsCount: number;
  /** 其中在架几件。**归档前要看** —— 在架的会一起下架 */
  onSaleCount: number;
  /** 还开着的子类目数。**大于 0 时后端仍会拒** —— 会冒出渲染不出来的孤儿节点 */
  activeChildren: number;
}

/**
 * 类目 × 规格总览的一行（规格库 V195，`GET /ops/category-specs`）。
 *
 * **一条规格都没绑的类目也会返回**：这张表真正要回答的是「哪些类目还没配」——
 * 只列已配的，缺口就永远看不见，而缺口的代价是那一类商家建品只能手打，
 * 手打的选项没有 code，跨店聚合就此断掉。
 */
export interface CategorySpec {
  /** 类目号 */
  categoryNo: string;
  /** 类目名 */
  categoryName: string;
  /** 一级类目名，用来分组 */
  parentName: string;
  /** 类目形态 */
  categoryType?: CategoryTemplate | null;
  /** 已绑维度数。0 就是缺口 */
  dimCount: number;
  /** 这个类目能用的规格维度 */
  dims: CategorySpecDim[];
}

/** 类目下的一个规格维度。 */
export interface CategorySpecDim {
  /** 规格维度号 */
  dimNo: string;
  /** 语义码 COLOR / WEIGHT。值编号与 optionCode 都以它为前缀 */
  code: string;
  /** 名称 */
  name: string;
  /** ENUM 枚举 / QUANT 数值+单位 */
  valueType: string;
  /** 单位 */
  unit?: string | null;
  /** SALE 进 SKU 笛卡尔积 / PROP 只是描述，不生成规格 */
  usage: string;
  /** 通用维度：值的含义跨类目一致（颜色、重量）。与只在本类目成立的专用维度相对 */
  universal: boolean;
  /** 主维度：商家建品选完类目，自动预填的就是它。每个类目至多一个 */
  primary: boolean;
  /** 有几个取值 */
  valueCount: number;
  /** 取值列表 */
  values: CategorySpecValue[];
}

/** 维度下的一个取值。 */
export interface CategorySpecValue {
  /** 规格取值号 */
  valueNo: string;
  /** 码值 */
  code: string;
  /** 该类目下的展示文案，可能被类目级换名换过（500g → 约1斤） */
  label: string;
  /** 归一量：500g / 半斤 / 0.5kg 都是 500。排序与同规格比价靠它 */
  numericValue?: number | null;
  /** 归一量的单位。与 numericValue 一起才有意义 */
  numericUnit?: string | null;
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
 * 待审草稿的字段级差异（双版本，`GET /ops/goods/{goodsNo}/draft-preview`）。
 *
 * <p>审核开着时，队列里那件 AUDITING 商品**线上照卖旧版**，详情给的也是旧版 ——
 * 没有这份 diff，审核员批准的是一个自己从没看过的版本。与商家发布确认页
 * 出自服务端**同一段代码**，两边看到的不可能不一致。
 *
 * <p>接口对无草稿的商品返回 `null`（新建提审等老链路审的是内容本身）—— 常态不是错误。
 */
export interface GoodsDraftPreview {
  /** 逐字段差异。label 是服务端给的中文名，界面只排版不加工 */
  changes: { field: string; label: string; before: string | null; after: string | null }[];
  /** 引用了已停用/已合并规格档的档位 —— 非空时「通过」会在换版时失败（80017） */
  blocked: string[];
  /** 草稿基版过期（商家确认后会重新基线）。审核侧只作提示，不拦动作 */
  stale: boolean;
  /** 差异所基于的线上 version（商家确认发布用；审核侧不消费） */
  baseVersion: number;
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
  /** 专题号 */
  topicNo: string;
  /** 标题 */
  title: string;
  /** 一句话说明，如「7 点前送到」。空 = 不展示副标题 */
  subtitle?: string;
  /** 封面图 */
  cover?: string;
  /** 首页排序，小的在前 */
  sort: number;
  /** 生效起止（毫秒）。**都可空 = 常设专题** —— 填一个假的结束时间会让它某天悄悄消失 */
  startAt?: number;
  /** 结束时刻 */
  endAt?: number;
  /** ACTIVE / ARCHIVED。归档不删：分享出去的海报还指着它 */
  status?: string;
  /** 专题里有几件商品。**空专题在 C 端是一个点进去什么都没有的入口**，列表要看得见 */
  goodsCount: number;
}

export interface SpuStd extends Archivable {
  /** 标准品号 */
  stdNo: string;
  /** 所属类目。商家取用后**改不掉**（服务端覆盖）：类目决定形态 */
  categoryNo: string;
  /** 类目名 */
  categoryName?: string;
  /** 标题 */
  title: string;
  /** 标题的多语言版本 */
  titleI18n?: Record<string, string>;
  /** 副标题 */
  subtitle?: string;
  /** 封面图 */
  cover?: string;
  /** 图集 */
  images?: string[];
  /** 每个选项都必须带 `optionCode` —— 这是标准品存在的唯一理由 */
  specGroups: { name: string; options: string[]; optionCodes?: string[]; templateNo?: string }[];
  /** 别名/品牌/俗称，空格分隔。商家搜「洋芋」也要能命中标题是「土豆」的那条 */
  keywords?: string;
  /** 状态 */
  status?: string;
  /** 被引用次数。只服务排序与去重判断，不参与任何校验 */
  refCount?: number;
  /** 商品条码。**空是常态** —— 生鲜、现做熟食、服务本来就没有条码 */
  barcode?: string;
  /**
   * 出处：`OPS` 运营手录 / `OFF` 从开放库导入。
   *
   * <p>导进来的那批标题是原始众包文案（品牌写法不一、错别字都有），
   * 所以全部落成归档态等人过目。运营靠这一列把「还没人看过的」与「自己录的」分开审。
   */
  source?: string;
}

/**
 * 类目 × 支付方式（线下）。
 *
 * **`offlineAllowed` 的默认是「允许」**：后端那张表的语义是
 * 「没有行即放行，插 allowed=0 才是禁止」。设计成白名单的话，
 * 上线当天得先把 57 个类目全配一遍才有人下得了单。
 */
export interface CategoryPayMode {
  /** 类目号 */
  categoryNo: string;
  /** 类目名 */
  categoryName: string;
  /** 父类目名。**同名子类目很常见**，只给自己的名字分不清是哪个 */
  parentName: string;
  /** 这个类目准不准线下付。**默认放行** —— 没有行即不限制 */
  offlineAllowed: boolean;
  /** 是否**显式配过**。与 offlineAllowed 分开：没配过也是允许，但两者含义不同 */
  configured: boolean;
}

/**
 * 类目 × 积分发放规则。**平台按类目统一管理，商家不参与配置** ——
 * 依据是实测：线上 199 件商品里，用商品级配置配了积分的是 0 件。
 *
 * `earnValue` 是**整数**：FIXED 存分、RATIO 存万分比（千分之一 = 10）。
 * 不用浮点 —— 金额与比例一旦用 double，对账时的分位差没人说得清。
 */
export interface CategoryPoints {
  /** 类目号 */
  categoryNo: string;
  /** 类目名 */
  categoryName: string;
  /** 父类目名。**同名子类目很常见**，只给自己的名字分不清是哪个 */
  parentName: string;
  /** FIXED 定额 / RATIO 按成交额比例；**空 = 没配**，走平台兜底 */
  earnMode: "FIXED" | "RATIO" | null;
  /** 发分比例（万分比） */
  earnValue: number | null;
}

/**
 * 积分的**端策略**。存的是**禁用名单，不是允许名单** ——
 * `X-Client` 头今天还没有哪个端全量在发，用允许名单会让开关一上线就把全站积分静默关掉。
 *
 * ⚠️ 它**不是合规硬闸**：端标识来自客户端、可伪造，只能用于平台策略。
 */
export interface ClientPointsPolicy {
  /** 这些端不发放积分 */
  earnDeny: string[];
  /** 这些端不能用积分抵扣 */
  redeemDeny: string[];
  /** 当面付能不能用积分抵扣。**默认开** —— 成本本来就在商家，线下反而比线上简单 */
  offlineRedeem: boolean;
}

/**
 * 商品域平台统计（M4）。
 *
 * <p>此前这个域**一个统计数字都没有**，而商品是这个平台的主体。
 * 四个数各自对应一个能做的事，不是四个摆着看的指标。
 */
export interface ProductStats {
  categories: number;
  /** 至少被一个商品用过的类目数 */
  categoriesUsed: number;
  skus: number;
  /** 填了条码的。**扫码功能的天花板就是这个数** */
  skusWithBarcode: number;
  /** 填了商家货号的 */
  skusWithCode: number;
  specDims: number;
  /** 至少挂到一个类目上的维度数。规格库只增不减，没挂上的是清理依据 */
  specDimsBound: number;
  auditApproved: number;
  auditRejected: number;
  auditPending: number;
  /** 最近 N 天的审核动作数 —— **吞吐**，与上面三个累计数不是一回事 */
  auditActions: number;
  auditDays: number;
}

/**
 * 单商品全链路状态（M5）。
 *
 * <p>「审核到哪了、建账了吗、有库存吗、卖了多少」此前要在四个页面之间跳着看，
 * 而它们各自的主键还不一样。卡点用词与链条画像同一套 —— 分叉就是两套结论。
 */
export interface GoodsChain {
  goodsNo: string;
  title: string | null;
  entityNo: string;
  auditStatus: string | null;
  onSale: boolean;
  skuCount: number;
  /**
   * 其中在进销存里建了账的。**少于 skuCount 就是投影没搬全** ——
   * 商家端的表现是「有些规格盘得着、有些盘不着」，极难自查
   */
  bookedSkus: number;
  onHand: number;
  available: number;
  soldCount: number;
  /** 卡在哪一层。null = 这一件是通的 */
  /** 与链条画像用同一套词（）—— 两处分叉就是两套结论 */
  stuckAt: MerchantChainStuck | null;
}

/**
 * 平台禁售词（商品①）。商家提审商品时前置校验标题。
 *
 * <p>**此前只有事后驳回**：带违禁词的标题会进审核队列、占一个审核员的时间、
 * 再被驳回，而商家隔几天才知道要改哪个字。
 */
export interface BannedWord {
  id: number;
  /** 词。**存的是小写**，匹配时两边都转小写 */
  word: string;
  /** 为什么禁。**会原样出现在给商家的报错里**，所以要写成他看得懂的一句话 */
  reason: string | null;
  enabled: boolean;
}

/**
 * 建品规则（商品①）。提审那一刻校验，**拦在进审核队列之前**。
 *
 * <p>**三条默认全关**（等于今天的行为）：一旦打开，命中的存量商品下次提审全会被拦，
 * 而平台上有 200 个 SPU、194 个正卡在审核里。默认打开等于在没人预告的情况下
 * 让一批商家的提交突然失败。
 */
export interface ProductPolicy {
  /** 提审前必须有主图 */
  requireCover: boolean;
  /** 标题最少几个字，0 = 不限 */
  titleMinLength: number;
  /** 标题最多几个字，0 = 不限 */
  titleMaxLength: number;
}
