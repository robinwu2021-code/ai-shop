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
import { computed, ref, watch } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { CATEGORY_TYPE, MARKETS, TEMPLATE_TO_TYPE } from "@shared/utils/constants";
import { MAX_IMAGE_BYTES, pickImages } from "@shared/ports/media";
import { toMajor, toMinor } from "@shared/utils/money";
import type { Category, CategoryType, CurrencyCode, MarketId, I18nText, SpecTemplate, SpuStd } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

/**
 * 商家自助能建的类目模板。**一期只开三支**（2026-08-20 收窄，08-21 从品类搬到类目）。
 *
 * <p>去掉 `VOUCHER`（卡券）与 `VIRTUAL`（虚拟）：这两类牵扯发放、核销与资金，
 * 走的是运营配置那条路。摆在建品表单里，商家点进去只会得到一个建了也卖不出去的商品。
 *
 * <p><b>为什么是模板不是品类</b>：品类现在由类目派生（见 `select`），
 * 收窄必须落在**输入**那一侧才有意义 —— 收窄输出的话，商家仍然选得到卡券类目，
 * 只是形态那行写着一个他建不了的词。
 *
 * <p>老商品仍然打得开：这里砍的是**可选项**，回填走的是 `findPath`，
 * 不受这张表影响。
 */
const ALLOWED_TEMPLATES = ["STANDARD", "FRESH", "SERVICE"];

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
/**
 * 引用的标准品（TDD-标准品库）。**为空 = 自建品。**
 *
 * <p>「从标准品开始」只是把字段**填进表单**，商家照样能改标题与图；
 * 但类目与 optionCode 由**服务端**强制以标准品为准 —— 端上算错、
 * 或者有人直接构造请求，都写不进一条破坏跨店可比的数据。
 *
 * <p>脱离时置空即可：提交体不带 stdNo，后端据此清掉溯源。
 */
const stdNo = ref("");
const stdTitle = ref("");
const stdKeyword = ref("");
const stdResults = ref<SpuStd[]>([]);
const showStd = ref(false);
const stdSearching = ref(false);

async function searchStd() {
  stdSearching.value = true;
  try {
    stdResults.value = await api.mSpuStdSearch({ keyword: stdKeyword.value.trim() });
  } catch {
    // 搜不出来不该挡住建品：标准品是加速器，不是必经之路
    stdResults.value = [];
  } finally {
    stdSearching.value = false;
  }
}

/** 取用标准品：填充表单。**已填的字段不覆盖** —— 商家可能先手打了标题再来搜 */
function pickStd(t: SpuStd) {
  stdNo.value = t.stdNo;
  stdTitle.value = t.title;
  if (!title.value["zh-CN"].trim()) title.value = { ...title.value, "zh-CN": t.title };
  if (!subtitle.value["zh-CN"].trim() && t.subtitle) {
    subtitle.value = { ...subtitle.value, "zh-CN": t.subtitle };
  }
  if (!cover.value && t.cover) cover.value = t.cover;
  if (!images.value.length && t.images?.length) images.value = [...t.images];
  // 类目直接落定：服务端反正会以标准品为准，端上先对齐，免得他选完又被改回去
  const path = findPath(categoryTree.value, t.categoryNo);
  if (path.length) {
    catPath.value = path;
    categoryNo.value = t.categoryNo;
    const inferred = path[path.length - 1]?.template;
    if (inferred && TEMPLATE_TO_TYPE[inferred]) {
      type.value = TEMPLATE_TO_TYPE[inferred] as CategoryType;
      void loadTemplates();
    }
  }
  // 规格组整份取用：code 是它的价值所在，只取文案等于白取
  if (t.specGroups?.length) {
    groups.value = t.specGroups.map((g) => ({
      name: g.name,
      options: [...g.options],
      codes: g.optionCodes ? [...g.optionCodes] : undefined,
      templateNo: g.templateNo,
    }));
    // 矩阵由 watch(groups) 自动重建，这里不必手动调
  }
  showStd.value = false;
}

/** 脱离标准品。**只清引用，不清已填的内容** —— 他要的是「这条以后不算标准品」 */
function detachStd() {
  stdNo.value = "";
  stdTitle.value = "";
}

/** 商品主图。拍一张就有，替掉 emoji 占位（E9） */
const cover = ref("");
/** 详情轮播图。与封面分开：封面进列表卡片，这些进详情页的轮播 */
const images = ref<string[]>([]);
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
/**
 * 商品形态。**派生值，不是输入** —— 由所选类目的 `template` 带出（见 `select`）。
 * 页面上它只是一行只读文字；后端也不采信请求里的 type，自己按 categoryNo 查一遍。
 */
const type = ref<CategoryType>(CATEGORY_TYPE.NORMAL as CategoryType);

/**
 * 履约方式：这件货**怎么送到买家手上**。
 *
 * <p>后端一直支持改（留空=不改、空数组=拒），而端上**从来没给过入口** ——
 * 于是新建商品默认「实物类全支持」，商家永远收窄不了：一件只能自提的货
 * 会被下成快递单，而 F-1 的「下单必须支持该履约方式」校验因此形同虚设。
 *
 * <p>候选项按形态给：实物类给自提/快递那几种，服务类给到店核销/上门。
 * 一件大米不该在选项里看到「到店核销」。
 */
const PHYSICAL_FULFILLMENTS = ["STORE_PICKUP", "NEIGHBOR_PICKUP", "MERCHANT_DELIVERY", "EXPRESS"];
const SERVICE_FULFILLMENTS = ["STORE_VERIFY", "APPOINTMENT"];
const fulfillments = ref<string[]>([]);
const fulfillmentOptions = computed(() =>
  type.value === CATEGORY_TYPE.SERVICE ? SERVICE_FULFILLMENTS : PHYSICAL_FULFILLMENTS,
);
/**
 * 履约方式**单选**。
 *
 * <p>字段仍是数组（后端与订单侧按数组读），但界面只让选一种 ——
 * 多选看着更灵活，实际是把「这件货到底怎么交付」推给下单那一刻再决定，
 * 而那时买家看到的候选项是商家从没想清楚的那几种组合。
 *
 * <p>再点一次已选项**不取消**：履约方式必填，允许取消只会多出一个
 * 「一种都没选」的中间态，而它唯一的用途是让保存按钮变灰。
 */
function pickFulfillment(f: string) {
  fulfillments.value = [f];
}

/** 每人限购，0/空 = 不限 */
const limitPerUser = ref("");

/**
 * 生鲜段与服务段。**按形态显示** —— 形态由类目带出，所以选完类目字段区就跟着换。
 *
 * <p>这几个字段此前只有种子数据写得进去（`SaveCommand` 里根本没有对应参数），
 * 商家建出来的生鲜没有截单时间、不按实称，「按标称预扣、多退少补」这条链
 * 在真实数据上跑不起来。
 */
const fresh = ref({ cutoffAt: "", arrivalDesc: "", weighed: false, origin: "" });
const service = ref({ durationMin: "", storeName: "" });
/** 拼团档：两个值要么都填要么都不填 —— 缺一个开不出团，而界面上看着是配着的 */
const groupBuy = ref({ minCount: "", price: "" });

const isFresh = computed(() => type.value === CATEGORY_TYPE.FRESH);
const isService = computed(() => type.value === CATEGORY_TYPE.SERVICE);

/**
 * 类目（三级树）。
 *
 * ⚠️ 与上面的 `type`（五品类）**不是一回事**，页面上要分开两个控件：
 * `type` 决定履约与合规（生鲜要截单时间、服务不发货），平台硬编码；
 * 类目决定归类与经营准入，运营可维护。合成一个控件的话，
 * 商家改一次类目会连带改掉履约方式 —— 而他只是想把货归得更准一点。
 */
/** 图文详情正文。纯文本、不做多语言 —— 逼商家填三遍的结果是两遍空着 */
const detail = ref("");
/**
 * 这件商品当前是不是草稿（新建时也算）。
 *
 * <p>决定底部是一个「保存」还是两个按钮 —— 已过审的商品没有「提交审核」这一步：
 * 他一保存就自动回到待审，多给一个按钮只会让人以为不点就不用重审。
 */
const isDraft = ref(true);
const categoryTree = ref<Category[]>([]);
const categoryNo = ref("");
/** **已选**的路径，[一级, 二级]；只选到一级也允许。面包屑与提交都取它 */
const catPath = ref<Category[]>([]);

/**
 * 当前展开的一级类目 —— **与「已选」分开的两个状态**。
 *
 * <p>翻着看不等于改了选择：商家点开「食品生鲜」看了一眼又回到「日用百货」，
 * 已选的那一项不该被清掉。
 */
const parentNo = ref("");

/**
 * 二级候选。平台类目**就两级**（V168），所以这一行永远是最后一行 ——
 * 此前是逐级下钻的弹层，一次只看得见一层，改个类目要连点返回往上爬；
 * 两级平铺之后，父与子同屏，改哪一级都是一下。
 */
const children = computed<Category[]>(
  () => categoryTree.value.find((c) => c.categoryNo === parentNo.value)?.children ?? [],
);

/** 面包屑：「食品生鲜 / 蔬菜」。没选时为空，由占位文案顶上 */
const categoryLabel = computed(() =>
  catPath.value.length ? catPath.value.map((c) => c.name).join(" / ") : "",
);

/**
 * 选一级。
 *
 * <p><b>只展开，不改已选</b> —— 除非这一级底下没有二级（服务类目本来就只有两级，
 * 硬凑一层是为了对齐而对齐）。那种情况下它自己就是终点，直接选中。
 */
function pickParent(c: Category) {
  parentNo.value = c.categoryNo;
  if (!c.children?.length) select([c]);
}

/** 选二级。到这里就是终点，不再往下 */
function pickChild(c: Category) {
  const parent = categoryTree.value.find((x) => x.categoryNo === parentNo.value);
  select(parent ? [parent, c] : [c]);
}

/**
 * 落选 —— **全页唯一一处写 `categoryNo` 与 `type`**。
 *
 * <p>类目在库里就带着 `template`（STANDARD/FRESH/SERVICE/VOUCHER/VIRTUAL），
 * 它与品类是同一件事的两套码。此前页面上另有一排品类 chip，商家把同一件事
 * 填两遍，而且**可以互相矛盾**：选「叶菜」类目配「日用品」品类，没有一处会拦，
 * 直到下单时才因为履约方式不对而出问题（生鲜要截单、服务不发货）。
 *
 * <p>现在 chip 已经删掉，`type` 只是个**展示值** —— 后端也不采信请求里的 type，
 * 它自己按 categoryNo 查一遍。两边同源，端上算错也写不进库。
 */
function select(path: Category[]) {
  const leaf = path[path.length - 1];
  if (!leaf) return;
  catPath.value = path;
  categoryNo.value = leaf.categoryNo;
  const inferred = leaf.template ? TEMPLATE_TO_TYPE[leaf.template] : undefined;
  if (inferred && inferred !== type.value) {
    type.value = inferred as CategoryType;
    // 规格模板按品类推荐，品类变了要重取 —— 否则给生鲜推的还是上一类的模板
    void loadTemplates();
  }
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
  /*
   * **类目必填**（2026-08-21）。它此前是选填的，而品类是必填的 ——
   * 现在两者调了个个：品类由类目派生，没类目就没有形态可派生，
   * 商品会落进「新建默认 NORMAL」那条回落，而商家以为自己建的是生鲜。
   *
   * 归类还是经营准入的判据（`required_code`），不填等于绕过那道闸 ——
   * 只不过它在上架时才校验，保存这一步拦住的是「填了一半就走」。
   */
  if (!categoryNo.value) out.push(t("goods.category"));
  // 一种履约都不选的商品谁也买不了 —— 后端会拒，这里先说出来
  if (!fulfillments.value.length) out.push(t("goods.fulfillment"));
  /*
   * 拼团两个值要么都填要么都不填。**只填一个不是"填了一半"，是配了一个开不出的团** ——
   * 后端按两者都齐来判「能不能开团」，只填团价的商家会以为自己开了团。
   */
  const gbFilled = [groupBuy.value.minCount, groupBuy.value.price].filter((v) => Number(v) > 0);
  if (gbFilled.length === 1) out.push(t("goods.groupBuyIncomplete"));
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
/**
 * 详情轮播图。**这个入口此前根本不存在** —— 契约里 `GoodsDraft.images` 一直有，
 * 页面没填，于是提交体里没有这一项；而后端那时是无条件覆盖，
 * `writeJson(null)` 返回 `"[]"`，结果是<b>改一次标题轮播图就全没了</b>。
 *
 * <p>后端已改成「不传 = 不改」（P0-1 第一步），但只修那一半的话，
 * 轮播图变成了「不会丢，也永远存不进去」—— 一个字段有列、有契约、
 * 有下发、就是没有写入路径，与这轮修的其余几处是同一个形状。
 */
const IMAGE_LIMIT = 6;

async function addImages() {
  if (uploading.value) return;
  const room = IMAGE_LIMIT - images.value.length;
  if (room <= 0) {
    uni.showToast({ title: t("goods.imageLimit", { n: IMAGE_LIMIT }), icon: "none" });
    return;
  }
  let picked;
  try {
    picked = await pickImages(room, ["album", "camera"]);
  } catch {
    return; // 用户取消，不是错误
  }
  // 与封面同一道端上闸：超限的图走完整个上传才被服务端拒，那几秒是白等的
  const tooBig = picked.find((p) => p.size > MAX_IMAGE_BYTES);
  if (tooBig) {
    uni.showToast({ title: t("goods.imageTooLarge"), icon: "none" });
    return;
  }
  uploading.value = true;
  try {
    for (const img of picked) {
      const { url } = await api.mUploadImage(img.tempPath);
      images.value = [...images.value, url];
    }
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    uploading.value = false;
  }
}

function removeImage(i: number) {
  images.value = images.value.filter((_, idx) => idx !== i);
}

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
        /*
         * **形态跟着识别出的类目走** —— 而不是跟着 `guess.type`。
         *
         * 识别结果里那个 type 现在没人要了（后端按 categoryNo 派生），
         * 照着它填会出现「类目是叶菜、形态写日用品」，正是这轮要消掉的那种矛盾。
         * 类目树在候选表里被砍过（prunable），path 找得到就说明这个类目商家建得了。
         */
        const inferred = path[path.length - 1]?.template;
        if (inferred && TEMPLATE_TO_TYPE[inferred]) {
          type.value = TEMPLATE_TO_TYPE[inferred] as CategoryType;
          await loadTemplates();
        }
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

/**
 * 规格一变就重建矩阵。**不再只靠 `@blur`**。
 *
 * <p>此前重建的唯一触发点是输入框失焦。实测踩过：用系统返回键收键盘
 * **不触发 blur**，矩阵就停在旧状态 —— 屏幕上写着两个选项，底下却只有一行 SKU，
 * 而这时点保存，发出去的是「声明了 2 个选项、只带 1 个 SKU」的不一致包体。
 * 真实用户点保存时 blur 通常会先触发，但那是时序上的巧合，不是设计。
 *
 * <p>防抖 250ms：每敲一个字符重建一次，矩阵会在打字过程中反复闪；
 * 停手四分之一秒再重建，观感上就是「填完就出来了」。
 */
let rebuildTimer: ReturnType<typeof setTimeout> | undefined;
watch(
  groups,
  () => {
    clearTimeout(rebuildTimer);
    rebuildTimer = setTimeout(rebuild, 250);
  },
  { deep: true },
);

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
  // 取不到不该挡住整个编辑页：拿不到就退化成「不归类」，商品照样存得下
  categoryTree.value = prunable(await api.mCategoryTree().catch(() => []));
}

/**
 * 砍掉商家自助建不了的那几支。
 *
 * <p>这条规则原先长在品类 chip 上（"一期只开 NORMAL/FRESH/SERVICE 三个"）——
 * 品类改成由类目派生之后，它必须跟着搬到**类目树**上：留着虚拟/卡券的类目，
 * 商家选进去就得到一个建了也卖不出去的商品，而形态那行还会理直气壮地写着「卡券」。
 *
 * <p>按 `template` 砍而不是按名字：类目名运营随时可改，模板是判据。
 * 一级砍掉整支 —— 虚拟与卡券在树上本来就是独立的一级分支。
 */
function prunable(tree: Category[]): Category[] {
  return tree.filter((c) => !c.template || ALLOWED_TEMPLATES.includes(c.template));
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
  /*
   * 有模板就顺手摊开。加完组，商家面对的是两个空框，而「规格名该填什么」
   * 恰恰是此刻最难的一步 —— 平台模板就在同一张卡片里，却要再点一次才看得到。
   * 已经有组了就不摊开：那时他多半知道自己在干什么。
   */
  if (templates.value.length && groups.value.length === 1) {
    showTemplates.value = true;
  }
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
   * 轮播图要回显 —— 保存是整份覆盖，不回显就等于「打开编辑页再保存一次就清空」。
   * 与封面、三语原文、多市场价是同一个形状的故障，这一处是最后补上的。
   */
  images.value = [...(g.images ?? [])];
  // 溯源要回显：不回显的话，编辑一次就等于自动脱离了标准品（提交体不带 stdNo）
  stdNo.value = g.stdNo ?? "";
  // 标题在标准品那边，这里只有编号；徽标显示编号即可（要标题得再查一次，不值得）
  stdTitle.value = g.stdNo ?? "";
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
  detail.value = g.detail ?? "";
  isDraft.value = g.status === "DRAFT";
  type.value = g.type;
  categoryNo.value = g.categoryNo ?? "";
  catPath.value = categoryNo.value ? findPath(categoryTree.value, categoryNo.value) : [];
  /*
   * 履约方式与几段可选字段**都要回显**：保存是整份覆盖，回显不全就等于每保存一次
   * 清一次 —— 与轮播图、三语原文是同一个形状的故障（都不报错）。
   */
  /*
   * 老数据可能带多种履约方式（此前是多选）。这里**收敛到第一种** ——
   * 界面已经改成单选，留着多种只会让他看到一屏选中态却只能改掉其中一个，
   * 而保存写回的仍是收敛后的那一种。
   */
  fulfillments.value = (g.fulfillments ?? []).slice(0, 1);
  limitPerUser.value = g.limitPerUser ? String(g.limitPerUser) : "";
  fresh.value = {
    cutoffAt: g.cutoffAt ? new Date(g.cutoffAt).toISOString().slice(0, 16) : "",
    arrivalDesc: g.arrivalDesc ?? "",
    weighed: !!g.weighed,
    origin: g.origin ?? "",
  };
  service.value = {
    durationMin: g.durationMin ? String(g.durationMin) : "",
    storeName: g.storeName ?? "",
  };
  groupBuy.value = {
    minCount: g.groupBuy ? String(g.groupBuy.minCount) : "",
    price: g.groupBuy ? toMajor(g.groupBuy.price) : "",
  };
  groups.value = g.specGroups.map((sg) => ({
    name: sg.name,
    options: [...sg.options],
    codes: sg.optionCodes ? [...sg.optionCodes] : undefined,
    templateNo: sg.templateNo,
  }));
  rows.value = g.skus.map((k) => {
    /*
     * **整份回填各市场价。**
     *
     * 这里原先只回填当前市场那一格（后端当时不下发 priceByMarket），
     * 而保存是整份覆盖 —— 于是改一次标题，AE/US 两行的价就被删了，
     * 且不报错：那两个市场的买家从此看不到这件商品。
     * 与三语原文是逐字同款的故障，那边补了下发，这边当时没补。
     */
    const priceMajor = emptyPrices();
    priceMajor[market.value] = toMajor(k.price);
    for (const m of MARKET_CURRENCIES) {
      const v = k.priceByMarket?.[m.id];
      if (v != null) priceMajor[m.currency] = toMajor(v);
    }
    return {
      skuNo: k.skuNo,
      optionValues: [...k.optionValues],
      priceMajor,
      stock: String(k.stock),
    };
  });
});

async function save(thenSubmit = false) {
  if (!canSave.value || saving.value) return;
  saving.value = true;
  try {
    const saved = await api.mSaveGoods({
      goodsNo: goodsNo.value || undefined,
      title: title.value,
      subtitle: subtitle.value,
      // 详情：空串也要发 —— 后端「不传 = 不改」，删光了不发就删不掉
      detail: detail.value,
      // type **不再提交**：五品类由 categoryNo 派生，后端拿到也会忽略（P1-1）。
      // 留着发一个不被采信的值，只会让下一个人以为它还起作用
      //
      // 轮播图：**空数组也要发**。后端「不传 = 不改」，所以删光了不发的话删不掉
      images: images.value,
      // 溯源。不传 = 自建品 / 已脱离 —— 后端据此清掉 std_no
      stdNo: stdNo.value || undefined,
      // 必填由 `missing` 守着（按钮点不动），这里不再兜 undefined ——
      // 兜的话，一个空类目会被静默送进后端，走「新建默认 NORMAL」那条回落
      categoryNo: categoryNo.value,
      // 履约方式：**空数组也要发**，它与「不传」是两件事 —— 后端会拒空数组
      // （一种履约都不支持的商品谁也买不了），而这正是我们要的报错
      fulfillments: fulfillments.value,
      limitPerUser: Number(limitPerUser.value) || 0,
      // 生鲜段与服务段只在对应形态下提交：一件大米带上「服务时长 90 分钟」
      // 不会报错，但它会出现在服务类的详情模板里
      fresh: isFresh.value
        ? {
            cutoffAt: fresh.value.cutoffAt ? new Date(fresh.value.cutoffAt).getTime() : undefined,
            arrivalDesc: fresh.value.arrivalDesc.trim(),
            weighed: fresh.value.weighed,
            origin: fresh.value.origin.trim(),
          }
        : undefined,
      service: isService.value
        ? {
            durationMin: Number(service.value.durationMin) || undefined,
            storeName: service.value.storeName.trim(),
          }
        : undefined,
      // 两个都空 = 显式关掉拼团；只填一个后端会拒（`missing` 已经先拦一道）
      groupBuy: {
        minCount: Number(groupBuy.value.minCount) || undefined,
        price: groupBuy.value.price ? toMinor(groupBuy.value.price) : undefined,
      },
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
    /*
     * 「保存并提交」是两次调用，不是一个开关：
     * 保存要能单独成立（填一半先存着），而提交是他另一个决定。
     * 后端的 submit 对新建返回的 goodsNo 幂等，重复点不会出问题。
     */
    if (thenSubmit) {
      const no = goodsNo.value || saved.goodsNo;
      if (no) await api.mSubmitGoods(no);
    }
    uni.showToast({ title: t(thenSubmit ? "goods.submitted" : "common.saved"), icon: "none" });
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

      <!-- 详情轮播图。此前没有这个入口：契约里有、页面没填、后端当成「清空」 -->
      <view class="field">
        <text class="field__label">{{ $t("goods.images") }}</text>
        <view class="imgs">
          <view v-for="(img, i) in images" :key="img + i" class="imgs__cell">
            <sh-cover class="imgs__img" :src="img"></sh-cover>
            <text class="imgs__del" @tap="removeImage(i)">×</text>
          </view>
          <view v-if="images.length < IMAGE_LIMIT" class="imgs__add" @tap="addImages">
            <text class="imgs__plus">＋</text>
          </view>
        </view>
        <text class="sh-muted hint">{{ $t("goods.imagesHint", { n: IMAGE_LIMIT }) }}</text>
      </view>

      <!--
        从标准品开始（TDD-标准品库）。放在标题**之前**：它是「少填几个字段」的入口，
        填完标题再来搜就没意义了。

        **搜不到必须能直接往下建**，所以这一栏只是一行入口，不是一道必经的步骤 ——
        标准库对「张姐家的酱菜」永远无效，而那类货是这个平台的一部分主力。
      -->
      <view class="field">
        <view v-if="stdNo" class="std-on">
          <text class="std-on__txt">{{ $t("goods.fromStd", { s: stdTitle || stdNo }) }}</text>
          <text class="std-on__off" @tap="detachStd">{{ $t("goods.detachStd") }}</text>
        </view>
        <view v-else class="std-pick" @tap="showStd = true">
          <text class="std-pick__val">{{ $t("goods.pickStd") }}</text>
          <text class="cat-pick__arrow">›</text>
        </view>
        <text class="sh-muted hint">{{ $t(stdNo ? "goods.fromStdHint" : "goods.pickStdHint") }}</text>
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
      <!--
        分类**只有这一个控件**。

        此前这里是两个并列的控件：一个选「形态」（五品类）、一个选「类目」，
        而形态本来就写在类目节点上 —— 于是商家把同一件事填两遍，还能填出
        「叶菜类目 + 日用品形态」这种组合，页面只提示不阻断，代价要到下单
        那一刻才显形（生鲜要截单、服务不发货）。

        现在形态是选完类目后的一行只读文字：它的作用是让商家确认
        「系统认为这是生鲜」，不是让他改。真要改，改的是类目。
      -->
      <!--
        图文详情：**纯文本长文**，不做富文本 —— 手机端做不出像样的富文本编辑，
        而收 HTML 就要在三端各做一次消毒，漏一处就是 XSS。
      -->
      <view class="field">
        <text class="field__label">{{ $t("goods.detail") }}</text>
        <textarea
          v-model="detail"
          class="field__area"
          :placeholder="$t('goods.detailPh')"
          :maxlength="2000"
        />
      </view>

      <view class="field">
        <text class="field__label">{{ $t("goods.category") }} *</text>
        <!--
          **两级平铺，不再逐级下钻。**

          此前是一个弹层，一次只看得见一层：商家要改个类目，先点开、再连点返回
          往上爬；识别自动填好的路径更糟 —— 他没点过任何一级，却要按两次返回
          才看得到选项。平台类目降到两级（V168）之后，父与子一屏放得下，
          那层弹层就只剩成本。
        -->
        <view class="cat-lv">
          <text class="cat-lv__t">{{ $t("goods.categoryL1") }}</text>
          <view class="cat-lv__opts">
            <text
              v-for="c in categoryTree"
              :key="c.categoryNo"
              class="sh-chip"
              :class="{ 'sh-chip--primary': parentNo === c.categoryNo }"
              @tap="pickParent(c)"
            >
              {{ c.name }}
            </text>
          </view>
        </view>

        <!-- 二级只在选了一级之后出现：先摆一排空椅子只会让人以为加载失败 -->
        <view v-if="parentNo && children.length" class="cat-lv">
          <text class="cat-lv__t">{{ $t("goods.categoryL2") }}</text>
          <view class="cat-lv__opts">
            <text
              v-for="c in children"
              :key="c.categoryNo"
              class="sh-chip"
              :class="{ 'sh-chip--primary': categoryNo === c.categoryNo }"
              @tap="pickChild(c)"
            >
              {{ c.name }}
            </text>
          </view>
        </view>

        <text v-if="categoryLabel" class="cat-lv__sel">{{ categoryLabel }}</text>
        <!-- 形态：派生值。没选类目时不显示 —— 那时它是个凭空的默认值，只会误导 -->
        <text v-if="categoryLabel" class="sh-muted hint">
          {{ $t("goods.typeDerived", { s: $t(`goods.categoryType.${type}`) }) }}
        </text>
      </view>

      <!--
        履约方式：后端一直收得下，端上从来没给过入口 —— 于是新建商品默认
        「实物类全支持」，商家永远收窄不了，一件只能自提的货会被下成快递单。
        候选项跟着形态走：一件大米不该在选项里看到「到店核销」。
      -->
      <view class="field">
        <text class="field__label">{{ $t("goods.fulfillment") }} *</text>
        <view class="chips">
          <text
            v-for="f in fulfillmentOptions"
            :key="f"
            class="sh-chip"
            :class="{ 'sh-chip--primary': fulfillments.includes(f) }"
            @tap="pickFulfillment(f)"
          >
            {{ $t(`goods.fulfillmentType.${f}`) }}
          </text>
        </view>
        <text class="sh-muted hint">{{ $t("goods.fulfillmentTip") }}</text>
      </view>

      <!-- 生鲜段：形态由类目带出，所以选完类目这一段自动出现 -->
      <view v-if="isFresh" class="field">
        <text class="field__label">{{ $t("goods.freshSection") }}</text>
        <view class="kv">
          <text class="kv__k">{{ $t("goods.cutoffAt") }}</text>
          <input v-model="fresh.cutoffAt" class="field__input" placeholder="2026-08-22T18:00" />
        </view>
        <view class="kv">
          <text class="kv__k">{{ $t("goods.arrivalDesc") }}</text>
          <input v-model="fresh.arrivalDesc" class="field__input" />
        </view>
        <view class="kv">
          <text class="kv__k">{{ $t("goods.origin") }}</text>
          <input v-model="fresh.origin" class="field__input" />
        </view>
        <!-- 用 chip 而不是 switch：全仓没有第二处 switch，
             而 uni 的 switch 事件类型在 vue-tsc 下要额外收窄，不值得为一个开关引入 -->
        <view class="kv">
          <text class="kv__k">{{ $t("goods.weighed") }}</text>
          <text
            class="sh-chip"
            :class="{ 'sh-chip--primary': fresh.weighed }"
            @tap="fresh.weighed = !fresh.weighed"
          >
            {{ fresh.weighed ? $t("common.yes") : $t("common.no") }}
          </text>
        </view>
        <text class="sh-muted hint">{{ $t("goods.freshTip") }}</text>
      </view>

      <!-- 服务段 -->
      <view v-if="isService" class="field">
        <text class="field__label">{{ $t("goods.serviceSection") }}</text>
        <view class="kv">
          <text class="kv__k">{{ $t("goods.durationMin") }}</text>
          <input v-model="service.durationMin" class="field__input" type="number" />
        </view>
        <view class="kv">
          <text class="kv__k">{{ $t("goods.verifyStore") }}</text>
          <input v-model="service.storeName" class="field__input" />
        </view>
      </view>

      <!-- 限购与拼团：与形态无关，所有商品都有 -->
      <view class="field">
        <view class="kv">
          <text class="kv__k">{{ $t("goods.limitPerUser") }}</text>
          <input v-model="limitPerUser" class="field__input" type="number" placeholder="0" />
        </view>
        <view class="kv">
          <text class="kv__k">{{ $t("goods.groupMinCount") }}</text>
          <input v-model="groupBuy.minCount" class="field__input" type="number" />
        </view>
        <view class="kv">
          <text class="kv__k">{{ $t("goods.groupPrice") }}</text>
          <input v-model="groupBuy.price" class="field__input" type="digit" />
        </view>
        <text class="sh-muted hint">{{ $t("goods.groupBuyTip") }}</text>
      </view>
    </view>

    <!-- 标准品搜索弹层。搜不到时给的是「直接自建」而不是一句「没找到」 -->
    <view v-if="showStd" class="cat-mask" @tap="showStd = false">
      <view class="cat-sheet" @tap.stop>
        <view class="cat-sheet__bar">
          <text class="cat-sheet__title">{{ $t("goods.pickStd") }}</text>
          <text class="cat-sheet__close" @tap="showStd = false">×</text>
        </view>
        <view class="std-search">
          <input
            v-model="stdKeyword"
            class="field__input"
            :placeholder="$t('goods.stdSearchPh')"
            @confirm="searchStd"
          />
          <text class="mini" @tap="searchStd">{{ $t("common.search") }}</text>
        </view>
        <view v-if="!stdResults.length" class="cat-sheet__empty">
          <text class="sh-muted">
            {{ stdSearching ? $t("common.loading") : $t("goods.stdEmpty") }}
          </text>
        </view>
        <view
          v-for="t in stdResults"
          :key="t.stdNo"
          class="cat-sheet__row"
          @tap="pickStd(t)"
        >
          <text>{{ t.title }}</text>
          <text class="sh-muted">{{ t.categoryName || "" }}</text>
        </view>
      </view>
    </view>

    <!-- 类目选择弹层：一次只显示一层，选到叶子自动收起 -->
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

      <!--
        列头。**只在多规格时画** —— placeholder 一旦填了字就消失，
        而多规格滚到第 6 行时，两列数字看不出哪列是价、哪列是库存。
        单规格只有一行，两个 placeholder 一直看得见，不需要列头。
      -->
      <view v-if="multi" class="row row--head">
        <text class="row__spec"></text>
        <text class="row__col">{{ $t(priceLabel) }}</text>
        <text class="row__col">{{ $t("goods.stock") }}</text>
      </view>

      <view v-for="(r, i) in rows" :key="i" class="row" :class="{ 'row--single': !multi }">
        <!--
          单规格不画左边这一格。只有一行时「默认规格」是在回答没人问的问题，
          还占掉近半行宽度 —— 而这一行真正要填的只有价与库存两个数。
        -->
        <text v-if="multi" class="row__spec">{{ r.optionValues.join(" · ") }}</text>
        <input
          v-model="r.priceMajor[market]"
          class="row__input sh-num"
          type="digit"
          :placeholder="$t(priceLabel)"
        />
        <!-- 库存 0 = 这个规格顾客买不到。多规格时最容易漏填的就是它 -->
        <input
          v-model="r.stock"
          class="row__input sh-num"
          :class="{ 'is-out': Number(r.stock) === 0 }"
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
    <!--
      草稿给两个按钮：**保存**（填一半先存着，不惊动运营）与**保存并提交**。
      已过审的商品只给一个 —— 它一保存就自动回到待审，多一个按钮反而让人以为
      不点就不用重审。
    -->
    <view class="acts">
      <view class="sh-btn save" :class="{ 'sh-btn--muted': !canSave }" @tap="save(false)">
        {{ isDraft ? $t("goods.saveDraft") : $t("common.save") }}
      </view>
      <view
        v-if="isDraft"
        class="sh-btn save"
        :class="{ 'sh-btn--muted': !canSave }"
        @tap="save(true)"
      >
        {{ $t("goods.saveAndSubmit") }}
      </view>
    </view>
    <text class="tip">{{ $t(isDraft ? "goods.draftTip" : "goods.saveTip") }}</text>
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
/* 标准品入口：未取用是一行可点的占位，取用后变成一枚可撤的徽标 */
.std-pick,
.std-on {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20rpx 0;
}
.std-pick__val {
  font-size: 28rpx;
  /* 不用主色当文字色（design-tokens 守卫）：主色留给按钮与选中态，
     一行可点的入口靠右侧箭头指路就够了 */
  color: var(--sh-text-1);
}
.std-on__txt {
  font-size: 26rpx;
  color: var(--sh-text-2);
}
.std-on__off {
  font-size: 24rpx;
  color: var(--sh-text-3);
}
.std-search {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 16rpx 24rpx;
}
.std-search .field__input {
  flex: 1;
  margin-top: 0;
}

/* 轮播图九宫格。固定尺寸方格，删除按钮压在右上角 —— 长按删在小程序上不好发现 */
.imgs {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-top: 12rpx;
}
.imgs__cell {
  position: relative;
  width: 150rpx;
  height: 150rpx;
}
.imgs__img {
  width: 150rpx;
  height: 150rpx;
  border-radius: 16rpx;
}
.imgs__del {
  position: absolute;
  top: -10rpx;
  right: -10rpx;
  width: 40rpx;
  height: 40rpx;
  line-height: 36rpx;
  text-align: center;
  border-radius: 50%;
  background: var(--sh-text-1);
  color: var(--sh-bg);
  font-size: 26rpx;
}
.imgs__add {
  width: 150rpx;
  height: 150rpx;
  border: 2rpx dashed var(--sh-border);
  border-radius: 16rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}
.imgs__plus {
  font-size: 40rpx;
  color: var(--sh-text-3);
}
/* 标签 + 输入框一行。标签固定宽度，几行叠起来时冒号后的输入框才对得齐 */
.kv {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 16rpx;
}
.kv__k {
  flex: 0 0 160rpx;
  font-size: 26rpx;
  color: var(--sh-text-2);
}
.kv .field__input {
  flex: 1;
  margin-top: 0;
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
.acts {
  display: flex;
  gap: 16rpx;
}

.acts .save {
  flex: 1;
}

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
/* 列头：不是输入框，弱一档 */
.row--head {
  margin-bottom: 4rpx;
}
.row__col {
  flex: 1;
  font-size: 24rpx;
  color: var(--sh-sub);
  text-align: center;
}
/* 缺货：这一格要能被扫到，它是「填完还差什么」里最常漏的一项 */
.row__input.is-out {
  color: var(--sh-danger);
}
/* 单规格：左边那格不画，两个输入框各占一半 */
.row--single .row__input {
  flex: 1;
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
.cat-lv {
  margin-top: 12rpx;
}

.cat-lv__t {
  display: block;
  margin-bottom: 8rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}

.cat-lv__opts {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

/* 已选那一行：面包屑是**结果确认**，比候选项重一档 */
.cat-lv__sel {
  display: block;
  margin-top: 16rpx;
  font-size: 26rpx;
  color: var(--sh-ink);
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
