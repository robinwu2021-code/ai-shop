<script setup lang="ts">
// 两列网格里的商品格：图 · 名字 · 价格 + ＋。
//
// 给**店铺详情**用：在一家店里逛，每张卡都写一遍这家店的名字是纯重复 ——
// 横排商品卡（biz-goods-card）的落款行、副标题、销量在这里全是噪音，
// 两列网格一屏能看到的货多一倍。售罄规则与商品卡同一条（goodsSoldOut）。
import { computed } from "vue";
import { GOODS_COVER_FALLBACK } from "@shared/utils/constants";
import { goodsSoldOut } from "@shared/utils/goods";
import { money } from "@shared/utils/format";
import type { Goods } from "@shared/types";

const props = defineProps<{ goods: Goods }>();
// add 透传原始 tap 事件：「飞入购物车」要它的落点坐标（同商品卡）
defineEmits<{ (e: "add", ev: unknown): void; (e: "tap"): void }>();

const soldOut = computed(() => goodsSoldOut(props.goods));
</script>

<template>
  <view class="tile" @tap.stop="$emit('tap')">
    <sh-cover class="sh-center tile__cover" :src="goods.cover || GOODS_COVER_FALLBACK"></sh-cover>
    <view class="tile__body">
      <text class="txt-strong tile__title">{{ goods.title }}</text>
      <view class="sh-row tile__foot">
        <view class="sh-fill sh-row tile__price">
          <text class="txt-price price__now sh-num">{{ money(goods.price) }}</text>
          <text v-if="goods.originPrice && goods.originPrice > goods.price" class="sh-was sh-num">{{ money(goods.originPrice) }}</text>
        </view>
        <text v-if="soldOut" class="sh-chip">{{ $t("goods.soldOut") }}</text>
        <view v-else class="sh-center add sh-hit" @tap.stop="$emit('add', $event)">
          <text class="add__sign">＋</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.tile {
  overflow: hidden;
  border-radius: 24rpx;
}
/* 方图：宽由网格给，高取同值 —— 两列等宽，所以每格都是正方形 */
.tile__cover {
  display: flex;
  width: 100%;
  height: 320rpx;
  font-size: 160rpx;
}
.tile__body {
  padding: 16rpx 20rpx 20rpx;
}
.tile__title {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  height: 80rpx;
  overflow: hidden;
  color: var(--sh-ink);
}
.tile__foot {
  gap: 8rpx;
  margin-top: 12rpx;
}
.tile__price {
  gap: 8rpx;
  min-width: 0;
  overflow: hidden;
}
.price__now {
  color: var(--sh-ink);
  flex-shrink: 0;
}
.add {
  width: 56rpx;
  height: 56rpx;
  border-radius: 9999px;
  background: var(--sh-primary-tint);
  flex-shrink: 0;
  /* 靠右。只靠价格那格的 sh-fill 撑不够：真机上「＋」贴在价格后面（2026-09-19） */
  margin-inline-start: auto;
}
.add__sign {
  color: var(--sh-primary-text);
  font-size: 32rpx;
}
</style>
