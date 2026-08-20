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
        <!--
          自营标识。**电商法 §37 的法定义务** —— 平台自营业务必须以显著方式区分标记。
          排在店名**之前**：它回答的是「谁在卖」，而店名只回答「货是谁供的」。
          放后面会被读成店名的一个后缀。
        -->
        <text v-if="merchant.selfOperated" class="sh-chip sh-chip--accent bar__self">
          {{ $t("merchant.selfOperated") }}
        </text>
        <text class="bar__name">{{ merchant.name }}</text>
        <text v-if="merchant.verified" class="sh-chip sh-chip--primary bar__verified">
          {{ $t("merchant.verified") }}
        </text>
      </view>
      <!-- **没人评过 ≠ 0 分**：一家 0 分的店是被打出来的，一家没人评过的只是新开的。
           给新店挂一排空星，看着像差评店 —— 而它连被评的机会都还没有 -->
      <sh-rating v-if="merchant.ratingCount > 0" :value="merchant.rating" :size="24"></sh-rating>
      <text v-else class="sh-muted bar__norate">{{ $t("merchant.noRating") }}</text>
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
.bar__self {
  flex-shrink: 0;
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
.bar__norate {
  font-size: 24rpx;
}
.bar__more {
  font-size: 24rpx;
  color: var(--sh-primary-text);
  flex-shrink: 0;
}
</style>
