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
  <view class="sh-row bar" @tap.stop="$emit('tap')">
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
        <!-- 认证用盾牌图标，与店铺列表 / 店铺详情同一种 —— 文字 chip 与前面的「自营」chip 把店名夹在中间 -->
        <sh-icon v-if="merchant.verified" name="verified" :size="28" color="var(--sh-primary)"></sh-icon>
      </view>
      <!-- **没人评过 ≠ 0 分**：一家 0 分的店是被打出来的，一家没人评过的只是新开的。
           给新店挂一排空星，看着像差评店 —— 而它连被评的机会都还没有 -->
      <sh-rating v-if="merchant.ratingCount > 0" :value="merchant.rating" :size="24"></sh-rating>
      <text v-else-if="!quietNoRating" class="sh-muted bar__norate">{{ $t("merchant.noRating") }}</text>
    </view>
    <!-- 整条可点，行尾只留箭头（与店铺列表一致）；「进店 ›」三个字是在重复「这一条能点」 -->
    <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
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
  min-width: 0;
  color: var(--sh-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bar__norate {
}
</style>
