<script setup lang="ts">
// 商品新建/编辑 + **多规格 SKU 矩阵**（B-11.3.1~3.7）。
//
// 矩阵怎么来：规格组（重量、包装…）× 各组选项 → 笛卡尔积 = SKU 列表。
// 三个决定：
//   1. **改规格时保留已填的价与库存**。按「选项组合」做 key 匹配 —— 加一个新包装规格
//      不该让另外 6 行的价格全部清零，那等于让店主重填一遍。
//   2. **复用原 skuNo**。历史订单行、购物车、库存流水都引用它；重新生成等于把它们
//      指向不存在的规格。
//   3. **批量设价/设库存**。8 个 SKU 一个个填是劝退的，多数店主其实只想「都设成 12 块」。
//
// 价格用主单位输入、最小单位存储 —— 店主输 12.5，存 1250。
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { CATEGORY_TYPE, MARKETS, TEMPLATE_TO_TYPE } from "@shared/utils/constants";
import { MAX_IMAGE_BYTES, pickImages } from "@shared/ports/media";
import { toMajor, toMinor } from "@shared/utils/money";
import type { Category, CategoryType, CurrencyCode, MarketId, I18nText, SpecTemplate } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const TYPES = Object.values(CATEGORY_TYPE) as CategoryType[];

/**
 * 多语言 / 多市场的**展示开关**（2026-08-20）。
 *
 * <p>当前只做中文单市场，界面上那两排页签（中/EN/ع、CNY/AED/USD）对店主是纯噪音：
 * 他九成时间只填中文、只卖 CN，却要在每次建品时看见并绕过它们。
 *
 * <p><b>关的是展示，不是能力</b> —— 三语与按市场分别定价（B6）是已经实现并有数据落地的
 * 功能，删掉将来要重写，而重写一次的代价远大于留一个 false。
 * 关掉时的行为：文案只填中文那一格，价只填 CN 市场，与打开时填了中文/CN 的结果一模一样。
 */
const MULTI_LANG_UI = false;
const MULTI_MARKET_UI = false;

interface Row {
  skuNo?: string;
  optionValues: string[];
  /** 按市场分别填的价（主单位字符串）。未填 = 不在该市场售卖 */
  priceMajor: Record<CurrencyCode, string>;
  stock: string;
}

/** 空价格表：三个市场各一格 */
function emptyPrices(): Record<CurrencyCode, string> {
  return { CNY: "", USD: "", AED: "" };
}

const goodsNo = ref("");
/** 商品主图。拍一张就有，替掉 emoji 占位（E9） */
const cover = ref("");
const uploading = ref(false);

/**
 * 商品文案是**三语**（B-4.9）。三语是一期范围，但此前商品只有一份文案 ——
 * 中文抄进三语，切到英文看到的还是中文。
 *
 * 交互取舍：**不给三个输入框并排**，而是一个框 + 语言 tab。
 * 并排三个会把表单撑长两倍，而店主九成时间只填中文；
 * 需要翻译时再切过去，切换成本远低于永远多两个框。
 */
const LANGS = [
  { id: "zh-CN" as const, key: "goods.langZh" },
  { id: "en" as const, key: "goods.langEn" },
  { id: "ar" as const, key: "goods.langAr" },
];
const lang = ref<"zh-CN" | "en" | "ar">("zh-CN");
const title = ref<I18nText>({ "zh-CN": "", en: "", ar: "" });
const subtitle = ref<I18nText>({ "zh-CN": "", en: "", ar: "" });

/** 哪些语言还没填 —— 给个明确提示，而不是让人逐个点过去看 */
const untranslated = computed(() =>
  LANGS.filter((l) => l.id !== "zh-CN" && !title.value[l.id].trim()).map((l) => l.key),
);
const type = ref<CategoryType>(CATEGORY_TYPE.NORMAL as CategoryType);

/**
 * 类目（三级树）。
 *
 * ⚠️ 与上面的 `type`（五品类）**不是一回事**，页面上要分开两个控件：
 * `type` 决定履约与合规（生鲜要截单时间、服务不发货），平台硬编码；
 * 类目决定归类与经营准入，运营可维护。合成一个控件的话，
 * 商家改一次类目会连带改掉履约方式 —— 而他只是想把货归得更准一点。
 */
const categoryTree = ref<Category[]>([]);
const categoryNo = ref("");
/** **已选**的三级路径，[一级, 二级, 三级]；只走到二级也允许。面包屑与提交都取它 */
const catPath = ref<Category[]>([]);
/**
 * **正在浏览**的层级 —— 与 `catPath` 分开的两个状态。
 *
 * <p>此前两者共用 `catPath`，于是**选定之后就改不了了**：选到叶子后
 * `catOptions` 取的是叶子的 children（空），再打开弹层只有一句「已是最末级」
 * 和一个返回按钮 —— 商家想换个类目，得先连按返回往上爬。
 * 识别自动填了三级路径时更糟：他没点过任何一级，却要按两次返回才看得到选项。
 *
 * <p>拆开之后：打开弹层从**已选项的同级**开始浏览（`catPath` 去掉最后一级），
 * 也就是「你现在选的是这个，它旁边还有这些」—— 这正是要改类目的人想看的那一屏。
 */
const browse = ref<Category[]>([]);
const showCategory = ref(false);

function openCategory() {
  browse.value = catPath.value.slice(0, -1);
  showCategory.value = true;
}

/** 面包屑：「食品生鲜 / 蔬菜 / 叶菜」。没选时给占位，不留空白 */
const categoryLabel = computed(() =>
  catPath.value.length ? catPath.value.map((c) => c.name).join(" / ") : "",
);

/** 当前层可选项：还没选就是一级，选了就是最后一级的子节点 */
const catOptions = computed<Category[]>(() =>
  browse.value.length ? (browse.value[browse.value.length - 1]?.children ?? []) : categoryTree.value,
);

function pickCategory(c: Category) {
  const next = [...browse.value, c];
  browse.value = next;
  // 每一级都是合法的选择（只走到二级也允许），所以每点一下都更新已选
  catPath.value = next;
  categoryNo.value = c.categoryNo;
  /*
   * **类目带出品类。**
   *
   * 类目在库里就带着 `template`（STANDARD/FRESH/SERVICE/VOUCHER），它与品类
   * 是同一件事的两套码。此前端上拿不到这个字段，于是商家要把同一件事填两遍 ——
   * 而两者**可以互相矛盾**：选「生鲜」品类配「纸品清洁」类目，没有一处会拦，
   * 直到下单时才因为履约方式不对而出问题（生鲜要截单、服务不发货）。
   *
   * 仍然允许他改：类目树是运营维护的，可能有归类不准的时候，
   * 而品类决定的是履约，最终解释权该在开店的人手里。改了就提示两者不一致。
   */
  const inferred = c.template ? TEMPLATE_TO_TYPE[c.template] : undefined;
  if (inferred && inferred !== type.value) {
    type.value = inferred as CategoryType;
    void loadTemplates();
  }
  // 叶子节点即选定；还有下级就留在弹层里继续选
  if (!c.children?.length) showCategory.value = false;
}

/**
 * 品类与所选类目是否对不上。**只提示不阻断** —— 见 pickCategory 的说明。
 * 类目没选时不提示：那时没有可比的对象，提示只会变成噪音。
 */
const typeMismatch = computed(() => {
  const leaf = catPath.value[catPath.value.length - 1];
  const inferred = leaf?.template ? TEMPLATE_TO_TYPE[leaf.template] : undefined;
  return !!inferred && inferred !== type.value;
});

/** 回退一级。整棵重选比逐级点返回更烦 —— 商家改类目通常只是改最后一级 */
/** 往上一级。**只动浏览位置，不动已选** —— 翻着看不等于改了选择 */
function popCategory() {
  browse.value = browse.value.slice(0, -1);
}

/** 按 categoryNo 还原选择路径（回显已有商品时用） */
function findPath(nodes: Category[], target: string, trail: Category[] = []): Category[] {
  for (const n of nodes) {
    const next = [...trail, n];
    if (n.categoryNo === target) return next;
    const hit = findPath(n.children ?? [], target, next);
    if (hit.length) return hit;
  }
  return [];
}
/** 规格组。空 = 单规格商品 */
const groups = ref<{ name: string; options: string[]; codes?: (string | undefined)[]; templateNo?: string }[]>([]);
/** 可用模板：平台按类目预置 + 本商家存的常用 */
const templates = ref<SpecTemplate[]>([]);
const showTemplates = ref(false);
const rows = ref<Row[]>([{ optionValues: [], priceMajor: emptyPrices(), stock: "0" }]);

/**
 * 当前正在编辑哪个市场的价（B6）。
 * 与语言 tab 同一套交互：一列输入框 + 市场 tab，不给三列并排 ——
 * SKU 矩阵本来就可能有 8 行，再乘 3 列在手机上没法填。
 */
const MARKET_CURRENCIES = MARKETS.map((m) => ({ id: m.id, currency: m.currency }));
const market = ref<CurrencyCode>(MARKET_CURRENCIES[0]!.currency);
const saving = ref(false);
/** 批量填充 */
const bulk = ref({ price: "", stock: "" });

const isEdit = computed(() => !!goodsNo.value);
const multi = computed(() => groups.value.length > 0);
/**
 * 还差什么才能保存。**把判据说出来，而不是只把按钮灰掉。**
 *
 * <p>此前按钮灰着的时候一个字都没有，而判据有两条（中文名 + 每行至少一个价）——
 * 多规格商品有 8 行时，商家得挨行找是哪一行没填价。灰按钮只说明「不行」，
 * 不说明「差什么」，而后者才是他下一步要做的事。
 *
 * <p>只有中文必填 —— 其余语言留空回落中文（不做机翻）。
 * 价格只要求**至少一个市场**填了，未填的市场就是不在那边卖。
 */
const missing = computed<string[]>(() => {
  const out: string[] = [];
  if (!title.value["zh-CN"].trim()) out.push(t("goods.name"));
  const noPrice = rows.value.filter(
    (r) => !Object.values(r.priceMajor).some((v) => Number(v) > 0),
  );
  if (noPrice.length) {
    // 单规格就说「价格」；多规格点名是哪几个规格没填，省得他逐行找
    out.push(
      multi.value && noPrice.length < rows.value.length
        ? `${t("goods.price")}（${noPrice.map((r) => r.optionValues.join("/")).join("、")}）`
        : t("goods.price"),
    );
  }
  return out;
});
const canSave = computed(() => missing.value.length === 0);
/** 展示价 = 最低 SKU 价，与列表页「¥12 起」同口径 */
/*
 * 价格字段叫什么，跟着**资金路径**走而不是门店的经营模式 ——
 * 与积分能力同一根轴：**责任跟着钱走**。
 *
 * 归集（钱进平台账户）：平台是销售主体，最终售价平台定，商家填的是「期望收购价」。
 * 直连：他自己就是销售主体，那就是售价。
 *
 * 仍然让他填 —— 不填的等于让他闭眼供货。填的值是议价的起点，不是最终价。
 */
const aggregated = computed(() => merchant.profile?.fundsMode === "AGGREGATED");
const priceLabel = computed(() =>
  aggregated.value ? "goods.priceAggregated" : "goods.price",
);

const fromPrice = computed(() => {
  const prices = rows.value.map((r) => Number(r.priceMajor[market.value])).filter((n) => n > 0);
  return prices.length ? Math.min(...prices).toFixed(2) : "—";
});

/** 哪些市场一个价都没填 —— 明确告诉商家这些市场不会售卖 */
const unpricedMarkets = computed(() =>
  MARKET_CURRENCIES.filter(
    (m) => !rows.value.some((r) => Number(r.priceMajor[m.currency]) > 0),
  ).map((m) => m.currency),
);

/**
 * 拍照建品（B-11.3.7）。
 *
 * 价值不在识别多准，在于把建品从「填表」变成「拍照」：
 *   1. 拍一张当主图 —— 这个立刻有用
 *   2. 识别只用来**猜一个标题**，可编辑，猜错无所谓
 *   3. **绝不自动上架** —— 识别错了价格也错，货会以错价卖出去
 *
 * 端差异都在 ports/media 与服务端：小程序不能跑本地模型，所以识别统一在服务端。
 */
async function shoot(source: "camera" | "album") {
  if (uploading.value) return;
  let picked;
  try {
    picked = await pickImages(1, [source]);
  } catch {
    return; // 用户取消，不是错误
  }
  const img = picked[0];
  if (!img) return;

  /*
   * 先在端上挡一道。`pickImages` 一直把 `size` 带回来，却从来没人比过 ——
   * 于是一张超限的图要走完整个上传才在服务端被拒，商家等的那几秒是白等的，
   * 流量也是白花的（他多半正用着移动网络）。与后端 MAX_BYTES 同一个数。
   */
  if (img.size > MAX_IMAGE_BYTES) {
    uni.showToast({ title: t("goods.imageTooLarge"), icon: "none" });
    return;
  }

  uploading.value = true;
  try {
    const { url } = await api.mUploadImage(img.tempPath);
    cover.value = url;

    // 识别失败不该拖累「拍照设主图」——主图已经拿到了，识别只是锦上添花
    const guess = await api.mRecognizeGoods(url).catch(() => null);
    if (!guess) return;

    // 置信度低就不预填，只提示 —— 塞一个错标题进去，店主还得先删掉
    if (guess.confidence < 0.6) {
      uni.showToast({ title: t("goods.guessLow"), icon: "none" });
      return;
    }
    /*
     * **逐个字段判空后再填，不整体覆盖**：店主可能先手打了标题再去拍照，
     * 那时副标题与类目仍是空的 —— 整体覆盖会抹掉他写的，整体跳过又浪费了识别结果。
     *
     * 识别结果只写进**中文**那一格：识别本身是中文的，塞进英文格是假装翻译过。
     */
    let filled = false;
    if (!title.value["zh-CN"].trim() && guess.title) {
      title.value = { ...title.value, "zh-CN": guess.title };
      lang.value = "zh-CN";
      type.value = guess.type;
      await loadTemplates();
      filled = true;
    }
    if (!subtitle.value["zh-CN"].trim() && guess.subtitle) {
      subtitle.value = { ...subtitle.value, "zh-CN": guess.subtitle };
      filled = true;
    }
    /*
     * 类目要连**面包屑**一起还原，不能只塞编号：只设 categoryNo 的话，
     * 页面上那一栏仍显示「选择类目（选填）」，而提交时却带着一个类目 ——
     * 商家看到的和将要保存的不是一回事。
     */
    if (!categoryNo.value && guess.categoryNo) {
      const path = findPath(categoryTree.value, guess.categoryNo);
      if (path.length) {
        catPath.value = path;
        categoryNo.value = guess.categoryNo;
        filled = true;
      }
    }
    if (filled) {
      uni.showToast({ title: t("goods.guessed"), icon: "none" });
    }
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    uploading.value = false;
  }
}

function keyOf(values: string[]): string {
  return values.join("");
}

/** 规格组变化后重建矩阵，按选项组合保留已填的价与库存 */
function rebuild() {
  if (!groups.value.length) {
    rows.value = [
      {
        skuNo: rows.value[0]?.skuNo,
        optionValues: [],
        priceMajor: rows.value[0]?.priceMajor ?? emptyPrices(),
        stock: rows.value[0]?.stock ?? "0",
      },
    ];
    return;
  }
  const before = rows.value;
  const prev = new Map(before.map((r) => [keyOf(r.optionValues), r]));
  let combos: string[][] = [[]];
  for (const g of groups.value) {
    const opts = g.options.map((o) => o.trim()).filter(Boolean);
    if (!opts.length) continue;
    combos = combos.flatMap((c) => opts.map((o) => [...c, o]));
  }

  /**
   * 新组合**继承哪一行的价与库存**。
   *
   * <p>只按精确键匹配是不够的 —— `keyOf` 是把选项值拼起来，所以**增删规格组会让
   * 每一个键都变**：单规格的键是 `""`，加一组「尺寸」之后变成 `"S"`/`"M"`，
   * 一个都对不上，于是店主刚填的价与库存**全部清零**。
   * 而页面底部一直写着「改规格时已填的价与库存会按选项组合保留」——
   * 文案与行为对不上，且清零不报错，他要滚回去才发现。
   *
   * <p>三级回落，从最精确到最合理：
   *   1. 精确命中（同组内增删选项，原行原样保留）
   *   2. **前缀命中**（在已有规格上再加一组：`["S"]` → `["S","红"]` 继承 `["S"]`）
   *   3. **单行回落**（单规格 → 多规格：只有一行可继承，那就是它）——
   *      店主说「这个商品现在有 S 和 M」时，他的意思显然是两个都从刚填的价起步
   */
  const inherit = (values: string[]): Row | undefined => {
    const exact = prev.get(keyOf(values));
    if (exact) return exact;
    const prefix = before.find(
      (r) => r.optionValues.length > 0
        && r.optionValues.length < values.length
        && r.optionValues.every((v, i) => v === values[i]),
    );
    if (prefix) return prefix;
    return before.length === 1 ? before[0] : undefined;
  };

  rows.value = combos.map((values) => {
    const old = inherit(values);
    return {
      // **skuNo 只跟精确命中走** —— 前缀/单行回落继承的是「价与库存」这类可重填的值，
      // 而 skuNo 是身份：两行拿同一个编号，历史订单与库存流水就指向了错的规格
      skuNo: prev.get(keyOf(values))?.skuNo,
      optionValues: values,
      priceMajor: old?.priceMajor ?? emptyPrices(),
      stock: old?.stock ?? "0",
    };
  });
}

/** 套用模板：一次点选替代逐个手输，同时把 code 带进来（这是二期能做规格聚合的前提） */
function applyTemplate(tpl: SpecTemplate) {
  // 已有同名规格组就替换，避免点两次出来两个「重量」
  const exist = groups.value.findIndex((g) => g.name === tpl.name);
  const row = {
    name: tpl.name,
    options: tpl.options.map((o) => o.label),
    codes: tpl.options.map((o) => o.code),
    templateNo: tpl.templateNo,
  };
  if (exist >= 0) groups.value[exist] = row;
  else if (groups.value.length >= 3) {
    uni.showToast({ title: t("goods.groupLimit"), icon: "none" });
    return;
  } else groups.value.push(row);
  showTemplates.value = false;
  rebuild();
}

/** 把当前规格组存为「我的常用」，下次建品直接套 */
async function saveAsTemplate(gi: number) {
  const g = groups.value[gi];
  if (!g?.name.trim()) {
    uni.showToast({ title: t("goods.templateNeedName"), icon: "none" });
    return;
  }
  try {
    await api.mSaveSpecTemplate({ name: g.name.trim(), options: g.options });
    templates.value = await api.mSpecTemplates(type.value);
    uni.showToast({ title: t("goods.templateSaved"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

async function loadCategories() {
  // 取不到不该挡住整个编辑页：类目是选填的，拿不到就退化成「不归类」
  categoryTree.value = await api.mCategoryTree().catch(() => []);
}

async function loadTemplates() {
  templates.value = await api.mSpecTemplates(type.value).catch(() => []);
}

function addGroup() {
  // 三个维度已经是 3×3×3=27 个 SKU，手机上再多就没法维护了
  if (groups.value.length >= 3) {
    uni.showToast({ title: t("goods.groupLimit"), icon: "none" });
    return;
  }
  groups.value.push({ name: "", options: [""] });
}

function removeGroup(i: number) {
  groups.value.splice(i, 1);
  rebuild();
}

function addOption(gi: number) {
  const g = groups.value[gi]!;
  g.options.push("");
  // 手加的选项没有 code：它不是模板里的值，不该假装能参与聚合
  if (g.codes) g.codes.push(undefined);
}

/** 手改了模板带来的选项文字 → 该位置的 code 作废（值已经不是模板那个值了） */
function onOptionEdited(gi: number, oi: number) {
  const g = groups.value[gi]!;
  const tpl = templates.value.find((x) => x.templateNo === g.templateNo);
  const original = tpl?.options[oi]?.label;
  if (g.codes && original !== undefined && g.options[oi] !== original) g.codes[oi] = undefined;
  rebuild();
}

function removeOption(gi: number, oi: number) {
  const g = groups.value[gi]!;
  g.options.splice(oi, 1);
  g.codes?.splice(oi, 1);
  rebuild();
}

function applyBulk() {
  rows.value = rows.value.map((r) => ({
    ...r,
    // 批量只作用在**当前市场**：避免把美元价误批到人民币上
    priceMajor: bulk.value.price
      ? { ...r.priceMajor, [market.value]: bulk.value.price }
      : r.priceMajor,
    stock: bulk.value.stock || r.stock,
  }));
  uni.showToast({ title: t("goods.bulkDone"), icon: "none" });
}

onLoad(async (q) => {
  await Promise.all([loadTemplates(), loadCategories()]);
  if (!q?.goodsNo) return;
  goodsNo.value = q.goodsNo;
  const g = await api.mGoodsDetail(q.goodsNo);
  /*
   * **主图要回显**。保存时无条件带 `cover: cover.value`，而这里不回填的话
   * 它是空串 —— 于是「编辑一次商品，主图就没了」，且页面上那个 📷 占位
   * 看起来就像这个商品本来就没图，谁也不会把两件事联系起来。
   */
  cover.value = g.cover ?? "";
  /*
   * **三语要整份回显**。保存时发的是整个 `title` 三格，
   * 只回填当前那一格的话，用中文改一次就把英文与阿语清空了 ——
   * 而这个故障不报错：C 端缺译文时回落中文，看起来一切正常。
   *
   * 后端给不出 `titleI18n` 的老数据（或 C 端拍平的那份）才回落到
   * 「只填当前语言」，那是能拿到的全部信息。
   */
  title.value = g.titleI18n
    ? { ...title.value, ...g.titleI18n }
    : { ...title.value, [lang.value]: g.title };
  subtitle.value = g.subtitleI18n
    ? { ...subtitle.value, ...g.subtitleI18n }
    : { ...subtitle.value, [lang.value]: g.subtitle };
  type.value = g.type;
  categoryNo.value = g.categoryNo ?? "";
  catPath.value = categoryNo.value ? findPath(categoryTree.value, categoryNo.value) : [];
  groups.value = g.specGroups.map((sg) => ({
    name: sg.name,
    options: [...sg.options],
    codes: sg.optionCodes ? [...sg.optionCodes] : undefined,
    templateNo: sg.templateNo,
  }));
  rows.value = g.skus.map((k) => ({
    skuNo: k.skuNo,
    optionValues: [...k.optionValues],
    // 详情按当前市场拍平，只能回填当前市场那一格（同三语的局限）
    priceMajor: { ...emptyPrices(), [market.value]: toMajor(k.price) },
    stock: String(k.stock),
  }));
});

async function save() {
  if (!canSave.value || saving.value) return;
  saving.value = true;
  try {
    await api.mSaveGoods({
      goodsNo: goodsNo.value || undefined,
      title: title.value,
      subtitle: subtitle.value,
      type: type.value,
      // 空串要转成 undefined：后端拿到空串会当成「归到一个叫空的类目」，而不是「没归类」
      categoryNo: categoryNo.value || undefined,
      // 封面必须带上：上传完只存在 ref 里的话，店主看着图在、保存后 C 端却是空白
      cover: cover.value,
      specGroups: groups.value
        .filter((g) => g.name.trim() && g.options.some((o) => o.trim()))
        .map((g) => ({
          name: g.name.trim(),
          options: g.options.map((o) => o.trim()).filter(Boolean),
          optionCodes: g.codes,
          templateNo: g.templateNo,
        })),
      skus: rows.value.map((r) => {
        /*
         * **键是市场码，值取自币种列**。页内那套输入框按币种索引（页签就是币种），
         * 但 `priceByMarket` 落到 `prd_sku.market` 上 —— 发币种码等于往市场列写
         * 一个不存在的市场：多一行 `market='CNY'` 的死数据，而 AED/USD 填的价
         * 在 AE/US 两个市场一分钱也卖不出去。两套码一一对应，所以错了不报任何错。
         */
        const byMarket = MARKET_CURRENCIES.reduce<Partial<Record<MarketId, number>>>(
          (acc, m) => {
            if (Number(r.priceMajor[m.currency]) > 0) {
              acc[m.id] = toMinor(r.priceMajor[m.currency]);
            }
            return acc;
          },
          {},
        );
        const homeMarket = MARKET_CURRENCIES.find((m) => m.currency === market.value)!.id;
        return {
          skuNo: r.skuNo,
          optionValues: r.optionValues,
          // price 保留当前市场值，兼容按单市场读取的地方
          price: byMarket[homeMarket] ?? Object.values(byMarket)[0] ?? 0,
          priceByMarket: byMarket,
          stock: Number(r.stock) || 0,
        };
      }),
    });
    uni.showToast({ title: t("common.saved"), icon: "none" });
    setTimeout(() => uni.navigateBack(), 600);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <!-- 建/改商品与价格属于 `biz:goods`；商品列表的入口已判过，这里给深链兜底 -->
  <sh-scaffold
    :title-key="isEdit ? 'goods.editTitle' : 'goods.createTitle'"
    :denied="!merchant.can('biz:goods')"
  >
    <text class="sh-h1">{{ isEdit ? $t("goods.editTitle") : $t("goods.createTitle") }}</text>

    <view class="sh-card mt">
      <!-- 拍照建品：主图 + 猜标题。放在最前，因为它是最快的建品入口 -->
      <view class="field">
        <text class="field__label">{{ $t("goods.cover") }}</text>
        <view class="shoot">
          <!-- 这里原先只画 <image>：老商品的封面是 emoji，于是编辑页显示一个空灰框，
               而列表里明明有图 —— 看着像图丢了。sh-cover 按值分流，两种都画得出来。 -->
          <view class="shoot__preview">
            <sh-cover v-if="cover" class="shoot__img" :src="cover"></sh-cover>
            <text v-else class="shoot__ph">📷</text>
          </view>
          <view class="shoot__ops">
            <text class="mini" @tap="shoot('camera')">
              {{ uploading ? $t("goods.uploading") : $t("goods.shoot") }}
            </text>
            <text class="mini" @tap="shoot('album')">{{ $t("goods.fromAlbum") }}</text>
          </view>
        </view>
        <text class="sh-muted hint">{{ $t("goods.shootHint") }}</text>
      </view>

      <!-- 三语：一个框 + 语言 tab，不给三个框并排 -->
      <view class="field">
        <view class="field__head">
          <text class="field__label">{{ $t("goods.name") }}</text>
          <view v-if="MULTI_LANG_UI" class="langs">
            <text
              v-for="l in LANGS"
              :key="l.id"
              class="lang"
              :class="{ 'is-on': lang === l.id, 'is-empty': l.id !== 'zh-CN' && !title[l.id].trim() }"
              @tap="lang = l.id"
            >
              {{ $t(l.key) }}
            </text>
          </view>
        </view>
        <input v-model="title[lang]" class="field__input" />
      </view>
      <view class="field">
        <text class="field__label">{{ $t("goods.subtitle") }}</text>
        <input v-model="subtitle[lang]" class="field__input" />
      </view>
      <text v-if="MULTI_LANG_UI && untranslated.length" class="sh-muted hint">
        {{ $t("goods.untranslated", { s: untranslated.map((k) => $t(k)).join("、") }) }}
      </text>
      <view class="field">
        <text class="field__label">{{ $t("goods.type") }}</text>
        <view class="chips">
          <text
            v-for="ty in TYPES"
            :key="ty"
            class="sh-chip"
            :class="{ 'sh-chip--primary': type === ty }"
            @tap="type = ty"
          >
            {{ $t(`goods.categoryType.${ty}`) }}
          </text>
        </view>
      </view>

      <!-- 类目：与上面的「形态」分开两个控件（见 script 里 categoryTree 的注释） -->
      <view class="field">
        <!-- 品类与类目对不上：不阻断，但要说出来 —— 品类决定履约，错了要到下单才显现 -->
        <text v-if="typeMismatch" class="missing">{{ $t("goods.typeMismatch") }}</text>
        <text class="field__label">{{ $t("goods.category") }}</text>
        <view class="cat-pick" @tap="openCategory">
          <text v-if="categoryLabel" class="cat-pick__val">{{ categoryLabel }}</text>
          <text v-else class="cat-pick__ph">{{ $t("goods.categoryPh") }}</text>
          <text class="cat-pick__arrow">›</text>
        </view>
        <text class="sh-muted hint">{{ $t("goods.categoryTip") }}</text>
      </view>
    </view>

    <!-- 类目选择弹层：一次只显示一层，选到叶子自动收起 -->
    <view v-if="showCategory" class="cat-mask" @tap="showCategory = false">
      <view class="cat-sheet" @tap.stop>
        <view class="cat-sheet__bar">
          <text v-if="browse.length" class="cat-sheet__back" @tap="popCategory">‹ {{ $t("common.back") }}</text>
          <text class="cat-sheet__title">{{ categoryLabel || $t("goods.category") }}</text>
          <text class="cat-sheet__close" @tap="showCategory = false">×</text>
        </view>
        <view v-if="!catOptions.length" class="cat-sheet__empty">
          <text class="sh-muted">{{ $t("goods.categoryLeaf") }}</text>
        </view>
        <view
          v-for="c in catOptions"
          :key="c.categoryNo"
          class="cat-sheet__row"
          @tap="pickCategory(c)"
        >
          <text>{{ c.name }}</text>
          <text v-if="c.children?.length" class="cat-pick__arrow">›</text>
        </view>
      </view>
    </view>

    <!-- 规格组 -->
    <view class="sh-card mt">
      <view class="sec">
        <text class="sh-h2">{{ $t("goods.specs") }}</text>
        <view class="sec__ops">
          <text v-if="templates.length" class="link" @tap="showTemplates = !showTemplates">
            {{ $t("goods.useTemplate") }}
          </text>
          <text class="link" @tap="addGroup">{{ $t("goods.addGroup") }}</text>
        </view>
      </view>
      <text class="sh-muted hint">{{ $t("goods.specHint") }}</text>

      <!-- 模板：点一下替代逐个手输。平台模板带 code，商家自存的只有文字 -->
      <view v-if="showTemplates" class="tpls">
        <text class="sh-muted tpls__hint">{{ $t("goods.templateHint") }}</text>
        <view v-for="tpl in templates" :key="tpl.templateNo" class="tpl" @tap="applyTemplate(tpl)">
          <view class="tpl__head">
            <text class="tpl__name">{{ tpl.name }}</text>
            <text class="sh-chip" :class="tpl.scope === 'PLATFORM' ? 'sh-chip--primary' : ''">
              {{ tpl.scope === "PLATFORM" ? $t("goods.tplPlatform") : $t("goods.tplMine") }}
            </text>
          </view>
          <text class="sh-muted">{{ tpl.options.map((o) => o.label).join(" · ") }}</text>
        </view>
      </view>

      <view v-for="(g, gi) in groups" :key="gi" class="group">
        <view class="group__head">
          <input
            v-model="g.name"
            class="field__input flex1"
            :placeholder="$t('goods.groupNamePh')"
            @blur="rebuild"
          />
          <text class="del" @tap="saveAsTemplate(gi)">☆</text>
          <text class="del" @tap="removeGroup(gi)">✕</text>
        </view>
        <view class="opts">
          <view v-for="(o, oi) in g.options" :key="oi" class="opt">
            <input
              v-model="g.options[oi]"
              class="opt__input"
              :placeholder="$t('goods.optionPh')"
              @blur="onOptionEdited(gi, oi)"
            />
            <text v-if="g.options.length > 1" class="del small" @tap="removeOption(gi, oi)">✕</text>
          </view>
          <text class="link" @tap="addOption(gi)">{{ $t("goods.addOption") }}</text>
        </view>
      </view>
    </view>

    <!-- SKU 矩阵 -->
    <view class="sh-card mt">
      <view class="sec">
        <text class="sh-h2">{{ $t("goods.skuMatrix") }}</text>
        <view v-if="MULTI_MARKET_UI" class="langs">
          <text
            v-for="m in MARKET_CURRENCIES"
            :key="m.currency"
            class="lang"
            :class="{
              'is-on': market === m.currency,
              'is-empty': unpricedMarkets.includes(m.currency),
            }"
            @tap="market = m.currency"
          >
            {{ m.currency }}
          </text>
        </view>
      </view>
      <!-- 「按市场分别定价」的说明只在多市场打开时才有意义 -->
      <text v-if="MULTI_MARKET_UI" class="sh-muted hint">{{ $t("goods.marketPriceHint") }}</text>
      <!-- 归集路径必须说清「这不是最终售价」—— 只改标签不解释，
           商家会以为平台擅自改了他的价 -->
      <text v-if="aggregated" class="sh-muted hint">
        {{ $t("goods.priceAggregatedHint") }}
      </text>

      <view v-if="rows.length > 1" class="bulk">
        <input
          v-model="bulk.price"
          class="bulk__input sh-num"
          type="digit"
          :placeholder="$t(aggregated ? 'goods.priceAggregated' : 'goods.bulkPrice')"
        />
        <input
          v-model="bulk.stock"
          class="bulk__input sh-num"
          type="number"
          :placeholder="$t('goods.bulkStock')"
        />
        <text class="link" @tap="applyBulk">{{ $t("goods.applyBulk") }}</text>
      </view>

      <view v-for="(r, i) in rows" :key="i" class="row">
        <text class="row__spec">
          {{ multi ? r.optionValues.join(" · ") : $t("goods.singleSpec") }}
        </text>
        <input
          v-model="r.priceMajor[market]"
          class="row__input sh-num"
          type="digit"
          :placeholder="$t(priceLabel)"
        />
        <input
          v-model="r.stock"
          class="row__input sh-num"
          type="number"
          :placeholder="$t('goods.stock')"
        />
      </view>

      <view class="from">
        <!-- 币种后缀只在多市场下有意义：单市场时「（CNY）」是在回答没人问的问题 -->
        <text class="sh-muted">
          {{ $t("goods.fromPrice") }}<text v-if="MULTI_MARKET_UI">（{{ market }}）</text>
        </text>
        <text class="sh-num from__v">{{ fromPrice }}</text>
      </view>
      <text v-if="MULTI_MARKET_UI && unpricedMarkets.length" class="sh-muted hint">
        {{ $t("goods.unpriced", { s: unpricedMarkets.join("、") }) }}
      </text>
    </view>

    <!-- 差什么就说什么 —— 灰按钮只说明「不行」，不说明「下一步做什么」 -->
    <text v-if="missing.length" class="missing">
      {{ $t("goods.missing", { s: missing.join("、") }) }}
    </text>
    <view class="sh-btn save" :class="{ 'sh-btn--muted': !canSave }" @tap="save">
      {{ $t("common.save") }}
    </view>
    <text class="tip">{{ $t("goods.saveTip") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.mt {
  margin-top: 16rpx;
}
.field__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12rpx;
}
.langs {
  display: flex;
  gap: 8rpx;
}
.lang {
  padding: 8rpx 18rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  color: var(--sh-sub);
  font-size: 24rpx;
}
.lang.is-on {
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
  font-weight: 600;
}
/* 未填的语言标出来 —— 否则要逐个点过去才知道漏了哪门 */
.lang.is-empty::after {
  content: " ·";
  color: var(--sh-warning);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}
.chips .sh-chip {
  font-size: 24rpx;
  padding: 14rpx 24rpx;
}
.sec {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.shoot {
  display: flex;
  align-items: center;
  gap: 20rpx;
}
.shoot__preview {
  width: 140rpx;
  height: 140rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}
.shoot__img {
  width: 140rpx;
  height: 140rpx;
  /* emoji 封面按这个字号排；真图时 sh-cover 内部撑满，字号用不上 */
  font-size: 72rpx;
}
.shoot__ph {
  font-size: 48rpx;
}
.shoot__ops {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}
.mini {
  padding: 16rpx 28rpx;
  border-radius: 16rpx;
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
  font-size: 24rpx;
  font-weight: 600;
  text-align: center;
}
.sec__ops {
  display: flex;
  gap: 24rpx;
}
.tpls {
  margin-top: 20rpx;
  padding: 20rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
}
.tpls__hint {
  display: block;
  margin-bottom: 16rpx;
  line-height: 1.6;
}
.tpl {
  padding: 18rpx 20rpx;
  border-radius: 24rpx;
  background: var(--sh-surface);
  margin-bottom: 12rpx;
}
.tpl__head {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-bottom: 8rpx;
}
.tpl__name {
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.hint {
  display: block;
  margin-top: 10rpx;
  line-height: 1.6;
}
/* 差什么：**不是报错**（他还没做错任何事），所以用警示色不用危险色 */
.missing {
  display: block;
  margin: 16rpx 8rpx 0;
  font-size: 24rpx;
  color: var(--sh-warning);
  line-height: 1.5;
}
.link {
  font-size: 24rpx;
  font-weight: 600;
  color: var(--sh-primary-text);
}
.group {
  margin-top: 20rpx;
}
.group__head {
  display: flex;
  align-items: center;
  gap: 16rpx;
}
.flex1 {
  flex: 1;
}
.del {
  width: 56rpx;
  text-align: center;
  color: var(--sh-sub);
  font-size: 28rpx;
}
.del.small {
  width: 40rpx;
  font-size: 24rpx;
}
.opts {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12rpx;
  margin-top: 16rpx;
}
.opt {
  display: flex;
  align-items: center;
  background: var(--sh-faint);
  border-radius: 16rpx;
  padding: 0 8rpx 0 16rpx;
}
.opt__input {
  width: 150rpx;
  height: 64rpx;
  font-size: 24rpx;
  color: var(--sh-ink);
}
.bulk {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin: 20rpx 0;
}
.bulk__input {
  flex: 1;
  height: 72rpx;
  padding: 0 20rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  font-size: 24rpx;
  color: var(--sh-ink);
}
.row {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 16rpx;
}
.row__spec {
  flex: 1;
  min-width: 0;
  font-size: 24rpx;
  color: var(--sh-ink);
}
.row__input {
  width: 150rpx;
  height: 72rpx;
  padding: 0 20rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  font-size: 24rpx;
  color: var(--sh-ink);
}
.from {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-top: 20rpx;
}
.from__v {
  font-size: 34rpx;
  font-weight: 600;
  color: var(--sh-primary-text);
}
.cat-pick {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20rpx 24rpx;
  border: 2rpx solid var(--sh-line);
  /* 与本页其它表单控件同档（16rpx）。此前写 var(--sh-radius) —— 该变量不存在
     且没给兜底，圆角实际是 0，在一片圆角控件里方棱棱地突兀 */
  border-radius: 16rpx;
}
.cat-pick__ph {
  color: var(--sh-sub);
}
.cat-pick__arrow {
  color: var(--sh-sub);
}
.cat-mask {
  position: fixed;
  inset: 0;
  background: var(--sh-scrim);
  z-index: 20;
}
.cat-sheet {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  max-height: 70vh;
  overflow-y: auto;
  background: var(--sh-surface);
  /* 底部弹层用 xl 档（44rpx），与 sh-theme-sheet 一致。
     同上：var(--sh-radius) 不存在，此前这张品类弹层是**直角**的 */
  border-radius: 44rpx 44rpx 0 0;
}
.cat-sheet__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24rpx;
  border-bottom: 2rpx solid var(--sh-line);
}
.cat-sheet__title {
  font-weight: 600;
}
.cat-sheet__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28rpx 24rpx;
  border-bottom: 2rpx solid var(--sh-line);
}
.cat-sheet__empty {
  padding: 40rpx 24rpx;
  text-align: center;
}
.save {
  margin-top: 24rpx;
}
.tip {
  display: block;
  margin: 20rpx 8rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
</style>
