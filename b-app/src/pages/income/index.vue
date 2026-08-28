<script setup lang="ts">
// 我的收入（B-11.9）。
//
// **四个数是四种状态，不是四个口袋** —— 它们加起来等于全部结算单。
//
// 在这一页之前，结算页只显示一个「商家实得」，读起来像已到手 ——
// 商家拿它去对银行流水，对不上就来找客服，而客服看到的状态也只有一个词。
// 更糟的是那个词曾经是「已分账」，而底下调的是桩实现：一分钱都没有真的动过。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import { datetime } from "@shared/utils/datetime";
import type { IncomeSummary } from "@shared/types";

const merchant = useMerchantStore();
const canView = computed(() => merchant.can("biz:finance"));

const sum = ref<IncomeSummary | null>(null);
const allStores = ref(false);

/**
 * 在途卡了多久。**只给金额的话商家看不出是一笔大的还是很多笔**，
 * 而「卡了多久」才是他真正想问的 —— 客服也是。
 */
const stuckDays = computed(() => {
  const at = sum.value?.oldestInFlightAt;
  if (!at) return 0;
  return Math.floor((Date.now() - at) / 86_400_000);
});

async function load() {
  sum.value = await api.mIncomeSummary(allStores.value);
}

function toggleScope() {
  allStores.value = !allStores.value;
  void load();
}

onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold title-key="income.title" :denied="!canView">
    <template v-if="sum">
      <view class="txt-sub scope" @tap="toggleScope">
        {{ allStores ? $t("income.scopeAll") : $t("income.scopeCurrent") }}
      </view>

      <view class="sh-card">
        <text class="sh-muted">{{ $t("income.received") }}</text>
        <text class="txt-mega amt sh-num">{{ money(sum.receivedMinor) }}</text>
        <text class="txt-caption sub sh-muted">{{ $t("income.receivedHint") }}</text>
      </view>

      <!--
        在途这一档是本批新拆出来的。**此前它混在「已到账」里** ——
        而底下是桩实现，那些钱一分都没动过，商家却以为收到了。
      -->
      <view v-if="sum.inFlightMinor > 0" class="sh-card sh-mt-sm hold">
        <view class="line sh-row sh-row--between sh-row--baseline">
          <text class="sh-muted">{{ $t("income.inFlight") }}</text>
          <text class="txt-price sh-num">{{ money(sum.inFlightMinor) }}</text>
        </view>
        <text class="txt-caption sub sh-muted">
          {{ $t("income.inFlightHint", { n: sum.inFlightCount }) }}
          <text v-if="stuckDays > 0">　{{ $t("income.stuckDays", { d: stuckDays }) }}</text>
        </text>
        <text v-if="sum.oldestInFlightAt" class="txt-caption sub sh-muted sh-num">
          {{ datetime(sum.oldestInFlightAt) }}
        </text>
      </view>

      <view class="sh-card sh-mt-sm">
        <view class="line sh-row sh-row--between sh-row--baseline">
          <text class="sh-muted">{{ $t("income.pending") }}</text>
          <text class="txt-price sh-num">{{ money(sum.pendingMinor) }}</text>
        </view>
        <text class="txt-caption sub sh-muted">{{ $t("income.pendingHint") }}</text>
      </view>

      <!--
        当面收款：**这部分他早就拿到了**。
        不显示的话，他会以为平台还欠着这笔；混进「待结算」更糟。
      -->
      <view v-if="sum.offlineMinor > 0" class="sh-card sh-mt-sm">
        <view class="line sh-row sh-row--between sh-row--baseline">
          <text class="sh-muted">{{ $t("income.offline") }}</text>
          <text class="txt-price sh-num">{{ money(sum.offlineMinor) }}</text>
        </view>
        <text class="txt-caption sub sh-muted">{{ $t("income.offlineHint") }}</text>
      </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.scope {
  margin-bottom: 12rpx;
  color: var(--sh-primary-text);
}
.amt {
  display: block;
  margin-top: 8rpx;
}
/* 同一页上比 .amt 小一档的金额（在途/待结/线下），名字要说清它是钱 */

.sub {
  display: block;
  margin-top: 8rpx;
}
/* 在途那一档用暖色底：它是「要留意」而不是「有问题」 */
.hold { background: var(--sh-faint); }
</style>
