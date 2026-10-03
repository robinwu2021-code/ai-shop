<script setup lang="ts">
// 店铺页的一行 —— **单位是门店**（TDD-C端门店化与门店门户 s01/s02）。
//
// 三行：门店名 ·（「我的店」才有）为什么它在这里 · 营业状态与营业时间；距离靠右。
// 第二行是给商家的回报：别人点开他分享的门店，这家店就以「朋友分享」留在对方的列表里。
// 暂停营业的店照常列、整行压淡 —— 它是你常去的店，藏起来反而以为店没了。
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { distance, isoDate } from "@shared/utils/format";
import type { StoreCard } from "@shared/types";

const props = defineProps<{ store: StoreCard }>();
defineEmits<{ (e: "tap"): void }>();

const { t } = useI18n();

const paused = computed(() => props.store.status === "READONLY");

/** 为什么在「我的店」里：买过的说次数，没买过的说怎么来的。「附近」那一段没有这一行 */
const relText = computed(() => {
  const r = props.store.relation;
  if (!r) return "";
  if (r.orderCount > 0) {
    const last = r.lastOrderAt ? ` · ${isoDate(r.lastOrderAt)}` : "";
    return `${t("shops.relBought", { n: r.orderCount })}${last}`;
  }
  const how = r.firstSource === "SHARE" ? t("shops.relShared") : t("shops.relViewed");
  return r.lastViewAt ? `${how} · ${isoDate(r.lastViewAt)}` : String(how);
});

/** 营业状态 · 营业时间。认不出营业时间（openNow 为空）就只写营业时间，不猜开没开 */
const statusText = computed(() => {
  const s = props.store;
  if (paused.value) return String(t("shops.paused"));
  const parts: string[] = [];
  if (s.openNow === true) parts.push(String(t("shops.openNow")));
  if (s.openNow === false) parts.push(String(t("shops.closedNow")));
  if (s.openHours) parts.push(s.openHours);
  return parts.join(" · ");
});
</script>

<template>
  <view class="row sh-row" :class="{ 'row--paused': paused }" @tap.stop="$emit('tap')">
    <biz-shop-avatar :name="store.storeName" :logo="store.logo" :size="88"></biz-shop-avatar>
    <view class="sh-fill row__main">
      <text class="txt-strong row__line row__name">{{ store.storeName }}</text>
      <text v-if="relText" class="txt-caption row__line row__rel">{{ relText }}</text>
      <text v-if="statusText" class="txt-caption txt-quiet row__line">{{ statusText }}</text>
    </view>
    <text v-if="store.distanceM != null" class="txt-caption txt-quiet sh-num row__dist">{{ distance(store.distanceM) }}</text>
  </view>
</template>

<style scoped>
.row {
  gap: 20rpx;
  padding: 20rpx 24rpx;
}
.row--paused {
  opacity: 0.55;
}
.row__main {
  min-width: 0;
}
.row__line {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row__rel {
  margin-top: 4rpx;
  color: var(--sh-primary-text);
}
.row__line + .row__line {
  margin-top: 4rpx;
}
.row__dist {
  flex-shrink: 0;
}
</style>
