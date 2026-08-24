<script setup lang="ts">
/**
 * 页头的「当前门店」胶囊：显示在看哪家店，点一下直达「选择门店」。
 *
 * 门店是 App 级上下文，**切换动作只发生在选择门店那一页** ——
 * 这里只是它的入口。曾经做成右对齐的一行小字、点了先跳「我的」再找入口，
 * 实际反馈是「没看到门店切换在哪」：长得不像能点的东西，就等于没有。
 * 单店主体不渲染。
 */
import { onMounted } from "vue";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";

const merchant = useMerchantStore();

onMounted(() => {
  void merchant.ensureStores();
});

function go() {
  // 直达选择门店页。此前是先跳「我的」再找入口 —— 多一跳，而且反馈是「没看到切换在哪」
  uni.navigateTo({ url: ROUTES.storePick });
}
</script>

<template>
  <view v-if="merchant.multiStore" class="tag" @tap="go">
    <sh-icon name="store" :size="18" color="var(--sh-primary-text)"></sh-icon>
    <text class="tag__name">{{ merchant.currentStore?.name || "—" }}</text>
    <text class="tag__switch">{{ $t("storePick.switch") }}</text>
  </view>
</template>

<style scoped>
/* 左置胶囊：带底色与边界，看得出「这是一个控件」——右对齐小字的教训 */
.tag {
  display: inline-flex;
  align-items: center;
  gap: 10rpx;
  margin-bottom: 16rpx;
  padding: 12rpx 20rpx;
  border-radius: 44rpx;
  background: var(--sh-primary-tint);
}
.tag__name {
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.tag__switch {
  font-size: 24rpx;
  color: var(--sh-primary-text);
}
</style>
