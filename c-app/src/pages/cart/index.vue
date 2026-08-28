<script setup lang="ts">
// 购物车：按履约方式分组（自提 / 快递 / 到店核销），一组一单。
import { useI18n } from "vue-i18n";
import { onShow } from "@dcloudio/uni-app";
import { useCartStore } from "@/stores/cart";
import { money } from "@shared/utils/format";
import { ROUTES } from "@shared/utils/constants";
import type { CartItem, FulfillmentType } from "@shared/types";
import { pick } from "@ai-shop/ui/prompt";

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
async function checkout() {
  const groups = cart.groups;
  if (!groups.length) return;
  if (groups.length === 1) {
    go(groups[0]!.fulfillment, groups[0]!.items);
    return;
  }
  const idx = await pick({
    title: String(t("cart.pickFulfillment")),
    items: groups.map((g) => String(t(`fulfillment.${g.fulfillment}`))),
  });
  if (idx === null) return;
  const g = groups[idx];
  if (g) go(g.fulfillment, g.items);
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

      <!--
        商家段。**一段 = 结算后的一笔子订单** —— 用户要在提交前看见会拆成几单。
        只有一家店时不画段头：一家店还套个分组框是纯噪音。
      -->
      <template v-for="m in g.merchants" :key="m.merchantNo">
        <view v-if="g.merchants.length > 1" class="seg">
          <text class="txt-strong">{{ m.merchantName || $t("cart.unknownMerchant") }}</text>
        </view>

        <biz-sku-row
          v-for="it in m.items"
          :key="it.skuNo"
          :cover="it.cover"
          :title="it.title"
          :spec="it.spec"
          size="lg"
        >
          <view v-if="it.giftQty" class="giftrow">
            <text class="txt-caption giftrow__tag">{{ $t("promo.gift") }}</text>
            <text class="txt-caption giftrow__text sh-num">
              {{ $t("promo.giftItem", { title: it.title, n: it.giftQty }) }}
            </text>
          </view>

          <view class="row__foot">
            <text class="txt-price sh-num">{{ money(it.price) }}</text>
            <view class="stepper">
              <view class="txt-body stepper__btn" @tap="dec(it.skuNo, it.qty)"><text>−</text></view>
              <text class="txt-strong stepper__num sh-num">{{ it.qty }}</text>
              <view class="txt-body stepper__btn" @tap="inc(it.skuNo, it.qty)"><text>＋</text></view>
            </view>
          </view>
        </biz-sku-row>
      </template>

      <!-- 会拆几单，说在提交之前。放在提交之后就只剩解释作用了（C-OD-06 的意图前移） -->
      <text v-if="g.merchants.length > 1" class="txt-caption splitnote">
        {{ $t("cart.splitNote", { n: g.merchants.length }) }}
      </text>
    </view>

    <sh-empty bare v-if="!cart.items.length" :text='$t("cart.empty")'></sh-empty>

    <template v-if="cart.items.length">
      <sh-actionbar pill="lead" tabbar :pad="140">
        <view class="sh-fill">
          <text class="sh-muted">{{ $t("cart.total") }}</text>
          <text class="txt-price checkoutbar__total sh-num">{{ money(cart.totalFen) }}</text>
        </view>
        <view class="txt-body sh-btn checkoutbar__btn" @tap="checkout">
          {{ $t("cart.checkout") }} ({{ cart.count }})
        </view>
      </sh-actionbar>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.group {
  margin-bottom: 20rpx;
}
.seg {
  display: flex;
  align-items: center;
  margin: 24rpx 0 8rpx;
}

.splitnote {
  display: block;
  margin-top: 16rpx;
}
.giftrow {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 16rpx;
  background: var(--sh-danger-tint);
  border-radius: 16rpx;
  padding: 10rpx 16rpx;
}
.giftrow__tag {
  color: var(--sh-danger);
  flex-shrink: 0;
}
.giftrow__text {
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

.stepper {
  display: flex;
  align-items: center;
  gap: 8rpx;
  background: var(--sh-faint);
  border-radius: 9999px;
  padding: 8rpx;
}
.stepper__btn {
  width: 52rpx;
  height: 52rpx;
  border-radius: 9999px;
  background: var(--sh-surface);
  display: flex;
  align-items: center;
  justify-content: center;
}
.stepper__num {
  min-width: 52rpx;
  text-align: center;
}
/* 悬浮结算条要压在底部菜单之上 —— 之前只算了安全区，被菜单盖住了 */

.checkoutbar__total {
  display: block;
}
.checkoutbar__btn {
  flex: 0 0 auto;
  padding-left: 48rpx;
  padding-right: 48rpx;
}
/* 给悬浮结算条留出的滚动空间（菜单高度由 sh-scaffold 的 has-tabbar 另行留出） */
</style>
