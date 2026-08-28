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
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { buildSpecOverride } from "@/utils/spec-override";
import { ROUTES } from "@/shared/nav";
import { SHOW_CATEGORY_GATE, SHOW_FRESH_FIELDS } from "@/shared/flags";
import type { GoodsGuess } from "@/api/contract";
import { CATEGORY_TYPE, MARKETS, TEMPLATE_TO_TYPE } from "@shared/utils/constants";
import { MAX_IMAGE_BYTES, pickImages } from "@shared/ports/media";
import { toMajor, toMinor } from "@shared/utils/money";
import type { Category, CategoryType, CurrencyCode, MarketId, I18nText, GoodsParam, SpecOption, SpecTemplate, SpuStd, StoreCategory } from "@shared/types";
import { confirm, pick } from "@ai-shop/ui/prompt";

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


// ── 一、货号与标准品 ────────────────────────────────────────────────────────
//    从平台标准品填充，或自己起一个货号
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


// ── 二、商品图（状态） ──────────────────────────────────────────────────────
//    cover 与 images 存储上分两个字段，界面上合成一组
/**
 * 商品主图。拍一张就有，替掉 emoji 占位（E9）。
 *
 * <p>**存储上仍与轮播分开**（契约 `cover` / `images` 两个字段没动），
 * 但界面上已经合成一组「商品图」—— 见 `photos`。
 */
const cover = ref("");
/** 详情轮播图。与封面分开：封面进列表卡片，这些进详情页的轮播 */
const images = ref<string[]>([]);

/**
 * 界面上的「商品图」：**主图就是第一张**。
 *
 * <p>此前主图与轮播是两个控件，于是商家每传一张图都要先回答
 * 「这张算主图还是轮播」—— 那个问题来自数据表（`cover` 与 `images` 是两列），
 * 不来自他要做的事：他心里只有「这个商品长什么样」。
 * 合并之后第一张即封面，是电商 App 的通行约定，不用教。
 *
 * <p>只合并**界面**：存的时候照旧拆回两个字段，后端与 C 端零改动。
 */
const photos = computed(() =>
  cover.value
    // **去重**：第一张上传时既写进 cover 又留在 images（存储照旧，C 端轮播里
    // 本来就该有封面那一张）。不去重的话，传一张图界面上冒出两个一模一样的格子。
    ? [cover.value, ...images.value.filter((x) => x !== cover.value)]
    : [...images.value],
);
/** 勾了本店没开通的送货方式（后端 `FULFILLMENT_NOT_SUPPORTED`）。出路是去开通，不是改这一页 */
const FULFILLMENT_NOT_SUPPORTED = 70013;

/** 一组图的上限 = 封面 1 + 轮播 6。存的时候两个字段各自的上限没变 */
const PHOTO_LIMIT = 7;
/**
 * 详情图：图文详情正文**下方**按顺序全宽竖排的长图。
 *
 * <p>与轮播图分成两组存、两组传：轮播是顶部可左右滑的方图，详情图是 1:3 的长图。
 * 混在一个数组里的话，端上只能靠宽高比猜哪几张该轮播 —— 猜错就是一张长图
 * 被塞进方形轮播里，而这件事没有任何一处会报错。
 */
const detailImages = ref<string[]>([]);
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

// ── 三、标题与多语言 ────────────────────────────────────────────────────────
//    中/英/阿三份文案，untranslated 盯着没填的那几格
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

// ── 四、渠道 · 履约 · 团购 ────────────────────────────────────────────
//    实物与服务两套履约集合，门店渠道决定哪些能开
const fulfillments = ref<string[]>([]);
const fulfillmentOptions = computed(() =>
  type.value === CATEGORY_TYPE.SERVICE ? SERVICE_FULFILLMENTS : PHYSICAL_FULFILLMENTS,
);

/**
 * 本店**实际开通**的送货方式（`mch_fulfillment_channel`）。
 *
 * <p>此前这一栏的候选项是四个写死的常量，**与门店开没开通无关** ——
 * 门店没开快递，商品照样勾得上快递，而错要到买家下单那一刻才显形
 * （F-1「下单必须支持该履约方式」在商品这一侧从来没有对齐过）。
 *
 * <p>取不到就退回四个常量：这一栏是必填项，因为一次网络失败让人建不了商品，
 * 比多给两个选项糟得多。
 */
const storeChannels = ref<string[]>([]);
/**
 * 这份名单的状态。**分三档，不是一个 boolean** ——
 * 「还没读到」与「读失败了」在界面上要说不同的话：前者等一下就好，
 * 后者要给一个「重试」。此前两者都退回「四路全开」，于是**网络抖一下，
 * 商家就能勾上一条本店没开的路**，商品存得下去、买家下不了单，
 * 而错要到结算那一刻才显形。
 */
const channelsState = ref<"loading" | "ok" | "error">("loading");
const channelsLoaded = computed(() => channelsState.value === "ok");

/**
 * 这一路本店开了吗。**以后端为准**（`mch_fulfillment_channel`）。
 *
 * <p>服务类两种不归门店送货方式管，恒为可选。
 *
 * <p>名单为空时放行：与后端 `MerchantGoodsServiceImpl` 那句
 * 「空集 = 未迁移到 channel 模型，放行」**一字对齐** ——
 * 老商家一行 channel 记录都没有，前端在这里拦死的话，他连商品都建不了，
 * 而后端本来是让他过的。两侧规则必须同一条，否则一边说能、一边说不能。
 */
function channelOpen(f: string): boolean {
  if (SERVICE_FULFILLMENTS.includes(f)) return true;
  if (channelsState.value !== "ok" || !storeChannels.value.length) return true;
  return storeChannels.value.includes(f);
}

/**
 * 默认选中哪一路。
 *
 * <p>只开了一路 → 就是它；开了多路 → 按固定优先级取第一条。
 * 优先级不是拍脑袋：**自送与到店自提是社区店的日常**，快递是少数商家才走的一路。
 * （二期给 `mch_fulfillment_channel` 加「默认路」让商家自己指，那之前用这个。）
 */
const FULFILLMENT_PRIORITY = ["MERCHANT_DELIVERY", "STORE_PICKUP", "NEIGHBOR_PICKUP", "EXPRESS"];

async function loadStoreChannels() {
  const res = await api.mStoreFulfillment(merchant.storeNo || "default").catch(() => null);
  if (!res) {
    // 失败**不清空已有名单**：从门店页回来重拉时抖一下，不该让整排 chip 跳一下
    channelsState.value = storeChannels.value.length ? "ok" : "error";
    return;
  }
  channelsState.value = "ok";
  storeChannels.value = res.channels.filter((c) => c.enabled).map((c) => c.channel);
  /*
   * **只给新建商品预选**。编辑已有商品时一律不动他选过的那一路 ——
   * 那是已经在卖的事实，替他改掉等于悄悄换了这件货的交付方式。
   */
  if (isEdit.value || fulfillments.value.length) return;
  const pick = FULFILLMENT_PRIORITY.find((f) => storeChannels.value.includes(f));
  if (pick) fulfillments.value = [pick];
}

/** 编辑时：原来那一路已经被门店关掉了 —— 说出来，但不替他改 */
const fulfillmentClosed = computed(
  () =>
    channelsLoaded.value &&
    storeChannels.value.length > 0 &&
    fulfillments.value.some((f) => !SERVICE_FULFILLMENTS.includes(f) && !storeChannels.value.includes(f)),
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
async function pickFulfillment(f: string) {
  /*
   * 没开通的那一路**点不动**，并且说清去哪开 —— 直接让他勾上的话，
   * 商品保存得下去，买家却下不了单，而两处都不报错。
   */
  if (!channelOpen(f)) {
    /*
     * 出路给在**他伸手要这一路的那一刻**，不常驻在页面上。
     *
     * <p>此前是页面底下常挂一条「去开启」链接：它服务的是一个**难得出现一次**
     * 的需求（这家店要新开一种送货方式），却一直占着一行，
     * 而且挂在那儿时谁都看不出它要开的是哪一路。
     * 现在点灰掉的那一路就问他去不去开，问句里带着这一路的名字。
     */
    if (
      await confirm({
        title: String(t("goods.fulfillmentClosedTip")),
        hint: String(t("goods.fulfillmentClosedAsk", { s: String(t(`goods.fulfillmentType.${f}`)) })),
        confirmText: String(t("goods.toStoreScope")),
      })
    ) {
      toStoreScope();
    }
    return;
  }
  fulfillments.value = [f];
}

/** 去门店设置开这一路。回来时 `onShow` 会重拉一次开通状态（见页尾的 onShow） */
function toStoreScope() {
  uni.navigateTo({ url: ROUTES.storeScope });
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
/**
 * 拼团开关。**不是新字段** —— 它只是「这两个框填不填」的可见表示：
 * 后端认的仍旧是 groupPriceMinor / groupMinCount，两个都空即关闭。
 *
 * 单独立一个 ref 而不是用 `groupBuy.price !== ""` 推导：那样一来，
 * 打开开关但还没输价格的那一刻，开关会自己弹回去。
 */
const groupBuyOpen = ref(false);

/** 起团人数默认 2 —— 后端本来就按 `< 2 → 2` 兜底，端上不给默认等于让人猜 */
function toggleGroupBuy() {
  groupBuyOpen.value = !groupBuyOpen.value;
  if (groupBuyOpen.value) {
    if (!groupBuy.value.minCount) groupBuy.value.minCount = "2";
  } else {
    // 关掉就是真的关掉：留着值会在保存时把拼团又开出去
    groupBuy.value = { minCount: "", price: "" };
  }
}

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

// ── 五、类目 ────────────────────────────────────────────────────────────────
//    父子两级 + 最近用过；选中类目会连带定履约与默认规格
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

/**
 * 这个类目商家**能不能选**。
 *
 * <p>判据与后端一致：无门槛，或主体持有那张码。端上先说清楚 ——
 * 让他选完、填完一屏、点保存才被拒，是最差的一种拒绝，
 * 而那句「你还没有资质授权」既说不出缺哪张证，也说不出去哪申请。
 *
 * <p><b>仍然可选，只是标出来</b>：草稿归到一个还没批下来的类目下是合法的，
 * 他可能正准备去申请 —— 真正拦在上架那一刻（后端闸一）。
 */
function gateOf(c: Category) {
  const code = c.requiredCode;
  if (!code) return null;
  return {
    granted: merchant.categoryCodes.includes(code),
    qualification: (c.qualifications ?? []).join("、"),
  };
}

/** 已选类目的门槛。选完才提示 —— 见 gateOf 的说明 */
const pickedGate = computed(() => {
  const leaf = catPath.value[catPath.value.length - 1];
  return leaf ? gateOf(leaf) : null;
});

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
  if (!c.children?.length) void select([c]);
}

/**
 * 「最近用过」的类目。**本地记录，不占后端**：一家店的货高度集中，
 * 建第二个商品时要选的那一档多半就在这三五个里 —— 一点就换。
 *
 * <p>它同时是「识别没填对」和「压根没识别出来」两种情况下最快的入口，
 * 所以不藏起来，就摆在一级类目上面那一行。
 */
const RECENT_CATS_KEY = "biz.recentCategories";

/*
 * 【已移除】specOpen / paramOpen —— 规格与参数现在**常驻展开**。
 *
 * <p>收起态是为了替不分规格的商家省一屏，但账压在了另一头：
 * 他看不见这一类到底有哪些规格，而「还能按什么分」恰恰是建品时最难的一步 ——
 * **藏起来的东西不会被想起来**。省下的一屏，代价是他要等买家问
 * 「有没有大份的」才发现自己漏了。
 *
 * <p>「记住他上次是开是合」更糟：同一个类目，换台手机就是另一套默认，
 * 而他不知道为什么。默认值要能被解释，本机记忆解释不了。
 */
const recentCats = ref<{ categoryNo: string; name: string }[]>([]);

function loadRecentCats() {
  try {
    const raw = uni.getStorageSync(RECENT_CATS_KEY);
    recentCats.value = Array.isArray(raw) ? raw.slice(0, 5) : [];
  } catch {
    recentCats.value = [];
  }
}

/** 保存成功后记一笔。同一个类目再选只是提前，不重复入列 */
function rememberCat() {
  const leaf = catPath.value[catPath.value.length - 1];
  if (!leaf) return;
  const next = [
    { categoryNo: leaf.categoryNo, name: leaf.name },
    ...recentCats.value.filter((c) => c.categoryNo !== leaf.categoryNo),
  ].slice(0, 5);
  recentCats.value = next;
  try {
    uni.setStorageSync(RECENT_CATS_KEY, next);
  } catch {
    // 存不进去不影响建品，最近用过下次不显示而已
  }
}

/** 点「最近用过」：按编号回原树找路径，连面包屑与形态一起落 */
function pickRecent(no: string) {
  const path = findPath(categoryTree.value, no);
  if (!path.length) {
    // 类目被运营下架了：静默从最近列表里摘掉，不给一个点了没反应的 chip
    recentCats.value = recentCats.value.filter((c) => c.categoryNo !== no);
    return;
  }
  void select(path);
}

/** 选二级。到这里就是终点，不再往下 */
function pickChild(c: Category) {
  const parent = categoryTree.value.find((x) => x.categoryNo === parentNo.value);
  void select(parent ? [parent, c] : [c]);
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
async function select(path: Category[]) {
  const leaf = path[path.length - 1];
  if (!leaf) return;
  catPath.value = path;
  parentNo.value = path[0]?.categoryNo ?? leaf.categoryNo;
  categoryNo.value = leaf.categoryNo;
  const inferred = leaf.template ? TEMPLATE_TO_TYPE[leaf.template] : undefined;
  if (inferred && inferred !== type.value) {
    type.value = inferred as CategoryType;
  }
  /*
   * **类目一确定就重取模板** —— 不再只在品类变了的时候取。
   *
   * 只传品类拿回来的是兜底那批（STANDARD 一个盖住 18 个二级类目：手机数码
   * 与鲜花共用「包装：袋装/瓶装/罐装」，等于没有推荐）。类目级模板才有信息量，
   * 而它只有带上 categoryNo 才拿得到。
   */
  await Promise.all([loadTemplates(), loadPickableDims(), loadProps()]);
  /*
   * **不再自动建规格组。**
   *
   * <p>档位改成「一个都不预选」之后，自动建出来的是一个空壳：占着一张展开的卡，
   * 里面一排灰着的档位，而他还没说这件货要不要分档。
   * 现在收起态直接摆候选（＋重量 ＋包装 …），他点一个才成组 ——
   * 少一次「先撤销系统替我做的事」。
   */
}

/**
 * 按类目铺开**默认规格**。
 *
 * <p>只在**类目级**模板（`tpl.categoryNo` 有值）上做：品类兜底模板太泛，
 * 自动套给一件手机是帮倒忙。没有类目级模板时维持原来的 chip 推荐，
 * 商家点一下才成组。
 *
 * <p>三条边界：
 * <ul>
 *   <li><b>编辑已有商品永不自动套</b> —— 他的规格是已经卖过的事实，不是待填的空白
 *   <li>已经建过规格组就不动，除非那组正是上一次自动套出来、且他一个字没改
 *   <li>套出来的每个选项都可勾掉（见模板 chip 那一段），矩阵按选项组合保值
 * </ul>
 */
function autoApplyDefaultSpec() {
  if (isEdit.value) return;
  const tpl = templates.value.find((x) => x.scope === "PLATFORM" && x.categoryNo);
  if (!tpl) return;
  // 手动建过组就不覆盖；自动套过的那一组（autoSpecNo 记着）可以换成新类目的
  const only = groups.value.length === 1 ? groups.value[0] : undefined;
  const replaceable = !groups.value.length || (only && only.templateNo === autoSpecNo.value);
  if (!replaceable) return;
  if (only?.templateNo === tpl.templateNo) return;
  groups.value = [];
  applyTemplate(tpl);
  autoSpecNo.value = tpl.templateNo;
}

/** 上一次**自动**套上的模板号。手点的不算 —— 手点过的规格组不该被换类目冲掉 */
const autoSpecNo = ref("");

/** 撤销自动套上的规格组：回到单规格 */
function clearAutoSpec() {
  groups.value = [];
  autoSpecNo.value = "";
  rebuild();
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

/** 拉本店货架。取不到不该挡住建品：那时退回全量类目树，与改版前一样 */
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

// ── 六、规格状态与详情生成 ──────────────────────────────────────────────────
//    groups/templates 是下面第八、九节的共同底座
/**
 * 正在把已有商品回填进表单。
 *
 * <p>只在**编辑**时为真。新建没有这一步 —— 一进来空表单本来就是对的，
 * 那时候的「待填写」是真话。
 */
const hydrating = ref(false);

/** 规格组。空 = 单规格商品 */
const groups = ref<{ name: string; options: string[]; codes?: (string | undefined)[]; templateNo?: string }[]>([]);
/** 可用模板：平台按类目预置 + 本商家存的常用 */
const templates = ref<SpecTemplate[]>([]);
/** 「加规格组」时的维度选择面板：本类目已配 → 平台通用 → 自建 */
const pickableDims = ref<SpecTemplate[]>([]);
/** 正在生成图文详情 */
const generating = ref(false);

/**
 * 自动生成图文详情。
 *
 * <p>**先要有商品名**：没名字模型只能瞎编，生成出来的是一段和这件货无关的话，
 * 而商家多半会直接保存 —— 那比空白更糟。服务端同样拒绝这一档，
 * 这里先说出来是为了省一次往返。
 *
 * <p>**覆盖前先问**：他可能已经写了几行，一键抹掉没有撤销。
 */
async function genDetail() {
  if (generating.value) return;
  if (!title.value["zh-CN"].trim()) {
    uni.showToast({ title: t("goods.genDetailNeed"), icon: "none" });
    return;
  }
  if (detail.value.trim()) {
    const ok = await confirm({ title: String(t("goods.genDetailOverwrite")), danger: true });
    if (!ok) return;
  }
  generating.value = true;
  try {
    const { detail: text } = await api.mDescribeGoods({
      imageUrl: cover.value || undefined,
      title: title.value["zh-CN"].trim(),
      subtitle: subtitle.value["zh-CN"].trim() || undefined,
      categoryNo: categoryNo.value || undefined,
    });
    // 空串 = 没生成出来。**不要把空白填进框** —— 那看起来像把他写的内容清掉了
    if (!text.trim()) {
      uni.showToast({ title: t("goods.genDetailFail"), icon: "none" });
      return;
    }
    detail.value = text;
    uni.showToast({ title: t("goods.genDetailDone"), icon: "none" });
  } catch {
    uni.showToast({ title: t("goods.genDetailFail"), icon: "none" });
  } finally {
    generating.value = false;
  }
}


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
const saving = ref(false);
/** 批量填充 */
const bulk = ref({ price: "", stock: "", cost: "" });

const isEdit = computed(() => !!goodsNo.value);
const multi = computed(() => groups.value.length > 0);

/**
 * 加一个规格维度要多填几行 —— **当场说出来**。
 *
 * <p>「3 × 2 = 6 个规格，要填 6 个价和库存」。此前页面从不提这件事，
 * 商家加完第二个维度才发现要填一屏，而那时他已经填了一半。
 * 只在**两个维度起**才显示：一个维度时「3 个档位 = 3 行」是自明的。
 */
const skuCost = computed(() => {
  const counts = groups.value
    .map((g) => g.options.filter((o) => o.trim()).length)
    .filter((n) => n > 0);
  if (counts.length < 2) return "";
  const n = counts.reduce((a, b) => a * b, 1);
  return t("goods.skuCost", { s: counts.join(" × "), n });
});
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
  /*
   * 划线价 ≤ 售价必须在保存前拦住。后端会拒（返回 BAD_REQUEST），
   * 但那时商家已经点了保存，看到的是一句笼统的报错 —— 而错在哪一行不说。
   */
  if (rows.value.some(badOrigin)) out.push(t("goods.originPriceInvalid"));
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

// ── 八、商品图（操作） ──────────────────────────────────────────────────────
//    选图、上传、设封面、识图回填
/**
 * 详情轮播图。**这个入口此前根本不存在** —— 契约里 `GoodsDraft.images` 一直有，
 * 页面没填，于是提交体里没有这一项；而后端那时是无条件覆盖，
 * `writeJson(null)` 返回 `"[]"`，结果是<b>改一次标题轮播图就全没了</b>。
 *
 * <p>后端已改成「不传 = 不改」（P0-1 第一步），但只修那一半的话，
 * 轮播图变成了「不会丢，也永远存不进去」—— 一个字段有列、有契约、
 * 有下发、就是没有写入路径，与这轮修的其余几处是同一个形状。
 */
async function addImages() {
  if (uploading.value) return;
  // 余量按**合并后的总数**算：界面上是一组，弹「最多 6 张」而格子有 7 个说不通
  const room = PHOTO_LIMIT - photos.value.length;
  if (room <= 0) {
    uni.showToast({ title: t("goods.imageLimit", { n: PHOTO_LIMIT }), icon: "none" });
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
      /*
       * 主图还空着就用第一张详情图补上，并顺手识别。
       *
       * 此前详情图这条路**完全不识别**：从相册一次选好几张详情图的人，
       * 拿不到任何自动填写，还得回头再拍一次主图。
       * 只在主图为空时做，且只做第一张 —— 否则每张都识别一遍，
       * 会连弹好几个提示，还可能互相覆盖。
       */
      if (!cover.value) {
        cover.value = url;
        await recognizeInto(url);
      }
    }
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    uploading.value = false;
  }
}

/**
 * 删一张商品图。**删掉第一张时下一张顶上来当封面** ——
 * 不能留下「有图但没封面」的状态：那样列表页会退回 emoji 占位，
 * 而商家明明看见这个商品有四张图。
 */
function removePhoto(i: number) {
  const list = photos.value;
  const url = list[i];
  if (!url) return;
  // 按 url 删，不按下标 —— photos 是去重后的视图，下标与 images 的下标对不上
  images.value = images.value.filter((x) => x !== url);
  if (i === 0) cover.value = list[1] ?? "";
}

/**
 * 把第 i 张设为主图（与当前封面对调）。
 *
 * <p>**没做拖拽**：uni 的可拖网格要靠 movable-view 重写整块，
 * 而商家在这里真正要做的只有一件事 —— 换封面。对调一步到位，
 * 也不会把「顺序」这件他并不关心的事塞给他。
 */
function setCoverAt(i: number) {
  const picked = photos.value[i];
  if (!picked || i <= 0) return;
  // 只改封面指针。images 原样不动 —— 它是 C 端轮播的顺序，
  // 换个封面不该顺带把轮播重排一遍
  cover.value = picked;
}

/** 点非首张的图：只给「设为主图」一件事，删除仍走格子右上角的 ✕ */
async function tapPhoto(i: number) {
  if (i <= 0) return;
  if ((await pick({ items: [String(t("goods.setCover"))] })) === 0) setCoverAt(i);
}

/** 详情图上限。比轮播多：长图是「参数页 / 实拍页 / 售后页」这么一张张摞上去的 */
const DETAIL_IMAGE_LIMIT = 10;

async function addDetailImages() {
  if (uploading.value) return;
  const room = DETAIL_IMAGE_LIMIT - detailImages.value.length;
  if (room <= 0) {
    uni.showToast({ title: t("goods.imageLimit", { n: DETAIL_IMAGE_LIMIT }), icon: "none" });
    return;
  }
  let picked;
  try {
    picked = await pickImages(room, ["album", "camera"]);
  } catch {
    return; // 用户取消，不是错误
  }
  const tooBig = picked.find((img) => img.size > MAX_IMAGE_BYTES);
  if (tooBig) {
    uni.showToast({ title: t("goods.imageTooLarge"), icon: "none" });
    return;
  }
  uploading.value = true;
  try {
    for (const img of picked) {
      const { url } = await api.mUploadImage(img.tempPath);
      detailImages.value = [...detailImages.value, url];
    }
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    uploading.value = false;
  }
}

function removeDetailImage(i: number) {
  detailImages.value = detailImages.value.filter((_, idx) => idx !== i);
}

/**
 * 详情图**换顺序**。长图是有次序的（封面页 → 参数页 → 售后页），
 * 传错了只能全删重传就太贵了。
 *
 * <p>用两个箭头而不是长按拖拽：拖拽在 uni 的三端各有各的手势冲突
 * （小程序里 movable-view 与页面滚动打架），而这里最多 10 张、
 * 实际多半 2–3 张 —— 点两下就到位。
 */
function moveDetailImage(i: number, delta: number) {
  const to = i + delta;
  const list = [...detailImages.value];
  if (to < 0 || to >= list.length) return;
  const [row] = list.splice(i, 1);
  if (!row) return;
  list.splice(to, 0, row);
  detailImages.value = list;
}

/**
 * 看图填字段。**填不进去的一律变成候选，不丢弃。**
 *
 * 此前有两条静默丢弃的路径，店主都看不到识别到了什么：
 *   · `confidence < 0.6` → 只弹一句「未能识别」就返回，其实模型给了结果
 *   · 目标字段已有值 → 跳过，于是「先手打了标题再拍照」的人永远见不到识别出的类目
 *
 * 现在两种情况都进 `suggest`，在主图下面显示成一行可采用/可忽略的提示 ——
 * 识别结果从「要么替你填、要么消失」变成「永远看得见，填不填你定」。
 */
async function recognizeInto(url: string) {
  const guess = await api.mRecognizeGoods(url).catch(() => null);
  if (!guess) return;
  await applyGuess(guess);
}

/**
 * 把识别结果**当成表单的默认值填进去** —— 与「新建门店时带出上次的地址」同一性质。
 *
 * <p>界面上因此没有「AI」「识别」「置信度」这类字眼，也没有采用/忽略的候选条：
 * 识别本来就不准，把它包装成一件需要商家判断的事，等于每建一个商品多一道判断题。
 * 换来的是把**改的成本**压到最低 —— 名称一键清空、类目一步可换、最近用过就摆在外面。
 *
 * <p>三条规矩：
 * <ol>
 *   <li><b>只填空位</b>：先手打了标题再拍照的人，写的东西不会被顶掉
 *   <li><b>不分置信度</b>：低分同样预填。分档要么让人多想一次，要么被无视
 *   <li><b>识别不到什么也不发生</b>：不弹「未能识别」——那句提示除了打断没有用处
 * </ol>
 */
async function applyGuess(guess: GoodsGuess) {
  try {
    if (guess.title && !title.value["zh-CN"].trim()) {
      title.value = { ...title.value, "zh-CN": guess.title };
      lang.value = "zh-CN";
    }
    if (guess.subtitle && !subtitle.value["zh-CN"].trim()) {
      subtitle.value = { ...subtitle.value, "zh-CN": guess.subtitle };
    }
    /*
     * 类目要连**面包屑**一起还原，不能只塞编号：只设 categoryNo 的话，
     * 页面上那一栏仍显示「选择类目」，而提交时却带着一个类目 ——
     * 商家看到的和将要保存的不是一回事。
     *
     * `findPath` 找不到就不填：后端喂给模型的候选表只含二级与三级，
     * 但类目树在端上还被 `prunable` 砍过（虚拟/卡券建不了），
     * 落在被砍掉那一支上的编号在这棵树里不存在。
     * 只到一级也不填 —— 一级类目挂不住商品，填了反而让人以为已经选好了。
     */
    if (!categoryNo.value && guess.categoryNo) {
      const path = findPath(categoryTree.value, guess.categoryNo);
      if (path.length > 1 || (path.length === 1 && !path[0]?.children?.length)) {
        await select(path);
      }
    }
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}


// ── 九、SKU 矩阵：重建与模板 ────────────────────────────────────────────
//    规格组的笛卡尔积 → 行；套模板是它的入口
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
        originMajor: rows.value[0]?.originMajor ?? "",
        nominalGram: rows.value[0]?.nominalGram ?? "",
        costMajor: rows.value[0]?.costMajor ?? "",
        // 外部身份跟着第一行走：单规格拆成多规格时，原来那条 SKU 的条码不该凭空消失
        barcode: rows.value[0]?.barcode ?? "",
        merchantSkuCode: rows.value[0]?.merchantSkuCode ?? "",
        saleUnit: rows.value[0]?.saleUnit ?? "",
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
      /*
       * **必须拷一份，不能直接传引用。**
       *
       * `inherit()` 会让多个新组合回落到**同一个** old 行（前缀匹配、
       * 或单行回落那条）。直接传 `old.priceMajor` 的话，四个规格共用一个对象 ——
       * 改「袋装」的价，「盒装/桶装/整箱」跟着一起变，而且保存下去就是那样。
       *
       * 这个 bug 从初始提交就在，一直没被发现，因为 `stock` 是字符串（值类型）
       * 不受影响：表面症状只是「价格不对劲」，像操作失误，
       * 而不像四行绑到了同一份数据。多规格商品因此从来没能分别定价过。
       */
      priceMajor: old ? { ...old.priceMajor } : emptyPrices(),
      stock: old?.stock ?? "0",
      originMajor: old?.originMajor ?? "",
      nominalGram: old?.nominalGram ?? "",
      // 成本跟着价一起继承：加一个包装规格，进价多半还是那个进价
      costMajor: old?.costMajor ?? "",
      barcode: old?.barcode ?? "",
      merchantSkuCode: old?.merchantSkuCode ?? "",
      // 单位多半整件货一样：没有旧值时沿用第一行的，省得逐行敲「斤」
      saleUnit: old?.saleUnit ?? rows.value[0]?.saleUnit ?? "",
    };
  });
}

/** 套用模板：一次点选替代逐个手输，同时把 code 带进来（这是二期能做规格聚合的前提） */
function applyTemplate(tpl: SpecTemplate) {
  // 已有同名规格组就替换，避免点两次出来两个「重量」
  const exist = groups.value.findIndex((g) => g.name === tpl.name);
  // 空 options = 只填组名：留一个空档位给他自己写（与主维度预填同形）
  const row = tpl.options.length
    ? {
      name: tpl.name,
      options: tpl.options.map((o) => o.label),
      codes: tpl.options.map((o) => o.code),
      templateNo: tpl.templateNo,
    }
    : { name: tpl.name, options: [""], codes: [undefined], templateNo: tpl.templateNo };
  if (exist >= 0) groups.value[exist] = row;
  else if (groups.value.length >= 3) {
    uni.showToast({ title: t("goods.groupLimit"), icon: "none" });
    return;
  } else groups.value.push(row);
  rebuild();
}

/**
 * 推荐条的两个入口。
 *
 * <p>`applyTemplateEmpty` 只建组名 —— 与主维度预填同一条规矩：
 * **不替他填取值**。`applyTemplateWith` 带上他点的那一档，因为那是他自己选的，
 * 不是平台猜的；一步到位比「先建组、再进组里点档位」少一半动作，
 * 而多规格商品的第一档往往就是他要的那一档。
 */
function applyTemplateEmpty(tpl: SpecTemplate) {
  applyTemplate({ ...tpl, options: [] });
}

function applyTemplateWith(tpl: SpecTemplate, o: { code?: string; label: string }) {
  applyTemplate({ ...tpl, options: [o] });
}



// ── 十、规格维度 ────────────────────────────────────────────────────────────
//    平台模板 / 商家自存 / 按类目推荐，三个来源
/**
 * 推荐规格 = 平台模板。**商家自存的不算推荐** —— 那是他自己的历史，
 * 摊在最显眼处会盖住平台的统一口径（平台模板带 code，聚合靠它）。
 */
const suggestedSpecs = computed<SpecTemplate[]>(() =>
  templates.value.filter((tpl) => tpl.scope === "PLATFORM"),
);

/** 推荐规格这一组到底是「谁的常用」：有类目级就报类目名，否则报品类名 */
const suggestScope = computed(() => {
  const leaf = catPath.value[catPath.value.length - 1];
  const hasCatLevel = suggestedSpecs.value.some((t) => t.categoryNo);
  return hasCatLevel && leaf ? leaf.name : String(t(`goods.categoryType.${type.value}`));
});

/**
 * 拉规格模板。**要带上已选类目** —— 只传品类拿到的是兜底那批，
 * 而品类只有 3 个、二级类目有 32 个，STANDARD 一个就盖住 18 个：
 * 手机数码与鲜花会共用「包装：袋装/瓶装/罐装」，等于没有推荐。
 */
async function loadTemplates() {
  templates.value = await api
    .mSpecTemplates(type.value, categoryNo.value || undefined)
    .catch(() => []);
  // primeMainGroup 已移除：见 select() 里那段 —— 不自动建组，摆候选让他点
}

/**
 * 选完类目，**把主维度那一组先建出来**（组名填好，取值留空）。
 *
 * <p>此前平台配好的绑定只走到「摊开给他看」：模板卡片展开，他还得点一下才进规格组。
 * 而规格组名恰恰是建品最难的一步 —— 「这袋青菜该按什么分规格」比「有哪几档」难得多，
 * 平台已经替每个类目回答过了（主维度），却要商家自己再想一遍。
 *
 * <p><b>只填名，不预选取值。</b>预选「500g」的后果不是他发现填错，是他不假思索地留着：
 * 库里三千件商品整整齐齐写着 500g，而真实袋重是 400g、480g、一斤。
 * 那比空着更糟 —— 空着至少诚实，而「同规格比价」建立在这些数字真实的前提上，
 * 那正是平台养这个规格库的全部理由。规格名填错了他一眼看得出（「颜色」出现在
 * 一袋青菜上很刺眼），取值填错了没有任何视觉信号。
 *
 * <p>三条不动手的情形：已经有组了（他知道自己在干什么，或这是在编辑老商品）、
 * 没有主维度、这一组已经在了。
 */
function primeMainGroup() {
  /*
   * **选了类目才动手**。此前没有这道闸：一进新建页 `loadTemplates` 就跑一遍，
   * 那时还没选类目，拿回来的是按品类兜底的那批 —— 于是页面一打开就摆着一个
   * 「包装」空组，而商家还没说这是什么货。
   */
  if (groups.value.length || !categoryNo.value) return;
  /*
   * **只认主维度，不拿兜底模板顶上。**
   *
   * <p>试过放宽成「没有主维度就取这一类的第一条」，为的是覆盖那些还没配
   * 类目级模板的类目 —— 但那么做等于**每件新商品都从一个规格组开始**，
   * 价格与库存随之进入按 SKU 逐行填的模式。而社区店的货多半就是单规格
   * （一袋米、一瓶油、张姐的酱菜），等于为少数多规格商品给所有人加税。
   *
   * <p>更要紧的是兜底模板**不是平台对这一类的回答，只是对「标品/生鲜/服务」
   * 这个大类的猜测**：「包装」盖着 18 个二级类目，手机数码与鲜花共用
   * 「袋装/盒装/桶装/整箱」。拿猜测去代劳，会在库里留下一批
   * 「包装：（空）」的规格组，而平台养这个规格库全为了同规格比价。
   *
   * <p>覆盖率的问题在**数据侧**解决（给类目配模板），不在端上拿泛答案补。
   * 没配的类目走下面那条推荐 chip：一点即成组，代价一次点击。
   */
  const main = templates.value.find((t) => t.primary && t.scope === "PLATFORM");
  if (!main || groups.value.some((g) => g.name === main.name)) return;
  /*
   * **档位跟着一起带出来，不再只填组名。**
   *
   * 此前这里只填名、取值留空，理由写在上面那段注释里：预选「500g」会让商家
   * 不假思索地留着，于是库里三千件商品整整齐齐写着 500g，而真实袋重是 400g、
   * 一斤。那个判断针对的是**平台的猜测** —— 那时档位是平台按类目配死的，
   * 商家没有任何地方表达过「我这店卖哪几档」。
   *
   * 现在有了：「商品规格」页里他为每个类目**逐档确认过**留哪些、去掉哪些、
   * 叫什么名字（prd_merchant_spec_override）。带出来的是**他自己刚说过的话**，
   * 不是平台替他猜的。让他在建品页再点一遍，等于不认他刚才做的事。
   *
   * 不合适的那一档他一眼看得出（他自己删的那些根本不会出现在这里），
   * 而「撤销」就在旁边，整组去掉是一次点击。
   */
  groups.value.push({
    name: main.name,
    /*
     * **一个档位都不预选。** 上一版把本店确认过的那几档全填进去，
     * 而商家进来面对的是一排「已经替你选好」的东西 —— 删比选累，
     * 而且他很容易不假思索地留着，于是库里出现一批他并没有卖的规格。
     * 现在档位一律灰着摆出来，他点哪个是哪个。
     */
    options: [],
    codes: [],
    templateNo: main.templateNo,
  });
  /*
   * 记成「自动来的」：换类目时 `autoApplyDefaultSpec` 按这个判断能不能替换。
   * 不记的话，这一组会被当成商家手点的，换到一个配了类目级模板的类目也顶不掉它。
   */
  autoSpecNo.value = main.templateNo;
  rebuild();
}


/**
 * 取「还能加哪些维度」。
 *
 * <p><b>选完类目就取，不等他点开面板</b>：规格区末尾那行「这一类还能按 …」
 * 靠它渲染，懒加载的话那一行永远不出现 —— 而它恰恰是让商家知道
 * 「这一类不止一种分法」的唯一地方。
 *
 * <p>换了类目要重取：候选是按类目算的，留着上一类的会推错。
 */
async function loadPickableDims() {
  pickableDims.value = await api.mPickableDims(categoryNo.value || undefined).catch(() => []);
}

/** 挑中一个平台/自建维度：连同它的取值一起进来，值编号跟着走 */
function pickDim(tpl: SpecTemplate) {
  // 候选列表已经滤过一遍，这里再兜一道：同名即同一件事，编号不同是内部实现
  if (usedDimNames.value.has(tpl.name.trim())
      || groups.value.some((g) => g.templateNo === tpl.templateNo)) {
    uni.showToast({ title: t("goods.dimAlready"), icon: "none" });
    return;
  }
  /*
   * **档位默认全开**，与「我的规格」里加一个规格时同一条规矩：
   * 他加这个维度就是要用它，进来却是一排关着的档位，还得再点一遍才算数。
   * 不合适的那几档点掉就是了 —— 这一页只做减法。
   *
   * <p>没有档位可带的（自建维度刚建出来还没配值）才留一个空位。
   */
  // 同上：加进来的规格档位也一个不预选，灰着等他点
  groups.value.push({ name: tpl.name, options: [], codes: [], templateNo: tpl.templateNo });
  rebuild();
}

/**
 * 都不是，自己起一个名。**留着这条路，但把它放在最后** ——
 * 商家确实会有平台没想到的维度（「辣度」「打磨程度」），
 * 堵死它的结果是他退回「＋ 规格组」手输，那才是真正掉出聚合的那条路。
 */
/**
 * 去「商品规格」加新的规格或档位。
 *
 * <p>**新增只在那一处。** 那里加一次全店通用、有编号、参与跨店比价；
 * 在建品页手输只对这一件商品有效，而且从此掉出聚合 —— 代价看不见，
 * 所以不能把这条路留在这里让人顺手走。
 */
function gotoMySpecs() {
  uni.navigateTo({ url: ROUTES.mySpecs });
}

function removeGroup(i: number) {
  groups.value.splice(i, 1);
  rebuild();
}

/**
 * 自建一个规格维度：平台没有的那种（「辣度」）。
 *
 * <p>与「＋规格组」的差别是**它会落进规格库**（scope=MERCHANT），
 * 于是这家店下次建品还能选到它。代价要说清楚：不参与跨店比价。
 */
/**
 * 与输入的名字**近似**的既有维度。没有就返回 null。
 *
 * <p>只认两条：一方包含另一方（「辣」vs「辣度」）、长度相同且只差一个字
 * （「口味」vs「口感」）。**故意不做模糊匹配** —— 编辑距离放宽一格，
 * 「颜色」和「颜值」就会互相命中，而一个问错的确认框比不问更烦人：
 * 每次都弹的框，第三次起就没人读了。
 */
function nearestDimName(input: string): SpecTemplate | null {
  const a = input.trim();
  if (a.length < 2) return null;
  for (const d of pickableDims.value) {
    const b = d.name.trim();
    if (b === a) return d;                      // 完全同名：后端也会复用，但先问更清楚
    if (a.includes(b) || b.includes(a)) return d;
    if (a.length === b.length) {
      let diff = 0;
      for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) diff++;
      if (diff === 1) return d;
    }
  }
  return null;
}

/**
 * 面板里的三段。**顺序就是建议顺序**：这一类目平台配好的最该选，
 * 通用维度次之，自己建过的放最后（它们不参与跨店聚合）。
 */
/** 「通用规格」那一组是否展开。默认收起 —— 见模板里那段注释 */
const showUniversalDims = ref(false);

/**
 * 已经在用的维度**不再出现在候选里**。
 *
 * <p>此前只在 `pickDim` 里按 `templateNo` 拦一道，而同一个「重量」在类目绑定
 * 与候选池里是**两个不同的 templateNo**（一个来自 /biz/spec-templates，
 * 一个来自 /biz/spec-dims）—— 于是拦不住，点一下就多出第二个「重量」组，
 * 两组档位还不一样（一组是本店确认过的，一组是平台原样）。
 *
 * <p><b>按名字判重</b>：两个同名维度在商家眼里就是同一件事，编号不同是我们的内部事情。
 */
const usedDimNames = computed(
  () => new Set(groups.value.map((g) => g.name.trim()).filter(Boolean)),
);

function unused(list: SpecTemplate[]): SpecTemplate[] {
  return list.filter(
    (d) => !usedDimNames.value.has(d.name.trim())
      && !groups.value.some((g) => g.templateNo === d.templateNo),
  );
}

/**
 * 本类目还没用上的维度 —— **摆在眼前，不藏在面板后面**。
 *
 * <p>平台已经替这一类回答过「该按什么分」（蔬菜：重量 / 包装 / 等级），
 * 而默认只带出主维度那一个。其余几条藏在「＋ 规格」两层之后的话，
 * 商家会以为这一类只能按一种方式分 —— 那是平台配置白做了。
 *
 * <p><b>只摆出来让他点，不自动加。</b>三条全自动带出来意味着每件菜都变成
 * 一堆 SKU（3 × 4 × 4），那是帮倒忙。
 */
const moreFromCategory = computed(() =>
  unused(pickableDims.value.filter((d) => d.categoryNo)),
);

/**
 * 通用与自建的维度 —— **收在「更多」后面**。
 *
 * <p>`universal` 的判据是「值的含义跨类目一致」（给跨店聚合用），
 * 不是「哪些类目该用它」，所以手机数码下面会并排摆着口味、等级、尺码。
 * 不拦着他选，但也不把二十来个无关维度摆在眼前。
 */

const moreOther = computed(() => {
  /*
   * **还要跟前面那批去重。** 通用池里的「包装」与类目绑定的「包装」是
   * 两个不同的 templateNo，`unused()` 只挡「已经在用的」，挡不住这一对 ——
   * 展开「更多」后会看到两个「＋ 包装」，点哪个都对，但看起来像出了错。
   * 与判重同一条规矩：同名即同一件事。
   */
  const shown = new Set(moreFromCategory.value.map((d) => d.name.trim()));
  const seen = new Set<string>();
  return unused([
    ...pickableDims.value.filter((d) => !d.categoryNo && d.scope === "PLATFORM"),
    ...pickableDims.value.filter((d) => d.scope === "MERCHANT"),
  ]).filter((d) => {
    const n = d.name.trim();
    if (shown.has(n) || seen.has(n)) return false;
    seen.add(n);
    return true;
  });
});

/* ↑ 这一条 2026-08-27 从「十一、参数」搬回来：它算的是**还能加哪些规格维度**（模板里挂在 `pickDim` 那一排上），
   与参数无关，只是当初落错了节。**分节标题说了假话就不如没有** ——上一轮已经为同样的事
   把 `loadCategories`/`prunable` 搬回过一次。 */

// ── 十一、参数 ──────────────────────────────────────────────────────────────
//    不参与组合的属性（产地、材质）—— 与规格分开的理由见 propDims
/*
 * 商品参数（V250）：产地 / 保质期 / 材质这一类。
 *
 * <p><b>与销售规格分开的理由是性质，不是范围</b>：规格进笛卡尔积生成 SKU，
 * 每一档要单独定价与备库存；参数一项也不进，买家不用挑，只是看。
 * 混在一起的话「本地 × 500g」变成一个要单独定价备货的行，
 * 而他只想说「这袋菜是本地的」。
 */
const propDims = ref<SpecTemplate[]>([]);
/** dimNo → 已选的那一项。量纲型没有候选值，存的是他自己填的文字 */
const paramValues = ref<Record<string, GoodsParam>>({});

async function loadProps() {
  propDims.value = await api.mSpecProps(categoryNo.value || undefined).catch(() => []);
}

/*
 * **参数可以在这里现加**（规格不行）。
 *
 * <p>两者的代价不一样。规格进笛卡尔积、要单独定价备库存，在建品页现造一个
 * 只对这一件商品成立的维度，等于给自己开一条以后对不上账的路 ——
 * 所以规格一律去「商品规格和参数」加一次，全店通用。
 * 而参数是写给买家看的一行字：「海拔 1200 米」平台不会替他想到，
 * 他也不该为了标一行字先跳出去一趟、回来再重填一遍这件货。
 *
 * <p><b>但加出来的东西是一样的</b>：走同一个 `mAddSpecDim(PROP)` 落进规格库、
 * 拿到编号、挂到这个类目下 —— 下次建同类的品它就在那儿了。
 * 「只在这一件商品上有效」的私有字符串一条都不造，那是掉出聚合的那条路。
 */
const addingParam = ref(false);
const newParam = ref("");

/** 正在给哪个参数加值；null = 没在加 */
const addingValueFor = ref<SpecTemplate | null>(null);
const newParamValue = ref("");
/** 平台在这个参数下的**全部**值。分成「能加的」与「已经在用的」两排，见 openParamValue */
const paramPool = ref<SpecOption[]>([]);
/** 池子没取到。**与「平台没配」必须分开说** —— 两者在界面上都是一片空白 */
const paramPoolFailed = ref(false);

/**
 * 打开「加可选值」。**取的是平台这一项的全部值，不是「减去已有的」之后那点余数。**
 *
 * <p>此前这里直接把 `全部 − 已有` 存进 `paramCands`，于是当类目已经把平台该项的值
 * 全给了（「产地」平台就三个值，类目全给了），候选恒为空 —— 弹层里只剩一个
 * 「新建可选值」输入框，**看不到系统里有哪些值，也没有一句话说为什么**。
 * 商家的描述是「无法添加系统中的值」，而他看到的确实就是这样。
 *
 * <p>现在池子整份留着，由下面三个 computed 分成「能加的」与「已经在用的」，
 * 两排都摆出来 —— **「系统里有什么」和「你还能加什么」是两个问题**，
 * 只回答后者的话，前者就永远无解。
 */
async function openParamValue(d: SpecTemplate) {
  addingValueFor.value = d;
  newParamValue.value = "";
  paramPool.value = [];
  paramPoolFailed.value = false;
  try {
    paramPool.value = await api.mDimValues(d.templateNo);
  } catch {
    // 吞掉异常会让「取不到」和「平台没配」长成同一屏 —— 那句话就成了假话
    paramPoolFailed.value = true;
  }
}

/** 这一项当前已经能选的值（类目给的 + 他加过的） */
const paramHave = computed(
  () => new Set((addingValueFor.value?.options ?? []).map((o) => o.code ?? o.label)),
);
/** 平台有、这一类还没有的 —— 点一下就用上 */
const paramCands = computed(() => paramPool.value.filter((o) => !paramHave.value.has(o.code ?? o.label)));
/** 平台有、上面那排已经列着的。**照样摆出来**：他要确认的是「系统里有没有」 */
const paramUsed = computed(() => paramPool.value.filter((o) => paramHave.value.has(o.code ?? o.label)));

/*
 * 副标题要说当下这一屏的实话。**四种情况说四句** —— 此前是三种，
 * 而漏掉的那一种恰恰是最常见的：平台有值、但都已经在上面了。
 * 那一屏此前不说话，于是看起来和「平台什么都没配」一模一样。
 *
 *   取不到       → 「没取到平台的可选值，重开一次」  ← 此前被 catch 吞成「没配」
 *   有能加的     → 「平台可选值」
 *   池子非空但全在用 → 「平台这一项的值都已经在上面了」  ← 此前不说话
 *   池子是空的   → 「该参数暂无平台可选值」
 *
 * 一律写死一句的话，总有一屏在说假话 —— 而假话比没话更贵。
 */
const paramSheetHint = computed(() => {
  if (paramPoolFailed.value) return t("goods.paramPoolFailed");
  if (paramCands.value.length) return t("goods.paramMore");
  if (paramPool.value.length) return t("goods.paramAllAdded");
  return t("goods.paramFillHint");
});

function closeParamValue() {
  addingValueFor.value = null;
  newParamValue.value = "";
  paramPool.value = [];
  paramPoolFailed.value = false;
}

/**
 * 挑一个平台已有的值。**只落在这件货身上，不改本店配置。**
 *
 * <p>他这一下的意思是「这袋菜是云南的」，不是「以后蔬菜这一类都要有云南这一档」。
 * 顺手把它写进类目覆盖的话，全店所有蔬菜的参数列表都跟着变了 ——
 * 而他从没这么说过。要改那个，去「商品规格和参数」，那里的每一下都是全店的。
 */
function pickParamCand(o: SpecOption) {
  const d = addingValueFor.value;
  if (!d) return;
  pickParam(d, o);
  closeParamValue();
}

async function confirmAddParam() {
  const name = newParam.value.trim();
  if (!name || !categoryNo.value) return;
  try {
    const dim = await api.mAddSpecDim(name, [], "PROP");
    /*
     * **挂到这个类目下**，否则它只是躺在规格库里：下次进来这一页看不到它，
     * 而他明明刚建过 —— 与「我的规格」里加一个是同一条路，所以用同一个载荷拼装。
     *
     * <p>当前状态从**这两条按类目取的接口**拿，不从「本店货架类目」那份拿：
     * 这件货的类目不一定在他的货架上（货架是他摆出来卖的那几类，
     * 而建品页可以选到任何类目）。拿不到卡就静静不保存 —— 加完什么都没发生，
     * 而这条路上没有任何东西会报错。实测就是这么撞上的（蔬菜不在货架上）。
     *
     * <p>先取一份当前状态是因为后端先清后写：少带一条就抹掉一条。
     */
    const [dims, props] = await Promise.all([
      api.mSpecTemplates(undefined, categoryNo.value).catch(() => []),
      api.mSpecProps(categoryNo.value).catch(() => []),
    ]);
    await api.mSaveSpecOverride(
      categoryNo.value,
      buildSpecOverride({
        g: { categoryNo: categoryNo.value, categoryName: "", dims, props },
        added: dim,
      }),
    );
    await loadProps();
    addingParam.value = false;
    newParam.value = "";
    // 撞上平台已有的同名参数时后端直接返回它 —— 说一声，否则他以为自己白填了
    if (dim.name !== name) {
      uni.showToast({ title: t("mySpecs.valueMerged", { name: dim.name }), icon: "none" });
    }
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/**
 * 给一个没有候选值的参数填一个值。
 *
 * <p><b>填的是规格库里的一档，不是这件货身上的一个字符串。</b>
 * 「海拔」这种量纲型平台不会枚举值，但他填的「1200 米」仍然要拿到编号 ——
 * 否则三家店的「1200米」「1200 m」「一千二」永远聚不到一起，
 * 而那正是养这个库的全部理由。落库之后它也成了下一件货的候选。
 */
async function confirmParamValue() {
  const d = addingValueFor.value;
  const text = newParamValue.value.trim();
  if (!d || !text) return;
  try {
    const added = await api.mAddSpecValue(d.templateNo, text);
    await loadProps();
    const fresh = propDims.value.find((x) => x.templateNo === d.templateNo) ?? d;
    pickParam(fresh, { code: added.code || added.valueNo, label: added.label });
    closeParamValue();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 点一下选中/取消。**再点一次取消** —— 不给「清空」按钮，一排 chip 自己就是开关 */
function pickParam(dim: SpecTemplate, o: SpecOption) {
  const cur = paramValues.value[dim.templateNo];
  if (cur && cur.label === o.label) {
    const next = { ...paramValues.value };
    delete next[dim.templateNo];
    paramValues.value = next;
    return;
  }
  paramValues.value = {
    ...paramValues.value,
    /*
     * **不填 valueNo。** 端上手里只有 code —— 它才是跨店可比的那个稳定编码，
     * valueNo 是库里的行号。伪造一个行号发上去，后端存下来就是一条对不上的引用。
     * 真要它的话该由后端按 dimNo + code 反查（与规格值那侧的 resolveValueNos 同一条路）。
     */
    // name 一起存：买家页要显示「产地：本地」，只有 dimNo 的话那一行是 `SD_ORIGIN: 本地`。
    // 与 specGroups 的组名同一口径 —— 快照，商家事后改本店叫法不影响已建好的商品
    [dim.templateNo]: { dimNo: dim.templateNo, name: dim.name, code: o.code, label: o.label },
  };
}




/**
 * 点一个档位填进去。**优先填进第一个空格子**，没有空格子才追加 ——
 * 否则自动建组留下的那个空输入框会一直杵在列表最上面，
 * 而他点了三下之后要回过头去删它。
 */

// ── 十二、选项勾选 ──────────────────────────────────────────────────────────
//    维度内选值，并上「已经在用的」以兼容老数据
/**
 * 这一组能出现的**全部**档位：本店规格库里配的 ∪ 这件商品已经在用的。
 *
 * <p>并上「已经在用的」是为了老数据：早年手输的值不在规格库里，
 * 不并的话它们会从界面上消失，而商品身上还带着 —— 他会以为规格丢了。
 */
function allOptionsOf(gi: number): SpecOption[] {
  const g = groups.value[gi];
  if (!g) return [];
  const tpl = templates.value.find((t) => t.templateNo === g.templateNo)
    ?? pickableDims.value.find((t) => t.templateNo === g.templateNo);
  const out: SpecOption[] = [...(tpl?.options ?? [])];
  const known = new Set(out.map((o) => o.label));
  g.options.forEach((label, i) => {
    const l = label.trim();
    if (l && !known.has(l)) {
      out.push({ label: l, code: g.codes?.[i] } as SpecOption);
      known.add(l);
    }
  });
  return out;
}

/** 这一档这件商品有没有 */
function optionOn(gi: number, o: SpecOption): boolean {
  return !!groups.value[gi]?.options.some((x) => x.trim() === o.label);
}

/**
 * 点一下开合这一档。
 *
 * <p><b>建品页只做减法。</b>新的规格与档位统一在「商品规格」里加 ——
 * 那里加一次全店通用，而在建品页手输的值没有值编号，
 * 三家店的「500g」「五百克」「0.5kg」永远聚不到一起，比价也就不成立。
 * 这里能点回来的只是**本店已有的那些**，不是新造。
 *
 * <p>顺序始终按规格库的顺序重排，不按他点击的先后 ——
 * 否则同一个维度在不同商品上顺序不同，价格表看起来像是乱的。
 */
function toggleOption(gi: number, o: SpecOption) {
  const g = groups.value[gi];
  if (!g) return;
  const on = optionOn(gi, o);
  const next = new Set(g.options.map((x) => x.trim()).filter(Boolean));
  if (on) {
    if (next.size <= 1) return;   // 最后一档不给关：一个档位都没有的规格组没有意义
    next.delete(o.label);
  } else {
    next.add(o.label);
  }
  const all = allOptionsOf(gi);
  g.options = all.filter((x) => next.has(x.label)).map((x) => x.label);
  g.codes = all.filter((x) => next.has(x.label)).map((x) => x.code || undefined);
  rebuild();
}


// ── 十三、批量填充 ──────────────────────────────────────────────────────────
//    价与库存拆成两个动作，理由见 applyBulkPrice
/**
 * 批量填价。**拆成价与库存两个动作** —— 两者现在分属两张卡，
 * 一个按钮同时改两边的话，商家在库存卡点「批量填入」会顺带改掉价格。
 */
function applyBulkPrice() {
  if (!bulk.value.price) return;
  rows.value = rows.value.map((r) => ({
    ...r,
    // 批量只作用在**当前市场**：避免把美元价误批到人民币上
    priceMajor: { ...r.priceMajor, [market.value]: bulk.value.price },
  }));
  uni.showToast({ title: t("goods.bulkDone"), icon: "none" });
}

/**
 * 库存加减。**库存是每天都在动的数**，最常见的改动是「卖掉两袋」——
 * 点两下比调出键盘、全选、重打快得多；数字仍然可以直接键入。
 *
 * <p>不许减到负数：库存写成 -5 之后 C 端的置灰与到货提醒逻辑全乱。
 */
function stepStock(r: Row, delta: number) {
  const next = Math.max(0, (Number(r.stock) || 0) + delta);
  r.stock = String(next);
}

/** 批量填成本。与批量填价分开，同一个理由：两张卡各管各的 */
function applyBulkCost() {
  if (!bulk.value.cost) return;
  rows.value = rows.value.map((r) => ({ ...r, costMajor: bulk.value.cost }));
}

function applyBulkStock() {
  if (!bulk.value.stock) return;
  rows.value = rows.value.map((r) => ({ ...r, stock: bulk.value.stock }));
  uni.showToast({ title: t("goods.bulkDone"), icon: "none" });
}

/**
 * 从门店设置回来时**重拉一次开通状态**。
 *
 * <p>此前这一句只写在 `toStoreScope` 的注释里，页面**根本没有 onShow**：
 * 商家点「商家自送 · 未开」→ 去开通 → 回来一看还是「未开」，再点还是那句话。
 * 他做对了每一步，界面却告诉他没做过 —— 只能怀疑是开通没生效，
 * 而实际上开通早就成功了，只是这一页手里还攥着进来那一刻的旧名单。
 *
 * <p>首次进入时 onLoad 已经拉过一遍，这里会再拉一次；多一次请求换掉这个死角，
 * 值。加载中不清空旧值，所以不会闪。
 */
onShow(() => {
  if (channelsLoaded.value) void loadStoreChannels();
  /*
   * **规格也要重拉**，理由与送货方式一模一样：商家可能刚从「我的规格」回来，
   * 在那边给这一类停了一个维度、改了本店叫法、或者加了一个维度进来。
   * 不重拉的话，建品页手里还是**进来那一刻**的那份 —— 他会以为那一页白设了，
   * 而这正是今天在送货方式上踩过的同一个坑（注释写着会重拉，其实没有 onShow）。
   *
   * <p>只在已经选了类目时拉：没选类目时那份是「他自己的常用」，与门店设置无关。
   * `loadTemplates` 里的 `primeMainGroup` 有 `groups.length` 闸，
   * 已经建过组的不会被它覆盖。
   */
  if (categoryNo.value) void loadTemplates();
});

onLoad(async (q) => {
  loadRecentCats();
  // 用过一次条码/货号的人多半一直要用，不必每次去点
  try {
    externalOn.value = uni.getStorageSync(EXTERNAL_KEY) === true;
  } catch { /* 读不到就按默认收着 */ }
  if (!q?.goodsNo) {
    await Promise.all([loadTemplates(), loadCategories(), loadStoreChannels()]);
    return;
  }
  /*
   * **商品详情要与三个预载并行取，而不是排在它们后面。**
   *
   * 此前是先 `await` 类目树 / 模板 / 门店通道，再去拿这件商品 —— 于是打开
   * 「编辑」之后有一秒多，页面上是一张**空表单**：标题空、类目空、价格空，
   * 底下红字写着「待填写：商品名称、类目、价格」，两个按钮都是灰的，
   * 连按钮文案都误判成「保存草稿」（isDraft 还是默认值）。
   * 商家看到的就是「改商品，保存并提交按钮是灰的」—— 他不会知道那是加载中，
   * 因为页面没有任何一处说自己在加载。
   *
   * 并行之后这段时间少一半；剩下的那一半用 `hydrating` 盖住（见 `missing` 与按钮），
   * 让它显示「读取中」而不是一份假的待填清单。**说错话比不说话更糟。**
   */
  hydrating.value = true;
  goodsNo.value = q.goodsNo;
  // finally 而不是 try 包住整段：下面的回填全是同步赋值，中间不会渲染，
  // 而 `.finally` 让失败时也不会把页面永远卡在「读取中」
  const [g] = await Promise.all([
    api.mGoodsDetail(q.goodsNo),
    loadTemplates(),
    loadCategories(),
    loadStoreChannels(),
  ]).finally(() => { hydrating.value = false; });
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
  // 详情图与轮播图同理：保存整份覆盖，不回显就等于「打开编辑页再保存一次就清空」
  detailImages.value = [...(g.detailImages ?? [])];
  /*
   * 商品参数要回显 —— 保存是整份覆盖，不回显就等于
   * 「打开编辑页再保存一次，参数全没了」。与轮播图、三语原文是同一个形状的故障
   * （都不报错，只是数据静静少了一截）。
   */
  paramValues.value = Object.fromEntries((g.params ?? []).map((x) => [x.dimNo, x]));
  isDraft.value = g.status === "DRAFT";
  type.value = g.type;
  categoryNo.value = g.categoryNo ?? "";
  catPath.value = categoryNo.value ? findPath(categoryTree.value, categoryNo.value) : [];
  // 参数的候选也要取：不取的话编辑页那一段是空的，而商品身上明明带着值
  void loadProps();
  /*
   * **这件货身上有条码/货号/单位就自动展开** —— 收起会让他以为自己填的没了。
   * 与「已填过参数就展开」「已有规格组就展开」是同一条。
   */
  if (rows.value.some((r) => r.barcode || r.merchantSkuCode || r.saleUnit)) {
    externalOn.value = true;
  }
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
  // 已配过拼团的商品，进来就该是打开的 —— 否则那两个值存在却看不见
  groupBuyOpen.value = Boolean(g.groupBuy);
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
      originMajor: k.originPrice ? toMajor(k.originPrice) : "",
      nominalGram: k.nominalGram ? String(k.nominalGram) : "",
      costMajor: k.costPrice ? toMajor(k.costPrice) : "",
      barcode: k.barcode ?? "",
      merchantSkuCode: k.merchantSkuCode ?? "",
      saleUnit: k.saleUnit ?? "",
    };
  });
});


// ── 十四、保存 ──────────────────────────────────────────────────────────────
//    存草稿 / 存并提交审核
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
      // 详情图同理：空数组 = 清空，不传 = 不改。删光了不发就删不掉
      detailImages: detailImages.value,
      /*
       * 商品参数。**整份覆盖**（与 detailImages 同一口径）——
       * 只发改过的那几条会让「取消一个参数」变成不可能：后端分不出
       * 「他没动这一项」与「他把这一项去掉了」。
       */
      params: Object.values(paramValues.value),
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
      /*
       * 生鲜段入口关着的时候**一个字段都不发**（`undefined` = 不改）。
       * 发一份空值上去会把老商品已有的截单时间与产地清掉，而界面上
       * 那几行根本没显示过 —— 商家不会知道是自己保存时抹掉的。
       */
      fresh: SHOW_FRESH_FIELDS && isFresh.value
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
          /*
           * **留空 = 不改，0 = 清掉**（契约里写死的语义）。
           * 所以空串必须发 undefined 而不是 0 —— 发 0 会把已有的划线价抹掉，
           * 而商家只是没碰这一格。
           */
          originPrice: r.originMajor.trim() ? toMinor(r.originMajor) : undefined,
          nominalGram: r.nominalGram.trim() ? Number(r.nominalGram) || 0 : undefined,
          // 成本价同一口径：空串 = 不改（他没碰这一格），填 0 = 清掉
          costPrice: r.costMajor.trim() ? toMinor(r.costMajor) : undefined,
          /*
           * 外部身份三件套：**原样发，包括空串** —— 后端「不传 = 不改，空串 = 清空」，
           * 而端上这三格永远是有值的（空字符串），所以发的就是他此刻看到的那份。
           * 判空改成 undefined 的话，他把货号删掉就删不掉了。
           */
          barcode: r.barcode.trim(),
          merchantSkuCode: r.merchantSkuCode.trim(),
          saleUnit: r.saleUnit.trim(),
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
    // 记一笔类目：下次建品「最近用过」里就有它，一点就选中
    rememberCat();
    uni.showToast({ title: t(thenSubmit ? "goods.submitted" : "common.saved"), icon: "none" });
    setTimeout(() => uni.navigateBack(), 600);
  } catch (e) {
    /*
     * 后端对「勾了本店没开的送货方式」是**硬拒**（70013，方案 v4 的上架校验）。
     * 端上那份名单可能已经过时（他在别的设备上关掉了这一路），所以这一条要
     * 单独说清楚：通用的「操作失败」会让他反复点保存，而该做的是去开通。
     */
    if ((e as { code?: number }).code === FULFILLMENT_NOT_SUPPORTED) {
      void loadStoreChannels();   // 顺手把名单刷新到最新，chip 上立刻能看出是哪一路
      if (
        await confirm({
          title: String(t("goods.fulfillmentRejected")),
          hint: String(t("goods.fulfillmentRejectedHint")),
          confirmText: String(t("goods.toStoreScope")),
        })
      ) {
        toStoreScope();
      }
      return;
    }
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
    <!-- 页内不再重复标题：`sh-scaffold` 已用同一个 title-key 写进导航栏，
         页面顶部再画一遍 `txt-display` 是一字不差的重复，白占首屏一行 -->
    <view class="sh-card">
      <!--
        分区标题。此前**整页只有规格卡与 SKU 卡有标题**，前面 11 个字段组挤在
        一张无标题的卡里 —— 而字段标签（.field__label 26rpx 灰）与说明文字
        （.sh-muted 26rpx 灰）是同字号同颜色，于是「哪里是一节的开头」无从判断。
        分成四节各给一个 txt-title，层级才立得起来：标题 34rpx 深 > 标签 26rpx 深 > 说明 26rpx 灰。
      -->
      <text class="txt-title sh-mb-md sec__h">{{ $t("goods.secBasic") }}</text>

      <!--
        商品图。**主图就是第一张** —— 此前主图与轮播图是两个相邻的图片控件，
        商家每传一张都要先回答「这张算主图还是轮播」，而那个问题来自数据表
        （契约里 `cover` 与 `images` 是两列），不来自他要做的事。

        <p>合并的只是**界面**：保存时照旧拆回两个字段（见 `photos`），
        后端与 C 端零改动。老商品的 emoji 封面照常显示在第一格 ——
        `sh-cover` 按值分流，不必逼商家先换实拍图才能改别的。
      -->
      <view class="field">
        <view class="field__head">
          <text class="txt-strong field__label">{{ $t("goods.photos") }}</text>
          <text class="sh-muted imgs__n">
            {{ $t("goods.imagesCount", { n: photos.length, m: PHOTO_LIMIT }) }}
          </text>
        </view>
        <sh-uploader
          :list="photos"
          :max="PHOTO_LIMIT"
          :uploading="uploading"
          removable
          :badge="String($t('goods.coverBadge'))"
          @add="addImages"
          @remove="removePhoto"
          @tap-item="tapPhoto"
        ></sh-uploader>
        <text class="sh-muted hint">{{ $t("goods.photosHint") }}</text>
      </view>

      <!-- 三语：一个框 + 语言 tab，不给三个框并排 -->
      <view class="field">
        <view class="field__head">
          <text class="txt-strong field__label">{{ $t("goods.name") }}</text>
          <view v-if="MULTI_LANG_UI" class="langs">
            <text
              v-for="l in LANGS"
              :key="l.id"
              class="sh-chip"
              :class="{
                'sh-chip--primary': lang === l.id,
                'is-empty': l.id !== 'zh-CN' && !title[l.id].trim(),
              }"
              @tap="lang = l.id"
            >
              {{ $t(l.key) }}
            </text>
          </view>
        </view>
        <!--
          **预填的名称就是一个普通输入框** —— 不标来源、不加确认步骤。
          识别本来就不准，把它包装成一件要商家判断的事，等于每建一个商品多一道判断题。
          换来的是把改的成本压到最低：右边那个 ✕ 一下清空，长名不用逐字删。
        -->
        <view class="inline">
          <input maxlength="64" v-model="title[lang]" class="field__input sh-fill" />
          <sh-icon-btn v-if="title[lang]" class="inline__clear" name="close"
            color="var(--sh-sub)" @tap="title[lang] = ''"></sh-icon-btn>
        </view>
        <!--
          标准品降成名称下面的**一行入口**（TDD-标准品库）。

          <p>此前它是与「商品名称」平级的一个字段，带标签、带说明，占了三行 ——
          可它既不是要填的内容，也不是必经的步骤：标准库对「张姐家的酱菜」
          永远无效，而那类货是这个平台的一部分主力。
          现在它挨着名称（正是它要替你填的那一栏），搜不到就直接往下打字。
        -->
        <view v-if="stdNo" class="std-on">
          <text class="txt-sub">{{ $t("goods.fromStd", { s: stdTitle || stdNo }) }}</text>
          <text class="txt-caption" @tap="detachStd">{{ $t("goods.detachStd") }}</text>
        </view>
        <text v-else class="sh-link std-link" @tap="showStd = true">{{ $t("goods.pickStd") }}</text>
      </view>
      <view class="field">
        <!--
          去掉 placeholder：标签已经是深色半粗，框里再写一遍就是同一句话说两次；
          而 placeholder 一打字就消失 —— 「选填」这种**始终成立**的事实不该放在那里。
        -->
        <view class="field__head">
          <text class="txt-strong field__label">{{ $t("goods.subtitle") }}</text>
          <text class="sh-muted">{{ $t("goods.optional") }}</text>
        </view>
        <view class="inline">
          <input maxlength="64" v-model="subtitle[lang]" class="field__input sh-fill" />
          <sh-icon-btn v-if="subtitle[lang]" class="inline__clear" name="close"
            color="var(--sh-sub)" @tap="subtitle[lang] = ''"></sh-icon-btn>
        </view>
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
    </view>

    <!--
      图文详情独立成卡：正文与**详情图**说的是同一件事（这个商品详细长什么样），
      此前它们跟在「商品信息」里名称、副标题后面，中间还隔着标准品入口 ——
      商家要在两处描述同一件事，而两处都不像是同一节。
    -->
    <view class="sh-card sh-mt-sm">
      <text class="txt-title sh-mb-md sec__h">{{ $t("goods.detail") }}</text>

      <!--
        图文详情：**纯文本长文**，不做富文本 —— 手机端做不出像样的富文本编辑，
        而收 HTML 就要在三端各做一次消毒，漏一处就是 XSS。
      -->
      <view class="field">
        <view class="field__head">
          <!-- 卡片标题已经是「图文详情」，这里再写一遍就是同一句话连着出现两次；
               叫「正文」才说清它与同卡里的「详情图」是什么关系 -->
          <text class="txt-strong field__label">{{ $t("goods.detailBody") }}</text>
          <!--
            自动生成。**结果只填进这个框，不直接保存** ——
            模型不知道这家店真实的产地与保质期，一键写进详情
            等于替商家做了他没做过的承诺。让他改，比让他从空白开始容易得多。
          -->
          <text class="sh-link" @tap="genDetail">
            {{ generating ? $t("goods.genDetailing") : $t("goods.genDetail") }}
          </text>
        </view>
        <!--
          **随内容长高**。此前框高写死 140rpx，扣掉内边距只看得见两行半，
          而这个字段收 2000 字 —— 写到第三行就看不见上一句，校对只能往回滚。
          起步 6 行、随字数长，长到屏高六成为止（再长就该翻页了，不该继续吃屏）。
        -->
        <textarea
          v-model="detail"
          class="field__area field__area--grow"
          :placeholder="$t('goods.detailPh')"
          :maxlength="2000"
          auto-height
        />
        <!-- 字数常驻。不写的话，商家要一直写到第 2000 字才知道有上限 -->
        <text class="sh-muted area-len">{{ $t("goods.detailLen", { n: detail.length, m: 2000 }) }}</text>
      </view>

      <!--
        详情图：正文**下面**那一段长图，与顶部轮播是两回事。

        <p>形状上也分开：轮播是一排方格，详情图是一列窄高的格子并带上下箭头 ——
        长图是有次序的（封面页 → 参数页 → 售后页），传错了要能换，
        而不是只能全删重传。
      -->
      <view class="field">
        <view class="field__head">
          <text class="txt-strong field__label">{{ $t("goods.detailImages") }}</text>
          <text class="sh-muted imgs__n">
            {{ $t("goods.imagesCount", { n: detailImages.length, m: DETAIL_IMAGE_LIMIT }) }}
          </text>
        </view>
        <view class="dimgs">
          <view v-for="(img, i) in detailImages" :key="img + i" class="dimgs__row">
            <sh-cover class="dimgs__img" :src="img"></sh-cover>
            <text class="txt-caption dimgs__i">{{ i + 1 }}</text>
            <view class="dimgs__ops">
              <view class="txt-caption txt-bold mini" @tap="moveDetailImage(i, -1)"><sh-icon name="chevronUp" :size="24" color="var(--sh-primary-text)"></sh-icon></view>
              <view class="txt-caption txt-bold mini" @tap="moveDetailImage(i, 1)"><sh-icon name="chevronDown" :size="24" color="var(--sh-primary-text)"></sh-icon></view>
              <view class="txt-caption txt-bold mini" @tap="removeDetailImage(i)"><sh-icon name="close" :size="24" color="var(--sh-primary-text)"></sh-icon></view>
            </view>
          </view>
          <view
            v-if="detailImages.length < DETAIL_IMAGE_LIMIT"
            class="dimgs__add"
            @tap="addDetailImages"
          >
            <text v-if="uploading" class="dimgs__wait">…</text>
            <sh-icon v-else name="plus" :size="40" color="var(--sh-sub)"></sh-icon>
          </view>
        </view>
        <text class="sh-muted hint">{{ $t("goods.detailImagesHint") }}</text>
      </view>

    </view>

    <view class="sh-card sh-mt-sm">
      <text class="txt-title sh-mb-md sec__h">{{ $t("goods.secCategory") }}</text>

      <view class="field">
        <text class="txt-strong field__label">{{ $t("goods.category") }} *</text>
        <!--
          **两级平铺，不再逐级下钻。**

          此前是一个弹层，一次只看得见一层：商家要改个类目，先点开、再连点返回
          往上爬；识别自动填好的路径更糟 —— 他没点过任何一级，却要按两次返回
          才看得到选项。平台类目降到两级（V168）之后，父与子一屏放得下，
          那层弹层就只剩成本。
        -->
        <!--
          最近用过。**摆在最外面而不是藏进类目列表** —— 一家店的货高度集中，
          第二次建品要选的那一档多半就在这三五个里，一点就换。
          识别填错了、或者压根没识别出来，这一行都是最快的路。
        -->
        <!--
          最近用过。一家店的货高度集中，第二次建品要选的那一档多半就在这三五个里。
          识别填错了、或者压根没识别出来，这一行都是最快的路。
        -->
        <view v-if="recentCats.length" class="cat-lv">
          <text class="txt-caption cat-lv__t">{{ $t("goods.recentCats") }}</text>
          <view class="cat-lv__opts">
            <text
              v-for="c in recentCats"
              :key="c.categoryNo"
              class="sh-chip"
              :class="{ 'sh-chip--primary': categoryNo === c.categoryNo }"
              @tap="pickRecent(c.categoryNo)"
            >
              {{ c.name }}
            </text>
          </view>
        </view>

        <view class="cat-lv">
          <text class="txt-caption cat-lv__t">{{ $t("goods.categoryL1") }}</text>
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
          <text class="txt-caption cat-lv__t">{{ $t("goods.categoryL2") }}</text>
          <view class="cat-lv__opts">
            <text
              v-for="c in children"
              :key="c.categoryNo"
              class="sh-chip"
              :class="{
                'sh-chip--primary': categoryNo === c.categoryNo,
                'sh-chip--warning': SHOW_CATEGORY_GATE && gateOf(c) && !gateOf(c)?.granted,
              }"
              @tap="pickChild(c)"
            >
              {{ c.name
              }}<template v-if="SHOW_CATEGORY_GATE && gateOf(c) && !gateOf(c)?.granted">
                · {{ $t("goods.needCert") }}</template>
            </text>
          </view>
        </view>

        <text v-if="categoryLabel" class="txt-sub cat-lv__sel">{{ categoryLabel }}</text>
        <!--
          缺证的提示放在**选完之后**、而不是拦住不让选：草稿归到一个还没批下来的
          类目下是合法的，他可能正准备去申请。真正拦在上架那一刻。
        -->
        <text v-if="SHOW_CATEGORY_GATE && pickedGate && !pickedGate.granted" class="txt-caption cat-lv__gate">
          {{ $t("goods.gateMissing", { s: pickedGate.qualification || $t("goods.gateCert") }) }}
        </text>
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
        <text class="txt-strong field__label">{{ $t("goods.fulfillment") }} *</text>
        <view class="chips">
          <!--
            **没开通的那一路置灰，不隐藏** —— 隐藏的话商家会以为平台不支持快递，
            而其实只是他自己没开。灰着并写明「未开」，右边给一条去开的路。
          -->
          <text
            v-for="f in fulfillmentOptions"
            :key="f"
            class="sh-chip"
            :class="{
              'sh-chip--primary': fulfillments.includes(f),
              'is-off': !channelOpen(f),
            }"
            @tap="pickFulfillment(f)"
          >
            {{ $t(`goods.fulfillmentType.${f}`)
            }}<template v-if="!channelOpen(f)"> · {{ $t("goods.channelOff") }}</template>
          </text>
        </view>
        <!--
          名单没读到时**如实说**，别装作四路全开。
          读取中：一行浅字；读失败：一行 + 「重试」。
          此前这两种都退回「全开」，于是网络抖一下，商家就能勾上一条本店没开的路 ——
          商品存得下去、买家下不了单，而错要到结算那一刻才显形。
        -->
        <text v-if="channelsState === 'loading'" class="sh-muted hint">
          {{ $t("goods.channelsLoading") }}
        </text>
        <view v-else-if="channelsState === 'error'" class="inline">
          <text class="sh-muted hint sh-fill">{{ $t("goods.channelsFailed") }}</text>
          <text class="sh-link" @tap="loadStoreChannels">{{ $t("common.retry") }}</text>
        </view>

        <!-- 编辑老商品：原来那一路被门店关掉了。**不替他改**，只说出来 -->
        <text v-if="fulfillmentClosed" class="txt-caption cat-lv__gate">
          {{ $t("goods.fulfillmentClosedWarn") }}
        </text>
      </view>

      <!-- 生鲜段：形态由类目带出，所以选完类目这一段自动出现 -->
      <view v-if="SHOW_FRESH_FIELDS && isFresh" class="field">
        <text class="txt-strong field__label">{{ $t("goods.freshSection") }}</text>
        <sh-kv :label="String($t('goods.cutoffAt'))">
          <input maxlength="16" v-model="fresh.cutoffAt" class="field__input" placeholder="2026-08-22T18:00" />
        </sh-kv>
        <sh-kv :label="String($t('goods.arrivalDesc'))">
          <input maxlength="255" v-model="fresh.arrivalDesc" class="field__input" />
        </sh-kv>
        <!--
          **产地这一格搬走了。** 它与规格库里的 `SD_ORIGIN`（usage_type=PROP）
          是两套东西：商家在一处填了，另一处还是空的，而筛选读的是哪一处他不知道。
          现在统一走上面的「商品参数」—— 那里的值带 code，参与筛选与跨店比较，
          这个自由输入框不带。留着两处的代价是「填了没生效」，而它不报错。
        -->
        <!-- 用 chip 而不是 switch：全仓没有第二处 switch，
             而 uni 的 switch 事件类型在 vue-tsc 下要额外收窄，不值得为一个开关引入 -->
        <sh-kv :label="String($t('goods.weighed'))">
          <text
            class="sh-chip"
            :class="{ 'sh-chip--primary': fresh.weighed }"
            @tap="fresh.weighed = !fresh.weighed"
          >
            {{ fresh.weighed ? $t("common.yes") : $t("common.no") }}
          </text>
        </sh-kv>
        <text class="sh-muted hint">{{ $t("goods.freshTip") }}</text>
      </view>

      <!-- 服务段 -->
      <view v-if="isService" class="field">
        <text class="txt-strong field__label">{{ $t("goods.serviceSection") }}</text>
        <sh-kv :label="String($t('goods.durationMin'))">
          <input maxlength="6" v-model="service.durationMin" class="field__input" type="number" />
        </sh-kv>
        <sh-kv :label="String($t('goods.verifyStore'))">
          <input maxlength="64" v-model="service.storeName" class="field__input" />
        </sh-kv>
      </view>

    </view>

    <!-- 标准品搜索弹层。搜不到时给的是「直接自建」而不是一句「没找到」 -->
    <view v-if="showStd" class="cat-mask" @tap="showStd = false">
      <view class="cat-sheet" @tap.stop>
        <view class="cat-sheet__bar">
          <text class="txt-bold">{{ $t("goods.pickStd") }}</text>
          <sh-icon-btn name="close" @tap="showStd = false"></sh-icon-btn>
        </view>
        <view class="std-search">
          <input
            maxlength="32"
            v-model="stdKeyword"
            class="field__input"
            :placeholder="$t('goods.stdSearchPh')"
            @confirm="searchStd"
          />
          <text class="txt-caption txt-bold mini" @tap="searchStd">{{ $t("common.search") }}</text>
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
    <view class="sh-card sh-mt-sm">
      <!--
          **「套用模板」这个入口没了。** 选完类目已经把本店确认过的那一组
          （名字 + 档位 + code）直接预填进来了，而它展开后列出的第一条
          恰恰就是刚预填的那一组 —— 同一件事出现两次，第二次没有新信息。
          它唯一还独占的是「我的常用」，已经折进下面的「＋ 规格组」面板里，
          与本类目 / 平台通用 / 自己起名摆在一处：**一个入口，一次选择。**
        -->
      <sh-section :title="String($t('goods.specs'))"></sh-section>

      <!--
        **规格常驻展开，没有收起态。**

        <p>上一版对「不分规格的货」整块收起，为的是省一屏。但它省错了地方：
        菠菜的商家往下滑一段就过去了，而**该分档却没想到分档**的商家，
        要等买家问「有没有五斤装」才发现 —— 前者的代价是两秒，后者是一笔生意。

        <p>「记住他上次是开是合」也一并去掉：默认值要能被解释，
        而「因为你上次在这一类收起过」解释不了，换台手机还会变。
      -->

      <!--
        **候选固定在标题下，不再垂在最底下。**
        它回答的是「还能按什么分」，与下面「这一件货有哪几档」是两个问题；
        垂在底部的话，商家填完档位往下滚，又撞见一排长得差不多的 chip。
      -->
      <!--
        **这一页不新增规格，只把能用的摆出来。**

        <p>新的规格与档位统一在「商品规格」里加 —— 那里加一次全店通用、有编号、
        参与跨店比价；在建品页新造只对这一件商品有效，而代价（掉出聚合）看不见。
        所以这里没有输入框、没有「自定义」，只有一排现成的，点一下就用上。

        <p>本类目的排在前面（平台已经替这一类回答过「该按什么分」），
        通用与自建的收在「更多」后面 —— 它们跨类目通用，摆在眼前多半不对题。
      -->
      <view v-if="moreFromCategory.length || moreOther.length" class="addbar">
        <text
          v-for="d in moreFromCategory"
          :key="d.templateNo"
          class="sh-chip sh-chip--dashed"
          @tap="pickDim(d)"
        >＋ {{ d.name }}</text>
        <template v-if="showUniversalDims">
          <text
            v-for="d in moreOther"
            :key="d.templateNo"
            class="sh-chip sh-chip--dashed sh-chip--dashed-quiet"
            @tap="pickDim(d)"
          >＋ {{ d.name }}</text>
        </template>
        <text
          v-if="moreOther.length"
          class="txt-caption sh-link"
          @tap="showUniversalDims = !showUniversalDims"
        >{{ showUniversalDims ? $t("goods.moreFold") : $t("goods.moreOther", { n: moreOther.length }) }}</text>
      </view>


      <!--
        **跟着品类走的推荐规格，直接摊开成 chip。**

        此前平台模板藏在「套用模板」后面，要先点「＋规格组」或点那个链接
        才看得到 —— 而「规格名该填什么」正是此刻最难的一步。
        更要命的是 `prd_spec_template` 线上是空表，`v-if="templates.length"`
        永远为假，于是这个入口从上线到现在一次都没出现过（V174 补了种子数据）。


      <!-- 模板：点一下替代逐个手输。平台模板带 code，商家自存的只有文字 -->
      <!-- 维度选择面板：顺序即建议顺序，越靠前越该被选中 -->

      <!--
        **规格名只读，档位只做减法。**

        <p>名字与档位都来自「商品规格」—— 那里改一次全店通用。
        在这里手输的话，值没有编号，三家店的「500g」「五百克」「0.5kg」
        永远聚不到一起，而这正是平台养这个规格库的全部理由；
        而且同一个名字在不同商品上被改成不同写法，谁也说不清哪个才算数。

        <p>所以这一格只回答一个问题：**这件货有哪几档**。
        本店有的全列在这儿，这件没有的点掉。点掉的还能点回来 ——
        那是恢复，不是新造。
      -->
      <view v-for="(g, gi) in groups" :key="gi" class="group">
        <view class="group__head">
          <text class="txt-strong group__name">{{ g.name }}</text>
          <sh-icon-btn name="close" @tap="removeGroup(gi)"></sh-icon-btn>
        </view>
        <!--
          **多选靠形态说，不靠字重说。**

          <p>此前选中态是「tint 底 + 2rpx 主色实线描边 + 600」，而参数值（单选）
          是「tint 底」—— 两块长得像、行为相反（这一档是开关，参数是单选），
          于是靠一句提示文案说明。问题在于**视觉重量指向了错的那件事**：
          规格档位更重，读起来像「这一排更要紧」，而不是「这一排能多选」。

          <p>现在改成：选中的档位前面带一个 ✓，样式一律走 `.sh-chip--primary`。
          **勾是「已选上，可以再点掉」的通用记号**，一眼就与单选分得开；
          而描边与加粗都不再需要 —— 字阶那条也写着 600 只给标题与按钮。
        -->
        <view class="opts">
          <view
            v-for="o in allOptionsOf(gi)"
            :key="o.code || o.label"
            class="sh-chip sh-chip--icon"
            :class="{ 'sh-chip--primary': optionOn(gi, o) }"
            @tap="toggleOption(gi, o)"
          >
            <sh-icon v-if="optionOn(gi, o)" name="check" :size="20" color="currentColor"></sh-icon>
            <text>{{ o.label }}</text>
          </view>
        </view>
      </view>

      <!-- 平台真没有的（辣度、打磨程度）去那边加。压到最轻：多数人用不到 -->
      <text class="txt-caption sh-link sh-link--quiet more__manage" @tap="gotoMySpecs">
        {{ $t("goods.manageSpecs") }}
      </text>
    </view>

    <!--
      **商品参数**：产地 / 保质期 / 材质这一类。

      <p>与上面那张卡分开，因为它们的性质相反：规格进笛卡尔积生成 SKU、
      每一档要单独定价备库存；参数一项也不进，买家不用挑，只是看。
      摆在同一张卡里的话，商家没有任何线索分辨「填这个会不会让我多填一屏价格」。

      <p><b>选定类目后这张卡一定在，哪怕这一类一个参数都没配。</b>
      此前的条件是「有参数才显示」，而**新建参数的入口就在这张卡里** ——
      于是平台没配参数的类目成了死结：他想加第一个参数，可那个按钮所在的卡
      因为没有参数而不显示。空卡的代价是一小段留白，死结的代价是这个功能不存在。
    -->
    <view v-if="categoryNo" class="sh-card sh-mt-sm">
      <!--
        **加参数在标题行右边**，与「商品规格和参数」页类目卡上的那个加按钮
        同一个位置、同一个样子 —— 同一件事在两页别长两张脸。

        <p>它不摆在标题下方：那一排的位置属于**候选**（规格卡就是这么用的），
        而「加参数」不是候选，是一个开弹层的入口。只有一枚 chip 却独占一整行，
        看上去也像个被落下的按钮。
      -->
      <sh-section :title="String($t('goods.params'))">
        <sh-add :text="String($t('goods.addParam'))" @tap="addingParam = true"></sh-add>
      </sh-section>

      <!-- 与规格同一条：常驻展开，理由见上面那段 -->
      <!-- 这一类还没配参数：说清现状，并把唯一的下一步摆在眼前 -->
      <text v-if="!propDims.length" class="sh-muted hint">{{ $t("goods.paramsEmpty") }}</text>
      <!--
        **参数是单值，规格是多值** —— 一件货有三档重量，但只有一个产地。
        所以这里的 chip 是单选（再点取消），而规格那边是开关（本店有的全列、
        这件货没有的点掉）。两块长得像、行为不同，得说出来。
      -->
      <!-- 一个参数都没有时不说「每项单选」—— 那句话此刻没有对象 -->
      <text v-if="propDims.length" class="sh-muted hint">{{ $t("goods.paramsPick") }}</text>
      <view v-for="d in propDims" :key="d.templateNo" class="param">
        <text class="txt-sub param__k">{{ d.name }}</text>
        <!--
          **「＋ 加值」永远在**，不是只在一个候选都没有的时候才出现。
          平台给这一类配的那几个值是起点不是上限：产地列着本地/国产/进口，
          而他这批菜就是云南来的。上一版只在空列表时给入口 ——
          于是「有候选」反倒成了死路，他只能挑一个最接近的，或者干脆不填。

          <p>量纲型的参数（功率、海拔、净重）平台本来就不枚举值，
          刚自建出来的参数更是必然一个值都没有 —— 那种情况下这个 ＋ 就是唯一的路。

          <p>填的东西**落进规格库拿编号**（见 confirmParamValue），
          不是这件货身上的一个私有字符串：后者不参与筛选，也不参与跨店比较。
        -->
        <view class="param__opts">
          <text
            v-for="o in d.options"
            :key="o.code || o.label"
            class="sh-chip"
            :class="{ 'sh-chip--primary': paramValues[d.templateNo]?.label === o.label }"
            @tap="pickParam(d, o)"
          >{{ o.label }}</text>
          <sh-add small :text="String($t('goods.paramFill'))" @tap="openParamValue(d)"></sh-add>
        </view>
      </view>
      <text class="txt-caption sh-link sh-link--quiet more__manage" @tap="gotoMySpecs">
        {{ $t("goods.manageSpecs") }}
      </text>
    </view>

    <!--
      **加参数 / 填一个值走弹层**，与「商品规格和参数」那一页同一个形状：
      候选（这里没有）在上、自己填在下，代价就写在输入框下面。
      不用 uni.showModal —— 它的标题与输入框不是同一套字，排版不归我们管。
    -->
    <sh-sheet
      :visible="addingParam"
      :title="$t('goods.addParam')"
      :hint="$t('goods.addParamHint')"
      @close="addingParam = false; newParam = ''"
    >
      <view class="build">
        <input
          maxlength="64"
          v-model="newParam"
          class="txt-body build__input"
          :placeholder="$t('goods.addParamPh')"
          @confirm="confirmAddParam"
        />
        <text class="txt-strong sh-link" @tap="confirmAddParam">{{ $t("goods.save") }}</text>
      </view>
      <text class="txt-caption sh-muted build__s">{{ $t("goods.addParamCost") }}</text>
    </sh-sheet>

    <sh-sheet
      :visible="!!addingValueFor"
      :title="addingValueFor ? addingValueFor.name : ''"
      :hint="paramSheetHint"
      @close="closeParamValue"
    >
      <!-- 能加的在上：平台有、这一类还没有。虚线 = 点一下就加进来 -->
      <view v-if="paramCands.length" class="param__opts">
        <view
          v-for="o in paramCands"
          :key="o.code || o.label"
          class="sh-chip sh-chip--icon sh-chip--dashed"
          @tap="pickParamCand(o)"
        >
          <sh-icon name="plus" :size="18" color="currentColor"></sh-icon>
          <text>{{ o.label }}</text>
        </view>
      </view>
      <!--
        已经在用的也摆出来。**看起来是废话，其实是这一屏此前答不上来的那个问题**：
        「系统里到底有没有这个值」。只列「还能加的」时，平台的值全被类目收进来之后
        这里就是一片空白 —— 而空白既可能是「平台没有」，也可能是「都已经在上面了」。
        实线（非虚线）＝已经能选，点它直接选中，不用退出去再点一次。
      -->
      <template v-if="paramUsed.length">
        <text class="txt-strong param__own">{{ $t("goods.paramInUse") }}</text>
        <view class="param__opts">
          <text
            v-for="o in paramUsed"
            :key="o.code || o.label"
            class="sh-chip"
            @tap="pickParamCand(o)"
          >{{ o.label }}</text>
        </view>
      </template>
      <!-- 自己填放最后：顺序即建议，先看平台有没有现成的 -->
      <text class="txt-strong param__own">{{ $t("goods.paramFillOwn") }}</text>
      <view class="build">
        <input
          maxlength="64"
          v-model="newParamValue"
          class="txt-body build__input"
          :placeholder="$t('goods.paramFillPh')"
          @confirm="confirmParamValue"
        />
        <text class="txt-strong sh-link" @tap="confirmParamValue">{{ $t("goods.save") }}</text>
      </view>
      <text class="txt-caption sh-muted build__s">{{ $t("goods.paramFillCost") }}</text>
    </sh-sheet>

    <!-- SKU 矩阵 -->
    <view class="sh-card sh-mt-sm">
      <sh-section :title="String($t('goods.skuMatrix'))">
        <!--
          **字段切换，不是展开。**

          上一版「更多价格」是往每个规格下面追加两行，4 个规格就变成 12 行 ——
          清晰是清晰了，但一屏装不下，翻着找一个数比原来的表格还累。
          现在切换的是「这一列看哪个字段」，任何时候都只有
          「一行一个规格、一个数字」这一种形状。
        -->
        <!-- 单规格不需要字段切换：总共两三个数，直接排开比切来切去快 -->
        <view v-if="multi && priceFields.length > 1" class="segs">
          <text
            v-for="f in priceFields"
            :key="f.key"
            class="sh-chip"
            :class="{ 'sh-chip--primary': priceField === f.key }"
            @tap="priceField = f.key"
          >
            {{ $t(f.labelKey) }}
          </text>
        </view>
        <view v-if="MULTI_MARKET_UI" class="langs">
          <text
            v-for="m in MARKET_CURRENCIES"
            :key="m.currency"
            class="sh-chip"
            :class="{
              'sh-chip--primary': market === m.currency,
              'is-empty': unpricedMarkets.includes(m.currency),
            }"
            @tap="market = m.currency"
          >
            {{ m.currency }}
          </text>
        </view>
      </sh-section>
      <!-- 「按市场分别定价」的说明只在多市场打开时才有意义 -->
      <text v-if="MULTI_MARKET_UI" class="sh-muted hint">{{ $t("goods.marketPriceHint") }}</text>
      <!-- 归集路径必须说清「这不是最终售价」—— 只改标签不解释，
           商家会以为平台擅自改了他的价 -->
      <text v-if="aggregated" class="sh-muted hint">
        {{ $t("goods.priceAggregatedHint") }}
      </text>

      <!--
        **多规格改成纵向分组，不再是一行一行的表。**

        表的问题不是密度，是「同一份规格名在价格卡与库存卡各画一遍」，
        而两张卡的行序必须一一对应 —— 商家要改「5斤·袋装」的库存，
        得先在价格卡数它是第几行。纵向分组之后每组自带标题，两张卡各看各的。

        顺带解决横向拥挤：「更多价格」展开时**纵向追加副字段**，不横向加列。
        375 宽下三列本来就要靠撤 placeholder 才塞得下。
      -->
      <view v-if="multi && priceField === 'price'" class="bulk">
        <input
          maxlength="10"
          v-model="bulk.price"
          class="txt-caption bulk__input sh-num"
          type="digit"
          :placeholder="$t(aggregated ? 'goods.priceAggregated' : 'goods.bulkPrice')"
        />
        <text class="sh-link" @tap="applyBulkPrice">{{ $t("goods.applyAll") }}</text>
      </view>
      <!-- 成本多半各规格一个数，但「都填同一个」也常见（同一箱货拆规格卖） -->
      <view v-if="multi && priceField === 'cost'" class="bulk">
        <input
          maxlength="10"
          v-model="bulk.cost"
          class="txt-caption bulk__input sh-num"
          type="digit"
          :placeholder="$t('goods.bulkCost')"
        />
        <text class="sh-link" @tap="applyBulkCost">{{ $t("goods.applyAll") }}</text>
      </view>

      <!--
        **单规格：一行一个字段，全部排开。**

        输入框此前是 `flex:1`，一个四位数占掉两百多 px，左边一大片空白，
        而同一列的数字还对不齐。现在定宽右对齐 + 前缀符号，四个字段并成一叠，
        扫一眼就知道这件货卖多少、进多少、划线多少。
      -->
      <template v-if="!multi">
        <view class="pr">
          <text class="txt-sub pr__k sh-fill">{{ $t(priceLabel) }}</text>
          <text class="txt-sub pr__cur">￥</text>
          <input maxlength="10" v-model="rows[0]!.priceMajor[market]" class="txt-body pr__v sh-num" type="digit" />
        </view>
        <!-- 毛利跟在售价下面：填价那一刻要看的就是这个数 -->
        <text v-if="marginOf(rows[0]!)" class="txt-caption pr__margin">
          {{ $t("goods.margin", { a: marginOf(rows[0]!)!.amount, r: marginOf(rows[0]!)!.rate }) }}
        </text>
        <view class="pr">
          <text class="txt-sub pr__k sh-fill">{{ $t("goods.costPrice") }}</text>
          <text class="txt-sub pr__cur">￥</text>
          <input
            maxlength="10"
            v-model="rows[0]!.costMajor"
            class="txt-body pr__v sh-num"
            :class="{ 'is-bad': belowCost(rows[0]!) }"
            type="digit"
          />
        </view>
        <text v-if="belowCost(rows[0]!)" class="txt-caption pr__warn">{{ $t("goods.belowCost") }}</text>
        <text class="sh-muted hint">{{ $t("goods.costHint") }}</text>
        <view class="pr">
          <text class="txt-sub pr__k sh-fill">{{ $t("goods.originPrice") }}</text>
          <text class="txt-sub pr__cur">￥</text>
          <input
            maxlength="10"
            v-model="rows[0]!.originMajor"
            class="txt-body pr__v sh-num"
            :class="{ 'is-bad': badOrigin(rows[0]!) }"
            type="digit"
          />
        </view>
        <view v-if="SHOW_FRESH_FIELDS && isFresh" class="pr">
          <text class="txt-sub pr__k sh-fill">{{ $t("goods.nominalGram") }}</text>
          <text class="txt-sub pr__cur">g</text>
          <input maxlength="6" v-model="rows[0]!.nominalGram" class="txt-body pr__v sh-num" type="number" />
        </view>
      </template>

      <!--
        **多规格：一次看一列。** 8 个规格 × 4 个字段同屏没法填，
        所以切的是「这一列看哪个字段」，任何时候都只有「一行一个规格、一个数字」。
      -->
      <template v-else>
        <view v-for="(r, i) in rows" :key="i" class="pr">
          <text class="txt-sub pr__k sh-fill">{{ r.optionValues.join(" · ") }}</text>
          <text class="txt-sub pr__cur">{{ priceField === "gram" ? "g" : "￥" }}</text>
          <input
            maxlength="10"
            v-if="priceField === 'price'"
            v-model="r.priceMajor[market]"
            class="txt-body pr__v sh-num"
            type="digit"
          />
          <input
            maxlength="10"
            v-else-if="priceField === 'cost'"
            v-model="r.costMajor"
            class="txt-body pr__v sh-num"
            :class="{ 'is-bad': belowCost(r) }"
            type="digit"
          />
          <input
            maxlength="10"
            v-else-if="priceField === 'origin'"
            v-model="r.originMajor"
            class="txt-body pr__v sh-num"
            :class="{ 'is-bad': badOrigin(r) }"
            type="digit"
          />
          <input maxlength="6" v-else v-model="r.nominalGram" class="txt-body pr__v sh-num" type="number" />
        </view>
        <!-- 逐行看毛利在 8 行的表上没人看得过来，汇成一句 -->
        <text v-if="avgMargin !== null" class="txt-caption pr__margin">
          {{ $t("goods.avgMargin", { r: avgMargin }) }}
        </text>
      </template>

      <!--
        一个价都没填时不说这句：那时 fromPrice 是「—」，
        渲染出来是「C 端显示 ¥— 起」，比不写更糟。
      -->
      <text v-if="multi && fromPrice !== '—'" class="sh-muted hint">
        {{ $t("goods.fromPriceShort", { s: fromPrice }) }}
      </text>
      <text v-if="MULTI_MARKET_UI && unpricedMarkets.length" class="sh-muted hint">
        {{ $t("goods.unpriced", { s: unpricedMarkets.join("、") }) }}
      </text>

      <!--
        拼团。**从基本信息卡挪到这里**：它是价格，不是商品属性。

        默认折叠成一个开关，因为它是可选玩法而不是必填项 —— 此前三个输入框
        常驻在基本信息卡底部，既占地方又不说明填了会发生什么，
        线上 198 条商品**没有一条填过**。

        ⚠️ 这两个字段是拼团功能的**唯一开关**：`GroupServiceImpl` 开团时硬校验
        `groupPriceMinor > 0`，缺了就抛「该商品未开放拼团」。所以不能删，
        只能讲清楚 —— 团价由商家在商品上配，开团人不能自己定价。
      -->
      <view class="field">
        <sh-kv :label="String($t('goods.groupBuyOn'))">
          <text
            class="sh-chip"
            :class="{ 'sh-chip--primary': groupBuyOpen }"
            @tap="toggleGroupBuy"
          >
            {{ groupBuyOpen ? $t("common.yes") : $t("common.no") }}
          </text>
        </sh-kv>
        <template v-if="groupBuyOpen">
          <sh-kv :label="String($t('goods.groupMinCount'))">
            <input maxlength="6" v-model="groupBuy.minCount" class="field__input" type="number" />
          </sh-kv>
          <sh-kv :label="String($t('goods.groupPrice'))">
            <input maxlength="10" v-model="groupBuy.price" class="field__input" type="digit" />
          </sh-kv>
          <text class="sh-muted hint">{{ $t("goods.groupBuyOnHint") }}</text>
        </template>
      </view>
    </view>

    <!--
      库存**自成一卡**，不再和价格挤在同一行。

      两者是同一张表上的两列时，多规格滚到第 6 行就分不清哪列是价、哪列是库存；
      而它们的改动节奏也完全不同 —— 价格是建品时定一次，库存是每天都在动。
      分开之后，「改库存」这件高频事不必先滚过一整片价格字段。
    -->
    <view class="sh-card sh-mt-sm">
      <sh-section :title="String($t('goods.secStock'))"></sh-section>
      <text class="sh-muted hint">{{ $t("goods.stockHint") }}</text>

      <!-- 与价格卡同构：同样的分组、同样的规格名、同样的「统一填入」 -->
      <view v-if="multi" class="bulk">
        <input
          maxlength="6"
          v-model="bulk.stock"
          class="txt-caption bulk__input sh-num"
          type="number"
          :placeholder="$t('goods.bulkStock')"
        />
        <text class="sh-link" @tap="applyBulkStock">{{ $t("goods.applyAll") }}</text>
      </view>

      <view v-for="(r, i) in rows" :key="i" class="pr">
        <text class="txt-sub pr__k sh-fill">{{ multi ? r.optionValues.join(" · ") : $t("goods.stock") }}</text>
        <!--
          −／＋ 步进。**库存是每天都在动的数**，最常见的改动是「卖掉两袋」——
          点两下比调出键盘、全选、重打快得多。数字仍然可以直接键入。
        -->
        <view class="txt-body step" @tap="stepStock(r, -1)"><sh-icon name="minus" :size="26" color="var(--sh-sub)"></sh-icon></view>
        <!-- 库存 0 = 这个规格顾客买不到。多规格时最容易漏填的就是它 -->
        <input
          maxlength="6"
          v-model="r.stock"
          class="txt-body pr__v pr__v--n sh-num"
          :class="{ 'is-out': Number(r.stock) === 0 }"
          type="number"
        />
        <view class="txt-body step" @tap="stepStock(r, 1)"><sh-icon name="plus" :size="26" color="var(--sh-sub)"></sh-icon></view>
      </view>
      <!-- 多店：改的是哪家店的库存必须写出来。主体总量与门店库存是两个数 -->
      <text v-if="merchant.multiStore" class="sh-muted hint">
        {{ $t("goods.stockStoreScope", { s: merchant.currentStore?.name || "" }) }}
      </text>

      <view class="pr">
        <text class="txt-sub pr__k sh-fill">{{ $t("goods.limitPerUser") }}</text>
        <!-- 右侧留出 −／＋ 那两格的宽度，两行的输入框才在同一竖列上 -->
        <input maxlength="6" v-model="limitPerUser" class="txt-body pr__v pr__v--n pr__v--pad sh-num" type="number" />
      </view>
    </view>

    <!--
      **商品编码：自成一段，不塞进价格卡。**

      <p>塞进价格切换器之后那一行是「售价 成本价 划线价 条码 货号 单位」六项，
      手机上挤成一坨；而且它们本来就不是价格，并排放着商家得先分辨再选。

      <p>整段默认不出现 —— 社区店大半的货没有条码。用过一次的人记在本机，
      这件货身上有值时也自动展开（见 externalOn）。
    -->
    <view class="sh-card sh-mt-sm">
      <sh-section :title="String($t('goods.secCode'))">
        <text
          v-if="externalOn"
          class="sh-link sh-link--quiet"
          @tap="rememberExternal(false)"
        >{{ $t("goods.specFold") }}</text>
      </sh-section>
      <view v-if="!externalOn" class="askspec" @tap="rememberExternal(true)">
        <text class="txt-strong sh-muted askspec__t">{{ $t("goods.extShow") }}</text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
      <template v-else>
        <text class="sh-muted hint">{{ $t("goods.codeHint") }}</text>
        <!-- 一行一个字段；多规格时每个规格一行，与价格卡同构 -->
        <view v-for="f in extFields" :key="f.key" class="codeblock">
          <text class="txt-strong codeblock__k">{{ $t(f.labelKey) }}</text>
          <view v-for="(r, i) in rows" :key="i" class="pr">
            <text v-if="multi" class="txt-sub pr__k sh-fill">{{ r.optionValues.join(" · ") }}</text>
            <input
              maxlength="64"
              v-if="f.key === 'barcode'"
              v-model="r.barcode"
              class="txt-body pr__v pr__v--wide sh-num"
            />
            <input maxlength="64" v-else-if="f.key === 'code'" v-model="r.merchantSkuCode" class="txt-body pr__v pr__v--wide" />
            <input
              maxlength="64"
              v-else
              v-model="r.saleUnit"
              class="txt-body pr__v pr__v--wide"
              :placeholder="$t('goods.unitPh')"
            />
          </view>
        </view>
      </template>
    </view>


    <!--
      差什么就说什么 —— 灰按钮只说明「不行」，不说明「下一步做什么」。
      **但加载中不能说**：那时表单还是空的，这行会列出一份假的待填清单
      （「商品名称、类目、价格」全在里面，而它们其实都填着）。
    -->
    <text v-if="hydrating" class="txt-caption missing">{{ $t("common.loading") }}</text>
    <text v-else-if="missing.length" class="txt-caption missing">
      {{ $t("goods.missing", { s: missing.join("、") }) }}
    </text>
    <!--
      草稿给两个按钮：**保存**（填一半先存着，不惊动运营）与**保存并提交**。
      已过审的商品只给一个 —— 它一保存就自动回到待审，多一个按钮反而让人以为
      不点就不用重审。
    -->
    <!--
      **加载中整块不渲染**，而不是渲染成灰的。
      灰按钮在商家眼里是「我哪里填得不对」，他会去一格格找 —— 而真相是还没读完。
      一个都不显示反而诚实：上面那行写着「读取中」。
    -->
    <view v-if="!hydrating" class="acts">
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
    <text class="tip sh-hint">{{ $t(isDraft ? "goods.draftTip" : "goods.saveTip") }}</text>
  </sh-scaffold>
</template>

<style scoped>
/*
  「这件货要分档卖？」—— 收起态的整块。
  做成一行可点的问句而不是一个链接：它此刻是这一段唯一的操作，
  给足点击面积比省地方重要。
*/
.askspec {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 18rpx 0 2rpx;
}

.askspec__t {
  flex: 1;
  /* 同上：文字色走 primary-text */
  color: var(--sh-primary-text);
}

/* 规格名只读：它来自「商品规格」，在这儿改会让同一个名字在不同商品上写法不一 */
.group__name {
  flex: 1;
}

/*
  档位是一排开关：本店有的全列出来，这件货没有的点掉。
  关掉的压成描边灰字 —— 仍看得见「本店还有这一档」，与「这件货有」区分得开。
*/

/*
  关掉的档位：**虚线描边**，一眼看得出「还在，只是这件货没有」，
  而且点得回来。用实线灰底的话像是被禁用了，他不会再去点它。
*/
/*
  **选中高亮、未选中灰。**
  上一版反过来：默认全选中，取消变虚线+删除线 —— 一排划掉的字读起来像「作废」，
  而它其实只是「这件货没有这一档」。而且「默认全选」让商家一进来就背着
  一堆他没选过的档位，删比选累。
*/
/* 档位缩进在规格名下：视觉上是「这个规格的档」，不是又一排并列的东西 */
.opts {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12rpx;
  margin-top: 16rpx;
  padding-left: 20rpx;
}

/* 弹层里「自己填」那一段的小标题 —— 与候选拉开，说明它是另一回事 */

/* 与「商品规格和参数」页的 .picker__own-t 同一套：26/600/主色 —— 同一段东西同一张脸 */
.param__own {
  display: block;
  margin-top: 28rpx;
  color: var(--sh-primary-text);
}

/* 弹层里那一行输入：输入框吃满，保存压在右边 —— 与「商品规格和参数」那一页同形 */
.build {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 20rpx;
}

.build__input {
  flex: 1;
  height: 76rpx;
  padding: 0 24rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
}

.build__s {
  display: block;
  margin-top: 12rpx;
}

/* 专业商家的入口：与切换器同一行右侧，压到最轻 */
/*
  **「加规格」与「选档位」必须一眼分得开。**

  上一版两者都是 sh-chip：同样的圆角、同样的底色、同样的字号，只差一个 ＋，
  而且一个在卡顶一个在卡底 —— 商家分不清哪排是「加一个维度」、哪排是「选这件货的档」。

  现在给两套完全不同的形：
    加规格 = **虚线描边 + 主色 + ＋ 前缀 + 无底色**  → 「这是个动作」
    选档位 = **实心底 + 无前缀 + 缩进在规格名下**    → 「这是个选项」
  再加上位置分离（加规格固定在标题下、档位跟在各自的规格名下），
  两者在形、色、位三个维度上都不一样。
*/
.addbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12rpx;
  padding-bottom: 16rpx;
  border-bottom: var(--sh-hairline);
  margin-bottom: 8rpx;
}

/* 商品编码：一个字段一小段，段内每个规格一行 */
.codeblock {
  padding: 12rpx 0;
  border-top: var(--sh-hairline);
}

.codeblock__k {
  display: block;
  margin-bottom: 8rpx;
}

/* 条码/货号/单位是文本，比金额格宽 */
.pr__v--wide {
  width: 300rpx;
  text-align: left;
}

.more__manage {
  display: block;
  margin-top: 16rpx;
}

/* 商品参数：一行一项，左键右值 */
.param {
  display: flex;
  align-items: flex-start;
  gap: 16rpx;
  padding: 14rpx 0;
  border-top: var(--sh-hairline);
}

.param__k {
  width: 140rpx;
  flex: none;
  padding-top: 8rpx;
}

.param__opts {
  flex: 1;
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

/*
  参数值的选中态**就是库标准的 `.sh-chip--primary`**（tint 底 + primary-text），
  此前本页自己写了一遍，逐字相同 —— 删掉不改任何观感。

  留住当初那句判断：**参数不影响价格与库存，做得比规格还抢眼的话，
  商家会以为它更要紧** —— 所以它用的是最轻的那一档选中态，
  而不是规格档位那种带描边加粗的。
*/

/*
  字段标签在这一页改成**深色半粗**。

  `.field__label` 原本是 26rpx / 常规 / 灰，与它下面那行说明（.sh-muted 24rpx 灰）
  几乎一样重 —— 一屏灰字里看不出哪句是要你填的、哪句只是解释。
  这里升到 28rpx / 600 / 深，说明维持 24rpx 灰，一屏三档：
  节标题 > 字段标签 > 说明。

  ⚠️ **只在这一页覆盖**，没有直接改 packages/ui 里的 `.field__label` ——
  那个类 b-app 与 c-app 全站共用，一改是全站换档，要连带看一遍别的页有没有被挤开。
  这一页确认好了再提上去，是一次改一个变量。
*/

/*
  原型里我把节标题提到 700，**被字阶守卫拦下了**（tests/typography.test.ts：
  700 只给价格，别的东西要突出靠颜色与留白，不靠再加一道粗体）。
  这条规则是对的：这一页已经有 34rpx 深色的节标题，
  与 28rpx 的字段标签差着 6rpx 与一整个卡片间距，够分。
  所以只加粗字段标签，节标题维持 .txt-title 的 600。
*/

/*
  图文详情正文：起步 3 行，随内容长高，长到屏高六成为止。

  ⚠️ **H5 下「自动生成」填进来的正文不会把框撑高**（App / 小程序是原生实现，没这问题）：
  uni 的 auto-height 只跟着用户的输入事件走。试过四种自己算高度的办法
  （nextTick / rAF / 定时重量 / 影子元素量文本），量到的分别是 75、75、120、120px，
  而实际需要 140 —— 差的那一行来自「长高之后才出现的滚动条」，
  它把可用宽度又缩了十几像素。继续追下去要么改成常驻滚动条（难看），
  要么把 uni 的组件重写一遍。**不值这个价**：填完之后框内可以正常滚动、
  光标进去打一个字就会长开，代价只是「一屏少看一行」。

  上限要落在**里面那个真正的 textarea 上**：uni 的 auto-height 是给内层元素写
  内联 height，只给外壳设 max-height 的话，内层照样一路长下去 ——
  实测 452 字时长到 560px，而 60vh 是 487px，等于没有上限。
  超过之后框内自己滚，不再把下面的分区一路顶走。
*/
.field__area--grow {
  min-height: 150rpx;
  max-height: 60vh;
}
/* uni 把内联 height 写在 .uni-textarea-wrapper 上，textarea 还带内联 overflow:hidden ——
   两处都要压，只压外壳的话内容会从外壳里溢出去（外壳 487、里面 800） */
.field__area--grow :deep(.uni-textarea-wrapper),
.field__area--grow :deep(.uni-textarea-textarea) {
  max-height: 60vh;
  overflow-y: auto !important;
}

.std-link {
  display: block;
  margin-top: 12rpx;
}

.area-len {
  display: block;
  margin-top: 8rpx;
  text-align: right;
}

.langs {
  display: flex;
  gap: 8rpx;
}
/* 未填的语言标出来 —— 否则要逐个点过去才知道漏了哪门 */
/* 「这门语言 / 这个市场还没填」的提示点。**挂在本页自己的 .is-empty 上** ——
   药丸本身已经归 .sh-chip 了，而「没填」是这一页的业务状态，不是药丸的一档 */
.is-empty::after {
  content: " ·";
  color: var(--sh-warning);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}
.chips .sh-chip {
  padding: 14rpx 24rpx;
}
/* 标准品入口：取用后是一枚可撤的徽标 */
.std-on {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20rpx 0;
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
/*
 * 详情图：**一排小格子**，与主图那个单独的大方框形状上就不一样。
 *
 * 此前两者是 140rpx / 150rpx 的同款圆角方块，详情图反而更大 ——
 * 相邻摆着、只差一行 26rpx 灰标签，看不出哪个是主图。
 * 现在靠尺寸（140 vs 104）与排布（单个 vs 横排）区分，不依赖读标签。
 */
/*
 * 分区标题与卡内首个字段的距离。标题不是字段，不能沿用 .field 的间距 ——
 * 贴太近就退化成「又一个标签」，正是这轮要消掉的那种含混。
 */
/* 只剩「块级」。下间距交给间距档的 md（28rpx）—— 此前是 24rpx，不在五档上 */
.sec__h {
  display: block;
}
/*
 * 一行一个规格、一个数字。价格卡与库存卡共用这一套 ——
 * 两张卡长得一样，商家不需要在脑子里对齐行号。
 */
.pr {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 12rpx;
}
.pr__k {
  /* 标签吃掉剩余宽度，控件一律贴右 —— 一列数字对齐比标签对齐重要 */
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
/*
 * 数字输入框**定宽右对齐**，不再 `flex:1`。
 *
 * 铺满整行的输入框里躺着一个四位数，左边两百多 px 全是空白，而同一列的数字
 * 还各自从左边起排、对不齐。220rpx 装得下 999999.99，再宽只是白占地方。
 */
.pr__v {
  flex: none;
  width: 220rpx;
  height: 76rpx;
  padding: 0 20rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  text-align: right;
}
/* 库存与限购是整数，比金额短一截 */
.pr__v--n {
  width: 150rpx;
}
/* 让开右边那个 ＋（64rpx）加一道 gap（16rpx）：两行的输入框右缘才在同一竖线上 */
.pr__v--pad {
  margin-inline-end: 80rpx;
}
/* 货币符号贴着输入框左侧，不进框里 —— 进框里会被输入法当成待编辑内容 */
.pr__cur {
  flex: none;
}
/* 毛利：跟在售价下面右对齐，与数字同一竖列 */
.pr__margin {
  display: block;
  margin-top: 8rpx;
  text-align: right;
  color: var(--sh-success);
}
.pr__warn {
  display: block;
  margin-top: 8rpx;
  text-align: right;
  color: var(--sh-warning);
}
/* 库存 −／＋：与输入框同高，形状上是按钮不是文字 */
/* 图标居中：此前靠 line-height 让字符垂直居中，换成图标后要 flex ——
   line-height 对 mask 画的方块不起作用，会贴着顶边 */
.step {
  width: 64rpx;
  height: 64rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 16rpx;
  background: var(--sh-faint);
  color: var(--sh-sub);
  flex: none;
}
/* 一行里「输入框 + 一个小动作」的通用排布 */
.inline {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
/* 清空键。尺寸与颜色由 `sh-icon-btn` 给（它自带 88rpx 点按区，
   此前是一个 26rpx 的 ✕ 字符，手指要瞄）。这里只留「不被压缩」。 */
.inline__clear {
  flex: none;
}
/* 未开通的履约方式：灰着并可见，不隐藏 —— 隐藏会让人以为平台不支持 */
.sh-chip.is-off {
  opacity: 0.5;
}
/* 详情图：一列窄高的格子，形状上就与上面那排方形轮播图分开 */
.dimgs {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
  margin-top: 12rpx;
}
.dimgs__row {
  display: flex;
  align-items: center;
  gap: 16rpx;
}
.dimgs__img {
  width: 96rpx;
  height: 128rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  font-size: 40rpx;
}
.dimgs__i {
  flex: 1;
}
.dimgs__ops {
  display: flex;
  gap: 12rpx;
}
/* 「加一张」那一格。**它此前引用的是 `.imgs__add` / `.imgs__plus`，
   而那一族在 sh-uploader 收编时（2c3e4a2e）连同主图网格一起删掉了** ——
   于是这一格从那次起就没有任何样式：一个孤零零的加号浮在列表底下，
   没有框、没有底色、点按区只有字那么大。没人报，因为它仍然点得动。
   取值与 sh-uploader 的添加格一致（faint 底 + sm 圆角），尺寸跟这一列的图对齐。 */
.dimgs__add {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 96rpx;
  height: 128rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
}
.dimgs__wait {
  font-size: 40rpx;
  color: var(--sh-sub);
}
/* 字段切换：段落式小开关，不是按钮 —— 它切的是「看哪一列」，不是执行动作 */
.segs {
  display: flex;
  gap: 8rpx;
}
/*
 * 缺货：这一格要能被扫到，它是「填完还差什么」里最常漏的一项。
 * **随布局改版换过两次类名**（.row__input → .grp__v → .pr__v）。
 * 每次都要记得跟过来 —— 不跟的话库存 0 从此不再标红，而且不会有任何报错。
 */
.pr__v.is-out {
  color: var(--sh-danger);
}
/* 划线价填得比售价低时标红 —— 后端会拒，先在这一格说清是哪一行 */
.is-bad {
  color: var(--sh-danger);
}
/* 计数与标签同行右对齐：「已添加 2 / 9」比一句「最多 9 张」有用 */
.imgs__n {
  flex-shrink: 0;
}
.kv .field__input {
  flex: 1;
  margin-top: 0;
}
.mini {
  padding: 16rpx 28rpx;
  border-radius: 16rpx;
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
  text-align: center;
}
.hint {
  display: block;
  margin-top: 8rpx;
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
  color: var(--sh-warning);
}
.group {
  margin-top: 20rpx;
}
.group__head {
  display: flex;
  align-items: center;
  gap: 16rpx;
}

.del.small {

  width: 40rpx;

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
  color: var(--sh-ink);
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
  border-bottom: var(--sh-hairline);
}

.cat-sheet__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28rpx 24rpx;
  border-bottom: var(--sh-hairline);
}
.cat-lv {
  margin-top: 12rpx;
}

.cat-lv__t {
  display: block;
  margin-bottom: 8rpx;
}

.cat-lv__opts {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

/* 已选那一行：面包屑是**结果确认**，比候选项重一档 */
.cat-lv__gate {
  display: block;
  margin-top: 8rpx;
  color: var(--sh-warning);
}

.cat-lv__sel {
  display: block;
  margin-top: 16rpx;
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
  margin: 20rpx 8rpx;
}
</style>
