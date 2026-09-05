<script setup lang="ts">
// 商家详情：资质与评分 → 在售商品 → 全部评价。
// 一期平台方是唯一入驻方，页面照样按「多商家」写 —— 二期开放入驻只是数据变多。
import { ref } from "vue";
import { onLoad, onShareAppMessage } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useCartStore } from "@/stores/cart";
import { ROUTES, MERCHANT_LOGO_FALLBACK } from "@shared/utils/constants";
import { isoDate } from "@shared/utils/format";
import { firstBuyableSku } from "@shared/utils/goods";
import { flyToCart, tapPoint } from "@/shared/fly";
import { buildShareMessage } from "@shared/ports/share";
import type { Goods, Merchant, Review } from "@shared/types";

const cart = useCartStore();
const merchant = ref<Merchant | null>(null);
const goods = ref<Goods[]>([]);
const reviews = ref<Review[]>([]);
const tab = ref<"goods" | "reviews">("goods");

async function load(merchantNo: string) {
  const [m, g, r] = await Promise.all([
    api.merchantDetail(merchantNo),
    api.goodsList({ merchantNo, size: 50 }),
    api.reviewList({ merchantNo }),
  ]);
  merchant.value = m;
  goods.value = g.records;
  reviews.value = r;
  uni.setNavigationBarTitle({ title: m.name });
}

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
    <!-- 商家头部 -->
    <view class="sh-card head">
      <view class="head__top sh-row">
        <text class="head__logo">{{ merchant.logo || MERCHANT_LOGO_FALLBACK }}</text>
        <view class="sh-fill">
          <view class="head__title sh-row">
            <text class="txt-title">{{ merchant.name }}</text>
            <text
              v-if="merchant.verified"
              class="txt-caption sh-chip sh-chip--primary tiny"
            >
              {{ $t("merchant.verified") }}
            </text>
          </view>
          <text class="txt-caption sh-chip tiny">{{
            $t(`merchant.type.${merchant.type}`)
          }}</text>
        </view>
      </view>

      <text class="txt-sub head__desc">{{ merchant.desc }}</text>

      <view class="tags sh-wrap">
        <!-- 经营范围排在自定义标签之前：它不是修饰词，是**这家店的货能不能卖给我**，
             和「已认证」一样属于下单前必须先看到的事实 -->
        <text class="sh-chip sh-chip--primary">
          {{ $t(`serviceScope.${merchant.serviceScope}`) }}
        </text>
        <text v-for="tg in merchant.tags" :key="tg" class="sh-chip">{{
          tg
        }}</text>
      </view>

      <!-- 评分区：总分 + 分维度 + 依据 -->
      <view class="score sh-row">
        <view class="score__main">
          <!--
            **零评价时不给分数也不给星。** 后端对没人评过的商家回 5.0 ——
            那是默认值，不是「大家都给了满分」。下面「基于 0 条评价」那句
            虽然自证了，但先看到的是大大的 5.0，人不会往下读。
          -->
          <text v-if="merchant.ratingCount > 0" class="txt-hero score__num sh-num">{{
            merchant.rating.toFixed(1)
          }}</text>
          <text v-else class="txt-hero txt-body score__num txt-quiet">{{ $t("merchant.noRating") }}</text>
          <sh-rating
            v-if="merchant.ratingCount > 0"
            :value="merchant.rating"
            :size="24"
            :show-value="false"
          ></sh-rating>
          <text class="txt-caption score__basis sh-num">
            {{
              $t("merchant.basis", {
                r: merchant.ratingCount,
                s: merchant.salesCount,
              })
            }}
          </text>
        </view>
        <view class="score__dims">
          <view class="dim">
            <text class="txt-body dim__v sh-num">{{
              merchant.scores.goods.toFixed(1)
            }}</text>
            <text class="txt-caption dim__k">{{ $t("merchant.dim.goods") }}</text>
          </view>
          <view class="dim">
            <text class="txt-body dim__v sh-num">{{
              merchant.scores.service.toFixed(1)
            }}</text>
            <text class="txt-caption dim__k">{{ $t("merchant.dim.service") }}</text>
          </view>
          <view class="dim">
            <text class="txt-body dim__v sh-num">{{
              merchant.scores.speed.toFixed(1)
            }}</text>
            <text class="txt-caption dim__k">{{ $t("merchant.dim.speed") }}</text>
          </view>
        </view>
      </view>

      <view class="facts">
        <view v-if="merchant.address" class="fact sh-row sh-row--between sh-row--top">
          <text class="txt-caption fact__k">{{ $t("merchant.address") }}</text>
          <text class="txt-caption fact__v">{{ merchant.address }}</text>
        </view>
        <view v-if="merchant.openHours" class="fact sh-row sh-row--between sh-row--top">
          <text class="txt-caption fact__k">{{ $t("merchant.hours") }}</text>
          <text class="txt-caption fact__v sh-num">{{ merchant.openHours }}</text>
        </view>
        <view class="fact sh-row sh-row--between sh-row--top">
          <text class="txt-caption fact__k">{{ $t("merchant.joined") }}</text>
          <text class="txt-caption fact__v sh-num">{{ isoDate(merchant.joinedAt) }}</text>
        </view>
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

      <template v-if="tab === 'goods'">
        <biz-goods-card
          v-for="g in goods"
          :key="g.goodsNo"
          :goods="g"
          @add="add(g, $event)"
          @tap="openGoods(g)"
        ></biz-goods-card>
      </template>

      <template v-else>
        <biz-review
          v-for="r in reviews"
          :key="r.reviewNo"
          :review="r"
          @like="like(r)"
        ></biz-review>
        <sh-empty
          bare
          v-if="!reviews.length"
          :text="$t('common.empty')"
        ></sh-empty>
      </template>
    </view>
  </sh-scaffold>
</template>

<style scoped>

.head__top {
  gap: 24rpx;
}
.head__logo {
  width: 108rpx;
  height: 108rpx;
  border-radius: 32rpx;
  background: var(--sh-faint);
  text-align: center;
  line-height: 108rpx;
  font-size: 52rpx;
  flex-shrink: 0;
}

.head__title {
  gap: 12rpx;
  margin-bottom: 8rpx;
}
.tiny {
  padding: 4rpx 14rpx;
}
.head__desc {
  display: block;
  margin-top: 24rpx;
}
.tags {
  margin-top: 20rpx;
}
.score {
  gap: 24rpx;
  margin-top: 28rpx;
  background: var(--sh-faint);
  border-radius: 32rpx;
  padding: 28rpx;
}
.score__main {
  flex: 0 0 auto;
}
.score__num {
  display: block;
}
.score__basis {
  display: block;
  margin-top: 8rpx;
}
.score__dims {
  flex: 1;
  display: flex;
  justify-content: space-around;
}
.dim {
  text-align: center;
}
.dim__v {
  display: block;
}
.dim__k {
  display: block;
  margin-top: 4rpx;
}
.facts {
  margin-top: 24rpx;
}
.fact {
  gap: 32rpx;
  padding: 12rpx 0;
}
.fact__k {
  flex-shrink: 0;
}
.fact__v {
  color: var(--sh-ink);
  text-align: end;
}
</style>
