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
import { thumb } from "@shared/utils/media-thumb";
import { onLoad, onPageScroll, onShareAppMessage, onShareTimeline } from "@dcloudio/uni-app";
import { phoneRequired, withPhone, onPhoneBound, onPhoneGateClose } from "@/shared/phone-required";
import { api } from "@/api";
import { prompt } from "@ai-shop/ui/prompt";
import { useCartStore } from "@/stores/cart";
import { useUserStore } from "@/stores/user";
import { useCommunityStore } from "@/stores/community";
import { buildShareMessage, buildShareTimeline, shareImageUrl } from "@shared/ports/share";
import { navBox as readNavBox } from "@shared/ports/capsule";
import { CATEGORY_TYPE, FEATURES, FULFILLMENT, ROUTES, TRADE_RULES } from "@shared/utils/constants";
import { countdown, money } from "@shared/utils/format";
import { provinceNamesByCodes } from "@shared/utils/region";
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
import type { ActivityTag, Coupon, Goods, GoodsBatch, GoodsGroup, Review, Sku, Question, ReviewFilter } from "@shared/types";

const { t } = useI18n();
const cart = useCartStore();
const user = useUserStore();
const community = useCommunityStore();
/** 小程序才有原生分享按钮；H5 与团购页同一约定：不显示 */

const goods = ref<Goods | null>(null);
const reviews = ref<Review[]>([]);
/**
 * 评价筛选（§3.3）。后端按同一份取值域判，**不认识的词按全部处理** ——
 * 筛选是便利，不该因为传错一个词把整页打空。
 */
const reviewFilter = ref<ReviewFilter>("ALL");
/** 评分概览随详情一起下发 —— 首屏那一行不值得多打一次请求 */
const summary = computed(() => goods.value?.reviewSummary ?? null);
const REVIEW_FILTERS: ReviewFilter[] = ["ALL", "IMAGE", "GOOD", "BAD"];

/**
 * 推荐位（§3.4 批 4）：**同店在售**优先，不够再补同类目。
 *
 * <p>不做算法、不新开端点 —— 用的是列表那条现成的查询。理由有两条：
 * 线上一共三件商品，任何「猜你喜欢」都只是把同一批货换个顺序；
 * 而「这家店还卖什么」本身就是买家在详情页最常有的下一个问题。
 */
const recommends = ref<Goods[]>([]);
/**
 * 「本店热卖」「看了又看」总开关（2026-10-04 用户要求先关）。
 * 直接原因：这两块的缩略图是 thumb(r.cover)，而线上商品普遍还没传主图（cover 为空），
 * 出来的是一排裂图 —— 比没有推荐区更糟。等商品有真图（img.hxmall.top 域名）再改回 true。
 * 关掉时连推荐数据一起不拉（省两个 goodsList 请求），顶部「推荐」锚点也一并隐藏。
 */
const RECO_ENABLED = false;
/** 「大家还问」：只有已回答的会下发 —— 一排没人答的问题比没有问答区更糟 */
const questions = ref<Question[]>([]);
const asking = ref(false);

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
/**
 * 点图看大图（全站此前一处 `previewImage` 都没有：主图和详情长图都点不开，
 * 而淘宝 / 拼多多 两处都能点开、双指放大、长按保存）。
 *
 * 两条讲究：
 * ① **传原图不传缩略图**。页面上挂的是 `thumb(img, 750)`，放大了就糊 —— 调用点把原始
 *    数组传进来，这里不做任何转换。
 * ② **emoji 封面点不开**。`cover` 是二义字段（种子数据里是 🍚，商家传的才是 URL，
 *    见 sh-cover 的注释）。不是 URL 就直接 return，否则 previewImage 收到 "🍚" 会报错。
 */
function preview(urls: string[], current: string): void {
  if (!/^https?:\/\//.test(current)) return;
  const list = urls.filter((u) => /^https?:\/\//.test(u));
  if (!list.length) return;
  uni.previewImage({ urls: list, current });
}
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
    // 「不限地区（新疆、西藏除外）」：门店排除了的省不说出来，那儿的买家会以为能买（TDD-经营范围排除地区 AC4）
    return s.excludedNames?.length
      ? t("goods.scopeUnlimitedExcept", { names: s.excludedNames.join("、") })
      : t("goods.scopeUnlimited");
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
  () => !!sku.value && !soldOut.value && !cutoffPassed.value && appointmentReady.value
    && !outOfScope.value && !offSale.value,
);

/**
 * 商家把它下架了（2026-09-30）。**只在后端明确说 false 时**拦 —— 与 `deliverable` 同一条规矩。
 *
 * 此前这一页**一个字都没读过 `onSale`**：下架的货详情页照常打开，
 * 两颗按钮照常亮着，加购、下单一路通到底。后端从来都下发了这个字段
 * （线上实测 `onSale: false`），端上没有任何人看它。
 *
 * 不做成 404：买家从历史订单、分享链接点进来要能看到自己买过的东西。
 * 照常展示、不能下单 —— 这也是「已售罄」那条走的路。
 */
const offSale = computed(() => goods.value?.onSale === false);

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
  if (offSale.value) return String(t("goods.whyOffSale"));
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
    || !!promo.value || !!g.activityTags?.length
    || lowStock.value > 0;
});

/**
 * 活动标签的一句话（优惠券全链路梳理 批 3）：「满 ¥50 减 ¥8」「满 3 件减 ¥5」「新客立减 ¥5」。
 * 满减类活动此前只在下单页出现 —— 逛的时候不知道要凑单，这条标签就是为了让他在这里知道。
 */
function activityTagText(a: ActivityTag): string {
  const n = money(a.amountMinor);
  if (a.thresholdMinor > 0) return String(t("promo.cutAmount", { m: money(a.thresholdMinor), n }));
  if (a.thresholdQty > 0) return String(t("promo.cutQty", { q: a.thresholdQty, n }));
  return String(t(a.newCustomerOnly ? "promo.newCut" : "promo.cutAny", { n }));
}

/** 海报组件（§3.2 B3）。点「海报」时才开始画 —— 画布与下载都不该在进页面时发生 */
const poster = ref<{ open: () => void } | null>(null);
/** 从哪家门店的门户点进来的（链接上的 storeNo）。空 = 不是从门户来的 */
const viaStore = ref("");
/** 分享链接：带上门店号，对方打开后「进店」进的是这一家 */
const sharePath = computed(() => {
  const base = `${ROUTES.goods}?goodsNo=${goods.value?.goodsNo ?? ""}`;
  return viaStore.value ? `${base}&storeNo=${viaStore.value}` : base;
});

/**
 * 商品参数：商家填的 + **系统已经知道的事实**。
 *
 * <p>后者此前散落在参数卡里各写一行 `v-if`，而它们与商家填的参数是同一类信息
 * （买家在同一个地方找「这件货是什么样」）。收进同一个列表之后，
 * 「前几条直出、其余进抽屉」才有统一的口径 —— 否则抽屉里只有一半内容。
 *
 * <p><b>不收 arrivalDesc</b>：到货说明属于配送，配送在结算 / 订单页确认
 * （v4 2026-09-29 再次确认，见 goods-detail-layout.test.ts 头部的三次决定）。
 */
const facts = computed<Array<{ label: string; value: string }>>(() => {
  const g = goods.value;
  if (!g) return [];
  const out: Array<{ label: string; value: string }> = [];
  /*
   * **产地两层合成一行**。`SD_ORIGIN` 是粗枚举（本地 / 国产 / 进口），它的活是给买家筛选、
   * 给跨店聚合用；`SD_ORIGIN_DETAIL` 是商家自己填的精确产区（「陕西富平」），TEXT 维度不入池
   * （见 V375 迁移的注释）。两个都填，参数表就会出现「产地 国产」+「原产地 陕西富平」
   * 两行说同一件事 —— 而买水果的人要的只是后者，「国产」对他等于没说。
   *
   * 所以有精确产区就只出它一行，占粗产地那一行的**位置与名字**（名字取后端下发的维度名，
   * 不另造词条）。两边都没有时各走各的，行为不变。
   */
  const originDetail = (g.params ?? []).find((p) => p.dimNo === "SD_ORIGIN_DETAIL");
  const originCoarse = (g.params ?? []).find((p) => p.dimNo === "SD_ORIGIN");
  for (const p of g.params ?? []) {
    // 精确产区在场时，粗产地让位（它仍然在数据里，只是不单独占一行）
    if (originDetail && p.dimNo === "SD_ORIGIN") continue;
    if (p.dimNo === "SD_ORIGIN_DETAIL") {
      out.push({ label: originCoarse?.name || p.name || p.dimNo, value: p.label });
      continue;
    }
    /*
     * 商家自己写的「售后说明 / 售后服务」先不出（v4，2026-09-29）：售后要作为一整块重新设计，
     * 在那之前每家店各写一句，买家看到的是一套半截说法 —— 与平台规则打架时还说不清谁算数。
     * 按名字认：这一格没有固定维度，是商家自由起的参数名。
     */
    if ((p.name || "").includes("售后")) continue;
    out.push({ label: p.name || p.dimNo, value: p.label });
  }
  // 旧的 origin 列：参数里已经有产地就不再重复（两个产地谁也说不清哪个算数）
  if (isFresh.value && g.origin && !hasOriginParam.value) {
    out.push({ label: String(t("goods.origin")), value: g.origin });
  }
  if (isService.value && g.storeName) {
    out.push({ label: String(t("goods.store")), value: g.storeName });
  }
  if (saleScopeText.value) {
    out.push({ label: String(t("goods.scopeLabel")), value: saleScopeText.value });
  }
  // 限购地区（#3）：这件货不卖到的省。排除语义,省级码→省名。空则整行不出
  if (g.restrictedRegions?.length) {
    out.push({
      label: String(t("goods.restrictedLabel")),
      value: provinceNamesByCodes(g.restrictedRegions).join("、"),
    });
  }
  if (g.limitPerUser) {
    out.push({ label: String(t("goods.limitLabel")), value: String(t("goods.limit", { n: g.limitPerUser })) });
  }
  return out;
});

/**
 * **生鲜的决策属性前置**（A 档）。买水果的人要先看到「哪儿产的 · 什么口感 · 怎么存」——
 * 平台（淘宝 / 拼多多 / 京东生鲜）都把这几条做成标题下的标签，而不是让人翻到两屏以下的参数表。
 * 标品不走这条：它的决策看品牌型号规格，那本来就该是一张表。
 *
 * 只取**值**不取名（「陕西富平」而不是「产地：陕西富平」）—— 标签是给人扫一眼的，
 * 带上维度名三个标签就排不下一行了。完整的名值对仍在下面的参数表里，一条不少。
 * 最多 3 条：第四条起会折行，而折了行就不再是「一眼」。
 */
const SPEC_CHIP_DIMS = ["SD_ORIGIN_DETAIL", "SD_ORIGIN", "SD_TASTE", "SD_STORE_COND"] as const;
const specChips = computed<string[]>(() => {
  const g = goods.value;
  if (!g || !isFresh.value) return [];
  const ps = g.params ?? [];
  const out: string[] = [];
  for (const dim of SPEC_CHIP_DIMS) {
    // 产地同样是两层取一层：有精确产区就不再出粗枚举（与参数表同一口径）
    if (dim === "SD_ORIGIN" && ps.some((p) => p.dimNo === "SD_ORIGIN_DETAIL")) continue;
    const hit = ps.find((p) => p.dimNo === dim);
    if (hit?.label) out.push(hit.label);
    if (out.length === 3) break;
  }
  return out;
});

/** 前几条直出，其余进抽屉（淘宝京东同一形状）。4 条是一屏不被参数吃掉的上限 */
const FACTS_HEAD = 4;
const factsHead = computed(() => facts.value.slice(0, FACTS_HEAD));
const factsOpen = ref(false);

/** 图文正文按空行分段。后端存的是纯文本，这里只做排版，不解析任何标记 */
const detailParas = computed(() =>
  (goods.value?.detail ?? "").split(/\n\s*\n/).map((x) => x.trim()).filter(Boolean));

/** 商品参数卡有没有内容 —— 限购只在真有限购时算 */
/*
 * 参数卡有没有内容。
 *
 * <p><b>不再判 card / virtual</b>（§3.2 收尾）：`Goods.card`、`Goods.virtual`、
 * `Goods.points` 这三样端上声明了、而 `GoodsVO` 里<b>一个组件都没有</b> ——
 * 后端从来没发过，那几段 `v-if` 是死代码。留着的代价不是多几行，
 * 是让人以为卡券与虚拟商品的详情页「已经做好了」。
 */
const hasParams = computed(() => facts.value.length > 0 || !!goods.value?.weighed);

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
/** 规格面板里的「已选」：规格 · 件数 */
const chosenText = computed(() => {
  const spec = (sku.value?.spec || chosen.value.join(" ")).trim();
  // 不分规格的货没有规格名：只说件数，不留一个「 · 1 件」的空头
  return spec
    ? String(t("goods.chosenValue", { spec, n: qty.value }))
    : String(t("goods.chosenQty", { n: qty.value }));
});

/** 好评率：4、5 星之和 / 总数（dist 下标 0 是 1 星）。没人评过时不算 */
const goodRate = computed(() => {
  const s = summary.value;
  if (!s?.total || !s.dist || s.dist.length < 5) return 0;
  return Math.round(((s.dist[3] ?? 0) + (s.dist[4] ?? 0)) * 100 / s.total);
});
/** 评价与问答都空：合成一行（AC6）。新店的详情页不该一半是「还没有…」 */
const rvqaEmpty = computed(() => !summary.value?.total && !reviews.value.length && !questions.value.length);

/** 本店热卖：推荐里同店的前 3 件，挂在店铺卡下面（AC7） */
const shopHot = computed(() =>
  recommends.value.filter((r) => r.merchant?.merchantNo === goods.value?.merchant.merchantNo).slice(0, 3));
/** 看了又看：推荐里除去本店热卖的其余件；不足 2 件整块不出，免得一张卡孤零零一行（AC10） */
const lookMore = computed(() => {
  const hot = new Set(shopHot.value.map((r) => r.goodsNo));
  const rest = recommends.value.filter((r) => !hot.has(r.goodsNo));
  return rest.length >= 2 ? rest.slice(0, 6) : [];
});
/*
 * 「图文详情兜底」（v3 AC9/d05）**已去掉**。它的做法是商家没写正文也没传长图时
 * 把 `gallery` 再排一遍 —— 而 gallery 就是首屏主图轮播的那几张，等于同样的图
 * 在一屏之内出现两次，中间只隔着一个「商品详情」标题。
 *
 * 当时的理由是「没有这段往下滑是空的」。但线上实测 15 个在售商品里 **14 个没有长图**，
 * 也就是说这个兜底不是边角情况，它就是绝大多数商品的「商品详情」区 ——
 * 整整一屏，内容是上面那张图的复制品。淘宝 / 拼多多 都不会把主图再放一遍：
 * 没有详情就没有这一段。空着比重复诚实，也让「该传长图」这件事在后台看得见。
 */

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
    /*
     * **带上门店**：库存与在架按那家店算（TDD-C端商品归属门店与库存校验 AC7）。
     * 不带的话详情给的是主体总量 —— 页面显示有货、加到车里，
     * 到下单落店那一步才发现那家店没有，而那时人已经在结算页了。
     */
    const g = await api.goodsDetail(goodsNo, community.community?.communityNo,
      viaStore.value || undefined);
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
    const [rs, qs] = await Promise.all([
      api.reviewList({ goodsNo, filter: reviewFilter.value }),
      // 问答取不到不该拖垮评价：它是附加信息，而评价是这一屏的主角
      api.questionList(goodsNo).catch(() => []),
    ]);
    reviews.value = rs;
    questions.value = qs;
    void loadRecommends(g);
    failed.value = false;
    // 评价到了，下面两段的位置变了 —— 锚点重新量
    measureAnchors();
  } catch {
    failed.value = true;
  }
}

/**
 * 切换评价筛选。**重取而不是端上过滤**：端上只有第一页，按它过滤会得出
 * 「差评 0 条」这种结论，而那可能只是第一页里没有。
 */
async function pickReviewFilter(f: ReviewFilter) {
  if (reviewFilter.value === f) return;
  reviewFilter.value = f;
  const goodsNo = currentNo.value;
  try {
    reviews.value = await api.reviewList({ goodsNo, filter: f });
  } catch {
    reviews.value = [];
  }
  measureAnchors();
}

/** 提问。没登录时走登录页 —— 运营回答时要能回到问的那个人 */
async function askQuestion() {
  if (asking.value) return;
  const text = await prompt({ title: String(t("goods.askTitle")), placeholder: String(t("goods.askPh")) });
  if (!text || !text.trim()) return;
  asking.value = true;
  try {
    await api.askQuestion(currentNo.value, text.trim());
    uni.showToast({ title: t("goods.askDone"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    asking.value = false;
  }
}

/**
 * 取推荐。**失败就不显示**，一条都不补 —— 推荐是锦上添花，
 * 不能因为它把整页的失败态点亮（领券条同一条取舍）。
 */
async function loadRecommends(g: Goods) {
  if (!RECO_ENABLED) {
    recommends.value = [];
    return;
  }
  try {
    const page = await api.goodsList({ merchantNo: g.merchant.merchantNo, size: 10 });
    let list = (page.records ?? []).filter((x) => x.goodsNo !== g.goodsNo);
    if (list.length < 4 && g.categoryNo) {
      const more = await api.goodsList({ categoryNo: g.categoryNo, size: 10 }).catch(() => null);
      const seen = new Set([g.goodsNo, ...list.map((x) => x.goodsNo)]);
      // 同店的排在前面：它比「同类目的另一家店」更接近买家此刻的问题
      list = [...list, ...(more?.records ?? []).filter((x) => !seen.has(x.goodsNo))];
    }
    recommends.value = list.slice(0, 6);
  } catch {
    recommends.value = [];
  }
}

/** 点推荐位：**跳新页而不是原地换数据** —— 返回时他要回到原来那件货 */
function openGoods(goodsNo: string) {
  uni.navigateTo({ url: `${ROUTES.goods}?goodsNo=${goodsNo}` });
}

/**
 * 「进店」进的是**门户**，不是资质页：从门户点进来的回到那一家；别处来的给主体号，
 * 服务端落到它的默认营业门店（门店化 §2.1）。主体与资质只在门户「店铺」页签最后一行。
 */
function openMerchant() {
  const no = viaStore.value || goods.value?.merchant.merchantNo;
  if (no) uni.navigateTo({ url: `${ROUTES.store}?no=${no}&from=GOODS` });
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

/*
 * **加购刻意不要手机号**（2026-10-08 拍板）：先让人把东西装进车，
 * 到结算那一步再要号 —— 在加购就拦会把游客挡在转化漏斗最上面一层。
 * 收藏、关注、参团那些要号，因为它们本身就是「挂在账号下」的动作。
 */
async function addToCart(e: unknown) {
  const g = goods.value;
  if (!g || !sku.value) return;
  try {
    await cart.add(g.goodsNo, sku.value.skuNo, qty.value, viaStore.value || undefined);
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
  if (!g || !sku.value || !buyable.value || buying.value) return;
  buying.value = true;
  try {
    // 设成 1 件而不是再加 1 件 —— 理由见 cart.setForCheckout
    await cart.setForCheckout(g.goodsNo, sku.value.skuNo, 1, viaStore.value || undefined);
    uni.navigateTo({
      url: `${ROUTES.orderConfirm}?fulfillment=${defaultFulfillment(g)}&skus=${sku.value.skuNo}&openGroup=1`,
    });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    buying.value = false;
  }
}

/**
 * 「立即购买 / 开团」请求在途。**连点两下**以前会加购两次（数量 +2）、
 * 再往页面栈里叠两个结算页 —— 参团那边一直有 busy，这里漏了。
 */
const buying = ref(false);

/**
 * 立即购买：把车里这一行**设成**本次选的数量再进结算 —— 结算页统一从购物车取数，
 * 不另开一条「直购」链路。**不能 add**：后端加购是累加，每点一次车里 +1（见 cart.setForCheckout）。
 */
async function buyNow() {
  const g = goods.value;
  if (!g || !sku.value || !buyable.value || buying.value) return;
  buying.value = true;
  try {
    await cart.setForCheckout(g.goodsNo, sku.value.skuNo, qty.value, viaStore.value || undefined);
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
  } finally {
    buying.value = false;
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
  // 统一闸：没手机号弹授权层，绑完自动把这次收藏补上
  if (!(await withPhone(doToggleFavorite))) return;
}

async function doToggleFavorite() {
  const g = goods.value;
  if (!g) return;
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
  // 推荐排在最后：它是「看完了，还想看点别的」那一步（§3.4 批 4）
  { key: "recommend", label: "goods.anchorRecommend" },
] as const;
type AnchorKey = (typeof ANCHORS)[number]["key"];
const activeAnchor = ref<AnchorKey>("top");
/** 顶部锚点栏实际显示的那几个：推荐区关掉时（RECO_ENABLED=false）不留一个点了滚到空处的「推荐」 */
const shownAnchors = computed(() => ANCHORS.filter((a) => a.key !== "recommend" || RECO_ENABLED));
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
    q.select("#sec-recommend").boundingClientRect(() => {});
    q.exec((res: Array<{ scrollTop?: number; top?: number } | null>) => {
      const st = res[0]?.scrollTop ?? 0;
      const tops: Record<string, number> = {};
      if (res[1]?.top != null) tops.reviews = res[1].top + st;
      if (res[2]?.top != null) tops.detail = res[2].top + st;
      if (res[3]?.top != null) tops.recommend = res[3].top + st;
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
  // 落点是底栏的购物车（v3 起购物车从左上回到底栏，2026-09-28 用户拍板）
  nextTick(() => registerCartAnchor(".actionbar__cart", instance?.proxy));
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
  /*
   * 分享落地页之一（朋友圈那条落的是单页模式，参数全从 query 来）。
   * **邀请人要在这里接住** —— 登录页只读自己 query 上的那份，从这一屏点去登录时
   * 它不会跟过来（首页那条同理，判断收在 store 里共用）。
   */
  user.captureInviter(q?.inviterNo);
  // 从门户点进来会带门店号：「进店」回到这一家，分享与海报也落到这一家（门店化 AC15）
  viaStore.value = (q?.storeNo as string) || "";
  const no = (q?.goodsNo as string) || "";
  if (no) load(no);
  timer = setInterval(() => (now.value = Date.now()), 1000);
});

onUnmounted(() => clearInterval(timer));

onShareAppMessage(() =>
  buildShareMessage({
    title: goods.value?.title ?? "",
    path: sharePath.value,
    merchantNo: community.pickup?.hostMerchantNo,
    inviterNo: user.user?.cUserNo,
  }),
);

/*
 * 分享到**朋友圈**。此前全仓一处 `onShareTimeline` 都没有 ——
 * 于是小程序右上角那一栏里「分享到朋友圈」是灰的，点不了。
 * 而在社区场景里，朋友圈与群聊是同一量级的入口。
 *
 * <p>形状与转发给好友不同：朋友圈落的是**单页模式**，微信只接受 `query`
 * （给 path 会被忽略）—— 直接把好友那份返回过来，归因参数会一起没掉。
 */
onShareTimeline(() =>
  buildShareTimeline({
    title: goods.value?.title ?? "",
    path: ROUTES.goods,
    params: sharePath.value.split("?")[1] ?? "",
    // 朋友圈卡片配商品主图：不给的话是小程序默认图，看不出是哪件货
    imageUrl: shareImageUrl(thumb(goods.value?.cover, 375)),
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
          带「商品 / 评价 / 详情 / 推荐」锚点。右边让出微信胶囊的位置（navBox.right）。
          v3 起购物车回到底栏（2026-09-28 用户拍板，淘宝京东同位置），这里只留返回。
        -->
        <view class="topbar" :class="{ 'is-solid': solid }" :style="topbarStyle">
          <view class="topbar__row sh-row" :style="topRowStyle">
            <view class="topbar__btn sh-center sh-hit" :style="btnStyle" @tap="goBack">
              <sh-icon name="chevronLeft" :size="34" :color="solid ? 'var(--sh-ink)' : '#fff'"></sh-icon>
            </view>
            <view v-if="solid" class="sh-fill sh-row topbar__anchors">
              <text
                v-for="a in shownAnchors"
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
          <swiper-item v-for="(img, i) in gallery" :key="img + i" class="hero__item sh-center" @tap="preview(gallery, img)">
            <sh-cover class="hero__emoji" :src="img" :w="750"></sh-cover>
          </swiper-item>
        </swiper>
        <view v-else class="hero sh-center" @tap="preview(gallery, goods.cover)">
          <sh-cover class="hero__emoji" :src="goods.cover" :w="750"></sh-cover>
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
            <!-- 已售放价格行右侧（v3 d01）。0 不说：零销量是个劝退信号 -->
            <text v-if="goods.sales > 0" class="txt-caption sh-muted sh-num price__sold">{{ $t("common.sold", { n: goods.sales }) }}</text>
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
            <!--
              分享（§3.2；详情页 v4 第一条）：一颗「分享」，点开面板两条路 —— 发给朋友 / 生成海报。
              此前标题行并排「海报」「分享」两颗：同一件事的两条路并排摆，人不知道点哪个。
              海报没删，收进面板（朋友圈只吃图片，那条路一个像素都没少）。
            -->
            <biz-share-act
              compact
              poster
              :sheet-title="String($t('share.sheetGoods'))"
              :path="sharePath"
              :inviter-no="user.user?.cUserNo"
              :merchant-no="goods.merchant.merchantNo"
              @poster="poster?.open()"
            ></biz-share-act>
          </view>

          <!-- 生鲜的决策属性（产地 · 口感 · 储存）。素色 chip，与下面那排促销/时效标签分开：
               那排是会变的（倒计时、满减、仅剩 N 件），这排是这件货本身是什么样 -->
          <view v-if="specChips.length" class="chips sh-wrap specchips">
            <text v-for="(c, i) in specChips" :key="i" class="sh-chip">{{ c }}</text>
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
            <text v-if="promo" class="sh-chip sh-chip--danger">
              {{ $t("promo.buyNGetM", promoLabelArgs(promo)) }}
            </text>
            <text v-for="a in goods.activityTags ?? []" :key="a.activityNo" class="sh-chip sh-chip--danger sh-num">
              {{ activityTagText(a) }}
            </text>
            <!--
              紧缺才说「仅剩 N 件」。v2 起单规格商品不弹面板，这句只放面板里的话单规格就永远看不到 ——
              而库存紧缺恰恰是该让人看见的那一刻
            -->
            <text v-if="lowStock" class="sh-chip sh-chip--danger sh-num">{{ $t("goods.lowStock", { n: lowStock }) }}</text>
          </view>
        </view>

        <!-- 领券。**只在有券时出现**（原型 g01）。销售区域仍在商品参数里 -->
        <!-- 领券（s36）：与店铺页、商家页同一个组件；券由本页预取，首屏只渲染一次 -->
        <!-- 从朋友圈卡片进来（单页模式）：只能看，下单要点底部「前往小程序」 -->
        <biz-single-page-tip></biz-single-page-tip>
        <biz-coupon-strip :merchant-no="goods.merchant.merchantNo" :preset="coupons"></biz-coupon-strip>

        <!--
          v4（2026-09-29）去掉了「已选 / 配送 / 保障」选购卡：这时他在看货、还没选。
          已选是替他做决定；配送在结算 / 订单页确认；保障等售后整块重新设计之前先不出半套说法。
          规格面板只从底栏两颗按钮打开。截单倒计时仍在价格下（它影响「要不要现在买」）。
        -->
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
        <view class="sh-card block shop">
          <biz-merchant-bar :merchant="goods.merchant" :store-name="goods.store?.storeName" quiet-no-rating @tap="openMerchant"></biz-merchant-bar>
          <!-- 本店热卖（v3 d04）：推荐里同店的前 3 件。没有同店在售就只留商家条 -->
          <template v-if="shopHot.length">
            <view class="sh-row sh-row--between shop__head" @tap="openMerchant">
              <text class="txt-strong">{{ $t("goods.shopHot") }}</text>
              <text class="txt-caption sh-muted">{{ $t("goods.enterShop") }}</text>
            </view>
            <view class="shop__grid">
              <view v-for="r in shopHot" :key="r.goodsNo" class="shop__item" @tap="openGoods(r.goodsNo)">
                <image class="shop__img" :src="thumb(r.cover, 375)" mode="aspectFill" />
                <text class="txt-caption shop__t">{{ r.title }}</text>
                <text class="txt-caption txt-strong sh-num">{{ money(r.price) }}</text>
              </view>
            </view>
          </template>
        </view>

        <!-- 评价。排在参数与图文之前（原型 g02）：「别人买了觉得怎样」比长图先被看。id 给锚点用 -->
        <!-- 评价、问答都空时合成一行（v3 d04）：新店的详情页不该一半是「还没有…」。id 仍给锚点用 -->
        <!-- 外层挂 block、内层挂 sh-row：同一元素上 block 与 sh-row 并存时，UnoCSS 的 .block 会把横排压成竖排 -->
        <view v-if="rvqaEmpty" id="sec-reviews" class="sh-card block">
          <view class="rvqa-empty sh-row sh-row--between">
            <text class="txt-sub sh-muted">{{ $t("goods.rvqaEmpty") }}</text>
            <text class="txt-sub txt-primary sh-hit" @tap="askQuestion">{{ $t("goods.askAction") }}</text>
          </view>
        </view>
        <view v-else id="sec-reviews" class="sh-card block">
          <view class="rvhead">
            <text class="txt-title">
              {{ summary?.total ? $t("review.title", { n: summary.total }) : $t("review.titleBare") }}
            </text>
            <!--
              评分与三维度分（§3.3）。**总数与平均分来自概览而不是当前这一页** ——
              按页算平均分的话，翻页时那个「总分」会变，而它看起来完全正常。
            -->
            <text v-if="summary?.total" class="txt-sub sh-num sh-muted">
              {{ goodRate ? `${$t("goods.goodRate", { n: goodRate })} · ` : "" }}{{ summary.avg }} · {{ $t("review.dims", {
                g: summary.avgGoods, f: summary.avgFulfillment, s: summary.avgService,
              }) }}
            </text>
          </view>
          <!-- 筛选：切一次重取一次，不在端上过滤（端上只有第一页） -->
          <scroll-view v-if="summary?.total" class="rvfilter" scroll-x>
            <view class="sh-row rvfilter__row">
              <text
                v-for="f in REVIEW_FILTERS"
                :key="f"
                class="txt-caption sh-chip rvfilter__chip"
                :class="reviewFilter === f ? 'sh-chip--primary' : ''"
                @tap="pickReviewFilter(f)"
              >
                {{ $t(`review.filter${f}`) }}{{ f === "IMAGE" && summary.withImages ? ` ${summary.withImages}` : "" }}
              </text>
            </view>
          </scroll-view>
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
          大家还问（§3.3）。**只有已回答的会下发** —— 一排没人答的问题传达的是
          「这家店不管事」，比没有问答区更糟；待回答的在运营端那一屏。

          <p>一条问题占两行：问句 + 回答。没有问答时只留「我要问」那一行 ——
          空着的问答区对买家没有意义，而那一个入口有。
        -->
        <view v-if="!rvqaEmpty" class="sh-card block qa">
          <view class="sh-row sh-row--between">
            <text class="txt-title">{{ $t("goods.qaTitle") }}</text>
            <text class="txt-sub txt-primary sh-hit" @tap="askQuestion">{{ $t("goods.askAction") }}</text>
          </view>
          <view v-for="q in questions" :key="q.questionNo" class="qa__item">
            <text class="txt-body qa__q">{{ q.content }}</text>
            <text class="txt-sub sh-muted qa__a">{{ q.answer }}</text>
          </view>
          <text v-if="!questions.length" class="txt-sub sh-muted">{{ $t("goods.qaEmpty") }}</text>
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
          <!--
            **前 4 条直出，其余进抽屉**（§3.2，淘宝京东同一形状）。
            全列的话，参数多的商品会把评价与图文顶到两屏以外；
            而参数恰恰是「已经想买、来核对细节」的人才看的。
          -->
          <!-- 两列表（v3 d05）：同样四条只要原来一半高 -->
          <view class="facts">
            <template v-for="(f, i) in factsHead" :key="i">
              <text class="txt-sub fact__label">{{ f.label }}</text>
              <text class="txt-sub txt-ink">{{ f.value }}</text>
            </template>
          </view>
          <view
            v-if="facts.length > factsHead.length"
            class="sh-row sh-row--between fact fact--more"
            @tap="factsOpen = true"
          >
            <text class="txt-sub txt-primary">{{ $t("goods.paramsAll", { n: facts.length }) }}</text>
            <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
          </view>

          <view v-if="goods.weighed" class="sh-notice sh-notice--warning notice">
            <text class="txt-caption notice__text">{{ $t("goods.weighed") }}</text>
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
        <view v-if="detailParas.length || goods.detailImages?.length" class="sh-card block dt">
          <text class="txt-title dt__h">{{ $t("goods.detailTitle") }}</text>
          <!--
            **按空行分段**（§3.2）。后端存的是纯文本，这里只排版、不解析任何标记 ——
            收 HTML 要在三端各消毒一次，漏一处就是 XSS（建品页那侧的注释同此）。
            此前整段正文挤成一坨，商家写的分段在买家这边一行都看不出来。
          -->
          <text v-for="(para, i) in detailParas" :key="i" class="txt-body dt__text">{{ para }}</text>
          <!--
            长图**零缝拼接**：商家传的是一张整稿切成的片 —— 线上「脆柿子」那 10 张
            全是 1058×1026（同宽，高只差 1px），平台规范也是按 750 宽切、单片高 ≤1500。
            既然是切片，任何缝隙或圆角都会把一张完整的稿切碎，所以包一层 `dt__imgs`：
            它撑开卡片左右内边距通栏出血，里面的图挨着排、不留缝。淘宝 / 拼多多 同此。
            mode="widthFix" 不能去：不给的话 1:3 的长图会被压进默认的 320×240 里。
          -->
          <view v-if="goods.detailImages?.length" class="dt__imgs">
            <image
              v-for="(img, i) in goods.detailImages"
              :key="img + i"
              class="dt__img"
              :src="thumb(img, 750)"
              mode="widthFix"
              lazy-load
              @tap="preview(goods.detailImages ?? [], img)"
            />
          </view>
        </view>

        <!--
          推荐位（§3.4 批 4）：**同店在售优先**，不够再补同类目。
          不做算法 —— 线上一共三件商品，任何「猜你喜欢」都只是换个顺序；
          而「这家店还卖什么」本身就是买家看完详情最常有的下一个问题。
          取不到就整段不出，不留一个空标题。
        -->
        <view id="sec-recommend"></view>
        <!-- 看了又看（v3 d06）：本店热卖之外的推荐，双列卡；不足 2 件整块不出 -->
        <view v-if="lookMore.length" class="block lookmore">
          <text class="txt-title dt__h">{{ $t("goods.lookMore") }}</text>
          <view class="lookmore__grid">
            <view v-for="r in lookMore" :key="r.goodsNo" class="sh-card lookmore__item" @tap="openGoods(r.goodsNo)">
              <image class="lookmore__img" :src="thumb(r.cover, 375)" mode="aspectFill" />
              <view class="lookmore__body">
                <text class="txt-sub lookmore__t">{{ r.title }}</text>
                <text class="txt-strong sh-num">{{ money(r.price) }}</text>
                <text class="txt-caption sh-muted">{{ r.merchant?.name }}</text>
              </view>
            </view>
          </view>
        </view>

        <!-- 海报：画布离屏，用户看到的是画完导出的那张图 -->
        <!-- 从门户来的：海报写门店名、用门店码（扫出来进的是这一家） -->
        <biz-poster ref="poster" :goods="goods" :store="viaStore ? { storeNo: viaStore, storeName: '' } : undefined"></biz-poster>

        <!-- 服务承诺细则（§3.4）：承诺写在页面上，细则就得能查到 -->
        <!-- 全部参数 -->
        <sh-sheet :visible="factsOpen" :title="String($t('goods.paramsTitle'))" @close="factsOpen = false">
          <view class="sh-cells">
            <view v-for="(f, i) in facts" :key="i" class="sh-cell sh-row sh-row--between sh-row--top">
              <text class="txt-sub fact__label">{{ f.label }}</text>
              <text class="txt-sub fact__value">{{ f.value }}</text>
            </view>
          </view>
          <view class="sh-btn sheet__done" @tap="factsOpen = false">{{ $t("goods.couponDone") }}</view>
        </sh-sheet>

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


        <!--
          规格面板。规格矩阵、买赠提示、数量都在这里 —— 页面上只留一行「已选」。
          单规格商品点底栏的「加入购物车 / 立即购买」**不弹它**，直接按 1 件执行；
          多规格才先弹，在面板里选好再按。
        -->
        <sh-sheet :visible="showSku" :title="String($t('goods.chosen'))" @close="showSku = false">
          <view class="skuhead sh-row">
            <sh-cover class="skuhead__img" :src="goods.cover" :w="200"></sh-cover>
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

        <!-- dock：贴底通栏（2026-09-28 真机反馈浮动药丸滑动时与商品卡叠在一起看不清） -->
        <sh-actionbar pill="plain" dock :pad="160">
          <!-- 底栏：店铺 · 购物车 · 两颗按钮（v3，2026-09-28 用户拍板购物车回底栏）。分享仍在标题旁 -->
          <view class="actionbar__icon sh-center" @tap="openMerchant">
            <sh-icon name="store" :size="40" color="var(--sh-sub)"></sh-icon>
            <text class="txt-caption sh-muted">{{ $t("goods.shop") }}</text>
          </view>
          <view class="actionbar__icon actionbar__cart sh-center" :class="{ 'is-bouncing': bouncing }" @tap="gotoCart">
            <sh-icon name="cart" :size="40" color="var(--sh-sub)"></sh-icon>
            <text v-if="cart.count" class="sh-badge-count actionbar__badge sh-num">{{ cart.count > 99 ? "99+" : cart.count }}</text>
            <text class="txt-caption sh-muted">{{ $t("goods.cart") }}</text>
          </view>
          <!-- 拼团商品：单买 / 开团（s21）。参团在团页上，开团价由活动定 -->
          <!-- 仅活动可售：directBuyable 为假时没有单买 / 加购；拼团也没有就只剩一颗压暗的「暂不可购买」 -->
          <template v-if="grp && SHOW_GROUP_CTA">
            <view v-if="directBuyable" class="sh-btn sh-fill actionbar__add" :class="{ 'is-disabled': !barReady }" @tap="tapBuy">
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
            <view class="sh-btn sh-fill actionbar__add" :class="{ 'is-disabled': !barReady }" @tap="tapAdd($event)">
              {{ soldOut && !multiSku ? $t("goods.soldOut") : $t("goods.addCart") }}
            </view>
            <view class="txt-sub sh-btn actionbar__buy sh-fill" :class="{ 'is-disabled': !barReady }" @tap="tapBuy">
              {{ $t("goods.buyNow") }}
            </view>
          </template>
        </sh-actionbar>
  
  
    </template>
    <phone-gate :visible="phoneRequired.visible.value" :suggest="phoneRequired.suggest.value"
    @done="onPhoneBound" @close="onPhoneGateClose" />
</sh-scaffold>
</template>

<style scoped>





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
  /* 1:1。**rpx 的定义就是 750rpx = 屏宽**，所以 750rpx 高 = 正方形满宽框。
     原先是 560rpx（比例 1.34），而商家传的主图实测**全是 1:1**
     （线上 10 张无一例外：1280×1280 / 1200×1200），sh-cover 默认 aspectFill
     填满裁切，于是每张主图上下各被切掉约 25% —— 柿子那张的字就是这么没的。
     淘宝 / 拼多多 的主图区同样是满宽正方形。 */
  height: 750rpx;
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
.actionbar__cart.is-bouncing {
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
/* 段与段之间。此前是零 —— 商家按空行分的段，在买家这边又粘回了一坨；
   之前看不出来，是因为后面那张图的 margin 把最后一段顶开了，段间并没有距离 */
.dt__text + .dt__text {
  margin-top: 12rpx;
}
/* 图文详情的长图区。出血到卡片边：稿是按 750 宽做的，留着 32rpx 内边距等于
   给整张稿加一圈白框，稿里的字也跟着缩一圈。卡片的圆角要把出血的图裁住，
   所以 .dt 上挂 overflow: hidden。 */
.dt {
  overflow: hidden;
  /* 通栏到**屏幕边**，不是卡片边：详情稿按满屏 750 宽做，留着页边距等于给整张稿
     加一圈白框、稿里的字也跟着缩。所以这一段不当圆角卡片，按整屏楼层排 ——
     淘宝 / 拼多多 的「宝贝详情」同此。标题仍由卡片内边距缩进。 */
  margin-inline: calc(-1 * var(--sh-pad-page, 28rpx));
  border-radius: 0;
}
.dt__imgs {
  margin-top: 16rpx;
  margin-inline: calc(var(--sh-pad-card, 32rpx) * -1);
}
/* 切片之间**不留缝**（理由见模板那段注释）。vertical-align 防 inline 基线缝 */
.dt__img {
  display: block;
  width: 100%;
  vertical-align: top;
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


/* 「全部参数」入口：与参数行同一行高，靠 txt-primary 与上面几行区分 */
.fact--more {
  padding-top: 12rpx;
}

.sheet__done {
  margin-top: 24rpx;
}

/* 评价头部：标题与评分同一行的两端 */

/* 筛选条：横滑一行，不换行占两层 */
.rvfilter {
  margin-top: 12rpx;
  white-space: nowrap;
}
.rvfilter__row {
  gap: 12rpx;
}
.rvfilter__chip {
  flex-shrink: 0;
}

/* 问答：一问一答两行，问句重、答句轻 */
.qa__item {
  margin-top: 16rpx;
}
.qa__q,
.qa__a {
  display: block;
}
.qa__a {
  margin-top: 4rpx;
}

/* ── v3（TDD-C端商品详情页v3）── */
/* 已售挤到价格行最右 */
.price__sold {
  margin-inline-start: auto;
  align-self: center;
}
/* 评价、问答都空时的那一行 */
.rvqa-empty {
  gap: 24rpx;
}
/* 店铺卡下的本店热卖：三列等宽 */
.shop__head {
  margin-top: 20rpx;
}
.shop__grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16rpx;
  margin-top: 16rpx;
}
.shop__item {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  min-width: 0;
}
.shop__img {
  width: 100%;
  height: 200rpx;
  border-radius: 16rpx;
}
.shop__t,
.lookmore__t {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
/* 参数两列表：标签列按内容宽，值列吃余量 */
.facts {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 12rpx 32rpx;
  margin-top: 16rpx;
}
/* 看了又看：双列卡，卡片本身是块，没有外层白卡 */
.lookmore__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16rpx;
  margin-top: 16rpx;
}
.lookmore__item {
  padding: 0;
  overflow: hidden;
}
.lookmore__img {
  width: 100%;
  height: 330rpx;
}
.lookmore__body {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  padding: 12rpx 16rpx 16rpx;
}
/* 底栏购物车的件数角标 */
.actionbar__badge {
  position: absolute;
  top: -4rpx;
  inset-inline-end: 4rpx;
}
</style>
