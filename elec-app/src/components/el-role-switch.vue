<script setup lang="ts">
// 买家 / 供应商切换条。买家首页与供应商工作台顶上各一条，长得一样、位置一样 ——
// 他要知道「这两页是同一个东西的两面」，而不是两个不相干的功能。
// 红点来自 GET /elec/me：买家那边是有新报价的询价单数，供应商那边是待回的求购 + 快到期的库存。
import { computed } from "vue";
import type { ElecMe } from "@shared/types";
import { switchRole, type ElecRole } from "@/shared/role";

const props = defineProps<{ active: ElecRole; me: ElecMe | null }>();

const isSupplier = computed(() => !!props.me?.supplier);
const buyerDot = computed(() => props.me?.badges.rfqNewOffers ?? 0);
const supplierDot = computed(() =>
  (props.me?.badges.dispatchPending ?? 0) + (props.me?.badges.stockExpiring ?? 0));

function tap(r: ElecRole) {
  if (r === props.active) return;
  switchRole(r, isSupplier.value);
}
</script>

<template>
  <view class="rs">
    <view class="rs__item" :class="{ 'is-on': active === 'buyer' }" @tap="tap('buyer')">
      <text class="txt-sub">我要买</text>
      <text v-if="buyerDot && active !== 'buyer'" class="sh-badge-count rs__dot">{{ buyerDot }}</text>
    </view>
    <view class="rs__item" :class="{ 'is-on': active === 'supplier' }" @tap="tap('supplier')">
      <text class="txt-sub">{{ isSupplier ? "我是供应商" : "成为供应商" }}</text>
      <text v-if="supplierDot && active !== 'supplier'" class="sh-badge-count rs__dot">{{ supplierDot }}</text>
    </view>
  </view>
</template>

<style scoped>
.rs {
  display: flex;
  gap: 8rpx;
  padding: 8rpx;
  margin-bottom: 24rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
}
.rs__item {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  padding: 16rpx 0;
  border-radius: 9999px;
  color: var(--sh-sub);
}
.rs__item.is-on {
  background: var(--sh-surface);
  color: var(--sh-ink);
  font-weight: 600;
}
.rs__dot {
  position: static;
}
</style>
