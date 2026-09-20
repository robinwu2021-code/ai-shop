<script setup lang="ts">
// 商品详情（五态）：标品 / 生鲜 / 服务 / 虚拟 / 卡券。
// 五态共用一套骨架，差异只落在「规格矩阵 → 事实区 → 底部条」三处，
// 与 strategies（计价 + 履约）的分层保持一致。
//
// v2（2026-09-19，原型 prototypes/c-goods-group.html g01–g04）按常用详情页的骨架排：
// 主图顶到状态栏，「返回 · 购物车」浮在图左上（右上是微信胶囊，谁都不能放）；
// 分享在标题旁；「已选」「范围」两行去掉 —— 规格与件数在点底栏按钮后的面板里选，
// 销售区域进商品参数；底栏从五格减到三格（店铺 · 加入购物车 · 立即购买）。
// 往下滑过主图后顶部换成实色导航，带「商品 / 评价 / 详情」三个锚点。
import { computed, getCurrentInstance, nextTick, onUnmounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad, onPageScroll, onShareAppMessage } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useCartStore } from "@/stores/cart";
import { useUserStore } from "@/stores/user";
import { useCommunityStore } from "@/stores/community";
import { buildShareMessage, canNativeShare } from "@shared/ports/share";
import { navBox as readNavBox } from "@shared/ports/capsule";
import { CATEGORY_TYPE, FEATURES, FULFILLMENT, ROUTES, TRADE_RULES } from "@shared/utils/constants";
import { countdown, money } from "@shared/utils/format";
import {
  clearCartAnchor,
  flyState,
  flyToCart,
  registerCartAnchor,
  tapPoint,
} from "@/shared/fly";
import { buyNGetM, giftQtyFor, promoLabelArgs } from "@shared/utils/promotion";
import { scrollToTop, scrollToY } from "@ai-shop/ui/scroll";
import { defaultFulfillment } from "@shared/utils/goods";
import type { Coupon, Goods, GoodsBatch, GoodsGroup, Review, Sku } from "@shared/types";

const { t } = useI18n();
const cart = useCartStore();
const user = useUserStore();
const community = useCommunityStore();
/** 小程序才有原生分享按钮；H5 与团购页同一约定：不显示 */
const nativeShare = canNativeShare();

const goods = ref<Goods | null>(null);
const reviews = ref<Review[]>([]);

/**
 * 顶部轮播的图。**封面排第一** —— 它是买家在列表里点进来时看到的那张，
 * 详情页第一屏换成另一张会让人怀疑点错了。
 *
 * <p>去重：商家常把封面也放进详情图里，不去重就会连着出现两张一样的。
 */
const gallery = computed<string[]>(() => {
  const g = goods.value;
  if (!g) return [];
  return [g.cover, ...(g.images ?? [])].filter((x, i, arr) => x && arr.indexOf(x) === i);
});
/** 各规格维度上当前选中的取值，下标与 specGroups 对齐 */
const chosen = ref<string[]>([]);
const qty = ref(1);
const now = ref(Date.now());
/** 预约：选中的日期与时刻 */
const slotDate = ref("");
const slotTime = ref("");
let timer: ReturnType<typeof setInterval> | undefined;

const isFresh = computed(() => goods.value?.type === CATEGORY_TYPE.FRESH);

/**
 * 商品参数里是否已经有产地。
 *
 * <p>有的话就不再显示 `prd_goods.origin` 那一列 —— 那是参数落地之前的老字段
 * （建品页里那个自由输入框已经撤掉了）。两处都显示的话，
 * 买家会看到两个产地，而谁也说不清哪个算数。
 *
 * <p>按**维度名**判而不是按 dimNo：平台的产地维度在不同类目下是不同的 dimNo
 * （SD_ORIGIN / SD_ORIGIN_F …），认编号会漏。
 */
const hasOriginParam = computed(
  () => (goods.value?.params ?? []).some((p) => p.name === "产地" || p.dimNo.includes("ORIGIN")),
);
/**
 * 销售范围那一行的文案。空串 = 整行不渲染。
 *
 * 三支各有一句，且**「空」与「不限」必须分开**：
 * 后端已经把「同一个空数组两种意思」判完了（只做自提的空 = 谁也看不到，
 * 开了配送的空 = 不限），端上只读 `unlimited`。
 * 在这儿按 `areaNames.length` 推的话，会把前一种说成「不限地区」——
 * 一句正好相反的承诺，而页面上看不出任何异常。
 */
const saleScopeText = computed(() => {
  const s = goods.value?.saleScope;
  if (!s) {
    return "";
  }
  if (s.unlimited) {
    return t("goods.scopeUnlimited");
  }
  if (!s.areaNames.length) {
    return "";
  }
  const names = s.areaNames.join("、");
  return s.areaCount > s.areaNames.length
    ? t("goods.scopeMore", { names, n: s.areaCount })
    : names;
});
const isService = computed(() => goods.value?.type === CATEGORY_TYPE.SERVICE);
const isVirtual = computed(() => goods.value?.type === CATEGORY_TYPE.VIRTUAL);
const isCard = computed(() => goods.value?.type === CATEGORY_TYPE.CARD);
const needAppointment = computed(() =>
  goods.value?.fulfillments.includes(FULFILLMENT.APPOINTMENT),
);

/** 选中的组合命中哪个 SKU（多规格矩阵） */
const sku = computed<Sku | null>(() => {
  const g = goods.value;
  if (!g) return null;
  return (
    g.skus.find((s) => s.optionValues.every((v, i) => v === chosen.value[i])) ?? null
  );
});

/**
 * 某个维度上的某个取值是否还可选：
 * 固定其它维度的当前选择，看是否存在有货的 SKU。
 * 没有这层判断，多规格商品会让用户选出一个根本不存在的组合。
 */
function optionState(groupIndex: number, option: string) {
  const g = goods.value;
  if (!g) return { exists: false, inStock: false };
  const probe = [...chosen.value];
  probe[groupIndex] = option;
  const matches = g.skus.filter((s) =>
    s.optionValues.every((v, i) => (i === groupIndex ? v === option : v === probe[i])),
  );
  return {
    exists: matches.length > 0,
    inStock: matches.some((s) => s.stock > 0),
  };
}

const cutoffPassed = computed(
  () => !!goods.value?.cutoffAt && now.value > goods.value.cutoffAt,
);
const cutoffText = computed(() =>
  goods.value?.cutoffAt ? countdown(goods.value.cutoffAt - now.value) : "",
);
const soldOut = computed(() => (sku.value?.stock ?? 0) <= 0);
const appointmentReady = computed(
  () => !needAppointment.value || (!!slotDate.value && !!slotTime.value),
);
const buyable = computed(
  () => !!sku.value && !soldOut.value && !cutoffPassed.value && appointmentReady.value && !outOfScope.value,
);

/**
 * 买不了是**为什么**。空串 = 买得了，或者**已经有别的地方说过了**。
 *
 * `buyable` 有四条否决条件，而按钮文案只区分两条（「已售罄」/「加入购物车」）、
 * 截单另有一枚红 chip —— 剩下「没选预约时段」那一条，此前页面上一个字都没有：
 * 两个按钮双双变灰，而屏幕上没有任何解释。
 *
 * **判据与 `buyable` 共用同一份**，不写成并排的两套 if ——
 * 分开写的话，第五个条件加进 `buyable` 时这里不会跟着变，
 * 于是又回到「灰着，不说话」。
 *
 * 售罄与截单**故意返回空串**：那两件事已经说过了，
 * 同一件事说两遍会让人以为是两个问题（与结算页「一次只说一条」同源）。
 */
/**
 * 送不到你那儿（原型 g05）：**只在后端明确说 false 时**拦 —— null / 缺省是「没判」，不是「送不到」。
 * 判据是收货地址推出来的社区，与首页商品池同一份（TDD-C端商品收藏与送达判断）。
 */
const outOfScope = computed(() => goods.value?.deliverable === false);

const buyBlockedReason = computed(() => {
  if (!goods.value) return "";
  if (outOfScope.value) return String(t("goods.whyOutOfScope", { scope: saleScopeText.value || "—" }));
  if (activityClosed.value) return String(t("goods.whyActivityOnly"));
  if (!sku.value) return String(t("goods.whyNoSku"));
  if (soldOut.value || cutoffPassed.value) return "";
  if (!appointmentReady.value) return String(t("goods.whyNoSlot"));
  return "";
});

/** 买 N 送 M 促销（一期一个商品最多一条） */
const promo = computed(() => buyNGetM(goods.value?.promotions));
/** 按当前购买数量算出的赠品件数 */
const giftQty = computed(() => giftQtyFor(promo.value, qty.value));


/** 主图轮播当前是第几张（右下角「1/N」） */
const heroAt = ref(0);

/** 库存到这个数以下才告诉买家「仅剩 N 件」—— 平时的库存数对买家没有意义 */
const LOW_STOCK_AT = 10;
/** 0 = 不说 */
const lowStock = computed(() => {
  const n = sku.value?.stock ?? 0;
  return n > 0 && n <= LOW_STOCK_AT ? n : 0;
});

/** 比划线价省了多少（最小货币单位）。0 = 没有划线价或不比它便宜，不显示 */
const saved = computed(() => {
  const s = sku.value;
  return s?.originPrice && s.originPrice > s.price ? s.originPrice - s.price : 0;
});

/** 价格下面那排小标签有没有内容 —— 全空时整排不渲染，不留一道空缝 */
const hasChips = computed(() => {
  const g = goods.value;
  if (!g) return false;
  return (isFresh.value && !!cutoffText.value && !cutoffPassed.value) || cutoffPassed.value
    || (isService.value && !!g.durationMin) || isVirtual.value
    || (isCard.value && !!(g.card?.timesTotal || g.card?.faceValueMinor))
    || !!promo.value || (FEATURES.points && !!g.points) || g.sales > 0 || lowStock.value > 0;
});

/** 商品参数卡有没有内容 —— 限购只在真有限购时算 */
const hasParams = computed(() => {
  const g = goods.value;
  if (!g) return false;
  return !!g.params?.length || (isFresh.value && !!g.origin && !hasOriginParam.value)
    || (isService.value && !!g.storeName) || (isCard.value && !!g.card)
    || !!g.limitPerUser || !!g.weighed || (isVirtual.value && !!g.virtual)
    // v2 起销售区域在参数里：只有它时参数卡也要出
    || !!saleScopeText.value;
});

/** 多规格才需要先弹面板；单规格直接按 1 件执行 */
const multiSku = computed(() => (goods.value?.skus.length ?? 0) > 1);
const showSku = ref(false);
/**
 * 面板是被哪颗按钮叫出来的。面板底部**只放那一个动作** ——
 * 点的是「加入购物车」，面板里就只有「加入购物车」，不再让人在面板里二选一（原型 g03）。
 */
type SheetMode = "add" | "buy" | "group";
const sheetMode = ref<SheetMode>("add");
function openSheet(mode: SheetMode) {
  sheetMode.value = mode;
  showSku.value = true;
}
/** 「已选」那一行：规格 · 件数 */
const chosenText = computed(() =>
  String(t("goods.chosenValue", { spec: sku.value?.spec || chosen.value.join(" "), n: qty.value })),
);
/**
 * 底栏按钮点不点得动。多规格时**恒可点** —— 它的作用是打开面板，
 * 当前选中的规格卖完了，他还得能进面板换一个；单规格时就是 buyable。
 */
const barReady = computed(() => !outOfScope.value && (multiSku.value || buyable.value));

/**
 * 能不能走普通下单（加购 / 立即购买 / 单买）—— 仅活动可售（TDD-商品仅活动可售 §5）。
 * **后端算、这里只读**：特价、买赠、平台活动端上并不知道，自己拼就是第二个判定入口，
 * 迟早与下单那道闸对不上。老后端不发这个字段时按「能买」处理（undefined ≠ false）。
 */
const directBuyable = computed(() => goods.value?.directBuyable !== false);
/** 仅活动、且此刻什么路都没开：普通下单不行，也没有拼团可开 */
const activityClosed = computed(() => !directBuyable.value && !grp.value);

/** 当前选中日期的可选时刻 */
const times = computed(
  () => goods.value?.slots?.find((s) => s.date === slotDate.value)?.times ?? [],
);

/** 这次没取到。**与「这个东西不存在」是两件事** —— 整页都挂在 `goods` 后面，
 *  拉不到连外壳都不渲染，是一整块白屏：没有导航栏、没有一个字、退不回去 */
const failed = ref(false);
/** 重试要把单号带回去 —— `@retry` 不带参数 */
const currentNo = ref("");

/**
 * 社区集单（原型 s26 · ADR-024）：几点截单、哪天到哪取、这一期已订多少。
 * 买家要记住的只有两个时间，所以只给这三行；集单价已经是上面那个现价。
 */
const batch = ref<GoodsBatch | null>(null);

/**
 * 拼团（原型 s21）：正在拼的团（最多 3 个）与「开团 ¥8」。与集单块一样**独立加载**：
 * 挂在详情的加载链上的话，它失败会把整页拖成「加载失败」，而它只是一个可选的块。
 */
const grp = ref<GoodsGroup | null>(null);

/**
 * 底栏那颗「开团 ¥X」**暂时关掉**（用户 2026-09-20）。
 *
 * <p>关它的直接原因是底栏：拼团商品的底栏是「店铺 · 单买 ¥50.00 · 开团 ¥5.00」，
 * 三样挤一行，两颗按钮被压到只剩几个字宽，**而「加入购物车」整个没有位置** ——
 * 详情页最常用的那个动作反而不在。关掉之后底栏回到「店铺 · 加入购物车 · 立即购买」。
 *
 * <p><b>团没有被删</b>：页面上方那张团卡（有团时「去拼团」）照旧，首页与团列表的入口也都在。
 * 关掉的只是「从详情页发起一个新团」这一条。要放回来把这个常量改成 true 就行。
 */
const SHOW_GROUP_CTA = false;

/** 取不到按「没有团」算 —— 拼团是补充信息，不该拖垮详情 */
async function fetchGroup(goodsNo: string): Promise<GoodsGroup | null> {
  // async + try 而不是 `.catch()`：调用本身同步抛错（比如接口不存在）时，`.catch` 接不住，
  // 整个 load() 就断在这儿、页面一片空白 —— 与「补充信息不拖垮详情」正好相反
  try {
    return await api.goodsGroup(goodsNo);
  } catch {
    return null;
  }
}

/** 某个团离截止还有多久。每秒跟着 now 走（本页已有的时钟） */
function groupLeft(expireAt: number): string {
  return countdown(expireAt - now.value);
}

function openGroupPage(groupNo: string) {
  uni.navigateTo({ url: `${ROUTES.group}?groupNo=${groupNo}` });
}

/**
 * 领券（原型 s36）：这家店的券与平台券，在「领券」那一行里领。
 * 行尾一个字的状态 —— 能领是红字「领取」，领过是灰字「已领」；不做成按钮，避免一屏一排红胶囊。
 * 同样独立加载：取不到就不出这一行，不拖垮详情。
 */
const coupons = ref<Coupon[]>([]);
const showCoupons = ref(false);
const claiming = ref("");

/** 取不到就不出「领券」那一行 —— 同样不拖垮详情 */
async function fetchCoupons(): Promise<Coupon[]> {
  try {
    return await api.couponList();
  } catch {
    return [];
  }
}

/** 这件商品能用的券：本店的 + 平台出资的，且没过期。要等详情回来才知道是哪家店 */
function couponsFor(all: Coupon[], g: Goods): Coupon[] {
  return all.filter((c) => c.endAt > Date.now()
    && (c.merchantNo === g.merchant.merchantNo || c.funder === "PLATFORM"));
}

/** 「满 50 减 5」「9 折 · 封顶 ¥20」 */
function couponRuleText(c: Coupon): string {
  if (c.type === "DISCOUNT") {
    return String(t("goods.couponRate", { n: (c.discountRate / 1000).toFixed(1).replace(/\.0$/, ""),
      cap: money(c.maxDiscountMinor) }));
  }
  return c.thresholdMinor
    ? String(t("goods.couponCut", { m: money(c.thresholdMinor), n: money(c.faceMinor) }))
    : String(t("goods.couponCutAny", { n: money(c.faceMinor) }));
}

function couponUntil(c: Coupon): string {
  const d = new Date(c.endAt);
  return String(t("goods.couponUntil", {
    d: `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`,
  }));
}

async function claim(c: Coupon) {
  if (c.received || claiming.value) return;
  claiming.value = c.couponNo;
  try {
    await api.receiveCoupon(c.couponNo);
    c.received = true;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    claiming.value = "";
  }
}

/** 取不到按「没有集单」算 */
async function fetchBatch(goodsNo: string): Promise<GoodsBatch | null> {
  try {
    return await api.goodsBatch(goodsNo);
  } catch {
    return null;
  }
}

function batchDay(ms: number): string {
  const d = new Date(ms);
  return `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function batchTime(ms: number): string {
  const d = new Date(ms);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

/** 首屏补充信息最多等这么久。超过就先出页面，它们到了再补上 —— 宁可偶尔晚一拍，不让整页干等 */
const FIRST_SCREEN_WAIT_MS = 800;

/** p 在 ms 内落地就用它的值；没落地返回 undefined（调用方据此决定「晚到再补」） */
function within<T>(p: Promise<T>, ms: number): Promise<T | undefined> {
  return Promise.race([p, new Promise<undefined>((r) => setTimeout(() => r(undefined), ms))]);
}

async function load(goodsNo: string) {
  currentNo.value = goodsNo;
  /*
   * **首屏会用到的三份一起发、一起落。**
   *
   * 此前是先等详情、渲染整页，再去取券与拼团 —— 它们晚 200ms 回来，
   * 领券那一行插在价格卡下面，把商家卡、规格整片往下推 61px；
   * 同一刻底部按钮从「加入购物车 / 立即购买」换成「单买 / 开团」。
   * 用户看到的就是点进来之后「跳一下」（2026-09-19 逐次记录 DOM 变化量出来的）。
   *
   * 这两个请求本来就不依赖详情：券只是回来后要按商家过滤，拼团按商品号取。
   * 所以三个同时发，总耗时约等于最慢那个，正常情况下与原来持平；
   * 等它们都有了结果再给 goods 赋值 —— goods 一有值整页才渲染，于是只渲染一次。
   *
   * 失败不拖垮：fetchGroup / fetchCoupons 自己吞掉错误，落成「没有」。
   * 太慢不干等：超过 FIRST_SCREEN_WAIT_MS 就先出页面，晚到的再补。
   */
  const groupP = fetchGroup(goodsNo);
  const couponsP = fetchCoupons();
  const batchP = fetchBatch(goodsNo);
  try {
    // 带上收货地址推出来的社区：后端据此判「卖不卖到你那儿」（原型 g05）。
    // 只有模糊定位时没有社区号 —— 那就不判，只准到区，拿它判会误拦
    const g = await api.goodsDetail(goodsNo, community.community?.communityNo);
    const [grpNow, allNow, batchNow] = await Promise.all([
      within(groupP, FIRST_SCREEN_WAIT_MS),
      within(couponsP, FIRST_SCREEN_WAIT_MS),
      within(batchP, FIRST_SCREEN_WAIT_MS),
    ]);
    grp.value = grpNow ?? null;
    coupons.value = allNow ? couponsFor(allNow, g) : [];
    batch.value = batchNow ?? null;
    // 最后才给 goods 赋值：上面两样先就位，第一次渲染就是完整的首屏
    goods.value = g;
    // 晚到的补上（只在超时那种少见情况下发生）
    if (grpNow === undefined) void groupP.then((v) => { if (currentNo.value === goodsNo) grp.value = v; });
    if (allNow === undefined) void couponsP.then((v) => { if (currentNo.value === goodsNo) coupons.value = couponsFor(v, g); });
    // 社区集单块（s26）也一起并发：它通常在首屏以下，但高屏手机（896）上刚好露在底边，
    // 晚到就是一块从底下冒出来。失败照样吞掉 —— 不会让下面默认选规格那几步跑不了
    if (batchNow === undefined) void batchP.then((v) => { if (currentNo.value === goodsNo) batch.value = v; });
  // 默认选中第一个有货的 SKU 的组合
  const first = g.skus.find((s) => s.stock > 0) ?? g.skus[0];
  chosen.value = first ? [...first.optionValues] : [];
  slotDate.value = g.slots?.[0]?.date ?? "";
  uni.setNavigationBarTitle({ title: g.title });
  measureCartAnchor();
    reviews.value = await api.reviewList({ goodsNo });
    failed.value = false;
    // 评价到了，下面两段的位置变了 —— 锚点重新量
    measureAnchors();
  } catch {
    failed.value = true;
  }
}

function openMerchant() {
  uni.navigateTo({ url: `${ROUTES.merchant}?merchantNo=${goods.value?.merchant.merchantNo}` });
}

async function likeReview(r: Review) {
  const updated = await api.toggleReviewLike(r.reviewNo);
  const i = reviews.value.findIndex((x) => x.reviewNo === r.reviewNo);
  if (i >= 0) reviews.value[i] = updated;
}

function choose(groupIndex: number, option: string) {
  const state = optionState(groupIndex, option);
  if (!state.exists) return;
  const next = [...chosen.value];
  next[groupIndex] = option;
  chosen.value = next;
}

/**
 * 这一次最多能买几件：**限购与库存取小**。
 *
 * ⚠️ 此前只封限购，于是「库存 3 件、买 50 件」一路走到提交才被后端的
 * 锁库存拒 —— 加购不拦、试算也不拦（预览**刻意**不锁库存，因为用户会在
 * 结算页反复改地址）。后端那一道必须留着，它才是真源；端上这一层
 * 只是让人早点知道。
 *
 * 两个上限缺省都是「不限」而不是 0 —— 缺省当 0 的话，没带库存的商品
 * 一件都买不了，而且只在那些环境里才出现（与购物车同一条口径）。
 */
const maxQty = computed(() =>
  Math.min(goods.value?.limitPerUser || Infinity, sku.value?.stock ?? Infinity),
);

/*
 * 换规格时数量要跟着回落。
 * 不回落的话：「选了还剩 99 件的 A 规格、买 50 件，再切到只剩 3 件的 B 规格」，
 * 数量还停在 50 —— 屏幕上是一个当场就买不成的数。
 */
watch(maxQty, (max) => {
  if (qty.value > max) qty.value = Math.max(1, max === Infinity ? qty.value : max);
});

async function addToCart(e: unknown) {
  const g = goods.value;
  if (!g || !sku.value) return;
  try {
    await cart.add(g.goodsNo, sku.value.skuNo, qty.value);
    const p = tapPoint(e as Parameters<typeof tapPoint>[0]);
    flyToCart(p.x, p.y, g.cover);
  } catch (err) {
    uni.showToast({ title: (err as Error).message, icon: "none" });
  }
}

/**
 * 开团：与立即购买同一条路（先加购再进结算），只是带上 openGroup —— 团在下单那一刻建，
 * 付了款我就是第一人。团价由后端按活动算，端上只负责把意图带过去。
 */
async function openGroupBuy() {
  const g = goods.value;
  if (!g || !sku.value || !buyable.value) return;
  try {
    await cart.add(g.goodsNo, sku.value.skuNo, 1);
    uni.navigateTo({
      url: `${ROUTES.orderConfirm}?fulfillment=${defaultFulfillment(g)}&skus=${sku.value.skuNo}&openGroup=1`,
    });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 立即购买：先加购再进结算 —— 结算页统一从购物车取数，不另开一条「直购」链路 */
async function buyNow() {
  const g = goods.value;
  if (!g || !sku.value || !buyable.value) return;
  try {
    await cart.add(g.goodsNo, sku.value.skuNo, qty.value);
    const f = defaultFulfillment(g);
    const at = needAppointment.value && slotDate.value && slotTime.value
      ? new Date(`${slotDate.value}T${slotTime.value}:00`).getTime()
      : undefined;
    uni.navigateTo({
      url: `${ROUTES.orderConfirm}?fulfillment=${f}&skus=${sku.value.skuNo}` +
        (at ? `&appointmentAt=${at}` : ""),
    });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 底栏「加入购物车」：多规格先弹面板，单规格直接加 */
function tapAdd(e: unknown) {
  if (multiSku.value) return openSheet("add");
  if (buyable.value) void addToCart(e);
}

/** 底栏「立即购买 / 单买」：同上 */
function tapBuy() {
  if (multiSku.value) return openSheet("buy");
  if (buyable.value) void buyNow();
}

/** 底栏「开团」：同上 */
function tapGroup() {
  if (multiSku.value) return openSheet("group");
  if (buyable.value) void openGroupBuy();
}

/** 面板里的按钮：先收面板再执行，否则跳结算页时面板还盖在上一页 */
function sheetAdd(e: unknown) {
  if (!buyable.value) return;
  showSku.value = false;
  void addToCart(e);
}
function sheetBuy() {
  if (!buyable.value) return;
  showSku.value = false;
  void buyNow();
}
function sheetGroup() {
  if (!buyable.value) return;
  showSku.value = false;
  void openGroupBuy();
}

function gotoCart() {
  uni.switchTab({ url: ROUTES.cart });
}

/** 送不到时那一行的「换地址」：去收货地址页，换一条回来详情会按新社区重判 */
function gotoAddress() {
  uni.navigateTo({ url: ROUTES.address });
}

/**
 * 收藏 / 取消（原型 g07）。没登录先静默登录（小程序里无感）；静默失败才去登录页。
 * 状态以后端回的为准，不在端上自己取反 —— 连点两下时两次请求的先后不保证。
 */
const faving = ref(false);
async function toggleFavorite() {
  const g = goods.value;
  if (!g || faving.value) return;
  if (!user.isLogin) await user.silentLogin().catch(() => {});
  if (!user.isLogin) {
    uni.navigateTo({ url: ROUTES.login });
    return;
  }
  faving.value = true;
  try {
    const { favorited } = await api.toggleFavoriteGoods(g.goodsNo);
    g.favorited = favorited;
    uni.showToast({ title: String(t(favorited ? "goods.favDone" : "goods.favUndone")), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    faving.value = false;
  }
}

/**
 * 左上的返回。**从分享卡片进来时栈里只有这一页** —— navigateBack 什么都不做，
 * 那颗箭头就成了点不动的摆设；这时回首页。
 */
function goBack() {
  if (getCurrentPages().length > 1) uni.navigateBack();
  else uni.switchTab({ url: ROUTES.home });
}

/** 顶部浮层的位置：小程序上对齐微信胶囊，H5 / App 从状态栏往下（端差异在 ports/capsule） */
const navBox = readNavBox();
/** 浮层整条的高度（到胶囊下沿再留 6px） */
const barH = navBox.top + navBox.height + 6;
const topbarStyle = { height: `${barH}px` };
const topRowStyle = {
  top: `${navBox.top}px`,
  height: `${navBox.height}px`,
  // 右边让出胶囊的位置，锚点再多也不会钻到它底下
  paddingRight: `${navBox.right + 8}px`,
};
const btnStyle = { width: `${navBox.height}px`, height: `${navBox.height}px` };

/** 主图高度（px）：560rpx 按屏宽换算。滑过它，顶部就换成实色导航 */
const HERO_RPX = 560;
const heroPx = (HERO_RPX * navBox.winW) / 750;
const solid = ref(false);

/** 三个锚点。「商品」回到顶，另两个滚到对应那一段的上沿（让出实色导航的高度） */
const ANCHORS = [
  { key: "top", label: "goods.anchorGoods" },
  { key: "reviews", label: "goods.anchorReviews" },
  { key: "detail", label: "goods.anchorDetail" },
] as const;
type AnchorKey = (typeof ANCHORS)[number]["key"];
const activeAnchor = ref<AnchorKey>("top");
/** 各段的绝对上沿（px）。页面渲染完量一次；滚动时拿它判当前在哪一段 */
const anchorTops = ref<Record<string, number>>({});

/** @param then 量完之后要做的事（点锚点时：现量现滚 —— 详情长图晚到，早先量的位置会过期） */
function measureAnchors(then?: () => void) {
  nextTick(() => {
    // 量不到（非小程序 / H5 运行时、单测环境）就不量：锚点只是便利，不能拖垮页面
    if (typeof uni.createSelectorQuery !== "function") return;
    const q = uni.createSelectorQuery().in(instance?.proxy);
    q.selectViewport().scrollOffset(() => {});
    q.select("#sec-reviews").boundingClientRect(() => {});
    q.select("#sec-detail").boundingClientRect(() => {});
    q.exec((res: Array<{ scrollTop?: number; top?: number } | null>) => {
      const st = res[0]?.scrollTop ?? 0;
      const tops: Record<string, number> = {};
      if (res[1]?.top != null) tops.reviews = res[1].top + st;
      if (res[2]?.top != null) tops.detail = res[2].top + st;
      anchorTops.value = tops;
      then?.();
    });
  });
}

function jump(key: AnchorKey) {
  activeAnchor.value = key;
  if (key === "top") {
    scrollToTop();
    return;
  }
  // 现量现滚：详情长图、评价晚到，早先量的位置会过期。
  // 不用 pageScrollTo 的 selector + offsetTop：H5 上 offsetTop 被忽略，那一段会钻到实色导航底下。
  // 走 @ai-shop/ui/scroll：桌面 H5 的滚动条在应用框里，直接 pageScrollTo 会静默无效。
  // （内容短时滚不到那么深是正常的 —— 已经到底了）
  measureAnchors(() => {
    const top = anchorTops.value[key];
    if (top != null) scrollToY(Math.max(0, top - barH));
  });
}

onPageScroll((e: { scrollTop: number }) => {
  solid.value = e.scrollTop > heroPx - barH;
  const y = e.scrollTop + barH + 1;
  const t = anchorTops.value;
  activeAnchor.value = t.detail != null && y >= t.detail ? "detail"
    : t.reviews != null && y >= t.reviews ? "reviews" : "top";
});

// 本页的飞入落点是操作条上的购物车入口，不是底部菜单（本页没有菜单）。
// ⚠️ 操作条挂在 `v-if="goods"` 下面 —— onMounted 时商品还没加载，元素不存在，量不到。
// 必须等数据到位、DOM 渲染完再量，否则动效会悄悄退回到「屏幕右下角」的兜底落点。
const instance = getCurrentInstance();
const bouncing = ref(false);

function measureCartAnchor() {
  // 落点是左上角浮着的那个购物车（v2 起底栏不再有购物车）
  nextTick(() => registerCartAnchor(".topbar__cart", instance?.proxy));
  measureAnchors();
}

// 离开本页时撤销落点，交还给 tab 页的底部菜单
onUnmounted(() => clearCartAnchor());

watch(
  () => flyState.landTick,
  () => {
    bouncing.value = false;
    nextTick(() => {
      bouncing.value = true;
      setTimeout(() => (bouncing.value = false), 420);
    });
  },
);

onLoad((q) => {
  const no = (q?.goodsNo as string) || "";
  if (no) load(no);
  timer = setInterval(() => (now.value = Date.now()), 1000);
});

onUnmounted(() => clearInterval(timer));

onShareAppMessage(() =>
  buildShareMessage({
    title: goods.value?.title ?? "",
    path: `${ROUTES.goods}?goodsNo=${goods.value?.goodsNo ?? ""}`,
    merchantNo: community.pickup?.hostMerchantNo,
    inviterNo: user.user?.cUserNo,
  }),
);
</script>

<template>
  <!-- immersive：不画标题栏，主图顶到状态栏；返回与购物车由下面的浮层画 -->
  <sh-scaffold
    immersive
    :pending="!goods"
    :failed="failed"
    @retry="() => load(currentNo)"
  >
    <!-- 正文全靠 `goods` 解引用，所以要一层 `v-if` 让 vue-tsc 收窄类型。
         **不写在 `<sh-scaffold>` 上**：写在那儿的话，`goods` 为空时连外壳都不渲染 ——
         没有导航栏、没有一个字，退不回去。守卫留在这里，外壳照常在。 -->
    <template v-if="goods">
        <!--
          顶部浮层（原型 g01 / g02）。压在主图上时是两颗半透明圆钮；滑过主图变实色导航，
          带「商品 / 评价 / 详情」锚点。右边让出微信胶囊的位置（navBox.right）。
          购物车在这里而不在底栏：随时看得到件数、点得到，又不占底栏。
        -->
        <view class="topbar" :class="{ 'is-solid': solid }" :style="topbarStyle">
          <view class="topbar__row sh-row" :style="topRowStyle">
            <view class="topbar__btn sh-center sh-hit" :style="btnStyle" @tap="goBack">
              <sh-icon name="chevronLeft" :size="34" :color="solid ? 'var(--sh-ink)' : '#fff'"></sh-icon>
            </view>
            <view class="topbar__btn topbar__cart sh-center sh-hit" :class="{ 'is-bouncing': bouncing }" :style="btnStyle" @tap="gotoCart">
              <sh-icon name="cart" :size="32" :color="solid ? 'var(--sh-ink)' : '#fff'"></sh-icon>
              <text v-if="cart.count" class="sh-badge-count topbar__badge sh-num">
                {{ cart.count > 99 ? "99+" : cart.count }}
              </text>
            </view>
            <view v-if="solid" class="sh-fill sh-row topbar__anchors">
              <text
                v-for="a in ANCHORS"
                :key="a.key"
                class="txt-body topbar__anchor"
                :class="activeAnchor === a.key ? 'is-on' : 'txt-quiet'"
                @tap="jump(a.key)"
              >{{ $t(a.label) }}</text>
            </view>
          </view>
        </view>

        <!-- 主视觉 -->
        <!--
          主视觉。**此前只画 cover 一张** —— `goods.images` 后端一直在发、
          商家在 B 端也一直传得进去，而这个页面里一次都没引用过：
          店主传了五张详情图，买家一张也看不到，两侧都不报错。

          只有一张时不套 swiper：一个滑不动的轮播还带着一个指示点，
          看着像坏了。
        -->
        <!--
          通栏，不带边距与圆角（原型「优化后」）。折扣不再压在图上 —— 挪到价格旁边说「省多少」，
          一个百分比贴在图上，买家要自己去和价格对上号。
          多张时右下角是「1/N」计数而不是指示点：点在浅色图上看不见。
        -->
        <swiper
          v-if="gallery.length > 1"
          class="hero sh-center"
          circular
          @change="(e: { detail: { current: number } }) => (heroAt = e.detail.current)"
        >
          <swiper-item v-for="(img, i) in gallery" :key="img + i" class="hero__item sh-center">
            <sh-cover class="hero__emoji" :src="img"></sh-cover>
          </swiper-item>
        </swiper>
        <view v-else class="hero sh-center">
          <sh-cover class="hero__emoji" :src="goods.cover"></sh-cover>
        </view>
        <view v-if="gallery.length > 1" class="hero__wrap">
          <text class="txt-caption hero__count sh-num">{{ heroAt + 1 }}/{{ gallery.length }}</text>
        </view>

        <!-- 价格 · 标题 · 卖点。价格放最上面：进来先看的就是多少钱 -->
        <view class="sh-card block pricecard">
          <!-- 拼团商品：大字给团价，旁边「N 人团」与单买价（原型 g04） -->
          <view v-if="grp" class="price sh-row sh-row--baseline">
            <text class="txt-hero sh-num">{{ money(grp.groupPrice) }}</text>
            <text class="txt-caption sh-chip sh-chip--primary save">{{ $t("home.groupTag", { n: grp.minCount }) }}</text>
            <text v-if="directBuyable" class="txt-caption txt-quiet sh-num">{{ $t("home.groupSolo", { p: money(sku?.price ?? goods.price) }) }}</text>
          </view>
          <view v-else class="price sh-row sh-row--baseline">
            <text class="txt-hero sh-num">{{ money(sku?.price ?? goods.price) }}</text>
            <text v-if="sku?.originPrice && saved" class="sh-was sh-num">
              {{ money(sku.originPrice) }}
            </text>
            <text v-if="saved" class="txt-caption sh-chip sh-chip--danger sh-num save">
              {{ $t("goods.saveAmount", { p: money(saved) }) }}
            </text>
          </view>
          <!-- 标题行：右边是分享（原型 g01）。小程序里是原生按钮盖在上面的透明层，版式交给 view -->
          <!-- 标题与副标题同在左列，分享在右 —— 分享比标题高，副标题放在外面会被它顶下去空出一行（真机 0.1.47） -->
          <view class="titlerow sh-row">
            <view class="sh-fill titlerow__main">
              <text class="txt-title title">{{ goods.title }}</text>
              <text v-if="goods.subtitle" class="sh-muted sub">{{ goods.subtitle }}</text>
            </view>
            <!-- 收藏（原型 g07）：空心 / 实心星 + 两个字，与分享并排 -->
            <view class="titlerow__act sh-center" @tap="toggleFavorite">
              <sh-icon :name="goods.favorited ? 'starFilled' : 'star'" :size="32" :color="goods.favorited ? 'var(--sh-primary)' : 'var(--sh-ink)'"></sh-icon>
              <text class="txt-caption" :class="goods.favorited ? 'txt-primary' : 'sh-muted'">{{ $t(goods.favorited ? "goods.favorited" : "goods.favorite") }}</text>
            </view>
            <view v-if="nativeShare" class="titlerow__act sh-center">
              <sh-icon name="share" :size="32" color="var(--sh-ink)"></sh-icon>
              <text class="txt-caption sh-muted">{{ $t("goods.share") }}</text>
              <button class="titlerow__share" open-type="share"></button>
            </view>
          </view>

          <view v-if="hasChips" class="chips sh-wrap">
            <!--
              没设截单时间的生鲜不出这个标签 —— 否则就是一个空的「距截单」，后面什么都没有。
              判据与商品卡（biz-goods-card 的 showCutoff）同一条：有倒计时文字才显示。
            -->
            <text v-if="isFresh && cutoffText && !cutoffPassed" class="sh-chip sh-chip--warning">
              {{ $t("home.cutoffIn", { t: cutoffText }) }}
            </text>
            <text v-if="cutoffPassed" class="sh-chip sh-chip--danger">
              {{ $t("goods.cutoffPassed") }}
            </text>
            <text v-if="isService && goods.durationMin" class="sh-chip sh-chip--primary">
              {{ $t("goods.duration", { n: goods.durationMin }) }}
            </text>
            <text v-if="isVirtual" class="sh-chip sh-chip--primary">
              {{ $t("goods.virtualTag") }}
            </text>
            <text v-if="isCard && goods.card?.timesTotal" class="sh-chip sh-chip--primary">
              {{ $t("goods.cardTimes", { n: goods.card.timesTotal }) }}
            </text>
            <text v-if="isCard && goods.card?.faceValueMinor" class="sh-chip sh-chip--primary">
              {{ $t("goods.cardValue", { v: money(goods.card.faceValueMinor) }) }}
            </text>
            <text v-if="promo" class="sh-chip sh-chip--danger">
              {{ $t("promo.buyNGetM", promoLabelArgs(promo)) }}
            </text>
            <text v-if="FEATURES.points && goods.points" class="sh-chip sh-chip--primary sh-num">
              {{ $t("points.earnChip", { n: goods.points }) }}
            </text>
            <!--
              紧缺才说「仅剩 N 件」。v2 起单规格商品不弹面板，这句只放面板里的话单规格就永远看不到 ——
              而库存紧缺恰恰是该让人看见的那一刻
            -->
            <text v-if="lowStock" class="sh-chip sh-chip--danger sh-num">{{ $t("goods.lowStock", { n: lowStock }) }}</text>
            <!-- 已售 0 不说：零销量是个负面信号，说出来只会劝退 -->
            <text v-if="goods.sales > 0" class="sh-chip sh-num">{{ $t("common.sold", { n: goods.sales }) }}</text>
          </view>
        </view>

        <!--
          领券。**只在有券时出现**（原型 g01）。
          「已选」一行去掉了：还没决定买就问规格和件数是反的 —— 点底栏按钮时面板里再选。
          「范围」一行也去掉了：销售区域挪进商品参数，对绝大多数人它是一句不用看的话。
        -->
        <view v-if="coupons.length" class="sh-card block rows">
          <!-- 领券（s36）：前两张券的规则直接摆出来，点开是面板 -->
          <view class="sh-row sh-row--divided row" @tap="showCoupons = true">
            <text class="txt-sub sh-muted row__label">{{ $t("goods.couponRow") }}</text>
            <view class="sh-fill sh-row couponchips">
              <text v-for="c in coupons.slice(0, 2)" :key="c.couponNo" class="txt-caption sh-chip sh-chip--danger sh-num">
                {{ couponRuleText(c) }}
              </text>
            </view>
            <text class="txt-sub is-danger">{{ $t("goods.couponClaim") }}</text>
            <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
          </view>
        </view>

        <!-- 预约：日期 + 时刻 -->
        <view v-if="needAppointment" class="sh-card block">
          <text class="sh-muted">{{ $t("goods.pickDate") }}</text>
          <scroll-view class="dates" scroll-x>
            <view
              v-for="s in goods.slots"
              :key="s.date"
              class="sh-seg date"
              :class="{ 'sh-seg--on': slotDate === s.date }"
              @tap="((slotDate = s.date), (slotTime = ''))"
            >
              <text class="txt-bold sh-num">{{ s.date.slice(5) }}</text>
            </view>
          </scroll-view>

          <text class="sh-muted times-label sh-wrap">{{ $t("goods.pickTime") }}</text>
          <view class="times sh-wrap">
            <view
              v-for="tm in times"
              :key="tm.time"
              class="sh-seg time"
              :class="{ 'sh-seg--on': slotTime === tm.time, 'sh-seg--off': tm.left <= 0 }"
              @tap="tm.left > 0 && (slotTime = tm.time)"
            >
              <text class="txt-bold time__t sh-num">{{ tm.time }}</text>
              <text class="txt-caption time__left">{{ $t("goods.slotLeft", { n: tm.left }) }}</text>
            </view>
          </view>

          <view class="sh-notice notice">
            <text class="txt-caption notice__text">
              {{ $t("goods.changeRule", { n: TRADE_RULES.appointmentChangeBeforeHours }) }}
            </text>
          </view>
        </view>

        <!-- 社区集单：截单 / 提货 / 已订（s26） -->
        <view v-if="batch" class="sh-card block">
          <view class="fact sh-row sh-row--between sh-row--top">
            <text class="txt-sub fact__label">{{ $t("goods.batchCutoff") }}</text>
            <text class="txt-sub fact__value sh-num">
              {{ $t("goods.batchCutoffAt", { d: batchDay(batch.cutoffAt), t: batchTime(batch.cutoffAt) }) }}
            </text>
          </view>
          <view class="fact sh-row sh-row--between sh-row--top">
            <text class="txt-sub fact__label">{{ $t("goods.batchPickup") }}</text>
            <text class="txt-sub fact__value sh-num">
              {{ $t("goods.batchPickupAt", { d: batch.pickupDate.slice(5), t: batch.pickupFrom || "" }) }}
            </text>
          </view>
          <view class="fact sh-row sh-row--between sh-row--top">
            <text class="txt-sub fact__label">{{ $t("goods.batchOrdered") }}</text>
            <text class="txt-sub fact__value sh-num">{{ $t("goods.batchQty", { n: batch.orderedQty }) }}</text>
          </view>
        </view>

        <!-- 拼团：正在拼的团，点进去参团（s21） -->
        <view v-if="grp && grp.openGroups.length" class="sh-card block">
          <text class="txt-sub sh-muted">{{ $t("goods.groupOpenList") }}</text>
          <view
            v-for="og in grp.openGroups"
            :key="og.groupNo"
            class="fact sh-row sh-row--between"
            @tap="openGroupPage(og.groupNo)"
          >
            <text class="txt-sub fact__label">
              {{ og.initiatorNickname ? $t("goods.groupOf", { name: og.initiatorNickname }) : $t("goods.groupMerchant") }}
              · {{ $t("goods.groupNeed", { n: og.need }) }}
            </text>
            <text class="txt-sub fact__value sh-num">{{ groupLeft(og.expireAt) }}</text>
          </view>
        </view>

        <!-- 商家。挪到配送与规格之后：先定「买不买」，再看「谁在卖」。没人评过时不说「暂无评价」 -->
        <view class="sh-card block">
          <biz-merchant-bar :merchant="goods.merchant" quiet-no-rating @tap="openMerchant"></biz-merchant-bar>
        </view>

        <!-- 评价。排在参数与图文之前（原型 g02）：「别人买了觉得怎样」比长图先被看。id 给锚点用 -->
        <view id="sec-reviews" class="sh-card block">
          <view class="rvhead">
            <text class="txt-title">{{ reviews.length ? $t("review.title", { n: reviews.length }) : $t("review.titleBare") }}</text>
          </view>
          <biz-review
            v-for="r in reviews"
            :key="r.reviewNo"
            :review="r"
            @like="likeReview(r)"
          ></biz-review>
          <!-- 空态一行：「还没有评价 · 购买后可发表评价」，不再占两行 -->
          <text v-if="!reviews.length" class="txt-sub sh-muted">
            {{ $t("review.empty") }} · {{ $t("review.emptyTip") }}
          </text>
        </view>

        <!--
          **商品参数**（产地 / 保质期 / 材质…）。商家在建品页填的就是这些，
          没有这一段他填了买家看不见。配送方式与到货时间已挪去首屏的配送卡。
          限购只在**真有限购**时出现 ——「限购：不限购」是一行什么都没说的话。
        -->
        <!-- id 给「详情」锚点用：参数与图文详情同属这一段 -->
        <view id="sec-detail"></view>
        <view v-if="hasParams" class="sh-card block">
          <text class="txt-title dt__h">{{ $t("goods.paramsTitle") }}</text>
          <view v-for="p in goods.params ?? []" :key="p.dimNo" class="fact sh-row sh-row--between sh-row--top">
            <text class="txt-sub fact__label">{{ p.name || p.dimNo }}</text>
            <text class="txt-sub fact__value">{{ p.label }}</text>
          </view>
          <!--
            旧的 `origin` 列：**参数里已经有产地就不再重复显示**。
            两处都显示的话，商家在新的参数里填了「本地」、老列里还留着
            早年填的「山东」—— 买家看到两个产地，而谁也说不清哪个算数。
          -->
          <view v-if="isFresh && goods.origin && !hasOriginParam" class="fact sh-row sh-row--between sh-row--top">
            <text class="txt-sub fact__label">{{ $t("goods.origin") }}</text>
            <text class="txt-sub fact__value">{{ goods.origin }}</text>
          </view>
          <view v-if="isService && goods.storeName" class="fact sh-row sh-row--between sh-row--top">
            <text class="txt-sub fact__label">{{ $t("goods.store") }}</text>
            <text class="txt-sub fact__value">{{ goods.storeName }}</text>
          </view>
          <view v-if="isCard && goods.card" class="fact sh-row sh-row--between sh-row--top">
            <text class="txt-sub fact__label">{{ $t("goods.validity") }}</text>
            <text class="txt-sub fact__value sh-num">
              {{ $t("goods.validDays", { n: goods.card.validDays }) }}
            </text>
          </view>
          <!--
            销售区域：**这件商品卖到哪**，是商品的属性。v2 起从首屏挪到这里 ——
            信息还在，只是不占首屏。整行不渲染的判据是后端给的 saleScopeText，
            **不是 areaNames 为空**：只做自提却没配范围的商家也是空的，而那个空的意思
            正好相反（谁也看不到），在端上判必然判反一半。
          -->
          <view v-if="saleScopeText" class="fact sh-row sh-row--between sh-row--top">
            <text class="txt-sub fact__label">{{ $t("goods.scopeLabel") }}</text>
            <text class="txt-sub fact__value">{{ saleScopeText }}</text>
          </view>
          <view v-if="goods.limitPerUser" class="fact sh-row sh-row--between sh-row--top">
            <text class="txt-sub fact__label">{{ $t("goods.limitLabel") }}</text>
            <text class="txt-sub fact__value">{{ $t("goods.limit", { n: goods.limitPerUser }) }}</text>
          </view>

          <view v-if="goods.weighed" class="sh-notice sh-notice--warning notice">
            <text class="txt-caption notice__text">{{ $t("goods.weighed") }}</text>
          </view>
          <view v-if="isVirtual && goods.virtual" class="sh-notice notice">
            <text class="txt-caption notice__text">{{ goods.virtual.deliverDesc }}</text>
          </view>
        </view>

        <!--
          图文详情。**这一段此前整个不存在** —— `detail`（正文）与 `detailImages`（长图）
          后端都在发，页面一个字都没渲染。商家写的产地、保质期、售后说明，
          买家从来没看到过。

          正文是**纯文本**：后端存的就是纯文本而不是 HTML（收 HTML 要在三端各消毒一次，
          漏一处就是 XSS），所以这里也不做富文本解析，按段落原样排。
          两样都没有时整段不渲染，不拿一个空白区块占着详情页。
        -->
        <view v-if="goods.detail || goods.detailImages?.length" class="sh-card block">
          <text class="txt-title dt__h">{{ $t("goods.detailTitle") }}</text>
          <text v-if="goods.detail" class="txt-body dt__text">{{ goods.detail }}</text>
          <!-- 长图按顺序全宽竖排。mode="widthFix" 是关键：不给的话
               1:3 的长图会被压进默认的 320×240 里 -->
          <image
            v-for="(img, i) in goods.detailImages ?? []"
            :key="img + i"
            class="dt__img"
            :src="img"
            mode="widthFix"
          />
        </view>

        <!--
          买不了要说是为什么。**贴着操作条上方** —— 他往下滚就是为了按那两个按钮，
          话要落在他视线的终点（与结算页的同名做法一致）。
          已经在别处说过的（售罄写在按钮上、截单有一枚红 chip）这里返回空串，不重复说。
        -->
        <view v-if="buyBlockedReason" class="txt-caption sh-notice sh-notice--warning why sh-row">
          <text class="sh-fill">{{ buyBlockedReason }}</text>
          <!-- 送不到时给出路：换一条收货地址（原型 g05） -->
          <text v-if="outOfScope" class="sh-link" @tap="gotoAddress">{{ $t("goods.changeAddress") }}</text>
        </view>

        <sh-sheet :visible="showCoupons" :title="String($t('goods.couponRow'))" @close="showCoupons = false">
          <view class="sh-cells">
            <view v-for="c in coupons" :key="c.couponNo" class="sh-cell sh-row sh-row--between" @tap="claim(c)">
              <text class="txt-body sh-num">{{ couponRuleText(c) }} <text class="sh-muted">· {{ couponUntil(c) }}</text></text>
              <text class="txt-body" :class="c.received ? 'sh-muted' : 'txt-primary'">
                {{ c.received ? $t("goods.couponGot") : $t("goods.couponClaim") }}
              </text>
            </view>
          </view>
          <view class="sh-btn coupon__done" @tap="showCoupons = false">{{ $t("goods.couponDone") }}</view>
        </sh-sheet>

        <!--
          规格面板。规格矩阵、买赠提示、数量都在这里 —— 页面上只留一行「已选」。
          单规格商品点底栏的「加入购物车 / 立即购买」**不弹它**，直接按 1 件执行；
          多规格才先弹，在面板里选好再按。
        -->
        <sh-sheet :visible="showSku" :title="String($t('goods.chosen'))" @close="showSku = false">
          <view class="skuhead sh-row">
            <sh-cover class="skuhead__img" :src="goods.cover"></sh-cover>
            <view class="sh-fill skuhead__main">
              <view class="sh-row sh-row--baseline">
                <text class="txt-display sh-num">{{ money(sku?.price ?? goods.price) }}</text>
                <text v-if="sku?.originPrice && saved" class="sh-was sh-num">{{ money(sku.originPrice) }}</text>
              </view>
              <text class="txt-sub sh-muted sh-num">{{ chosenText }}</text>
            </view>
          </view>

          <!-- 规格矩阵：每个维度一行，不可组合的取值置灰 -->
          <view v-for="(group, gi) in goods.specGroups" :key="group.name" class="specgroup">
            <text class="txt-strong">{{ group.name }}</text>
            <view class="specs sh-wrap">
              <view
                v-for="opt in group.options"
                :key="opt"
                class="sh-seg"
                :class="{
                  'sh-seg--on': chosen[gi] === opt,
                  'is-off': !optionState(gi, opt).inStock,
                }"
                @tap="choose(gi, opt)"
              >
                <text class="txt-bold">{{ opt }}</text>
              </view>
            </view>
          </view>

          <!-- 买赠：当前数量能拿几件赠品，实时算给用户看 -->
          <view v-if="promo" class="sh-notice sh-notice--danger giftline">
            <text class="txt-caption giftline__text is-danger">
              {{ giftQty > 0
                ? $t("promo.willGift", { n: giftQty })
                : $t("promo.needMore", { n: promo.buyN - (qty % promo.buyN) }) }}
            </text>
          </view>

          <view class="qty sh-row sh-row--between">
            <view class="qty__label">
              <text class="txt-strong">{{ $t("goods.qty") }}</text>
              <!-- 库存只在紧缺时说：「库存 100」对买家没有意义，「仅剩 3 件」才有 -->
              <text v-if="lowStock" class="txt-caption is-danger sh-num qty__low">
                {{ $t("goods.lowStock", { n: lowStock }) }}
              </text>
            </view>
            <sh-stepper v-model="qty" :max="maxQty"></sh-stepper>
          </view>

          <view v-if="buyBlockedReason" class="txt-caption sh-notice sh-notice--warning why">
            <text>{{ buyBlockedReason }}</text>
          </view>

          <!-- 面板底部只放叫出它的那一个动作（原型 g03） -->
          <view class="sheetbar sh-row">
            <view v-if="activityClosed" class="sh-btn sh-fill actionbar__buy is-disabled">
              {{ $t("goods.notBuyable") }}
            </view>
            <view v-else-if="sheetMode === 'group' && grp" class="sh-btn sh-fill actionbar__buy" :class="{ 'is-disabled': !buyable }" @tap="sheetGroup">
              {{ $t("goods.groupStart", { p: money(grp.groupPrice) }) }}
            </view>
            <view v-else-if="sheetMode === 'buy'" class="sh-btn sh-fill actionbar__buy" :class="{ 'is-disabled': !buyable }" @tap="sheetBuy">
              {{ soldOut ? $t("goods.soldOut") : grp ? $t("goods.buyAlone", { p: money(sku?.price ?? goods.price) }) : $t("goods.buyNow") }}
            </view>
            <view v-else class="sh-btn sh-fill actionbar__buy" :class="{ 'is-disabled': !buyable }" @tap="sheetAdd($event)">
              {{ soldOut ? $t("goods.soldOut") : $t("goods.addCart") }}
            </view>
          </view>
        </sh-sheet>

        <sh-actionbar pill="plain" :pad="220">
          <!-- 底栏三格：店铺 · 两颗按钮（原型 g01）。分享挪到标题旁、购物车挪到左上角 -->
          <view class="actionbar__icon sh-center" @tap="openMerchant">
            <sh-icon name="store" :size="40" color="var(--sh-sub)"></sh-icon>
            <text class="txt-caption sh-muted">{{ $t("goods.shop") }}</text>
          </view>
          <!-- 拼团商品：单买 / 开团（s21）。参团在团页上，开团价由活动定 -->
          <!-- 仅活动可售：directBuyable 为假时没有单买 / 加购；拼团也没有就只剩一颗压暗的「暂不可购买」 -->
          <template v-if="grp && SHOW_GROUP_CTA">
            <view v-if="directBuyable" class="sh-btn actionbar__add" :class="{ 'is-disabled': !barReady }" @tap="tapBuy">
              {{ soldOut && !multiSku ? $t("goods.soldOut") : $t("goods.buyAlone", { p: money(sku?.price ?? goods.price) }) }}
            </view>
            <view class="txt-sub sh-btn actionbar__buy sh-fill" :class="{ 'is-disabled': !barReady }" @tap="tapGroup">
              {{ $t("goods.groupStart", { p: money(grp.groupPrice) }) }}
            </view>
          </template>
          <view v-else-if="activityClosed" class="txt-sub sh-btn actionbar__buy sh-fill is-disabled">
            {{ $t("goods.notBuyable") }}
          </view>
          <template v-else>
            <view class="sh-btn actionbar__add" :class="{ 'is-disabled': !barReady }" @tap="tapAdd($event)">
              {{ soldOut && !multiSku ? $t("goods.soldOut") : $t("goods.addCart") }}
            </view>
            <view class="txt-sub sh-btn actionbar__buy sh-fill" :class="{ 'is-disabled': !barReady }" @tap="tapBuy">
              {{ $t("goods.buyNow") }}
            </view>
          </template>
        </sh-actionbar>
  
  
    </template>
  </sh-scaffold>
</template>

<style scoped>




.coupon__done {
  margin-top: 24rpx;
}

/* 买不了的原因。用 warning 不用 danger：**它不是故障，是还差一步**
   （与 order-confirm 的 .why 同一档） */
.why {
  margin: 0 24rpx;
}

/* 主图框。底色是**占位**（图没到、或 emoji 占位时露出来的那层），
   所以走弱色块而不是主色 tint —— 主色 tint 是「有语义」的那一档（提示条与选中态），
   拿它当占位会让一张还没加载的图看起来像在强调什么。sh-cover 的调用点一直是 faint。 */
/* 通栏：用负边距吃掉页面边距，贴满屏宽、贴住顶栏 */
.hero {
  position: relative;
  height: 560rpx;
  margin: calc(-1 * var(--sh-pad-page, 28rpx)) calc(-1 * var(--sh-pad-page, 28rpx)) 0;
  background: var(--sh-faint);
}
/* 原先只有字号 —— 那是给 emoji 写的。换成真图后没有可撑的尺寸，
   图会塌成 0 高；给满整块 hero，emoji 仍按字号居中显示。 */
.hero__emoji {
  width: 100%;
  height: 100%;
  border-radius: 0;
  font-size: 200rpx;
  line-height: 1;
}
/* 折扣标原先绝对定位在 hero 里；换成 swiper 之后它会跟着页面一起滑走，
   所以拎出来单独定位在轮播上方一层 */
.hero__wrap {
  position: relative;
  height: 0;
}

.title {
  display: block;
}
.sub {
  display: block;
  margin-top: 8rpx;
}
.price {
  gap: 16rpx;
}
.save {
  align-self: center;
}
.title {
  margin-top: 16rpx;
}

.chips {
  margin-top: 24rpx;
}
.specgroup + .specgroup {
  margin-top: 32rpx;
}
.specs {
  gap: 16rpx;
  margin-top: 16rpx;
}
/* 底色 / 圆角 / 选中实底都归 .sh-seg —— 那条「为什么不用 tint」的理由
   已经搬进 base.css，它不该只留在这一个页面里 */
.giftline {
  margin-top: 28rpx;
}
.qty {
  margin-top: 32rpx;
}
.dates {
  white-space: nowrap;
  margin-top: 16rpx;
}
/* 横滚排里才需要这两条：块本身的形态归 .sh-seg */
.date {
  display: inline-block;
  margin-inline-end: 12rpx;
}
.times-label {
  display: block;
  margin-top: 32rpx;
}
.times {
  gap: 16rpx;
  margin-top: 16rpx;
}
.time {
  text-align: center;
}
.time__t {
  display: block;
}
/*
 * 「剩 3 位」是**次要档**，平时要比时刻淡一档 —— 所以它自己声明了 color，
 * 也因此不会跟着块继承反白。选中时补这一条，否则实底主色上留着一行 sub 灰字，
 * 那一行恰恰是这个块里最需要看清的信息。
 */
.time__left {
  display: block;
  margin-top: 2rpx;
}
.sh-seg--on .time__left {
  color: var(--sh-on-primary);
}
.fact {
  gap: 32rpx;
  padding: 16rpx 0;
}
.fact__label {
  flex-shrink: 0;
}
.fact__value {
  color: var(--sh-ink);
  text-align: end;
}
.notice {
  margin-top: 16rpx;
}
/* 图标位：图标在上、字在下，不再是灰底圆钮 —— 三个都是圆钮时分不清哪个是哪个 */
.actionbar__icon {
  position: relative;
  flex: 0 0 auto;
  width: 88rpx;
  height: 88rpx;
  flex-direction: column;
  gap: 4rpx;
}
/* 标题行：标题占满，右边一格「分享」（图标在上、字在下） */
.titlerow {
  gap: 16rpx;
  align-items: flex-start;
  margin-top: 16rpx;
}
.titlerow__main {
  min-width: 0;
}
.titlerow .title {
  margin-top: 0;
}
.titlerow__act {
  position: relative;
  flex-shrink: 0;
  flex-direction: column;
  gap: 2rpx;
  width: 72rpx;
}
/* 分享的原生 <button> 只当点击层：铺满整格、完全透明，版式交给外面那个 view
   （直接把图标和字放进 button，真机上字会被它的默认行高挤下去 —— 0.1.42 修过一次） */
.titlerow__share {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  padding: 0;
  border: 0;
  opacity: 0;
}
.titlerow__share::after {
  border: none;
}

/* 顶部浮层：固定在屏顶。压在图上时透明，滑过主图后变实色 */
.topbar {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: var(--sh-z-nav);
  pointer-events: none;
}
.topbar.is-solid {
  background: var(--sh-surface);
}
.topbar__row {
  position: absolute;
  left: 0;
  right: 0;
  gap: 16rpx;
  padding-inline-start: 24rpx;
  box-sizing: border-box;
}
/* 圆钮：压在图上时是半透明深底 + 白图标，任何颜色的主图上都看得清 */
.topbar__btn {
  position: relative;
  flex-shrink: 0;
  border-radius: 9999px;
  background: var(--sh-scrim);
  pointer-events: auto;
}
.topbar.is-solid .topbar__btn {
  background: transparent;
}
.topbar__badge {
  position: absolute;
  top: -8rpx;
  inset-inline-end: -12rpx;
}
.topbar__anchors {
  gap: 32rpx;
  padding-inline-start: 8rpx;
  pointer-events: auto;
}
.topbar__anchor {
  padding: 8rpx 0;
  border-bottom: 4rpx solid transparent;
}
/* 当前段：墨色 + 主色下划线；其余两个次要色 */
.topbar__anchor.is-on {
  color: var(--sh-ink);
  border-bottom-color: var(--sh-primary);
}
.topbar__cart.is-bouncing {
  animation: shCartBounce var(--sh-t-slow) var(--sh-ease-spring);
}
@keyframes shCartBounce {
  0% { transform: scale(1); }
  40% { transform: scale(1.28); }
  100% { transform: scale(1); }
}
/*
 * 两颗按钮**等宽平分**剩下的地方，不再是「加购按内容宽、立即购买吃掉余量」。
 * 后者在拼团商品上被压到只剩四个字宽（真机 2026-09-20：「单买 ¥50」换行都放不下），
 * 而这两个动作在详情页是同等重要的 —— 宽度不该由文案长短决定。
 */
.actionbar__add,
.actionbar__buy {
  flex: 1;
  min-width: 0;
  padding: 28rpx 12rpx;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.actionbar__add {
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
}
.rvhead {
  margin-bottom: 24rpx;
}

/* 图文详情：正文与长图 */
.dt__h {
  display: block;
  margin-bottom: 16rpx;
}
.dt__text {
  display: block;
  white-space: pre-wrap;
}
.dt__img {
  display: block;
  width: 100%;
  margin-top: 16rpx;
  border-radius: 16rpx;
}

/* 主图右下角的「1/N」 */
.hero__count {
  position: absolute;
  top: -72rpx;
  inset-inline-end: 28rpx;
  padding: 4rpx 20rpx;
  border-radius: 9999px;
  background: var(--sh-scrim);
  color: #fff;
}
/* 配送卡、领券与已选卡：一行一件事，行高够一根手指 */
.row {
  min-height: 56rpx;
  gap: 16rpx;
}
.row__label {
  flex-shrink: 0;
  min-width: 72rpx;
}
.couponchips {
  gap: 12rpx;
  overflow: hidden;
}
/* 规格面板 */
.skuhead {
  gap: 24rpx;
  align-items: flex-end;
  margin-bottom: 32rpx;
}
.skuhead__img {
  width: 160rpx;
  height: 160rpx;
}
.skuhead__main {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.qty__label {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}
.sheetbar {
  gap: 16rpx;
  margin-top: 40rpx;
}
</style>
