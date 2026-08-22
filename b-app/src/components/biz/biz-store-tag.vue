<script setup lang="ts">
/**
 * 页头的「当前门店」只读标记。
 *
 * 门店是 App 级上下文，**切换只发生在一个地方**（我的 → 选择门店）。
 * 此前工作台、商品页各画一条切换条，同一件事在两处各有一套交互，
 * 而订单、营销、结算等页面又一条都没有 —— 人在那些页面上不知道自己在看哪家店。
 * 这个标记只负责「告诉你现在是哪家」，点它去「我的」切；单店主体不渲染。
 */
import { onMounted } from "vue";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";

const merchant = useMerchantStore();

onMounted(() => {
  void merchant.ensureStores();
});

function go() {
  uni.switchTab({ url: ROUTES.me });
}
</script>

<template>
  <view v-if="merchant.multiStore" class="tag" @tap="go">
    <text class="tag__label">{{ $t("storePick.current") }}</text>
    <text class="tag__name">{{ merchant.currentStore?.name || "—" }}</text>
    <sh-icon name="chevronRight" :size="18" color="var(--sh-sub)"></sh-icon>
  </view>
</template>

<style scoped>
.tag {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6rpx;
  margin-bottom: 12rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.tag__name {
  color: var(--sh-ink);
}
</style>
