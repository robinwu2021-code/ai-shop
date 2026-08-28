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
    <swiper v-if="gallery.length > 1" class="hero sh-center" :indicator-dots="true" circular>
      <swiper-item v-for="(img, i) in gallery" :key="img + i" class="hero__item sh-center">
        <sh-cover class="hero__emoji" :src="img"></sh-cover>
      </swiper-item>
    </swiper>
    <view v-else class="hero sh-center">
      <sh-cover class="hero__emoji" :src="goods.cover"></sh-cover>
    </view>
    <view v-if="off" class="hero__wrap">
      <text class="txt-caption hero__off sh-num">-{{ off }}%</text>
    </view>

    <!-- 标题与价格 -->
    <view class="sh-card block">
      <text class="txt-display title">{{ goods.title }}</text>
      <text class="sh-muted sub">{{ goods.subtitle }}</text>

      <view class="price">
        <text class="txt-hero sh-num sh-center">{{ money(sku?.price ?? goods.price) }}</text>
        <text v-if="sku?.originPrice" class="txt-sub price__was sh-num">
          {{ money(sku.originPrice) }}
        </text>
      </view>

      <view class="chips sh-wrap">
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
      <view v-if="promo" class="giftline">
        <text class="txt-caption giftline__text is-danger">
          {{ giftQty > 0
            ? $t("promo.willGift", { n: giftQty })
            : $t("promo.needMore", { n: promo.buyN - (qty % promo.buyN) }) }}
        </text>
      </view>

      <view class="qty sh-row sh-row--between">
        <text class="sh-muted sh-num">{{ $t("goods.stock", { n: sku?.stock ?? 0 }) }}</text>
        <view class="stepper sh-row">
          <view class="txt-body stepper__btn sh-center" @tap="stepQty(-1)"><text>−</text></view>
          <text class="txt-strong stepper__num sh-num">{{ qty }}</text>
          <view class="txt-body stepper__btn sh-center" @tap="stepQty(1)"><text>＋</text></view>
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

      <view class="notice notice--info">
        <text class="txt-caption notice__text is-warning">
          {{ $t("goods.changeRule", { n: TRADE_RULES.appointmentChangeBeforeHours }) }}
        </text>
      </view>
    </view>

    <!-- 事实区 -->
    <view class="sh-card block">
      <view class="fact sh-row sh-row--between sh-row--top">
        <text class="txt-sub fact__label">{{ $t("goods.fulfillment") }}</text>
        <text class="txt-sub fact__value">
          {{ goods.fulfillments.map((x) => $t(`fulfillment.${x}`)).join(" · ") }}
        </text>
      </view>
      <view v-if="isFresh && goods.arrivalDesc" class="fact sh-row sh-row--between sh-row--top">
        <text class="txt-sub fact__label">{{ $t("goods.arrival") }}</text>
        <text class="txt-sub fact__value">{{ goods.arrivalDesc }}</text>
      </view>
      <!--
        **商品参数**（产地 / 保质期 / 材质…）。商家在建品页填的就是这些。
        没有这一段的话，他填了买家看不见 —— 等于白填，而他不会知道。

        <p>接在既有的「事实区」里而不是另起一张卡：买家心里这些和履约方式、
        到货时间是同一类信息（「这货是什么样的」），分成两块只是把一件事拆散。
      -->
      <view v-for="p in goods.params ?? []" :key="p.dimNo" class="fact sh-row sh-row--between sh-row--top">
        <text class="txt-sub fact__label">{{ p.name || p.dimNo }}</text>
        <text class="txt-sub fact__value">{{ p.label }}</text>
      </view>
      <!--
        旧的 `origin` 列：**参数里已经有产地就不再重复显示**。
        两处都显示的话，商家在新的参数里填了「本地」、老列里还留着
        早年填的「山东」—— 买家看到两个产地，而谁也说不清哪个算数。
        存量商品（只有老列、没有参数）仍旧照常显示。
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
      <view class="fact sh-row sh-row--between sh-row--top">
        <text class="txt-sub fact__label">{{ $t("goods.limitLabel") }}</text>
        <text class="txt-sub fact__value">
          {{ goods.limitPerUser ? $t("goods.limit", { n: goods.limitPerUser }) : $t("goods.noLimit") }}
        </text>
      </view>

      <view v-if="goods.weighed" class="notice">
        <text class="txt-caption notice__text is-warning">{{ $t("goods.weighed") }}</text>
      </view>
      <view v-if="isVirtual && goods.virtual" class="notice notice--info">
        <text class="txt-caption notice__text is-warning">{{ goods.virtual.deliverDesc }}</text>
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

    <!-- 评价 -->
    <view class="sh-card block">
      <view class="rvhead">
        <text class="txt-title">{{ $t("review.title", { n: reviews.length }) }}</text>
      </view>
      <biz-review
        v-for="r in reviews"
        :key="r.reviewNo"
        :review="r"
        @like="likeReview(r)"
      ></biz-review>
      <text v-if="!reviews.length" class="txt-caption rvempty">{{ $t("review.empty") }}</text>
    </view>

    <!-- 底部操作条。详情页不是 tab 页，没有底部菜单，
         所以购物车入口必须在这里给 —— 否则加完购没有任何落点与反馈。 -->
    <sh-actionbar pill="plain" :pad="220">
      <view class="actionbar__icon sh-center" @tap="() => {}">
        <sh-icon name="share" :size="40" color="var(--sh-sub)"></sh-icon>
      </view>

      <view
        class="actionbar__icon actionbar__cart sh-center"
        :class="{ 'is-bouncing': bouncing }"
        @tap="gotoCart"
      >
        <sh-icon name="cart" :size="40" color="var(--sh-sub)"></sh-icon>
        <text v-if="cart.count" class="sh-badge-count actionbar__badge sh-num">
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
        class="txt-sub sh-btn actionbar__buy sh-fill"
        :class="{ 'is-disabled': !buyable }"
        @tap="buyable && buyNow()"
      >
        {{ $t("goods.buyNow") }}
      </view>
    </sh-actionbar>
  </sh-scaffold>
</template>

<style scoped>
.hero {
  position: relative;
  height: 440rpx;
  border-radius: 44rpx;
  background: var(--sh-primary-tint);
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
  /* 危险色是固定的语义红（不随皮肤变），白字压它的取舍见 base.css 的 .sh-btn--danger-solid */
  color: #fff;
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
  margin-top: 8rpx;
}
.price {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
  margin-top: 28rpx;
}

.price__was {
  text-decoration: line-through;
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
  background: var(--sh-danger-tint);
  border-radius: 24rpx;
  padding: 20rpx 26rpx;
}
.qty {
  margin-top: 32rpx;
}
.stepper {
  gap: 8rpx;
  background: var(--sh-faint);
  border-radius: 9999px;
  padding: 8rpx;
}
.stepper__btn {
  width: 56rpx;
  height: 56rpx;
  border-radius: 9999px;
  background: var(--sh-surface);
}
.stepper__num {
  min-width: 56rpx;
  text-align: center;
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
  padding: 18rpx 0;
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
  background: var(--sh-warning-tint);
  border-radius: 24rpx;
  padding: 22rpx 26rpx;
}
.notice--info {
  background: var(--sh-primary-tint);
}
.notice--info .notice__text {
  color: var(--sh-primary-text);
}
.actionbar__icon {
  position: relative;
  flex: 0 0 auto;
  width: 88rpx;
  height: 88rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
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
}
.actionbar__add,
.actionbar__buy {
  padding: 26rpx 8rpx;
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
.rvempty {
  display: block;
  text-align: center;
  padding: 40rpx 0;
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
</style>
