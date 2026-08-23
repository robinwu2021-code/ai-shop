<script setup lang="ts">
// 商品详情（五态）：标品 / 生鲜 / 服务 / 虚拟 / 卡券。
// 五态共用一套骨架，差异只落在「规格矩阵 → 事实区 → 底部条」三处，
// 与 strategies（计价 + 履约）的分层保持一致。
import { computed, getCurrentInstance, nextTick, onUnmounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad, onShareAppMessage } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useCartStore } from "@/stores/cart";
import { useUserStore } from "@/stores/user";
import { useCommunityStore } from "@/stores/community";
import { buildShareMessage } from "@shared/ports/share";
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
import { defaultFulfillment } from "@shared/utils/goods";
import type { Goods, Review, Sku } from "@shared/types";

const { t } = useI18n();
const cart = useCartStore();
const user = useUserStore();
const community = useCommunityStore();

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
  () => !!sku.value && !soldOut.value && !cutoffPassed.value && appointmentReady.value,
);

/** 买 N 送 M 促销（一期一个商品最多一条） */
const promo = computed(() => buyNGetM(goods.value?.promotions));
/** 按当前购买数量算出的赠品件数 */
const giftQty = computed(() => giftQtyFor(promo.value, qty.value));

const off = computed(() => {
  const s = sku.value;
  if (!s?.originPrice || s.originPrice <= s.price) return 0;
  return Math.round((1 - s.price / s.originPrice) * 100);
});

/** 当前选中日期的可选时刻 */
const times = computed(
  () => goods.value?.slots?.find((s) => s.date === slotDate.value)?.times ?? [],
);

async function load(goodsNo: string) {
  const g = await api.goodsDetail(goodsNo);
  goods.value = g;
  // 默认选中第一个有货的 SKU 的组合
  const first = g.skus.find((s) => s.stock > 0) ?? g.skus[0];
  chosen.value = first ? [...first.optionValues] : [];
  slotDate.value = g.slots?.[0]?.date ?? "";
  uni.setNavigationBarTitle({ title: g.title });
  measureCartAnchor();
  reviews.value = await api.reviewList({ goodsNo });
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

function stepQty(delta: number) {
  const max = goods.value?.limitPerUser || Infinity;
  qty.value = Math.min(max, Math.max(1, qty.value + delta));
}

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

function gotoCart() {
  uni.switchTab({ url: ROUTES.cart });
}

// 本页的飞入落点是操作条上的购物车入口，不是底部菜单（本页没有菜单）。
// ⚠️ 操作条挂在 `v-if="goods"` 下面 —— onMounted 时商品还没加载，元素不存在，量不到。
// 必须等数据到位、DOM 渲染完再量，否则动效会悄悄退回到「屏幕右下角」的兜底落点。
const instance = getCurrentInstance();
const bouncing = ref(false);

function measureCartAnchor() {
  nextTick(() => registerCartAnchor(".actionbar__cart", instance?.proxy));
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
  <sh-scaffold v-if="goods">
    <!-- 主视觉 -->
    <!--
      主视觉。**此前只画 cover 一张** —— `goods.images` 后端一直在发、
      商家在 B 端也一直传得进去，而这个页面里一次都没引用过：
      店主传了五张详情图，买家一张也看不到，两侧都不报错。

      只有一张时不套 swiper：一个滑不动的轮播还带着一个指示点，
      看着像坏了。
    -->
    <swiper v-if="gallery.length > 1" class="hero" :indicator-dots="true" circular>
      <swiper-item v-for="(img, i) in gallery" :key="img + i" class="hero__item">
        <sh-cover class="hero__emoji" :src="img"></sh-cover>
      </swiper-item>
    </swiper>
    <view v-else class="hero">
      <sh-cover class="hero__emoji" :src="goods.cover"></sh-cover>
    </view>
    <view v-if="off" class="hero__wrap">
      <text class="hero__off sh-num">-{{ off }}%</text>
    </view>

    <!-- 标题与价格 -->
    <view class="sh-card block">
      <text class="sh-h1 title">{{ goods.title }}</text>
      <text class="sh-muted sub">{{ goods.subtitle }}</text>

      <view class="price">
        <text class="price__now sh-num">{{ money(sku?.price ?? goods.price) }}</text>
        <text v-if="sku?.originPrice" class="price__was sh-num">
          {{ money(sku.originPrice) }}
        </text>
      </view>

      <view class="chips">
        <text v-if="isFresh && !cutoffPassed" class="sh-chip sh-chip--warning">
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
        <text class="sh-chip sh-num">{{ $t("common.sold", { n: goods.sales }) }}</text>
      </view>
    </view>

    <!-- 商家信息：商品与服务都要展示，点进商家详情 -->
    <view class="sh-card block">
      <biz-merchant-bar :merchant="goods.merchant" @tap="openMerchant"></biz-merchant-bar>
    </view>

    <!-- 规格矩阵：每个维度一行，不可组合的取值置灰 -->
    <view class="sh-card block">
      <view v-for="(group, gi) in goods.specGroups" :key="group.name" class="specgroup">
        <text class="sh-muted">{{ group.name }}</text>
        <view class="specs">
          <view
            v-for="opt in group.options"
            :key="opt"
            class="spec"
            :class="{
              'is-on': chosen[gi] === opt,
              'is-off': !optionState(gi, opt).inStock,
            }"
            @tap="choose(gi, opt)"
          >
            <text class="spec__name">{{ opt }}</text>
          </view>
        </view>
      </view>

      <!-- 买赠：当前数量能拿几件赠品，实时算给用户看 -->
      <view v-if="promo" class="giftline">
        <text class="giftline__text">
          {{ giftQty > 0
            ? $t("promo.willGift", { n: giftQty })
            : $t("promo.needMore", { n: promo.buyN - (qty % promo.buyN) }) }}
        </text>
      </view>

      <view class="qty">
        <text class="sh-muted sh-num">{{ $t("goods.stock", { n: sku?.stock ?? 0 }) }}</text>
        <view class="stepper">
          <view class="stepper__btn" @tap="stepQty(-1)"><text>−</text></view>
          <text class="stepper__num sh-num">{{ qty }}</text>
          <view class="stepper__btn" @tap="stepQty(1)"><text>＋</text></view>
        </view>
      </view>
    </view>

    <!-- 预约：日期 + 时刻 -->
    <view v-if="needAppointment" class="sh-card block">
      <text class="sh-muted">{{ $t("goods.pickDate") }}</text>
      <scroll-view class="dates" scroll-x>
        <view
          v-for="s in goods.slots"
          :key="s.date"
          class="date"
          :class="{ 'is-on': slotDate === s.date }"
          @tap="((slotDate = s.date), (slotTime = ''))"
        >
          <text class="date__text sh-num">{{ s.date.slice(5) }}</text>
        </view>
      </scroll-view>

      <text class="sh-muted times-label">{{ $t("goods.pickTime") }}</text>
      <view class="times">
        <view
          v-for="tm in times"
          :key="tm.time"
          class="time"
          :class="{ 'is-on': slotTime === tm.time, 'is-off': tm.left <= 0 }"
          @tap="tm.left > 0 && (slotTime = tm.time)"
        >
          <text class="time__t sh-num">{{ tm.time }}</text>
          <text class="time__left">{{ $t("goods.slotLeft", { n: tm.left }) }}</text>
        </view>
      </view>

      <view class="notice notice--info">
        <text class="notice__text">
          {{ $t("goods.changeRule", { n: TRADE_RULES.appointmentChangeBeforeHours }) }}
        </text>
      </view>
    </view>

    <!-- 事实区 -->
    <view class="sh-card block">
      <view class="fact">
        <text class="fact__label">{{ $t("goods.fulfillment") }}</text>
        <text class="fact__value">
          {{ goods.fulfillments.map((x) => $t(`fulfillment.${x}`)).join(" · ") }}
        </text>
      </view>
      <view v-if="isFresh && goods.arrivalDesc" class="fact">
        <text class="fact__label">{{ $t("goods.arrival") }}</text>
        <text class="fact__value">{{ goods.arrivalDesc }}</text>
      </view>
      <view v-if="isFresh && goods.origin" class="fact">
        <text class="fact__label">{{ $t("goods.origin") }}</text>
        <text class="fact__value">{{ goods.origin }}</text>
      </view>
      <view v-if="isService && goods.storeName" class="fact">
        <text class="fact__label">{{ $t("goods.store") }}</text>
        <text class="fact__value">{{ goods.storeName }}</text>
      </view>
      <view v-if="isCard && goods.card" class="fact">
        <text class="fact__label">{{ $t("goods.validity") }}</text>
        <text class="fact__value sh-num">
          {{ $t("goods.validDays", { n: goods.card.validDays }) }}
        </text>
      </view>
      <view class="fact">
        <text class="fact__label">{{ $t("goods.limitLabel") }}</text>
        <text class="fact__value">
          {{ goods.limitPerUser ? $t("goods.limit", { n: goods.limitPerUser }) : $t("goods.noLimit") }}
        </text>
      </view>

      <view v-if="goods.weighed" class="notice">
        <text class="notice__text">{{ $t("goods.weighed") }}</text>
      </view>
      <view v-if="isVirtual && goods.virtual" class="notice notice--info">
        <text class="notice__text">{{ goods.virtual.deliverDesc }}</text>
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
      <text class="sh-h2 dt__h">{{ $t("goods.detailTitle") }}</text>
      <text v-if="goods.detail" class="dt__text">{{ goods.detail }}</text>
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

    <!-- 评价 -->
    <view class="sh-card block">
      <view class="rvhead">
        <text class="sh-h2">{{ $t("review.title", { n: reviews.length }) }}</text>
      </view>
      <biz-review
        v-for="r in reviews"
        :key="r.reviewNo"
        :review="r"
        @like="likeReview(r)"
      ></biz-review>
      <text v-if="!reviews.length" class="rvempty">{{ $t("review.empty") }}</text>
    </view>

    <!-- 底部操作条。详情页不是 tab 页，没有底部菜单，
         所以购物车入口必须在这里给 —— 否则加完购没有任何落点与反馈。 -->
    <view class="actionbar">
      <view class="actionbar__icon" @tap="() => {}">
        <sh-icon name="share" :size="40" color="var(--sh-sub)"></sh-icon>
      </view>

      <view
        class="actionbar__icon actionbar__cart"
        :class="{ 'is-bouncing': bouncing }"
        @tap="gotoCart"
      >
        <sh-icon name="cart" :size="40" color="var(--sh-sub)"></sh-icon>
        <text v-if="cart.count" class="actionbar__badge sh-num">
          {{ cart.count > 99 ? "99+" : cart.count }}
        </text>
      </view>
      <view
        class="sh-btn actionbar__add"
        :class="{ 'is-disabled': !buyable }"
        @tap="buyable && addToCart($event)"
      >
        {{ soldOut ? $t("goods.soldOut") : $t("goods.addCart") }}
      </view>
      <view
        class="sh-btn actionbar__buy"
        :class="{ 'is-disabled': !buyable }"
        @tap="buyable && buyNow()"
      >
        {{ $t("goods.buyNow") }}
      </view>
    </view>
    <view class="actionbar__spacer" />
  </sh-scaffold>
</template>

<style scoped>
.hero {
  position: relative;
  height: 440rpx;
  border-radius: 44rpx;
  background: var(--sh-primary-tint);
  display: flex;
  align-items: center;
  justify-content: center;
}
/* 原先只有字号 —— 那是给 emoji 写的。换成真图后没有可撑的尺寸，
   图会塌成 0 高；给满整块 hero，emoji 仍按字号居中显示。 */
.hero__emoji {
  width: 100%;
  height: 100%;
  border-radius: 44rpx;
  font-size: 200rpx;
  line-height: 1;
}
.hero__item {
  display: flex;
  align-items: center;
  justify-content: center;
}
/* 折扣标原先绝对定位在 hero 里；换成 swiper 之后它会跟着页面一起滑走，
   所以拎出来单独定位在轮播上方一层 */
.hero__wrap {
  position: relative;
  height: 0;
}
.hero__off {
  position: absolute;
  top: -412rpx;
  inset-inline-start: 28rpx;
  background: var(--sh-danger);
  color: #fff;
  font-size: 24rpx;
  font-weight: 400;
  padding: 8rpx 20rpx;
  border-radius: 9999px;
}
.block {
  margin-top: 20rpx;
}
.title {
  display: block;
}
.sub {
  display: block;
  margin-top: 10rpx;
}
.price {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
  margin-top: 28rpx;
}
.price__now {
  font-size: 48rpx;
  font-weight: 700;
  color: var(--sh-ink);
}
.price__was {
  font-size: 26rpx;
  color: var(--sh-sub);
  text-decoration: line-through;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 24rpx;
}
.specgroup + .specgroup {
  margin-top: 32rpx;
}
.specs {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-top: 16rpx;
}
.spec {
  padding: 20rpx 32rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
}
/* 选中态用实底主色而非 tint：mono 这类低饱和皮肤下 tint 与 faint 几乎无差别，
   选没选中看不出来。实底在四套皮肤 × 明暗下都清晰。 */
.spec.is-on {
  background: var(--sh-primary);
}
.spec.is-on .spec__name {
  color: var(--sh-on-primary);
}
.spec.is-off {
  opacity: 0.35;
}
.spec__name {
  font-size: 26rpx;
  color: var(--sh-ink);
  font-weight: 600;
}
.giftline {
  margin-top: 28rpx;
  background: var(--sh-danger-tint);
  border-radius: 24rpx;
  padding: 20rpx 26rpx;
}
.giftline__text {
  font-size: 24rpx;
  color: var(--sh-danger);
  line-height: 1.5;
}
.qty {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 32rpx;
}
.stepper {
  display: flex;
  align-items: center;
  gap: 8rpx;
  background: var(--sh-faint);
  border-radius: 9999px;
  padding: 6rpx;
}
.stepper__btn {
  width: 56rpx;
  height: 56rpx;
  border-radius: 9999px;
  background: var(--sh-surface);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--sh-ink);
  font-size: 30rpx;
}
.stepper__num {
  min-width: 56rpx;
  text-align: center;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.dates {
  white-space: nowrap;
  margin-top: 16rpx;
}
.date {
  display: inline-block;
  padding: 18rpx 28rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  margin-inline-end: 12rpx;
}
.date.is-on {
  background: var(--sh-primary);
}
.date.is-on .date__text {
  color: var(--sh-on-primary);
}
.date__text {
  font-size: 26rpx;
  color: var(--sh-ink);
  font-weight: 600;
}
.times-label {
  display: block;
  margin-top: 32rpx;
}
.times {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-top: 16rpx;
}
.time {
  padding: 16rpx 26rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  text-align: center;
}
.time.is-on {
  background: var(--sh-primary);
}
.time.is-on .time__t,
.time.is-on .time__left {
  color: var(--sh-on-primary);
}
.time.is-off {
  opacity: 0.35;
}
.time__t {
  display: block;
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.time__left {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 2rpx;
}
.fact {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 32rpx;
  padding: 18rpx 0;
}
.fact__label {
  font-size: 26rpx;
  color: var(--sh-sub);
  flex-shrink: 0;
}
.fact__value {
  font-size: 26rpx;
  color: var(--sh-ink);
  text-align: end;
}
.notice {
  margin-top: 16rpx;
  background: var(--sh-warning-tint);
  border-radius: 24rpx;
  padding: 22rpx 26rpx;
}
.notice--info {
  background: var(--sh-primary-tint);
}
.notice__text {
  font-size: 24rpx;
  color: var(--sh-warning);
  line-height: 1.5;
}
.notice--info .notice__text {
  color: var(--sh-primary-text);
}
.actionbar {
  position: fixed;
  inset-inline: 28rpx;
  bottom: calc(28rpx + env(safe-area-inset-bottom));
  display: flex;
  gap: 16rpx;
  background: var(--sh-surface);
  border-radius: 9999px;
  padding: 12rpx;
}
.actionbar__icon {
  position: relative;
  flex: 0 0 auto;
  width: 88rpx;
  height: 88rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  display: flex;
  align-items: center;
  justify-content: center;
}
.actionbar__cart.is-bouncing {
  animation: shCartBounce 0.42s cubic-bezier(0.34, 1.56, 0.64, 1);
}
@keyframes shCartBounce {
  0% { transform: scale(1); }
  40% { transform: scale(1.28); }
  100% { transform: scale(1); }
}
.actionbar__badge {
  position: absolute;
  top: 8rpx;
  inset-inline-end: 8rpx;
  min-width: 30rpx;
  height: 30rpx;
  padding: 0 6rpx;
  border-radius: 9999px;
  background: var(--sh-danger);
  color: #fff;
  font-size: 24rpx;
  line-height: 30rpx;
  text-align: center;
}
.actionbar__add,
.actionbar__buy {
  flex: 1;
  min-width: 0;
  font-size: 26rpx;
  padding: 26rpx 8rpx;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.actionbar__add {
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
}
.actionbar__spacer {
  height: 220rpx;
}
.is-disabled {
  opacity: 0.45;
}
.rvhead {
  margin-bottom: 24rpx;
}
.rvempty {
  display: block;
  text-align: center;
  color: var(--sh-sub);
  font-size: 24rpx;
  padding: 40rpx 0;
}
/* 图文详情：正文与长图 */
.dt__h {
  display: block;
  margin-bottom: 16rpx;
}
.dt__text {
  display: block;
  font-size: 28rpx;
  line-height: 1.7;
  color: var(--sh-ink);
  white-space: pre-wrap;
}
.dt__img {
  display: block;
  width: 100%;
  margin-top: 16rpx;
  border-radius: 16rpx;
}
</style>
