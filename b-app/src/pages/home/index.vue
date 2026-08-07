<script setup lang="ts">
// 工作台（B-10.1 + B-11 汇总）。
//
// 设计要点：**数字即入口**。商家早上打开 App 只想知道「有几件事要我做」，
// 不需要 Banner、不需要推荐。所以第一屏是待办数字网格，点数字直接进对应列表。
// 这与 C 端首页（逛）是相反的信息架构，不复用页面。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useI18n } from "vue-i18n";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import type { MerchantStats, MerchantTodo } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const todo = ref<MerchantTodo | null>(null);
const stats = ref<MerchantStats | null>(null);

/** 待办格子。数字为 0 的也留着 —— 位置固定，商家才能形成肌肉记忆 */
const cells = computed(() => {
  const t = todo.value;
  if (!t) return [];
  // 显式标注：否则 TS 会把 route 收窄成 base 里那几个字面量，splice 进来的核销/分拣路由报错
  const base: { key: string; n: number; route: string }[] = [
    { key: "toShip", n: t.toShip, route: ROUTES.orders },
    { key: "toDeliver", n: t.toDeliver, route: ROUTES.delivery },
    { key: "afterSale", n: t.afterSale, route: ROUTES.afterSale },
    { key: "toReply", n: t.toReply, route: ROUTES.reviews },
  ];
  // 不承接自提点的商家不该看到核销/分拣 —— 那是自提点承接方的活（ADR-005）
  if (merchant.isPickupPoint) {
    base.splice(2, 0, { key: "toVerify", n: t.toVerify, route: ROUTES.verify });
    base.splice(3, 0, { key: "toPick", n: t.toPick, route: ROUTES.picking });
  }
  return base;
});

const ownedRate = computed(() =>
  stats.value ? `${Math.round(stats.value.ownedTrafficRate * 100)}%` : "—",
);

async function load() {
  await merchant.loadProfile().catch(() => null);
  if (!merchant.isActive) return;
  [todo.value, stats.value] = await Promise.all([api.mTodo(), api.mStats()]);
}

function open(route: string) {
  if (!route) {
    // 未交付的格子给明确说法，不做静默无响应 —— 点了没反应会被当成 bug
    uni.showToast({ title: t("home.laterBatch"), icon: "none" });
    return;
  }
  // tabBar 页只能 switchTab，普通页只能 navigateTo，用错会静默失败
  if (route === ROUTES.orders) uni.switchTab({ url: route });
  else uni.navigateTo({ url: route });
}

function goApply() {
  uni.navigateTo({ url: merchant.isLogin ? ROUTES.apply : ROUTES.login });
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="tab.home" tab="home">
    <!-- 未入驻：整屏只讲一件事 —— 去开张 -->
    <view v-if="!merchant.isActive" class="empty">
      <text class="sh-h1">{{ $t("home.notMerchant") }}</text>
      <text class="sh-muted mt">{{ $t("home.notMerchantHint") }}</text>
      <view class="sh-btn go" @tap="goApply">{{ $t("home.goApply") }}</view>
    </view>

    <template v-else>
      <text class="sh-h1">{{ $t("home.greeting") }}</text>

      <view class="grid">
        <view v-for="c in cells" :key="c.key" class="grid__cell" @tap="open(c.route)">
          <text class="grid__n sh-num" :class="{ 'is-zero': !c.n }">{{ c.n }}</text>
          <text class="grid__label">{{ $t(`home.${c.key}`) }}</text>
        </view>
      </view>

      <view v-if="stats" class="sh-card stats">
        <text class="sh-h2">{{ $t("home.today") }}</text>
        <view class="stats__row">
          <view class="stats__item">
            <text class="stats__v sh-num">{{ stats.todayOrders }}</text>
            <text class="sh-muted">{{ $t("home.orders") }}</text>
          </view>
          <view class="stats__item">
            <text class="stats__v sh-num">{{ money(stats.todayGmvMinor, stats.currency) }}</text>
            <text class="sh-muted">{{ $t("home.gmv") }}</text>
          </view>
          <view class="stats__item">
            <text class="stats__v sh-num">{{ stats.rating || "—" }}</text>
            <text class="sh-muted">{{ $t("home.rating") }}</text>
          </view>
        </view>
      </view>

      <!-- 自带客流占比：这是商家最该关心的数字，它直接决定费率档（ADR-004 §6） -->
      <view v-if="stats" class="sh-card owned">
        <view class="owned__row">
          <text class="sh-h2">{{ $t("home.ownedTraffic") }}</text>
          <text class="owned__v sh-num">{{ ownedRate }}</text>
        </view>
        <text class="sh-muted">{{ $t("home.ownedTrafficHint") }}</text>
      </view>

      <view
        v-if="merchant.isPickupPoint"
        class="sh-card entry"
        @tap="open(ROUTES.verify)"
      >
        <text class="sh-h2">{{ $t("home.fulfillEntry") }}</text>
        <text class="sh-muted">{{ $t("home.fulfillEntryHint") }}</text>
      </view>

      <view class="sh-card entry" @tap="open(ROUTES.store)">
        <text class="sh-h2">{{ $t("home.storeEntry") }}</text>
        <text class="sh-muted">{{ $t("home.storeEntryHint") }}</text>
      </view>

      <view class="sh-card entry" @tap="open(ROUTES.marketing)">
        <text class="sh-h2">{{ $t("home.marketingEntry") }}</text>
        <text class="sh-muted">{{ $t("home.marketingEntryHint") }}</text>
      </view>

      <view class="sh-card entry" @tap="open(ROUTES.groups)">
        <text class="sh-h2">{{ $t("home.groupEntry") }}</text>
        <text class="sh-muted">{{ $t("home.groupEntryHint") }}</text>
      </view>

      <view class="sh-card entry" @tap="open(ROUTES.quotes)">
        <text class="sh-h2">{{ $t("home.quoteEntry") }}</text>
        <text class="sh-muted">{{ $t("home.quoteEntryHint") }}</text>
      </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.mt {
  display: block;
  margin-top: 16rpx;
}
.go {
  margin-top: 48rpx;
}
.grid {
  display: flex;
  flex-wrap: wrap;
  gap: 20rpx;
  margin: 28rpx 0;
}
.grid__cell {
  flex: 1 1 calc(33.33% - 14rpx);
  min-width: calc(33.33% - 14rpx);
  background: var(--sh-surface);
  border-radius: 32rpx;
  padding: 28rpx 20rpx;
  text-align: center;
}
.grid__n {
  display: block;
  font-size: 48rpx;
  font-weight: 600;
  color: var(--sh-primary);
  line-height: 1.2;
}
.grid__n.is-zero {
  color: var(--sh-faint);
}
.grid__label {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.stats {
  margin-bottom: 24rpx;
}
.stats__row {
  display: flex;
  margin-top: 24rpx;
}
.stats__item {
  flex: 1;
  text-align: center;
}
.stats__v {
  display: block;
  font-size: 34rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.owned {
  margin-bottom: 24rpx;
  background: var(--sh-primary-tint);
}
.owned__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 12rpx;
}
.owned__v {
  font-size: 40rpx;
  font-weight: 600;
  color: var(--sh-primary);
}
.entry {
  margin-bottom: 24rpx;
}
.entry .sh-muted {
  display: block;
  margin-top: 8rpx;
}
/* 未入驻的整屏空态：它带标题与主按钮，不是通用空态那一行灰字，所以留在页面里 */
.empty {
  text-align: center;
  padding: 120rpx 40rpx;
}
</style>
