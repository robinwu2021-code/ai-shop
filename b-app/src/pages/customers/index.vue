<script setup lang="ts">
import { useMerchantStore } from "@/stores/merchant";

const merchant = useMerchantStore();
// 我的客户（B-11.2.8）。
//
// 平台电商给商家看的是「流量、转化率、UV」；小店老板要的是另一种东西：
// **张阿姨上个月每周都来，这半个月没来了**。
// 所以这页只回答两个问题：谁在买、谁不来了。没有图表，没有漏斗。
//
// 沉默客户排在最前 —— 那是店主唯一能立刻行动的信号（给他发条消息、留一份货）。
// 埋在列表底部等于没有。
//
// ⚠️ 隐私：只给脱敏昵称，不给完整手机号（B12）。店主想联系走平台的消息通道，
// 把号码直接摆出来，第二天就会有人拿去做别的事。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { money } from "@shared/utils/money";
import type { MerchantCustomer } from "@shared/types";

const list = ref<MerchantCustomer[]>([]);
const tab = ref<"all" | "silent" | "owned">("all");

const shown = computed(() => {
  if (tab.value === "silent") return list.value.filter((c) => c.silent);
  if (tab.value === "owned") return list.value.filter((c) => c.source === "MERCHANT_OWNED");
  return list.value;
});

const silentCount = computed(() => list.value.filter((c) => c.silent).length);
const ownedCount = computed(
  () => list.value.filter((c) => c.source === "MERCHANT_OWNED").length,
);
/** 复购率 = 买过两次以上的人占比。一次性客人多说明留不住 */
const repeatRate = computed(() => {
  if (!list.value.length) return 0;
  return Math.round(
    (list.value.filter((c) => c.orderCount >= 2).length / list.value.length) * 100,
  );
});

async function load() {
  list.value = await api.mCustomers();
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="customers.title" :denied="!merchant.can('biz:customer')">
    <text class="sh-h1">{{ $t("customers.title") }}</text>

    <view class="sh-card sum">
      <view class="sum__i">
        <text class="sum__v sh-num">{{ list.length }}</text>
        <text class="sh-muted">{{ $t("customers.total") }}</text>
      </view>
      <view class="sum__i">
        <text class="sum__v sh-num">{{ repeatRate }}%</text>
        <text class="sh-muted">{{ $t("customers.repeatRate") }}</text>
      </view>
      <view class="sum__i">
        <text class="sum__v sh-num" :class="{ 'is-warn': silentCount > 0 }">
          {{ silentCount }}
        </text>
        <text class="sh-muted">{{ $t("customers.silent") }}</text>
      </view>
    </view>

    <view class="tabs">
      <text class="sh-chip" :class="{ 'sh-chip--primary': tab === 'all' }" @tap="tab = 'all'">
        {{ $t("common.all") }}
      </text>
      <text
        class="sh-chip"
        :class="{ 'sh-chip--warning': tab === 'silent' }"
        @tap="tab = 'silent'"
      >
        {{ $t("customers.silent") }} {{ silentCount }}
      </text>
      <text
        class="sh-chip"
        :class="{ 'sh-chip--primary': tab === 'owned' }"
        @tap="tab = 'owned'"
      >
        {{ $t("customers.owned") }} {{ ownedCount }}
      </text>
    </view>

    <sh-empty v-if="!shown.length" :text='$t("customers.empty")'></sh-empty>

    <view v-for="c in shown" :key="c.nickname" class="sh-card row">
      <text class="row__avatar">{{ c.avatar }}</text>
      <view class="row__main">
        <view class="row__head">
          <text class="row__name">{{ c.nickname }}</text>
          <text v-if="c.silent" class="sh-chip sh-chip--warning">
            {{ $t("customers.silentTag", { n: c.daysSinceLast }) }}
          </text>
          <text v-else-if="c.source === 'MERCHANT_OWNED'" class="sh-chip sh-chip--primary">
            {{ $t("customers.ownedTag") }}
          </text>
        </view>
        <text class="sh-muted sh-num">
          {{ $t("customers.stat", { n: c.orderCount, m: money(c.totalSpentMinor) }) }}
        </text>
      </view>
      <text class="row__days sh-num">
        {{ c.daysSinceLast === 0 ? $t("customers.today") : $t("customers.daysAgo", { n: c.daysSinceLast }) }}
      </text>
    </view>

    <text class="tip">{{ $t("customers.privacyHint") }}</text>
    <text class="tip">{{ $t("customers.silentHint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.sum {
  display: flex;
  margin-top: 24rpx;
}
.sum__i {
  flex: 1;
  text-align: center;
}
.sum__v {
  display: block;
  font-size: 40rpx;
  font-weight: 600;
  color: var(--sh-ink);
  line-height: 1.2;
}
.sum__v.is-warn {
  color: var(--sh-warning);
}
.tabs {
  display: flex;
  gap: 12rpx;
  margin: 28rpx 0 20rpx;
}
.tabs .sh-chip {
  font-size: 24rpx;
  padding: 14rpx 24rpx;
}
.row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  margin-bottom: 16rpx;
}
.row__avatar {
  width: 84rpx;
  height: 84rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  font-size: 46rpx;
  text-align: center;
  line-height: 84rpx;
}
.row__main {
  flex: 1;
  min-width: 0;
}
.row__head {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.row__name {
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.row__days {
  font-size: 24rpx;
  color: var(--sh-sub);
}
.tip {
  display: block;
  margin: 20rpx 8rpx 0;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
</style>
