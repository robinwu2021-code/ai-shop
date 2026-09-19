<script setup lang="ts">
/*
 * 营销入口（原型 s01 · TDD-营销域-详细设计 §3.2）。
 *
 * 顶部两个数回答「营销花了多少、带来多少」；下面每一行是一个去处，右侧一句现状。
 * **只有需要处理的用黄标**（集单待处理、团差人、求团待报价）—— 其余是数字，
 * 满屏黄标等于没有黄标。
 *
 * 集单排在团前：它每天一期、每天要看；团是偶尔做一次的拉新（ADR-024）。
 * 数字全部现算（`/biz/marketing/summary`），不存计数。
 */
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import type { MarketingSummary } from "@shared/types";

const merchant = useMerchantStore();
const sum = ref<MarketingSummary | null>(null);
const failed = ref(false);

async function load() {
  try {
    sum.value = await api.mMarketingSummary();
    failed.value = false;
  } catch {
    failed.value = true;
  }
}

function hhmm(ms: number): string {
  const d = new Date(ms);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

function go(url: string) {
  uni.navigateTo({ url });
}

onShow(async () => {
  // 判权要先有门店与角色：冷启动直接进这一页时它们还没到
  await merchant.ensureStores().catch(() => null);
  if (merchant.can("biz:campaign")) void load();
});
</script>

<template>
  <sh-scaffold title-key="marketing.title" :denied="!merchant.can('biz:campaign')" :failed="failed" @retry="load">
    <view v-if="sum" class="sh-card stats">
      <sh-stat :items="[
        { value: money(sum.monthDiscountMinor), label: String($t('marketing.monthDiscount')) },
        { value: sum.monthOrders, label: String($t('marketing.monthOrders')) },
      ]"></sh-stat>
    </view>

    <view class="sh-cells">
      <view class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.activities)">
        <text class="txt-body">{{ $t("marketing.activities") }}</text>
        <view class="sh-row">
          <text v-if="sum" class="sh-muted sh-num">{{ $t("marketing.running", { n: sum.activityRunning }) }}</text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.coupons)">
        <text class="txt-body">{{ $t("marketing.coupons") }}</text>
        <view class="sh-row">
          <text v-if="sum" class="sh-muted sh-num">{{ $t("marketing.issuing", { n: sum.couponIssuing }) }}</text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.periods)">
        <text class="txt-body">{{ $t("marketing.periods") }}</text>
        <view class="sh-row">
          <text v-if="sum && sum.periodsShort" class="sh-chip sh-chip--warning">
            {{ $t("marketing.periodsShort", { n: sum.periodsShort }) }}
          </text>
          <text v-else-if="sum && sum.periodTodayCutoffAt" class="sh-muted sh-num">
            {{ $t("marketing.periodToday", { n: sum.periodTodayQty, t: hhmm(sum.periodTodayCutoffAt) }) }}
          </text>
          <text v-else-if="sum" class="sh-muted">{{ $t("marketing.periodNone") }}</text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.groups)">
        <text class="txt-body">{{ $t("marketing.groups") }}</text>
        <view class="sh-row">
          <text v-if="sum && sum.groupsShort" class="sh-chip sh-chip--warning">
            {{ $t("marketing.groupsShort", { n: sum.groupsShort }) }}
          </text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>
      <!-- 发出去的（原型 s01 加的一行 → m19）：消息与券的效果回看 -->
      <view class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.reachTasks)">
        <text class="txt-body">{{ $t("marketing.reachTasks") }}</text>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
    </view>

    <view class="sh-cells">
      <view class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.platformActivities)">
        <text class="txt-body">{{ $t("marketing.platform") }}</text>
        <view class="sh-row">
          <text v-if="sum && sum.enrollable" class="txt-body sh-muted sh-num">
            {{ $t("marketing.enrollable", { n: sum.enrollable }) }}
          </text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>
      <view class="sh-cell sh-row sh-row--between" @tap="go(ROUTES.quotes)">
        <text class="txt-body">{{ $t("marketing.quotes") }}</text>
        <view class="sh-row">
          <text v-if="sum && sum.quotesPending" class="sh-chip sh-chip--warning">
            {{ $t("marketing.quotesPending", { n: sum.quotesPending }) }}
          </text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.stats {
  padding-top: 8rpx;
  padding-bottom: 8rpx;
}
</style>
