<script setup lang="ts">
// 购物车：按履约方式分组（自提 / 快递 / 到店核销），一组一单。
import { useI18n } from "vue-i18n";
import { onShow } from "@dcloudio/uni-app";
import { useCartStore } from "@/stores/cart";
import { money } from "@shared/utils/format";
import { ROUTES } from "@shared/utils/constants";
import type { CartItem, FulfillmentType } from "@shared/types";

const { t } = useI18n();
const cart = useCartStore();

function dec(skuNo: string, qty: number) {
  cart.update(skuNo, qty - 1);
}
function inc(skuNo: string, qty: number) {
  cart.update(skuNo, qty + 1);
}
/**
 * 一组一单：按履约方式分组结算。
 * 有多组时先让用户选一组 —— 自提和快递的收货信息完全不同，混在一单里没法填。
 */
function checkout() {
  const groups = cart.groups;
  if (!groups.length) return;
  if (groups.length === 1) {
    go(groups[0]!.fulfillment, groups[0]!.items);
    return;
  }
  uni.showActionSheet({
    itemList: groups.map((g) => String(t(`fulfillment.${g.fulfillment}`))),
    success: (r) => {
      const g = groups[r.tapIndex];
      if (g) go(g.fulfillment, g.items);
    },
  });
}

function go(fulfillment: FulfillmentType, items: CartItem[]) {
  const skus = items.map((i) => i.skuNo).join(",");
  uni.navigateTo({
    url: `${ROUTES.orderConfirm}?fulfillment=${fulfillment}&skus=${skus}`,
  });
}

onShow(() => cart.load());
</script>

<template>
  <sh-scaffold title-key="cart.title" tab="cart">
    <view v-for="g in cart.groups" :key="g.fulfillment" class="sh-card group">
      <text class="sh-chip sh-chip--primary">{{ $t(`fulfillment.${g.fulfillment}`) }}</text>

      <view v-for="it in g.items" :key="it.skuNo" class="row">
        <view class="row__cover">{{ it.cover }}</view>
        <view class="row__main">
          <text class="row__title">{{ it.title }}</text>
          <text class="row__spec">{{ it.spec }}</text>
          <view v-if="it.giftQty" class="giftrow">
            <text class="giftrow__tag">{{ $t("promo.gift") }}</text>
            <text class="giftrow__text sh-num">
              {{ $t("promo.giftItem", { title: it.title, n: it.giftQty }) }}
            </text>
          </view>

          <view class="row__foot">
            <text class="row__price sh-num">{{ money(it.price) }}</text>
            <view class="stepper">
              <view class="stepper__btn" @tap="dec(it.skuNo, it.qty)"><text>−</text></view>
              <text class="stepper__num sh-num">{{ it.qty }}</text>
              <view class="stepper__btn" @tap="inc(it.skuNo, it.qty)"><text>＋</text></view>
            </view>
          </view>
        </view>
      </view>
    </view>

    <sh-empty bare v-if="!cart.items.length" :text='$t("cart.empty")'></sh-empty>

    <template v-if="cart.items.length">
      <view class="checkoutbar">
        <view class="checkoutbar__sum">
          <text class="sh-muted">{{ $t("cart.total") }}</text>
          <text class="checkoutbar__total sh-num">{{ money(cart.totalFen) }}</text>
        </view>
        <view class="sh-btn checkoutbar__btn" @tap="checkout">
          {{ $t("cart.checkout") }} ({{ cart.count }})
        </view>
      </view>
      <view class="checkoutbar__spacer" />
    </template>
  </sh-scaffold>
</template>

<style scoped>
.group {
  margin-bottom: 20rpx;
}
.row {
  display: flex;
  gap: 24rpx;
  margin-top: 28rpx;
}
.row__cover {
  width: 140rpx;
  height: 140rpx;
  border-radius: 28rpx;
  background: var(--sh-faint);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 64rpx;
  flex-shrink: 0;
}
.row__main {
  flex: 1;
  min-width: 0;
}
.row__title {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row__spec {
  display: block;
  font-size: 22rpx;
  color: var(--sh-sub);
  margin-top: 6rpx;
}
.giftrow {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 14rpx;
  background: var(--sh-danger-tint);
  border-radius: 16rpx;
  padding: 10rpx 16rpx;
}
.giftrow__tag {
  font-size: 20rpx;
  font-weight: 700;
  color: var(--sh-danger);
  flex-shrink: 0;
}
.giftrow__text {
  font-size: 21rpx;
  color: var(--sh-danger);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 20rpx;
}
.row__price {
  font-size: 32rpx;
  font-weight: 700;
  color: var(--sh-ink);
}
.stepper {
  display: flex;
  align-items: center;
  gap: 8rpx;
  background: var(--sh-faint);
  border-radius: 9999px;
  padding: 6rpx;
}
.stepper__btn {
  width: 52rpx;
  height: 52rpx;
  border-radius: 9999px;
  background: var(--sh-surface);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--sh-ink);
  font-size: 28rpx;
}
.stepper__num {
  min-width: 52rpx;
  text-align: center;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
/* 悬浮结算条要压在底部菜单之上 —— 之前只算了安全区，被菜单盖住了 */
.checkoutbar {
  position: fixed;
  inset-inline: 28rpx;
  bottom: calc(var(--sh-tabbar-h) + 20rpx + env(safe-area-inset-bottom));
  display: flex;
  align-items: center;
  gap: 24rpx;
  background: var(--sh-surface);
  border-radius: 9999px;
  padding: 16rpx 16rpx 16rpx 40rpx;
}
.checkoutbar__sum {
  flex: 1;
  min-width: 0;
}
.checkoutbar__total {
  display: block;
  font-size: 36rpx;
  font-weight: 700;
  letter-spacing: -0.6rpx;
  color: var(--sh-ink);
}
.checkoutbar__btn {
  flex: 0 0 auto;
  padding-left: 48rpx;
  padding-right: 48rpx;
  font-size: 28rpx;
}
/* 给悬浮结算条留出的滚动空间（菜单高度由 sh-scaffold 的 has-tabbar 另行留出） */
.checkoutbar__spacer {
  height: 140rpx;
}
</style>
