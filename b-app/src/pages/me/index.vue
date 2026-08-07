<script setup lang="ts">
// 我的（复用 C 端的外观面板：4 皮肤 × 明暗 × 三语 × 多市场）。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";

const merchant = useMerchantStore();
const sheetOpen = ref(false);

const statusKey = computed(() => `me.status${merchant.profile?.status ?? "NONE"}`);

function go(url: string) {
  uni.navigateTo({ url });
}

function goLogin() {
  uni.navigateTo({ url: ROUTES.login });
}

function later() {
  uni.showToast({ title: "该功能在后续批次交付", icon: "none" });
}

function logout() {
  merchant.logout();
  uni.showToast({ title: "已退出", icon: "none" });
}

onShow(() => {
  void merchant.loadProfile().catch(() => null);
});
</script>

<template>
  <sh-scaffold title-key="tab.me" tab="me">
    <view v-if="!merchant.isLogin" class="sh-card head" @tap="goLogin">
      <text class="sh-h2">{{ $t("me.notLogin") }}</text>
      <text class="sh-muted">{{ $t("me.notLoginHint") }}</text>
    </view>

    <view v-else class="sh-card head">
      <text class="head__logo">{{ merchant.profile?.logo || "🏪" }}</text>
      <view class="head__main">
        <text class="sh-h2">{{ merchant.profile?.name || $t("me.store") }}</text>
        <text class="sh-chip" :class="merchant.isActive ? 'sh-chip--primary' : 'sh-chip--warning'">
          {{ $t(statusKey) }}
        </text>
      </view>
    </view>

    <view class="sh-card cell" @tap="sheetOpen = true">
      <text>{{ $t("me.appearance") }}</text>
      <text class="sh-muted">{{ $t("me.appearanceValue") }}</text>
    </view>

    <view class="sh-card cell" @tap="go(ROUTES.settle)">
      <text>{{ $t("me.settle") }}</text>
      <text class="sh-muted">›</text>
    </view>

    <view class="sh-card cell" @tap="go(ROUTES.stats)">
      <text>{{ $t("me.stats") }}</text>
      <text class="sh-muted">›</text>
    </view>

    <view class="sh-card cell" @tap="later">
      <text>{{ $t("me.help") }}</text>
      <text class="sh-muted">›</text>
    </view>

    <view v-if="merchant.isLogin" class="sh-card cell" @tap="logout">
      <text>{{ $t("me.logout") }}</text>
    </view>

    <sh-theme-sheet v-model:visible="sheetOpen"></sh-theme-sheet>
  </sh-scaffold>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  gap: 24rpx;
  margin-bottom: 24rpx;
}
.head__logo {
  font-size: 64rpx;
  width: 108rpx;
  height: 108rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  text-align: center;
  line-height: 108rpx;
}
.head__main {
  flex: 1;
}
.head__main .sh-chip {
  margin-top: 12rpx;
}
.cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16rpx;
}
</style>
