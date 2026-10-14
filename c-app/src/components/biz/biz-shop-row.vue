<script setup lang="ts">
// 店铺列表的一行：头像 ·（自营）店名 ✓ · 一行成绩与范围 · 一行简介。**整行可点**。
//
// 此前店铺页三档各用各的样子：前两档套商家条（右侧一颗「进店 ›」）再在下面补 chip，
// 第三档另写一份密排行 —— 同一家店换一档就换一副长相，而那颗「进店 ›」在一张整行可点的卡上
// 是多余的：点哪儿都进店。
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { distance } from "@shared/utils/format";
import type { Merchant } from "@shared/types";

/**
 * @param meta 替换第二行。「我买过的」那一档用它说「买过 N 单 · 最近 …」——
 *   对回头客，这比评分更有用。
 */
const props = defineProps<{ merchant: Merchant; meta?: string }>();
defineEmits<{ (e: "tap"): void }>();

const { t } = useI18n();

/**
 * 第二行：成绩 · 范围 · 距离。
 * **没人评过就说「新店」，不说「暂无评价」**：后者是在说「没有」，对新店是劝退；
 * 前者是事实，还带一点「值得一试」的意思。
 */
const metaText = computed(() => {
  if (props.meta) return props.meta;
  const m = props.merchant;
  const parts: string[] = [];
  if (m.ratingCount > 0) {
    parts.push(`${m.rating.toFixed(1)} ★`);
    if (m.salesCount > 0) parts.push(String(t("shops.orders", { n: m.salesCount })));
  } else {
    parts.push(String(t("shops.newShop")));
  }
  if (m.serviceScope) parts.push(String(t(`serviceScope.${m.serviceScope}`)));
  if (m.distance) parts.push(distance(m.distance));
  return parts.join(" · ");
});
</script>

<template>
  <view class="row sh-row" @tap.stop="$emit('tap')">
    <biz-shop-avatar
      :name="merchant.name"
      :logo="merchant.logo"
      :self-operated="merchant.selfOperated"
      :size="88"
    ></biz-shop-avatar>
    <view class="sh-fill row__main">
      <view class="sh-row row__title">
        <!-- 自营标（电商法 §37），放店名前 -->
        <text v-if="merchant.selfOperated" class="sh-chip sh-chip--primary row__self">{{ $t("merchant.selfOperated") }}</text>
        <text class="txt-strong row__name">{{ merchant.name }}</text>
        <sh-icon v-if="merchant.verified" name="verified" :size="28" color="var(--sh-primary)"></sh-icon>
      </view>
      <text class="txt-caption txt-quiet row__line sh-num">{{ metaText }}</text>
      <text v-if="merchant.desc" class="txt-caption txt-quiet row__line">{{ merchant.desc }}</text>
    </view>
    <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
  </view>
</template>

<style scoped>
.row {
  gap: 20rpx;
  padding: 20rpx 24rpx;
}
.row__main {
  min-width: 0;
}
.row__title {
  gap: 8rpx;
}
.row__self {
  flex-shrink: 0;
  padding: 4rpx 12rpx;
}
.row__name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row__line {
  display: block;
  margin-top: 4rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
