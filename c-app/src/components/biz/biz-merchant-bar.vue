<script setup lang="ts">
// 商品/服务上的商家信息条。点进商家详情。
import type { MerchantBrief } from "@shared/types";

/**
 * @param quietNoRating 没人评过时**什么都不说**（默认会说「暂无评价」）。
 *   商品详情页用：那里商家条挪到了下半页，一行「暂无评价」只是在说「没有」，
 *   对新店是劝退。商家列表 / 搜索页不传，照旧显示 —— 在那里横向比较时它有意义。
 */
defineProps<{ merchant: MerchantBrief; goodsCount?: number; quietNoRating?: boolean }>();
defineEmits<{ (e: "tap"): void }>();
</script>

<template>
  <view class="sh-row bar" @tap="$emit('tap')">
    <biz-shop-avatar
      :name="merchant.name"
      :logo="merchant.logo"
      :self-operated="merchant.selfOperated"
      :size="80"
    ></biz-shop-avatar>
    <view class="sh-fill bar__main">
      <view class="sh-row bar__title">
        <!--
          自营标识。**电商法 §37 的法定义务** —— 平台自营业务必须以显著方式区分标记。
          排在店名**之前**：它回答的是「谁在卖」，而店名只回答「货是谁供的」。
          放后面会被读成店名的一个后缀。
        -->
        <text v-if="merchant.selfOperated" class="sh-chip sh-chip--primary bar__self">
          {{ $t("merchant.selfOperated") }}
        </text>
        <text class="txt-strong bar__name">{{ merchant.name }}</text>
        <text v-if="merchant.verified" class="sh-chip sh-chip--primary bar__verified">
          {{ $t("merchant.verified") }}
        </text>
      </view>
      <!-- **没人评过 ≠ 0 分**：一家 0 分的店是被打出来的，一家没人评过的只是新开的。
           给新店挂一排空星，看着像差评店 —— 而它连被评的机会都还没有 -->
      <sh-rating v-if="merchant.ratingCount > 0" :value="merchant.rating" :size="24"></sh-rating>
      <text v-else-if="!quietNoRating" class="sh-muted bar__norate">{{ $t("merchant.noRating") }}</text>
    </view>
    <text class="txt-caption bar__more">{{ $t("merchant.enter") }}</text>
  </view>
</template>

<style scoped>
.bar {
  gap: 20rpx;
}
.bar__title {
  gap: 12rpx;
  margin-bottom: 8rpx;
}
.bar__self {
  flex-shrink: 0;
}
.bar__name {
  color: var(--sh-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bar__verified {
  flex-shrink: 0;
  padding: 4rpx 16rpx;
}
.bar__norate {
}
.bar__more {
  color: var(--sh-primary-text);
  flex-shrink: 0;
}
</style>
