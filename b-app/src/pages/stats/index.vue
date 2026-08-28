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
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import type { MerchantStats } from "@shared/types";

const merchant = useMerchantStore();
const stats = ref<MerchantStats | null>(null);

const ownedPct = computed(() =>
  stats.value ? Math.round(stats.value.ownedTrafficRate * 100) : 0,
);

async function load() {
  stats.value = await api.mStats();
  // 门店数决定要不要给跨店入口 —— 深链进来时 stores 还是空的
  await merchant.ensureStores().catch(() => null);
}

/**
 * 跨店对比。**入口在这里而不是门店管理**：那一页答的是「此刻切到哪家、这家怎么管」，
 * 卡上给的是今天的数；「近 30 天哪家更好」与本店这几组数字是同一类问题，
 * 摆在一起才有可比性。两处都放的话，商家会在同一屏里读到今天和近 30 天两个数，
 * 而它们看起来互相矛盾。
 */
function goCompare() {
  uni.navigateTo({ url: ROUTES.crossStore });
}

onShow(load);
</script>

<template>
  <!-- 经营数据属于客户资产（`biz:customer`）；「我的」页的入口已判过，这里给深链兜底 -->
  <sh-scaffold title-key="stats.title" :denied="!merchant.can('biz:customer')">
    <template v-if="stats">
      <view class="sh-card block">
        <text class="txt-title">{{ $t("stats.today") }}</text>
        <view class="pair">
          <view class="pair__i">
            <text class="txt-display pair__v sh-num">{{ stats.todayOrders }}</text>
            <text class="sh-muted">{{ $t("stats.orders") }}</text>
          </view>
          <view class="pair__i">
            <text class="txt-display pair__v sh-num">{{ money(stats.todayGmvMinor, stats.currency) }}</text>
            <text class="sh-muted">{{ $t("stats.gmv") }}</text>
          </view>
        </view>
      </view>

      <view class="sh-card block">
        <text class="txt-title">{{ $t("stats.month") }}</text>
        <view class="pair">
          <view class="pair__i">
            <text class="txt-display pair__v sh-num">{{ stats.monthOrders }}</text>
            <text class="sh-muted">{{ $t("stats.orders") }}</text>
          </view>
          <view class="pair__i">
            <text class="txt-display pair__v sh-num">{{ money(stats.monthGmvMinor, stats.currency) }}</text>
            <text class="sh-muted">{{ $t("stats.gmv") }}</text>
          </view>
        </view>
      </view>

      <!-- 自带客流：这个平台特有的经营指标，直接对应费率 -->
      <view class="sh-card owned">
        <view class="owned__row sh-row sh-row--between sh-row--baseline">
          <text class="txt-title">{{ $t("stats.ownedTraffic") }}</text>
          <text class="txt-hero owned__v sh-num txt-primary">{{ ownedPct }}%</text>
        </view>
        <view class="bar">
          <view class="bar__fill" :style="{ width: `${ownedPct}%` }"></view>
        </view>
        <text class="sh-muted hint">{{ $t("stats.ownedHint") }}</text>
      </view>

      <view class="sh-card block">
        <view class="rate sh-row sh-row--between">
          <text class="txt-title">{{ $t("stats.rating") }}</text>
          <!-- 零评价时不画星：给商家看一个凭空的 5.0，他会以为真有人评过 -->
          <sh-rating v-if="stats.ratingCount > 0" :value="stats.rating"></sh-rating>
        </view>
        <text class="sh-muted">{{ $t("stats.ratingBasis", { n: stats.ratingCount }) }}</text>
      </view>
    </template>
    <!-- 多店才有「比」这回事。一家店时这一行是纯噪音 -->
    <view v-if="merchant.multiStore" class="sh-card cmp sh-row sh-row--between" @tap="goCompare">
      <text class="txt-title">{{ $t("stats.compareEntry") }}</text>
      <sh-icon name="chevronRight" :size="18" color="var(--sh-sub)"></sh-icon>
    </view>
  </sh-scaffold>
</template>

<style scoped>
/* 跨店入口：与上面几张数据卡同宽同缘，排在最后 —— 先看本店，再想到比 */

.pair {
  display: flex;
  margin-top: 16rpx;
}
.pair__i {
  flex: 1;
}
.pair__v {
  display: block;
}
.owned {
  background: var(--sh-primary-tint);
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
}
.rate {
  margin-bottom: 12rpx;
}
</style>
