<script setup lang="ts">
// 商品/服务上的商家信息条。点进商家详情。
import type { MerchantBrief } from "@shared/types";

defineProps<{ merchant: MerchantBrief; goodsCount?: number }>();
defineEmits<{ (e: "tap"): void }>();
</script>

<template>
  <view class="bar" @tap="$emit('tap')">
    <text class="bar__logo">{{ merchant.logo }}</text>
    <view class="bar__main">
      <view class="bar__title">
        <text class="bar__name">{{ merchant.name }}</text>
        <text v-if="merchant.verified" class="sh-chip sh-chip--primary bar__verified">
          {{ $t("merchant.verified") }}
        </text>
      </view>
      <sh-rating :value="merchant.rating" :size="24"></sh-rating>
    </view>
    <text class="bar__more">{{ $t("merchant.enter") }}</text>
  </view>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 20rpx;
}
.bar__logo {
  width: 80rpx;
  height: 80rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  text-align: center;
  line-height: 80rpx;
  font-size: 40rpx;
  flex-shrink: 0;
}
.bar__main {
  flex: 1;
  min-width: 0;
}
.bar__title {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-bottom: 8rpx;
}
.bar__name {
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bar__verified {
  flex-shrink: 0;
  padding: 4rpx 14rpx;
  font-size: 24rpx;
}
.bar__more {
  font-size: 24rpx;
  color: var(--sh-primary);
  flex-shrink: 0;
}
</style>
