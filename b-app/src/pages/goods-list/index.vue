<script setup lang="ts">
// 商品列表（B-11.3.5 / 3.6）。上下架与改库存是高频操作，做在列表行里，
// 不进详情页 —— 店主蹲在货架前改库存，不该点三层。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onReachBottom, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { takeGoodsCategory } from "@/shared/handoff";
import { SHOW_CATEGORY_GATE } from "@/shared/flags";
import { money } from "@shared/utils/money";
import { saveBase64Image } from "@/utils/image";
import type { Category, Goods, GoodsStatus, Poster, StoreCategory } from "@shared/types";
import { confirm, pick, prompt } from "@ai-shop/ui/prompt";
/**
 * 这一行上可能出现的动作。**具名联合而不是 string** ——
 * `runAct` 与菜单项都按它分发，写错一个拼法在 string 下是运行时静默无反应，
 * 在这里是编译期红字。
 */
type GoodsAct = "submit" | "onSale" | "offSale" | "share" | "editStorePrice";


const { t } = useI18n();
const merchant = useMerchantStore();

/**
 * 分享单品（P1，2026-08-24）。
 *
 * <p>「获客工具」原来只有整店分享 —— 但后端 `share-kit` 接口一直支持 `goodsNo`
 * （文案会变成「XX 上新了，点进来看看」），B 端却没有一个入口去用它。
 * 分享单品比分享整店更容易促成转化：老客收到的是一件具体的货，不是一句泛泛的「来看看」。
 *
 * <p>只给在售商品：分享一件还在审核/已下架的货，买家点进去要么看不见、要么下不了单，
 * 那条链接等于白发。
 */
const sharing = ref<Goods | null>(null);
const shareText = ref("");
const poster = ref<Poster | null>(null);
const shareLoading = ref(false);
async function shareGoods(g: Goods) {
  sharing.value = g;
  shareText.value = "";
  poster.value = null;
  shareLoading.value = true;
  try {
    // allSettled：海报是锦上添花，它抖一下不该连文案也弹不出来
    const [kit, p] = await Promise.allSettled([api.mShareKit(g.goodsNo), api.mPoster(g.goodsNo)]);
    if (kit.status === "fulfilled") {
      shareText.value = kit.value.text;
    } else {
      throw kit.reason;
    }
    poster.value = p.status === "fulfilled" ? p.value : null;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
    sharing.value = null;
  } finally {
    shareLoading.value = false;
  }
}
function closeShare() {
  sharing.value = null;
}
function copyShareText() {
  if (!shareText.value) return;
  uni.setClipboardData({
    data: shareText.value,
    success: () => uni.showToast({ title: t("store.copied"), icon: "none" }),
  });
}
function savePosterImage() {
  saveBase64Image(poster.value?.imageBase64, "goods-poster", t);
}

/*
 * 页签。**此前只有三个** —— 而 `status` 是四态：
 * 被驳回的商品商家筛不出来，只能在「全部」里一条条翻，
 * 而那恰恰是最需要他去处理的一批（改完才能重新提交）。
 */
const TABS: { key: GoodsStatus | "OUT_OF_STOCK" | ""; labelKey: string }[] = [
  { key: "", labelKey: "common.all" },
  { key: "ON_SALE", labelKey: "goods.statusON_SALE" },
  // 草稿排在待审前面：它是**等商家自己动手**的那一批，而待审是在等平台
  { key: "DRAFT", labelKey: "goods.statusDRAFT" },
  { key: "PENDING", labelKey: "goods.statusPENDING" },
  { key: "REJECTED", labelKey: "goods.statusREJECTED" },
  { key: "OFF_SALE", labelKey: "goods.statusOFF_SALE" },
  /*
   * 缺货（B-4.1 要求的第三筛，一直没实现）。
   *
   * **它与上面四个不是同一轴**：那四个是「审核结果 × 上下架」，缺货是库存算出来的，
   * 一件在售商品照样能全规格断货。放在同一排页签里是因为商家的心智就是
   * 「我要看哪一批货」，而不是「我要按哪个维度筛」。
   */
  { key: "OUT_OF_STOCK", labelKey: "goods.statusOUT_OF_STOCK" },
];

const tab = ref<GoodsStatus | "">("");
const list = ref<Goods[]>([]);
/**
 * 翻页。**后端一页最多 50 条**（`Math.min(size, 50)`），而这里原先写死
 * `size: 50` 且从不翻页 —— 商品超过 50 个的商家**永远只能看到 50 个**，
 * 且界面上没有任何迹象表明还有别的：没有页码、没有「加载更多」、拉到底就没了。
 *
 * 实测：194 条商品的账号，列表停在第 50 条，剩下 144 条在 B 端不存在。
 * 这个缺陷只有在真实数据量下才看得见 —— 四条种子数据时它完全正常。
 */
const page = ref(1);
const hasMore = ref(false);

/**
 * 按标题搜。**服务层一直支持，端点此前写死传 null**，所以这一页从来没有搜索 ——
 * 商品少时看不出来，194 条的账号找一个商品要滚三十屏。
 *
 * 防抖 300ms：每敲一个字发一次请求，既费流量又会让结果乱序回来
 * （后发的先到，界面上闪一下又变回去）。
 */
const keyword = ref("");
let searchTimer: ReturnType<typeof setTimeout> | undefined;
function onSearch(v: string) {
  keyword.value = v;
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => void load(), 300);
}
function clearSearch() {
  keyword.value = "";
  void load();
}
const loading = ref(false);

const empty = computed(() => !loading.value && !list.value.length);

/**
 * 这一行到底是什么状态。
 *
 * **不能只看 `onSale`**：新建和每次改动都会回到审核中，那时 `onSale` 是 false，
 * 照布尔值渲染就成了「已下架」+ 一个必然失败的「上架」。
 * 后端下发的 `status` 才是四态（PENDING / REJECTED / ON_SALE / OFF_SALE）；
 * 老数据没有这个字段时回落布尔值。
 */
function stateOf(g: Goods) {
  /*
   * **本店的上架态优先**（多门店）。上下架落在门店行上，而 `status`/`onSale` 是主体级的：
   * 主体的 onSale 是「任一门店在售就为真」的总闸 —— A 店下架完，B 店还在卖，
   * 这一行仍会显示「在售」，店长会以为没点上、再点一次（第二次点的是上架，又开回去）。
   *
   * `storeOnSale == null` 是「未按店管理」，跟随主体级 —— 与 false 分开判，
   * 合起来的话单店商家会全部变成「已下架」。
   * 审核态（PENDING/REJECTED）仍以主体级为准：那是平台的判断，与哪家店无关。
   */
  const base = g.status ?? (g.onSale ? "ON_SALE" : "OFF_SALE");
  if (base === "PENDING" || base === "REJECTED") return base;
  if (g.storeOnSale == null) return base;
  return g.storeOnSale ? "ON_SALE" : "OFF_SALE";
}

/** 审核中或被驳回 —— 这两种状态下商家自己按不了上架 */
function pending(g: Goods) {
  const s = stateOf(g);
  return s === "PENDING" || s === "REJECTED";
}

/**
 * @param more true = 追加下一页；false/省略 = 从第一页重来（切页签、切门店、改完数据）
 */
/**
 * 类目筛 = **这家店自己摆的货架**（门店类目），不是平台的全量类目树。
 *
 * <p><b>此前给的是一级类目，而那样一件也筛不出来</b>：商品挂的是二级类目
 * （goods-edit 选的就是二级），后端 `GET /biz/goods` 的 categoryNo 是
 * **精确匹配**（`eq`，不含子级）—— 拿「食品生鲜」去筛挂在「粮油调味」下的货，
 * 结果恒为空，而界面上看起来只是「这个类目没货」。
 *
 * <p>换成门店类目还顺带对齐了商家的心智：他在「我的类目」里摆了几个货架，
 * 商品列表就按那几个筛。平台有而他没摆的类目，本来就不该出现在他的工具栏里。
 */
const storeCategories = ref<StoreCategory[]>([]);
const categoryNo = ref("");

/** 平台全量树：只用来判「这件货的类目本店有没有资质」，不进筛选条 */
const rootCategories = ref<Category[]>([]);

async function loadCategories() {
  // 取不到不该挡住列表：筛选是锦上添花，商品列表本身要照常出来
  const [tree, mine] = await Promise.all([
    api.mCategoryTree().catch(() => [] as Category[]),
    /*
     * **先判 `biz:store` 再发**：这一页的门禁是 `biz:stock`（改库存是店员的日常），
     * 而门店货架要 `biz:store` —— 店员与理货员进得来，却打不通这个请求。
     * 不判的话他们每次进商品页都吃一个 70006，而「本店类目」那一段本来就该对他们不存在。
     */
    merchant.can("biz:store")
      ? api.mStoreCategories(merchant.storeNo || "default").catch(() => [] as StoreCategory[])
      : Promise.resolve([] as StoreCategory[]),
  ]);
  rootCategories.value = tree;
  storeCategories.value = mine;
  // 切店之后原来的筛选可能已经不在这家店的货架上了，留着它列表会一直是空的
  if (categoryNo.value && !mine.some((c) => c.categoryNo === categoryNo.value)) {
    categoryNo.value = "";
  }
}

/**
 * 类目编号 → 类目节点的**平铺索引**。
 *
 * <p>`mCategoryTree()` 返回的是树，而列表里每一行只有 `categoryNo` ——
 * 逐行去树里递归查是 O(行 × 树)，一屏 50 行就是几千次比较。
 */
const categoryIndex = computed<Map<string, Category>>(() => {
  const m = new Map<string, Category>();
  const walk = (list: Category[]) => {
    for (const c of list) {
      m.set(c.categoryNo, c);
      if (c.children?.length) walk(c.children);
    }
  };
  walk(rootCategories.value);
  return m;
});

/**
 * 这件商品**缺不缺资质**。缺 = 它现在点「上架」必被后端拒（70002）。
 *
 * <p>为什么要在列表页算：门槛卡在**上架**那一刻，而列表页此前没有任何迹象 ——
 * 商家只能一条一条点上架去撞。线上实测 M0001 有 138 件货处在这个状态，
 * 分布在 9 个类目里，一件都上不了架，而列表上看不出任何区别。
 *
 * <p>判据与后端 `requireCategoryAuthorized` 一致：类目挂了 `requiredCode`
 * 且主体没持有它。没归类的商品不算缺 —— 那是另一件事（后端也放行）。
 *
 * @returns null = 不缺；否则给出人读的资质名，用来说「缺哪张」
 */
function gateOf(g: Goods): string | null {
  const c = g.categoryNo ? categoryIndex.value.get(g.categoryNo) : undefined;
  const code = c?.requiredCode;
  if (!code || merchant.categoryCodes.includes(code)) return null;
  return (c?.qualifications ?? []).join("、") || code;
}

/** 本页缺资质的件数。**只统计当前已加载的**，不谎称是全店总数 */
const gatedCount = computed(() => list.value.filter((g) => gateOf(g) !== null).length);

function switchCategory(no: string) {
  categoryNo.value = categoryNo.value === no ? "" : no;
  void load();
}

/** 首屏到过没有。**不是 `loading`** —— 那个含下拉刷新，刷新时把列表换成空态是另一个 bug */
const loaded = ref(false);
/** 这次没取到。**与「确定为空」是两件事** —— 网络不通时不该显示「还没有…」 */
const failed = ref(false);

async function load(more = false) {
  if (!merchant.canOperate) return;
  if (more && (!hasMore.value || loading.value)) return;
  loading.value = true;
  try {
    const next = more ? page.value + 1 : 1;
    const res = await api.mGoodsList({
      status: tab.value || undefined,
      keyword: keyword.value.trim() || undefined,
      categoryNo: categoryNo.value || undefined,
      page: next,
      size: PAGE_SIZE,
    });
    list.value = more ? [...list.value, ...res.records] : res.records;
    page.value = next;
    // 拿满一页就认为还有下一页 —— 比信任 total 稳：total 与 records 的口径
    // 在按门店裁剪的场景下会分岔，而「这一页满了」是端上能自己看见的事实
    hasMore.value = res.records.length >= PAGE_SIZE;
    failed.value = false;
  } catch {
    // 此前这里只有 finally：请求挂了是一个没人接的 Promise 拒绝，
    // 界面上一个字都不说，列表停在空的 —— 与「这一档真的没有商品」一模一样
    failed.value = true;
  } finally {
    loading.value = false;
    loaded.value = true;
  }
}

/** 与后端上限同一个数。写 100 也只会拿回 50，而端上会以为「没有下一页了」 */
const PAGE_SIZE = 50;

onReachBottom(() => void load(true));

function switchTab(key: GoodsStatus | "") {
  tab.value = key;
  void load();
}

async function toggle(g: Goods) {
  /*
   * 上架前先在端上说清楚。**只拦上架，不拦下架** ——
   * 缺资质的商品要能下架（它可能是资质过期前上的架）。
   *
   * 不这样做的话商家看到的是后端那句通用错误，既说不出缺哪张证，
   * 也说不出是类目的问题 —— 他会反复回去改商品信息，而问题不在商品上。
   *
   * **闸门关着时这一段整个不走**（运营端开关，走 /biz/context）：后端此刻会放行，
   * 端上再拦就成了「点不动一个其实能按的按钮」，而且他无从知道为什么。
   */
  // 同上：要不要上架、闸门该不该拦，判据都是**这家店**的状态，不是主体总闸
  const willBeOnSale = stateOf(g) !== "ON_SALE";
  const need = merchant.categoryGateEnforced && willBeOnSale ? gateOf(g) : null;
  if (need) {
    uni.showToast({ title: t("goods.gateBlocked", { s: need }), icon: "none" });
    return;
  }
  try {
    await api.mToggleGoods(g.goodsNo, willBeOnSale);
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/**
 * 改库存（B-11.3.6）。
 * 单独给快捷入口而不是让店主进编辑页：**这是最高频的日常动作** ——
 * 一批菜卖完了要马上改数量，走完整表单（品类、规格、价格…）等于每次重填一遍。
 *
 * 多规格商品有多个 SKU，改哪个说不清楚，所以只对单规格开快捷入口，
 * 多规格仍进编辑页 —— 与其猜错，不如把话说明白。
 */
async function editStock(g: Goods) {
  /*
   * **进销存成了真相源之后，这里不再直接改。**
   *
   * 三档真相源（`shop.inventory.stock-authority`）里只有 `INVENTORY` 走这一支：
   * · `PLATFORM` / `DUAL` —— 平台仍是真相源，在这儿改库存**就是在改真相源**，
   *   天经地义，不拦；
   * · `INVENTORY` —— 改的是一个已经不作数的数，而进销存那边只会看到一条
   *   来路不明的调整，账上说不清这批货是哪来的。该走的是进货单 / 盘点单。
   *
   * **给去处，不是只说不行。**「这个功能不可用」而不告诉他改哪儿，
   * 等于把他卡在这一页 —— 而他手里那批菜是真的卖完了。
   */
  if (merchant.stockByInventory) {
    const go = await confirm({
      title: String(t("goods.stockByInvTitle")),
      hint: String(t("goods.stockByInvHint")),
      confirmText: String(t("goods.stockByInvGo")),
    });
    if (go) uni.navigateTo({ url: ROUTES.stockCheck });
    return;
  }

  if (g.skus.length > 1) {
    uni.showToast({ title: t("goods.multiSkuStock"), icon: "none" });
    uni.navigateTo({ url: `${ROUTES.goodsEdit}?goodsNo=${g.goodsNo}` });
    return;
  }
  const sku = g.skus[0];
  if (!sku) return;

  /*
   * **改之前先把进销存那本账摆出来。**
   *
   * 这一页的「库存」是平台侧的数（`prd_sku.stock` / `prd_store_stock`），
   * 而进销存是另一本账。两处都叫「库存」、都显示一个数字，界面上却没有
   * 任何地方说明它们的关系 —— 商家没法回答「哪个是对的」。
   *
   * 摆在这一刻而不是做成常驻的一行：他正要改这个数，此刻才需要知道
   * 另一本账记着多少。平时那是噪声。
   *
   * **查不到不挡着他改**：刚建的 SKU 在投影跑到之前没有物料，那是常态。
   * 网络失败同理 —— 一个用来对照的数，不该让主动作失败。
   */
  let inv: string = "";
  try {
    const item = await api.mItemBySku(sku.skuNo);
    if (item) {
      const where = (item.byLocation ?? [])
        .filter((l) => l.onHand !== 0)
        .map((l) => `${l.locationName} ${l.onHand}`)
        .join("、");
      inv = String(t("goods.stockInvHint", {
        n: item.onHand,
        where: where || String(t("goods.stockInvNowhere")),
      }));
    } else {
      inv = String(t("goods.stockInvNone"));
    }
  } catch {
    // 对照信息拿不到就不显示 —— 不打断改库存这件事
  }

  const value = await prompt({
    title: String(t("goods.editStock")),
    hint: inv,
    placeholder: String(sku.stock),
    type: "number",
  });
  if (!value?.trim()) return; // 取消或空输入

  const n = Number(value.trim());
  // 负数与非数字要挡住 —— 库存写成 -5 之后 C 端的置灰与到货提醒逻辑全乱
  if (!Number.isFinite(n) || n < 0) {
    uni.showToast({ title: t("goods.stockInvalid"), icon: "none" });
    return;
  }

  try {
    /*
     * 多店走门店库存，单店走主体库存。
     * 不分的话，多店商家改完发现页面数字没变 —— 他改的是主体总量，
     * 而页面显示的是当前门店的数（后端按店取），两个数各走各的。
     */
    if (merchant.multiStore) {
      await api.mSaveStoreStock(g.goodsNo, sku.skuNo, Math.floor(n));
    } else {
      await api.mSaveStock(g.goodsNo, sku.skuNo, Math.floor(n));
    }
    uni.showToast({ title: t("common.saved"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/**
 * 改**本店价**（多门店）。
 *
 * <p>与改库存同一个形状，但**回退方向相反**：清空 = 回到主体价，而不是 0。
 * 这条要有 —— 没有它，商家给某店定过价之后就再也回不去，「改成和总部一样」
 * 只能靠他自己抄一遍数字，而抄错没有任何一处会拦。
 */
async function editStorePrice(g: Goods) {
  if (g.skus.length > 1) {
    uni.showToast({ title: t("goods.multiSkuStock"), icon: "none" });
    uni.navigateTo({ url: `${ROUTES.goodsEdit}?goodsNo=${g.goodsNo}` });
    return;
  }
  const sku = g.skus[0];
  if (!sku) return;

  const current = sku.storePrice ?? sku.price;
  const value = await prompt({
    title: String(t("goods.editStorePrice")),
    // 说明走 hint。此前塞在 showModal 的 content 里，而 editable 下那是**初值** ——
    // 商家打开就看见一句「仅调整本门店售价…」躺在输入框里，得先清空才能填价
    hint: String(t("goods.storePriceTip")),
    placeholder: money(current),
    type: "digit",
  });
  /*
   * 取消与「清空输入框再确定」是两件事：前者什么都不做，后者是撤销本店价。
   * **此前这一条只写在注释里没有实现** —— showModal 的回调把取消也 resolve 成 ""，
   * 于是「清空后确定」和「取消」走同一条分支，撤销本店价这件事根本做不到。
   * prompt() 取消返回 null、清空返回 ""，两者才真的分得开。
   */
  if (value === null) return;

  const raw = value.trim();
  const price = raw ? Math.round(Number(raw) * 100) : null;
  if (price !== null && (!Number.isFinite(price) || price < 0)) {
    uni.showToast({ title: t("goods.priceInvalid"), icon: "none" });
    return;
  }
  try {
    await api.mSaveStorePrice(g.goodsNo, sku.skuNo, price);
    uni.showToast({ title: t("common.saved"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 草稿 → 待审。重复点无副作用，所以不做本地防抖之外的额外拦截 */
async function submit(g: Goods) {
  try {
    await api.mSubmitGoods(g.goodsNo);
    uni.showToast({ title: t("goods.submitted"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/**
 * 这一行上**除库存之外**的所有动作，按「该不该给他」筛完之后的完整清单。
 *
 * <p>顺序就是菜单里的顺序，也是挑主动作的优先级 —— 第一项即主动作。
 * 抽成一处而不是散在模板里：改版前六个按钮各带各的 `v-if`，
 * 条件重叠又互不相干（`pending(g)` 与 `status !== 'DRAFT'` 分别写在两个按钮上），
 * 想回答「这一行到底会出现几个按钮」只能把六个条件在脑子里合并一遍。
 */
function actsOf(g: Goods): GoodsAct[] {
  const out: GoodsAct[] = [];
  const canGoods = merchant.can("biz:goods");
  // 草稿只给「提交审核」：上架对它必被拒，而拒绝的理由（还没过审）
  // 对一个自己都没提交的商品说不通
  if (g.status === "DRAFT" && canGoods) out.push("submit");
  // 审核中/已驳回**不给上下架**：后端必拒（70003），
  // 留着它等于给商家一个永远点不动的按钮，而错在哪一句话都没有
  /*
   * ★ **按钮文案必须读 `stateOf(g)`（门店级），不能读 `g.onSale`（主体级）。**
   *
   * 主体级的 `onSale` 是「任一门店在售就为真」的总闸。多门店时，店长在自己店里
   * 下架完，主体级仍是 true —— 于是状态标已经写「已下架」，按钮却还写「下架」。
   * 他再点一次等于又把这家店开回去，而两次点击看起来做的是同一件事。
   * 2026-09-16 线上实测撞到：默认店 on_sale=0、分店 1、主体 1，标与按钮互相矛盾。
   */
  if (canGoods && !pending(g) && g.status !== "DRAFT") {
    out.push(stateOf(g) === "ON_SALE" ? "offSale" : "onSale");
  }
  /*
   * ★ **「编辑」不在这个清单里** —— 它是**点商品本身**（`onRowTap`）。
   *
   * 它此前是这里的一项，于是在「审核中/已驳回」之外的状态下都排在主动作之后，
   * 被收进「更多」：改一件商品要点两次，而「更多」这个词说不出里面有什么。
   * 编辑是这一页仅次于改库存的高频动作，且「点这件货 → 打开这件货」
   * 不需要任何标签来解释。放回二级菜单等于给最常用的动作加一道门。
   */
  // 只给在售商品：分享一件审核中/已下架的货，买家点进去要么看不见要么下不了单
  if (stateOf(g) === "ON_SALE" && merchant.can("biz:store")) out.push("share");
  // 本店价只在多门店时出现：单店商家改的就是主体价（编辑页那个），
  // 多给一个入口只会让他分不清自己改的是哪个数
  if (merchant.multiStore && canGoods) out.push("editStorePrice");
  return out;
}

/** 摆在最左、给主色的那一个。每种状态其实只有一件显然该做的事 */
function primaryOf(g: Goods): GoodsAct | null {
  return actsOf(g)[0] ?? null;
}

/** 收进「更多」的那些 —— 主动作之外的全部 */
function moreOf(g: Goods): GoodsAct[] {
  return actsOf(g).slice(1);
}

/**
 * 「更多」里只剩一项时的那一项。
 *
 * <p>模板要的是 `GoodsAct | null`；直接写 `moreOf(g)[0]` 在开了
 * `noUncheckedIndexedAccess` 的这份配置下是 `| undefined`，过不了类型检查。
 *
 * <p>⚠️ **不要用 `arr[0]!` 那种非空断言**。`]` 后面紧跟 `!` 会被 UnoCSS 的
 * `transformerVariantGroup` 当成「任意值 + important」语法去改写源码，
 * 与另一个 transformer 撞在同一块上，整个文件编译失败：
 * `[plugin:unocss:transformers:pre] Cannot split a chunk that has already been edited`。
 * **报错指向文件第 1 行、不指向这里**，而 vue-tsc 与 vitest 全绿 ——
 * 2026-09-16 为此二分了十几轮才定位到这一个字符。
 */
function soleMoreOf(g: Goods): GoodsAct | null {
  const [first, ...rest] = moreOf(g);
  return first !== undefined && rest.length === 0 ? first : null;
}

/**
 * 动作的显示名。
 *
 * <p><b>键写成显式映射表，不要拼 `t("goods." + act)`，也不要
 * `` t(`goods.${act}`) ``。</b> 前者 `check-i18n-orphan` 看不见，那几条词条会被
 * 报成孤儿、进而被人当死词条删掉；后者它认得，但「裸命名空间前缀一条就放行整片」
 * —— 整个 `goods.*` 从此对这道闸是瞎的（那道闸自己的注释里记着这个坑）。
 * 映射表两头都占：每个 key 都是字面量，而 `Record<GoodsAct, string>` 让漏一个变成编译错。
 *
 * <p><b>标签必须在脚本里算好，模板里不能写反引号模板串。</b>
 * 第一版写的是 <code>{{ $t(`goods.${primaryOf(g)}`) }}</code>，结果 UnoCSS 的
 * pre 转换器直接崩在整个文件上：
 * <code>[plugin:unocss:transformers:pre] Cannot split a chunk that has already been edited</code>
 * —— 整页白屏，而 vue-tsc 与 vitest 全绿，本地不跑一次根本发现不了。
 */
const ACT_LABEL: Record<GoodsAct, string> = {
  submit: "goods.submit",
  onSale: "goods.onSale",
  offSale: "goods.offSale",
  share: "goods.share",
  editStorePrice: "goods.editStorePrice",
};

function labelOf(act: GoodsAct): string {
  return t(ACT_LABEL[act]);
}

/*
 * 模板只调这四个 —— **不在模板里写 `!` 非空断言**。
 * 除了上面记的 UnoCSS 那个坑，模板里的断言本身也读不出「什么时候会是空」。
 */
function runPrimary(g: Goods) { const a = primaryOf(g); if (a) runAct(g, a); }
function primaryLabel(g: Goods) { const a = primaryOf(g); return a ? labelOf(a) : ""; }
function runSoleMore(g: Goods) { const a = soleMoreOf(g); if (a) runAct(g, a); }
function soleMoreLabel(g: Goods) { const a = soleMoreOf(g); return a ? labelOf(a) : ""; }

/** 一个动作一个入口，模板里不再各写各的 @tap */
function runAct(g: Goods, act: GoodsAct) {
  if (act === "submit") void submit(g);
  else if (act === "onSale" || act === "offSale") void toggle(g);
  else if (act === "share") void shareGoods(g);
  else if (act === "editStorePrice") void editStorePrice(g);
}

async function openMore(g: Goods) {
  const acts = moreOf(g);
  const i = await pick({
    title: g.title,
    items: acts.map(labelOf),
  });
  if (i != null && acts[i]) runAct(g, acts[i]);
}

/**
 * 点商品本身 → 打开它的编辑页。
 *
 * <p>写成具名函数而不是在模板里写 `merchant.can('biz:goods') && edit(g)`：
 * 「谁点得动」这条判断要能被读到、也要和右边那个 `›` 用的是同一个条件 ——
 * 两处各写各的，早晚会出现「有箭头但点不动」或反过来。
 *
 * <p>没有 `biz:goods` 的店员点不动：他能看货、能改库存，但改不了商品本身。
 * 这时右边不出箭头，行也没有按下态 —— 不给一个点了没反应的可点相。
 */
function onRowTap(g: Goods) {
  if (merchant.can("biz:goods")) edit(g);
}

function edit(g?: Goods) {
  uni.navigateTo({ url: g ? `${ROUTES.goodsEdit}?goodsNo=${g.goodsNo}` : ROUTES.goodsEdit });
}

/** 徽标点进发布确认页：先看差异，发布是那一页上的决定，这里不直接发 */
function toPublish(g: Goods) {
  uni.navigateTo({ url: `${ROUTES.goodsPublish}?goodsNo=${g.goodsNo}` });
}

/** 总库存 = 各规格之和。单规格可就地改（editStock），多规格进编辑页逐个改 */
function stockOf(g: Goods) {
  return g.skus.reduce((s, k) => s + k.stock, 0);
}

/** 上次拉类目时是哪家店 —— 货架按店走，切店必须重拉，否则筛的是上一家店的货架 */
const catsOfStore = ref("");

onShow(() => {
  /*
   * 从「我的类目」点某一类过来时，**带着那一类落地**。
   *
   * 不带的话他看到的是全部商品，得自己在筛选条里再选一次刚点过的那个类目 ——
   * 这一跳的意义正在于省掉那一次。
   *
   * 取完即清（takeGoodsCategory 一次性）：留着的话，下次从 tab 图标进来
   * 还会莫名停在上次那个类目上，而界面上没有任何东西解释为什么。
   */
  const handed = takeGoodsCategory();
  if (handed) categoryNo.value = handed;

  // 平台树几乎不变，但门店货架会随「我的类目」的编辑与切店而变
  if (!rootCategories.value.length || catsOfStore.value !== merchant.storeNo) {
    catsOfStore.value = merchant.storeNo;
    void loadCategories();
  }
  void load();
});
</script>

<template>
  <!--
    列表本身要 `biz:stock`（`/biz/goods`）。**它是 tabBar 四页之一**，
    客服与配送员没有这个码 —— 不判的话他们每天点一次「商品」，每天吃一个 70006 toast，
    而页内那几个按钮反倒早就按 can() 裁好了。门禁漏的偏偏是列表这一件必做的事。
  -->
  <sh-scaffold title-key="goods.title" tab="goods" :denied="!merchant.can('biz:stock')">
    <!--
      **搜索提到第一行，状态页签独占整宽。**

      此前这两者挤在同一行：六个状态页签本来就要横滚，右边那截「＋ 新建商品」
      又固定占掉约 96rpx —— 两个都不舒服，而最后一个状态常常划不到。
      新建改成右下悬浮按钮（见 .fab）之后，这一行就还给了页签。

      搜索排在最前，是因为商品一多，「找某一个」比「筛一批」高频得多。
    -->
    <view class="sh-searchbox search">
      <input
        maxlength="32"
        class="txt-sub search__input"
        :value="keyword"
        :placeholder="$t('goods.searchPh')"
        confirm-type="search"
        @input="onSearch(String(($event as any).detail.value ?? ''))"
      />
      <sh-icon-btn v-if="keyword" class="search__clear" name="close"
        color="var(--sh-sub)" @tap="clearSearch"></sh-icon-btn>
    </view>

    <view class="bar sh-row sh-row--between">
      <!-- 必须套一层容器：sh-tabs 是**多根组件**（v-if/v-else 两个根），
           Vue 3 下 class 无法透传到 fragment 根上，写在组件标签上会被静默丢弃 -->
      <view class="sh-fill">
        <sh-tabs
          :items="TABS.map((t) => ({ key: t.key, label: String($t(t.labelKey)) }))"
          :active="tab"
          @change="switchTab"
        ></sh-tabs>
      </view>
    </view>

    <!--
      缺资质汇总。**只在真有的时候出现**，且说清是「当前列表里」的数 ——
      分页只加载了一部分，把它说成全店总数是在编一个自己也不知道的数字。
    -->
    <text v-if="SHOW_CATEGORY_GATE && gatedCount" class="txt-caption sh-notice sh-notice--warning gate-sum">
      {{ $t("goods.gateCount", { n: gatedCount }) }}
    </text>

    <!-- 一级类目筛。只有一个类目时不显示 —— 那时它是个恒真的开关 -->
    <scroll-view v-if="storeCategories.length > 1" class="cats" scroll-x>
      <view class="cats__row">
        <text
          v-for="c in storeCategories"
          :key="c.categoryNo"
          class="txt-caption sh-chip cats__chip"
          :class="{ 'sh-chip--primary': categoryNo === c.categoryNo }"
          @tap="switchCategory(c.categoryNo)"
        >
          {{ c.name }}
        </text>
      </view>
    </scroll-view>

    <!--
      当前门店。**多店才显示** —— 单店商家看到「当前门店」只会疑惑还有别的店。
      不显示的代价是实测出来的：商家给某家店设了 1 件库存，商品页却显示主体总量 91，
      他会以为还有货。

      提示里那半句「没单独设过的门店按 0 卖」是后端的真实语义
      （`StockPortImpl.hasStoreStock`：任意一家店设过，这个 SKU 就整体转成按店算，
      没设的店按 0 —— 少卖可恢复，超卖不可）。真实链路上验过：
      在新店设了 5 件，主店那 80 件当场变成 0 —— **不写出来的话没人能预料到**。
    -->
    <!-- 当前门店只读标记（库存按店）：切店在「我的」 -->
    <biz-store-tag readonly></biz-store-tag>

    <!--
      空状态只说事实，不再放「新建第一个商品」——
      右下角那个常驻悬浮按钮已经是新建入口，同一屏两个一模一样的主色按钮
      只会让人怀疑它们做的不是同一件事。
    -->
    <sh-empty v-if="empty" :pending="!loaded" :failed="failed" @retry='() => load()' :text='$t("goods.empty")'></sh-empty>

    <!--
      **一行商品分成上下两段，不再是「左信息 / 右操作」两栏。**

      两栏在真实数据上塌了：多门店时右栏有四个按钮（编辑/上架/改库存/本店价），
      按钮把左栏挤到几十 px 宽 —— 商品名只剩一个字、价格与库存换行叠在一起，
      而这正是这一页唯一需要一眼看清的东西。实测 375 宽下「五常大米 10斤装」
      显示成「五」。

      现在：上段是「图 + 名 + 价/库存 + 状态」，下段整宽放按钮并允许换行。
      按钮多一个少一个都不再影响上面那行的可读性。
    -->
    <view v-for="g in list" :key="g.goodsNo" class="sh-card sh-mb-sm">
      <view
        class="row__top sh-row"
        :class="{ 'row__top--tap': merchant.can('biz:goods') }"
        @tap="onRowTap(g)"
      >
        <sh-cover class="row__cover" :src="g.cover"></sh-cover>
        <view class="sh-fill">
          <text class="txt-strong row__title">{{ g.title }}</text>
          <view class="row__meta sh-row sh-row--baseline">
            <text class="txt-strong row__price sh-num txt-primary">{{ money(g.price) }}</text>
            <text class="txt-sub row__stock sh-num" :class="{ 'is-danger': stockOf(g) === 0, 'txt-bold': stockOf(g) === 0 }">
              {{ $t("goods.stock") }} {{ stockOf(g) }}
            </text>
          </view>
        </view>
        <view class="state sh-row" :class="'state--' + stateOf(g)">
          <text class="state__dot"></text>
          <text class="txt-caption state__txt">{{ $t(`goods.status${stateOf(g)}`) }}</text>
        </view>
        <!-- 可点相。只在真的点得动时出现（见 onRowTap 的注释） -->
        <text v-if="merchant.can('biz:goods')" class="row__chev">›</text>
      </view>
      <view class="row__ops">
        <!--
          驳回 / 强制下架的理由。**没有它，商家面对「已驳回」只能猜要改什么** ——
          审计日志只有运营看得到。后端一直在发这个字段，端上此前连声明都没有。
        -->
        <text v-if="g.auditReason" class="txt-caption reason">{{ g.auditReason }}</text>
        <!--
          有未发布修改（双版本草稿）。**线上照卖旧版**，这行是提醒商家
          「你保存过的改动还没生效」—— 点它进发布确认页看差异。
          判据是草稿行存在（内容与线上相同时后端直接删行），所以它出现就意味着
          发布会真的改变线上。只给有发布权的人：店员看得到货，发不了版。
        -->
        <text
          v-if="g.hasDraft && merchant.can('biz:goods')"
          class="txt-caption reason is-warning"
          @tap="toPublish(g)"
        >
          {{ $t("goods.hasDraftRow") }}
        </text>
        <!--
          缺资质。放在状态那一列而不是标题旁边：它回答的是
          「这件货为什么上不了架」，属于状态，不是商品属性。
        -->
        <text v-if="SHOW_CATEGORY_GATE && gateOf(g)" class="txt-caption reason is-warning">
          {{ $t("goods.gateRow") }}
        </text>
        <!--
          ★ **一行最多两个按钮，其余收进「更多」。**

          改版前这里最多同时摆六个（编辑/提交审核/上下架/改库存/分享/本店价），
          全是同一种 `txt-caption mini`，于是**没有一个是主动作** ——
          多门店的在售商品一行五个按钮换行成两排，而其中四个一周也点不到一次。

          留下的两个的取法：
            · 主动作按状态定。每种状态其实只有一件显然该做的事
              （草稿→提交审核、在售→下架、已下架→上架），摆最左并给主色。
              审核中/已驳回没有主动作 —— 它们要做的是「改了再交」，
              而改走的是点商品本身那条路（见 `onRowTap`），不占按钮位。
            · 改库存**恒在**：它是最高频的（生鲜一天改几次），
              也是店员唯一点得动的那一个（biz:stock 不含 biz:goods）。
          其余全进「更多」。收纳走库里的 `pick()` 而不是 uni.showActionSheet ——
          系统面板在四个端上长相各不相同（statement 页的注释里记着同一条）。
        -->
        <view class="row__btns sh-row">
          <text
            v-if="primaryOf(g)"
            class="txt-caption mini mini--primary"
            @tap="runPrimary(g)"
          >{{ primaryLabel(g) }}</text>
          <text v-if="merchant.can('biz:stock')" class="txt-caption mini" @tap="editStock(g)">
            {{ $t("goods.editStock") }}
          </text>
          <!-- 只剩一项时不做成菜单：多一次点击换不来任何东西 -->
          <text
            v-if="moreOf(g).length > 1"
            class="txt-caption mini mini--more"
            @tap="openMore(g)"
          >{{ $t("goods.more") }}</text>
          <text
            v-else-if="soleMoreOf(g)"
            class="txt-caption mini"
            @tap="runSoleMore(g)"
          >{{ soleMoreLabel(g) }}</text>
        </view>
      </view>
    </view>

    <!--
      翻页反馈。**没有它，滚到底会以为「就这些了」** —— 而下一页可能正在路上。
      到底了也要说一声：194 条里滚到最后却什么提示都没有，人会怀疑是不是卡住了。
    -->
    <text v-if="loading && list.length" class="txt-caption more">{{ $t("common.loading") }}</text>
    <text v-else-if="list.length && !hasMore" class="txt-caption more">{{ $t("goods.noMore") }}</text>

    <!--
      新建商品。**建商品/改价属于 biz:goods**；店员只有 biz:stock，不显示这个入口。

      悬浮而不是嵌在顶部工具条里：那里的宽度要留给六个状态页签（它们本来就得横滚），
      而新建是低频高价值的动作 —— 拇指够得到、是全页唯一的主色实心块就够了。
      不放在导航栏右上：`sh-scaffold` 的标题栏在原生包里是系统导航栏，
      那个位置在 App / 小程序 / H5 三端不一致。
    -->
    <sh-fab
      v-if="merchant.can('biz:goods')"
      :text="`＋ ${$t('goods.add')}`"
      @tap="edit()"
    ></sh-fab>

    <!--
      分享单品浮层。**不做成新页面**：这是「顺手转发一下」的动作，跳一页再跳回来
      比弹一层重得多，而且要重新加载列表（跳页会触发 onShow）。
    -->
    <sh-sheet
      :visible="!!sharing"
      :title="sharing ? String($t('goods.shareTitle', { s: sharing.title })) : ''"
      @close="closeShare"
    >
      <text v-if="shareLoading" class="hint">{{ $t("common.loading") }}</text>
      <view v-else class="txt-sub kit">{{ shareText }}</view>
      <view v-if="!shareLoading" class="sh-btn" @tap="copyShareText">{{ $t("store.copyKit") }}</view>

      <!-- 真海报：合成好的一张图。生不出来（极端情况）就不占地方 -->
      <view v-if="poster?.imageBase64" class="poster">
        <image class="poster__img" :src="`data:image/png;base64,${poster.imageBase64}`" mode="widthFix" />
        <view class="sh-btn poster__save" @tap="savePosterImage">{{ $t("store.saveImage") }}</view>
      </view>
    </sh-sheet>
  </sh-scaffold>
</template>

<style scoped>
/* 搜索：贴着筛选条，不套卡片 —— 它是这一页的工具，不是一条内容 */
.search {
}
.search__input {
  flex: 1;
  height: 72rpx;
  color: var(--sh-ink);
}

/* 翻页反馈：弱化到底，它是状态不是内容 */
.more {
  display: block;
  padding: 24rpx 0 8rpx;
  text-align: center;
}
.reason {
  display: block;
  margin-top: 12rpx;
  text-align: end;
}
/* 未发布修改用 .is-warning（库件）：它不是错误，是「有事没做完」；可点，进发布确认页 */
/* 缺资质：用警示色而不是危险色 —— 商品本身没错，缺的是一张证 */
.gate-sum {
  display: block;
}

/*
 * 分栏横向可滚动（五个状态排不下）。作为 flex 子项，它默认按内容宽度撑开、
 * 溢出到「＋ 新建商品」底下，而且**因为自身盒子就等于内容宽度，反而滚不动** ——
 * 表现是最后一个 chip 被压掉半截且够不到，那个状态筛不了。
 *
 * `flex:1 + min-width:0` 给它一个确定且可收缩的宽度，滚动条件就成立了。
 * ⚠️ **不要再加 `overflow: hidden`** —— 试过，那会让 scroll-view 彻底滚不动
 * （裁是裁住了，但用户再也划不到后面的 chip，比溢出更糟）。
 */
/* 类目 chip 横向滚动：一级类目将来可能有七八个，换行会把工具栏顶成两行 */
.cats {
  white-space: nowrap;
}
.cats__row {
  display: inline-flex;
  gap: 12rpx;
}
.cats__chip {
  padding: 8rpx 20rpx;
  /*
   * 两条都要，缺一个都会换行 —— 类目从 3 个扩到 6 个之后才显形：
   *
   * · `white-space`：uni 的 `<text>` 自带 `pre-line`，会**盖掉**父级 `.cats` 上的
   *   nowrap（实测 computed 就是 pre-line），于是「食品生鲜」断成两行
   * · `flex-shrink`：父级是 inline-flex 但被容器宽度框住，默认 shrink=1 时
   *   6 个 chip 会被压到 33px 宽而不是横向溢出滚动（实测 computed w=33.5px）
   */
  white-space: nowrap;
  flex-shrink: 0;
}

/* 列表密度对齐 C 端（平台版式约定）：卡片之间只留一条缝。
   商家一天要扫几十次这类列表，行距每多 10rpx，一屏就少一行。 */

/* 上段：图 + 名/价 + 状态。状态贴右，名字吃掉中间所有剩余宽度 */
.row__top {
  gap: 20rpx;
}
.row__top--tap:active {
  opacity: 0.6;
}
.row__chev {
  margin-left: 4rpx;
  /* --sh-sub 不是 --sh-faint：后者是 #E4E5E8，分隔线那一档，白底上量出来几乎看不见 */
  color: var(--sh-sub);
  font-size: 32rpx;
  line-height: 1;
}
.row__cover {
  font-size: 60rpx;
  width: 96rpx;
  height: 96rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  text-align: center;
  line-height: 96rpx;
}

/*
 * **标题是这一页的识别锚点，要压过价格。**
 * 原先价格 30rpx/700 深红、标题 28rpx/600（字阶只到 30，所以标题取 30、价格降到 26） —— 商家扫列表是在找「哪个商品」，
 * 最抢眼的却是它的价格。维护页与 C 端商品卡的重点本来就相反：
 * 那边卖东西，价格该跳出来；这边管东西，名字才是入口。
 *
 * 单行省略：长名换行会把卡片撑高，一屏少一行 —— 194 条的列表里，
 * 每屏少一行就是多滚五屏。
 */
.row__title {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.row__meta {
  margin-top: 8rpx;
}
/* 降到属性档：仍是深红（可读性由 primary-text 保证），但不再抢标题的位 */

/* 卖完了要一眼扫得到 —— 它是「今天要干的活」，而 0 和 180 现在长得一样 */
/* 状态：色点 + 文字，**无底色** —— 与动作按钮在形态上分开。
   原先它和「编辑/改库存」同样是灰底圆角：一屏六行、每行三个圆角块，
   人得逐个试才知道哪个能按。状态是状态，不是动作。 */
.state {
  gap: 8rpx;
  flex: none;
}
.state__dot {
  width: 12rpx;
  height: 12rpx;
  border-radius: 9999px;
  background: var(--sh-sub);
}

.state--ON_SALE .state__dot {
  background: var(--sh-success);
}
.state--ON_SALE .state__txt {
  color: var(--sh-ink);
}
.state--PENDING .state__dot {
  background: var(--sh-warning);
}
.state--REJECTED .state__dot {
  background: var(--sh-danger);
}
.state--REJECTED .state__txt {
  color: var(--sh-danger);
}
.row__ops {
  text-align: end;
}
/* 按钮整宽一行、允许换行：四个按钮在 375 宽下正好排得下，
   五个（将来再加）就换行，而不是把上面那行挤没 */
.row__btns {
  justify-content: flex-end;
  align-items: center;
  gap: 16rpx;
  margin-top: 16rpx;
}
.mini {
  padding: 8rpx 16rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
}
/*
 * 主动作给主色。改版前六个按钮长得一模一样，**哪个是这一行该做的事无从看出** ——
 * 而每种状态其实只有一件（草稿→提交审核、在售→下架…）。
 */
.mini--primary {
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
}
/* 「更多」是收纳口不是动作：不给底色，免得看着像第三个并列按钮 */
.mini--more {
  background: transparent;
  padding-left: 8rpx;
  padding-right: 8rpx;
  color: var(--sh-sub);
}

/* 分享单品浮层：底部弹出，与 biz-region-picker 的 .sheet 同一形态，商家不用重新学 */
.poster {
  margin-top: 20rpx;
}
.poster__img {
  width: 100%;
  border-radius: 24rpx;
  border: var(--sh-hairline);
}
.poster__save {
  margin-top: 16rpx;
}
.kit {
  margin-bottom: 24rpx;
  padding: 24rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  color: var(--sh-ink);
}
</style>
