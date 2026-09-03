// 商品编辑页的**价格与 SKU 行**：一行一个规格组合，三个市场各一套价。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么单独一个文件
// ─────────────────────────────────────────────────────────────────────────────
// `goods-edit/index.vue` 曾经是 3869 行 —— 图片、类目、规格、价格、库存、编码
// 六件事的状态与规则全挤在一个 `<script setup>` 里，找一段要靠搜索。
// 这一块（价格 / 成本 / 毛利 / 条码货号单位 / SKU 行）自成一体：
// 它只依赖「是不是生鲜」这一个外部条件（标称重量那一格），其余都是自己的事。
//
// **搬过来的实现一个字没改**，只是把 `isFresh` 变成入参。
import { computed, ref } from "vue";
import type { Ref } from "vue";
import { MARKETS } from "@shared/utils/constants";
import type { CurrencyCode } from "@shared/types";
import { SHOW_FRESH_FIELDS } from "@/shared/flags";

export interface Row {
  skuNo?: string;
  optionValues: string[];
  /** 按市场分别填的价（主单位字符串）。未填 = 不在该市场售卖 */
  priceMajor: Record<CurrencyCode, string>;
  stock: string;
  /**
   * 划线价（主单位字符串）。**此前端上没有任何写入路径** ——
   * 列有、契约有、C 端也照着它渲染折扣标，就是没人填得进去，
   * 于是折扣标从上线到现在一次都没出现过（线上 198 条商品里只有 4 个 SKU 有值，
   * 还是种子数据带的）。必须高于售价，否则后端拒。
   */
  originMajor: string;
  /**
   * 标称重量（克）。生鲜「按标称预扣、称重后多退少补」整条链靠它，
   * 同样是有列、有契约、无入口。非生鲜不显示这一格。
   */
  nominalGram: string;
  /**
   * 成本价（主单位字符串）。**只有商家自己看得到** —— 不下发买家端。
   * 填了就在售价旁边实时算毛利；不填这一行就不出现。
   */
  costMajor: string;
  /**
   * 商品条码 EAN-13 / UPC（V252）。与 ERP、收银秤、供应商的通用键。
   * **空是常态** —— 生鲜、现做熟食、手工品本来就没有条码。
   */
  barcode: string;
  /** 商家自有货号。他自己 ERP 里的主键，对账靠它 */
  merchantSkuCode: string;
  /** 计量单位（件 / 斤 / kg / 份）。称重品与计件品的分界 */
  saleUnit: string;
}

/** 空价格表：三个市场各一格 */
export function emptyPrices(): Record<CurrencyCode, string> {
  return { CNY: "", USD: "", AED: "" };
}

/**
 * 价格与 SKU 行的全部状态与规则。
 *
 * @param isFresh 这件货是不是生鲜 —— 只有生鲜才有「标称重量」那一格
 */
export function usePriceRows(isFresh: Ref<boolean>) {
  // ── 七、价格 · 成本 · 毛利 ────────────────────────────────────────────
  //    三个市场各一套价，毛利与低于成本的告警都在这
  /**
   * 价格卡此刻在编辑**哪一个字段**。
   *
   * <p>上一版是「更多价格」开关，展开后往每个规格下面追加两行 ——
   * 4 个规格就是 12 行，一屏装不下，翻着找一个数比原来的表格还累。
   * 改成切换之后，任何时候都只有「一行一个规格、一个数字」这一种形状。
   */
  const priceField = ref<PriceField>("price");

  /**
   * 可切换的字段。**只有一项时整个切换器不显示** ——
   * 那时它是个恒定的标签，占一行却什么都不让人选。
   * 标称重量只对生鲜有意义（按标称预扣、称重后多退少补）。
   */
  // 见文件底部的具名导出
  type PriceField = "price" | "cost" | "origin" | "gram" | "barcode" | "code" | "unit";

  /*
   * **条码/货号/单位默认不出现。**
   *
   * <p>它们是专业商家的东西：两百个品、有自己的 ERP、要对账。
   * 而社区店大半的货没有条码（生鲜、现做熟食、手工品），把三列摆在
   * 每个人的价格卡里，等于让所有人替少数人付注意力。
   *
   * <p>入口**由数据自动点亮**：这件货身上但凡有一个值，进来就是展开的 ——
   * 收起会让他以为自己填的货号丢了。此外记在本机（与「最近用过的类目」同一套）：
   * 用过一次的人多半一直要用，不必每次去点。
   */
  const EXTERNAL_KEY = "biz.skuExternalOn";
  const externalOn = ref(false);

  function rememberExternal(on: boolean) {
    externalOn.value = on;
    try {
      uni.setStorageSync(EXTERNAL_KEY, on);
    } catch {
      /* 存不下就算了：下次按默认走，不影响这次操作 */
    }
  }
  /** 这三个是文本不是数字：不挂货币符号，输入框也要宽一些 */
  const isTextField = computed(
    () => priceField.value === "barcode" || priceField.value === "code" || priceField.value === "unit",
  );

  const priceFields = computed<{ key: PriceField; labelKey: string }[]>(() => {
    const out: { key: PriceField; labelKey: string }[] = [
      { key: "price", labelKey: "goods.fieldPrice" },
      // 成本价紧挨着售价：填价的时候看的就是这两个数之差
      { key: "cost", labelKey: "goods.fieldCost" },
      { key: "origin", labelKey: "goods.fieldOrigin" },
    ];
    // 标称重量属于「按标称预扣」那条链，与生鲜段一起收着（见 flags.ts）
    if (SHOW_FRESH_FIELDS && isFresh.value) out.push({ key: "gram", labelKey: "goods.fieldGram" });
    return out;
  });

  /*
   * **条码/货号/单位不进价格切换器。**
   *
   * <p>塞进去之后那一行是「售价 成本价 划线价 条码 货号 单位」六项，在手机上挤成一坨，
   * 而且它们本来就不是价格 —— 把不同性质的东西并排放，商家得先分辨再选。
   * 现在它们自成一段（见模板里的 ext 卡），段内一行一个字段，与价格卡互不干扰。
   */
  const extFields = computed<{ key: "barcode" | "code" | "unit"; labelKey: string }[]>(() => [
    { key: "barcode", labelKey: "goods.fieldBarcode" },
    { key: "code", labelKey: "goods.fieldSkuCode" },
    { key: "unit", labelKey: "goods.fieldUnit" },
  ]);

  /**
   * 毛利。**填了成本才算** —— 没填时这一行整个不出现，
   * 而不是显示「毛利 100%」（那是在替商家宣布他零成本）。
   *
   * @returns null = 算不出来（缺售价或缺成本）
   */
  function marginOf(r: Row): { amount: string; rate: number } | null {
    const price = Number(r.priceMajor[market.value]);
    const cost = Number(r.costMajor);
    if (!(price > 0) || !(cost > 0)) return null;
    return { amount: (price - cost).toFixed(2), rate: Math.round(((price - cost) / price) * 100) };
  }

  /** 成本高于售价：这一单亏钱。**提示但不拦** —— 引流款本来就可能亏本卖 */
  function belowCost(r: Row): boolean {
    const price = Number(r.priceMajor[market.value]);
    const cost = Number(r.costMajor);
    return price > 0 && cost > 0 && cost >= price;
  }

  /** 多规格时把毛利率汇总成一句话：逐行看毛利在 8 行的表上没人看得过来 */
  const avgMargin = computed<number | null>(() => {
    const rates = rows.value.map(marginOf).filter((m): m is { amount: string; rate: number } => !!m);
    if (!rates.length) return null;
    return Math.round(rates.reduce((sum, m) => sum + m.rate, 0) / rates.length);
  });

  /**
   * 划线价填得不对。**必须严格高于售价** —— 否则 C 端渲染出来是个
   * 「涨价了」的折扣标，后端也会拒。只在两者都填了的时候判。
   */
  function badOrigin(r: Row): boolean {
    const o = Number(r.originMajor);
    const p = Number(r.priceMajor[market.value]);
    return r.originMajor.trim() !== "" && o > 0 && p > 0 && o <= p;
  }
  const rows = ref<Row[]>([
    {
      optionValues: [],
      priceMajor: emptyPrices(),
      stock: "0",
      originMajor: "",
      nominalGram: "",
      costMajor: "",
      barcode: "",
      merchantSkuCode: "",
      saleUnit: "",
    },
  ]);

  /**
   * 当前正在编辑哪个市场的价（B6）。
   * 与语言 tab 同一套交互：一列输入框 + 市场 tab，不给三列并排 ——
   * SKU 矩阵本来就可能有 8 行，再乘 3 列在手机上没法填。
   */
  const MARKET_CURRENCIES = MARKETS.map((m) => ({ id: m.id, currency: m.currency }));
  const market = ref<CurrencyCode>(MARKET_CURRENCIES[0]!.currency);
  /** 批量填充 */
  const bulk = ref({ price: "", stock: "", cost: "" });

  /**
   * 从本机恢复「条码/货号/单位」的展开状态。
   * <p>读存储这件事跟着开关走 —— 页面只要说「恢复一下」，不必知道键名。
   */
  function restoreExternal() {
    try {
      externalOn.value = uni.getStorageSync(EXTERNAL_KEY) === true;
    } catch { /* 读不到就按默认收着 */ }
  }

  return {
    priceField, externalOn, rememberExternal, restoreExternal, isTextField, priceFields, extFields,
    marginOf, belowCost, avgMargin, badOrigin, rows, MARKET_CURRENCIES, market, bulk,
  };
}
