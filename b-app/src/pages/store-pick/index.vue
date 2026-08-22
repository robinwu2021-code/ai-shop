<script setup lang="ts">
/**
 * 选择门店。**唯一的切店入口**（进 App 时多店主体先到这里；之后从「我的」进来）。
 *
 * 只有一家能进的店时这一页不该出现 —— 调用方（App 启动、登录）按
 * `merchant.needsStorePick` 判；这里不再判，否则直接打开 URL 的人会被静默弹走。
 */
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";

const merchant = useMerchantStore();
/** 进 App 的那一次：选完去工作台；从「我的」进来的：选完回上一页 */
const entry = ref(false);
const picked = ref("");

const stores = computed(() => merchant.stores);
const current = computed(() => merchant.stores.find((s) => s.storeNo === picked.value));

onLoad(async (q) => {
  entry.value = q?.entry === "1";
  await merchant.ensureStores();
  picked.value = merchant.storeNo || merchant.usableStores[0]?.storeNo || "";
});

function choose(storeNo: string, status: string) {
  if (status !== "ACTIVE") return;
  picked.value = storeNo;
}

function confirm() {
  if (!picked.value) return;
  merchant.pickStore(picked.value);
  if (entry.value) {
    // reLaunch：这一页不该留在栈里，返回键不应回到「选择门店」
    uni.reLaunch({ url: ROUTES.home });
  } else {
    uni.navigateBack();
  }
}
</script>

<template>
  <sh-scaffold title-key="storePick.title">
    <text class="sh-h1">{{ $t("storePick.heading") }}</text>
    <text class="hint">{{ $t("storePick.hint") }}</text>

    <view class="list">
      <view
        v-for="s in stores"
        :key="s.storeNo"
        class="sh-card item"
        :class="{ 'is-on': s.storeNo === picked, 'is-off': s.status !== 'ACTIVE' }"
        @tap="choose(s.storeNo, s.status)"
      >
        <view class="item__main">
          <text class="item__name">
            {{ s.name }}<text v-if="s.isDefault" class="sh-chip item__chip">{{ $t("storePick.default") }}</text>
          </text>
          <text class="item__sub">
            <template v-if="s.status !== 'ACTIVE'">{{ $t("storePick.closed") }}</template>
            <template v-else>
              {{ s.address || "—" }}<template v-if="s.storeNo === merchant.storeNo && merchant.storePicked"> · {{ $t("storePick.last") }}</template>
            </template>
          </text>
        </view>
        <view class="item__radio" :class="{ 'is-on': s.storeNo === picked }">
          <text v-if="s.storeNo === picked" class="item__tick">✓</text>
        </view>
      </view>
    </view>

    <view class="sh-btn enter" :class="{ 'is-disabled': !current }" @tap="confirm">
      {{ $t("storePick.enter") }}
    </view>
    <text class="hint center">{{ $t("storePick.crossHint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.hint {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: var(--sh-sub);
}
.hint.center {
  margin-top: 20rpx;
  text-align: center;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  margin-top: 24rpx;
}
.item {
  display: flex;
  align-items: center;
  gap: 24rpx;
  border: 4rpx solid transparent;
}
.item.is-on {
  border-color: var(--sh-primary);
}
.item.is-off {
  opacity: 0.55;
}
.item__main {
  flex: 1;
  min-width: 0;
}
.item__name {
  display: block;
  font-size: 30rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.item__chip {
  margin-inline-start: 12rpx;
  font-weight: 400;
}
.item__sub {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.item__radio {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44rpx;
  height: 44rpx;
  border-radius: 9999px;
  border: 3rpx solid var(--sh-line);
  box-sizing: border-box;
}
.item__radio.is-on {
  border-color: var(--sh-primary);
  background: var(--sh-primary);
}
.item__tick {
  font-size: 24rpx;
  color: var(--sh-on-primary);
}
.enter {
  margin-top: 32rpx;
}
.enter.is-disabled {
  opacity: 0.5;
}
</style>
