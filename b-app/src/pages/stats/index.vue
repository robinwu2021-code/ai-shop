<script setup lang="ts">
// 经营数据（B-11.11）。
//
// 只放店主真的会看的四组数：今日、本月、评分、**自带客流占比**。
// 最后这个是这个平台特有的、也是店主最该盯的数字 —— 它直接决定费率档（ADR-004 §6）：
// 自己带来的客人，平台不抽（或少抽）；平台分配的客人才计佣金。
//
// 不做图表：一个小店一天几十单，折线图上就是一条抖动的线，读不出任何东西。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import type { MerchantStats } from "@shared/types";

const merchant = useMerchantStore();
const stats = ref<MerchantStats | null>(null);

const ownedPct = computed(() =>
  stats.value ? Math.round(stats.value.ownedTrafficRate * 100) : 0,
);

async function load() {
  stats.value = await api.mStats();
}

onShow(load);
</script>

<template>
  <!-- 经营数据属于客户资产（`biz:customer`）；「我的」页的入口已判过，这里给深链兜底 -->
  <sh-scaffold title-key="stats.title" :denied="!merchant.can('biz:customer')">
    <text class="sh-h1">{{ $t("stats.title") }}</text>

    <template v-if="stats">
      <view class="sh-card block">
        <text class="sh-h2">{{ $t("stats.today") }}</text>
        <view class="pair">
          <view class="pair__i">
            <text class="pair__v sh-num">{{ stats.todayOrders }}</text>
            <text class="sh-muted">{{ $t("stats.orders") }}</text>
          </view>
          <view class="pair__i">
            <text class="pair__v sh-num">{{ money(stats.todayGmvMinor, stats.currency) }}</text>
            <text class="sh-muted">{{ $t("stats.gmv") }}</text>
          </view>
        </view>
      </view>

      <view class="sh-card block">
        <text class="sh-h2">{{ $t("stats.month") }}</text>
        <view class="pair">
          <view class="pair__i">
            <text class="pair__v sh-num">{{ stats.monthOrders }}</text>
            <text class="sh-muted">{{ $t("stats.orders") }}</text>
          </view>
          <view class="pair__i">
            <text class="pair__v sh-num">{{ money(stats.monthGmvMinor, stats.currency) }}</text>
            <text class="sh-muted">{{ $t("stats.gmv") }}</text>
          </view>
        </view>
      </view>

      <!-- 自带客流：这个平台特有的经营指标，直接对应费率 -->
      <view class="sh-card owned">
        <view class="owned__row">
          <text class="sh-h2">{{ $t("stats.ownedTraffic") }}</text>
          <text class="owned__v sh-num">{{ ownedPct }}%</text>
        </view>
        <view class="bar">
          <view class="bar__fill" :style="{ width: `${ownedPct}%` }"></view>
        </view>
        <text class="sh-muted hint">{{ $t("stats.ownedHint") }}</text>
      </view>

      <view class="sh-card block">
        <view class="rate">
          <text class="sh-h2">{{ $t("stats.rating") }}</text>
          <sh-rating :value="stats.rating"></sh-rating>
        </view>
        <text class="sh-muted">{{ $t("stats.ratingBasis", { n: stats.ratingCount }) }}</text>
      </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.block {
  margin-top: 24rpx;
}
.pair {
  display: flex;
  margin-top: 24rpx;
}
.pair__i {
  flex: 1;
}
.pair__v {
  display: block;
  font-size: 40rpx;
  font-weight: 600;
  color: var(--sh-ink);
  line-height: 1.2;
}
.owned {
  margin-top: 24rpx;
  background: var(--sh-primary-tint);
}
.owned__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.owned__v {
  font-size: 48rpx;
  font-weight: 600;
  color: var(--sh-primary);
}
.bar {
  height: 16rpx;
  border-radius: 9999px;
  background: var(--sh-surface);
  margin: 20rpx 0 16rpx;
  overflow: hidden;
}
.bar__fill {
  height: 100%;
  border-radius: 9999px;
  background: var(--sh-primary);
  transition: width 0.3s ease;
}
.hint {
  display: block;
  line-height: 1.6;
}
.rate {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12rpx;
}
</style>
