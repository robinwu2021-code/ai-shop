<script setup lang="ts">
// 商家详情：头部（谁 · 范围 · 三个数）→ 在售商品（两列）/ 全部评价。
// 一期平台方是唯一入驻方，页面照样按「多商家」写 —— 二期开放入驻只是数据变多。
//
// 2026-09-19 收过一轮：头部去掉了「企业商家」类型标、入驻时间、「新评价权重更高」那句依据，
// 以及零评价时一排 5.0 的分维度分（那是默认值，不是评出来的）。
// 留下的都回答「这家店能不能买、靠不靠谱」。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad, onShareAppMessage } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useCartStore } from "@/stores/cart";
import { ROUTES } from "@shared/utils/constants";
import { firstBuyableSku } from "@shared/utils/goods";
import { flyToCart, tapPoint } from "@/shared/fly";
import { buildShareMessage } from "@shared/ports/share";
import type { Goods, Merchant, Review } from "@shared/types";

const { t } = useI18n();
const cart = useCartStore();
const merchant = ref<Merchant | null>(null);
const goods = ref<Goods[]>([]);
const reviews = ref<Review[]>([]);
const tab = ref<"goods" | "reviews">("goods");

/** 首屏到过没有。**不是 `loading`** —— 那个含下拉刷新，刷新时把列表换成空态是另一个 bug */
const loaded = ref(false);
/** 这次没取到。**与「确定为空」是两件事** —— 网络不通时不该显示「还没有…」 */
const failed = ref(false);

async function load(merchantNo: string) {
  try {
    const [m, g, r] = await Promise.all([
      api.merchantDetail(merchantNo),
      api.goodsList({ merchantNo, size: 50 }),
      api.reviewList({ merchantNo }),
    ]);
    merchant.value = m;
    goods.value = g.records;
    reviews.value = r;
    uni.setNavigationBarTitle({ title: m.name });
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

/**
 * 标签去掉与「自营」标重复的那条 —— 库里有店把「平台自营」也写进了标签，
 * 于是头部同时出现「自营」chip 和「平台自营」chip。
 */
const tags = computed(() => {
  const m = merchant.value;
  if (!m) return [];
  const self = String(t("merchant.selfOperated"));
  return m.tags.filter((tg) => !(m.selfOperated && tg.includes(self)));
});

/**
 * 三个数：评分 · 已售 · 营业时间（没填营业时间就换成在售件数）。
 * **没人评过写「新店」，不写分**：后端对零评价回 5.0，那是默认值。
 */
const stats = computed(() => {
  const m = merchant.value;
  if (!m) return [];
  return [
    { k: t("merchant.statRating"), v: m.ratingCount > 0 ? m.rating.toFixed(1) : String(t("shops.newShop")) },
    { k: t("merchant.statSold"), v: String(m.salesCount) },
    m.openHours
      ? { k: t("merchant.hours"), v: m.openHours }
      : { k: t("merchant.statGoods"), v: String(goods.value.length) },
  ];
});

function openGoods(g: Goods) {
  uni.navigateTo({ url: `${ROUTES.goods}?goodsNo=${g.goodsNo}` });
}

async function add(g: Goods, e: unknown) {
  try {
    await cart.add(g.goodsNo, firstBuyableSku(g).skuNo, 1);
    const p = tapPoint(e as Parameters<typeof tapPoint>[0]);
    flyToCart(p.x, p.y, g.cover);
  } catch (err) {
    uni.showToast({ title: (err as Error).message, icon: "none" });
  }
}

async function like(r: Review) {
  const updated = await api.toggleReviewLike(r.reviewNo);
  const i = reviews.value.findIndex((x) => x.reviewNo === r.reviewNo);
  if (i >= 0) reviews.value[i] = updated;
}

const currentNo = ref("");

onLoad((q) => {
  const no = (q?.merchantNo as string) || "";
  currentNo.value = no;
  if (no) load(no);
});

/*
 * 分享商家。**门店主页有这个、商家页此前没有** —— 而分享商家是 C-ST-05，
 * 与扫码同属 ADR-004 的主获客路径。
 *
 * 落点给门店主页而不是本页：门店主页是为「老客直达下单」设计的
 * （第一屏是常买、有再来一单），商家页是介绍页。把人分享到介绍页，
 * 他还要多点一次才能买。
 *
 * `merchantNo` 必须带上，否则进店归因断掉、费率分档判不出来（ADR-004 §5.4）。
 */
onShareAppMessage(() =>
  buildShareMessage({
    title: merchant.value?.name ?? "",
    path: `${ROUTES.store}?from=SHARE`,
    merchantNo: currentNo.value,
  }),
);
</script>

<template>
  <sh-scaffold v-if="merchant">
    <!-- 商家头部：谁（头像 · 自营 · 店名 · 认证）→ 能不能卖给我（范围 + 标签）→ 三个数 -->
    <view class="sh-card head">
      <view class="head__top sh-row">
        <biz-shop-avatar :name="merchant.name" :logo="merchant.logo" :self-operated="merchant.selfOperated" :size="128"></biz-shop-avatar>
        <view class="sh-fill head__who">
          <view class="head__title sh-row">
            <!-- 自营标（电商法 §37），放店名前：「谁在卖」先于店名 -->
            <text v-if="merchant.selfOperated" class="sh-chip sh-chip--primary tiny">{{ $t("merchant.selfOperated") }}</text>
            <text class="txt-title head__name">{{ merchant.name }}</text>
            <sh-icon v-if="merchant.verified" name="verified" :size="32" color="var(--sh-primary)"></sh-icon>
          </view>
          <text v-if="merchant.desc" class="txt-caption txt-quiet head__desc">{{ merchant.desc }}</text>
        </view>
      </view>

      <view class="tags sh-wrap">
        <!-- 经营范围排在自定义标签之前：它不是修饰词，是**这家店的货能不能卖给我** -->
        <text class="sh-chip sh-chip--primary">{{ $t(`serviceScope.${merchant.serviceScope}`) }}</text>
        <text v-for="tg in tags" :key="tg" class="sh-chip sh-chip--primary">{{ tg }}</text>
      </view>

      <view class="stats">
        <view v-for="st in stats" :key="st.k" class="sh-fill stat">
          <text class="txt-title stat__v sh-num">{{ st.v }}</text>
          <text class="txt-caption txt-quiet stat__k">{{ st.k }}</text>
        </view>
      </view>

      <!-- 分维度分只在真有人评过时出：零评价时后端给的是一排默认 5.0 -->
      <text v-if="merchant.ratingCount > 0" class="txt-caption txt-quiet head__dims sh-num">
        {{ $t("merchant.dim.goods") }} {{ merchant.scores.goods.toFixed(1) }} ·
        {{ $t("merchant.dim.service") }} {{ merchant.scores.service.toFixed(1) }} ·
        {{ $t("merchant.dim.speed") }} {{ merchant.scores.speed.toFixed(1) }}
      </text>
      <view v-if="merchant.address" class="fact sh-row sh-row--top">
        <sh-icon name="pin" :size="28" color="var(--sh-sub)"></sh-icon>
        <text class="txt-caption txt-quiet sh-fill">{{ merchant.address }}</text>
      </view>
    </view>

    <!-- 商品 / 评价：切换本身就是标题，收进块内 -->
    <view class="sh-block">
      <view class="sh-block__head sh-block__head--tabs">
        <sh-tabs
          :items="[
            {
              key: 'goods',
              label: String($t('merchant.goodsTab', { n: goods.length })),
            },
            {
              key: 'reviews',
              label: String($t('merchant.reviewTab', { n: reviews.length })),
            },
          ]"
          :active="tab"
          @change="(k: string) => (tab = k as typeof tab)"
        ></sh-tabs>
      </view>

      <!-- 两列网格：在一家店里逛，每张卡再写一遍店名是纯重复 -->
      <view v-if="tab === 'goods'" class="grid">
        <biz-goods-tile
          v-for="g in goods"
          :key="g.goodsNo"
          :goods="g"
          @add="add(g, $event)"
          @tap="openGoods(g)"
        ></biz-goods-tile>
      </view>

      <template v-else>
        <biz-review
          v-for="r in reviews"
          :key="r.reviewNo"
          :review="r"
          @like="like(r)"
        ></biz-review>
        <sh-empty
          bare
          v-if="!reviews.length" :pending="!loaded" :failed="failed" @retry='() => load(currentNo)'
          :text="$t('common.empty')"
        ></sh-empty>
      </template>
    </view>
    <!--
      悬浮购物车入口。**这三页此前加完购就没有下文** —— 不是 tab 页、没有操作条，
      屏幕上再没有任何东西提到购物车。它同时是飞入动效的落点（见组件注释）。
    -->
    <biz-cart-fab></biz-cart-fab>
  </sh-scaffold>
</template>

<style scoped>
.head__top {
  gap: 24rpx;
}
.head__who {
  min-width: 0;
}
.head__title {
  gap: 12rpx;
}
.head__name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.head__desc {
  display: block;
  margin-top: 8rpx;
}
.tiny {
  flex-shrink: 0;
  padding: 4rpx 16rpx;
}
.tags {
  margin-top: 24rpx;
}
/* 三个数：一道细线隔开，等分三栏 */
.stats {
  display: flex;
  margin-top: 24rpx;
  padding-top: 24rpx;
  border-top: 2rpx solid var(--sh-faint);
}
.stat {
  text-align: center;
}
.stat__v {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.stat__k {
  display: block;
  margin-top: 4rpx;
}
.head__dims {
  display: block;
  margin-top: 16rpx;
  text-align: center;
}
.fact {
  gap: 8rpx;
  margin-top: 20rpx;
}
/* 网格在白块里：两列，块本身的左右留白给网格 */
.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16rpx;
  padding: 0 24rpx;
}
</style>
