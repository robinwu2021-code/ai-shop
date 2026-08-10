<script setup lang="ts">
// 订单列表。tab 是「用户视角的下一步动作」，不是订单状态枚举 ——
// 用户关心的是「我要去付钱 / 我要去取货」，不是 PAID 和 ARRIVED 的区别。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { ROUTES } from "@shared/utils/constants";
import { datetime, money } from "@shared/utils/format";
import type { Order, OrderStatus } from "@shared/types";

/**
 * 每个 tab 对应一组订单状态。
 *
 * ⚠️「售后」是**唯一不按订单状态筛的页签** —— `statuses: null` 且单独取数。
 * 售后是挂在订单上的另一张单，与订单状态并存：一个「已完成」的订单
 * 照样可以有一张处理中的售后单。此前这个页签筛
 * `["REFUNDING","REFUNDED"]`，而 `REFUNDING` 从来只是售后单的状态、
 * 订单不会是这个值，于是处理中的售后一条也进不来。
 */
const TABS: { key: string; statuses: OrderStatus[] | null }[] = [
  { key: "all", statuses: null },
  { key: "toPay", statuses: ["WAIT_PAY"] },
  { key: "toPick", statuses: ["PAID", "ARRIVED", "SHIPPED"] },
  { key: "done", statuses: ["COMPLETED"] },
  { key: "afterSale", statuses: null },
];

const tab = ref("all");
const orders = ref<Order[]>([]);
/** 有售后单的订单号。「售后」页签靠它筛，而不是靠订单状态 */
const afterSaleOrderNos = ref<Set<string>>(new Set());
const loaded = ref(false);

const shown = computed(() => {
  if (tab.value === "afterSale") {
    return orders.value.filter((o) => afterSaleOrderNos.value.has(o.orderNo));
  }
  const def = TABS.find((x) => x.key === tab.value);
  if (!def?.statuses) return orders.value;
  return orders.value.filter((o) => def.statuses!.includes(o.status));
});

async function load() {
  const [res, afterSales] = await Promise.all([
    api.orderList({ size: 100 }),
    // 售后单独取。失败不该拖垮整个订单列表 —— 主列表是这一页的正事
    api.afterSaleList().catch(() => []),
  ]);
  orders.value = res.records;
  afterSaleOrderNos.value = new Set(afterSales.map((a) => a.orderNo));
  loaded.value = true;
}

function open(o: Order) {
  uni.navigateTo({ url: `${ROUTES.order}?orderNo=${o.orderNo}` });
}

function pay(o: Order) {
  uni.navigateTo({ url: `${ROUTES.pay}?orderNo=${o.orderNo}` });
}

function goShopping() {
  uni.switchTab({ url: ROUTES.home });
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="orders.title">
    <sh-tabs
      :items="TABS.map((tb) => ({ key: tb.key, label: String($t(`orders.tab.${tb.key}`)) }))"
      :active="tab"
      @change="(k: string) => (tab = k)"
    ></sh-tabs>

    <view v-for="o in shown" :key="o.orderNo" class="sh-card card" @tap="open(o)">
      <view class="card__head">
        <text class="card__pickup">
          {{ o.pickupName || $t(`fulfillment.${o.fulfillment}`) }}
        </text>
        <text class="card__status" :class="`is-${o.status}`">
          {{ $t(`orderStatus.${o.status}`) }}
        </text>
      </view>

      <biz-sku-row
        v-for="(it, i) in o.items.slice(0, 3)"
        :key="i"
        :cover="it.cover"
        :title="it.title"
        :spec="it.spec"
      >
        <template #right>
          <view class="row__right">
            <text v-if="it.isGift" class="sh-chip sh-chip--danger tiny">
              {{ $t("promo.gift") }}
            </text>
            <text v-else class="row__price sh-num">{{ money(it.price) }}</text>
            <text class="row__qty sh-num">×{{ it.qty }}</text>
          </view>
        </template>
      </biz-sku-row>
      <text v-if="o.items.length > 3" class="more sh-num">
        {{ $t("orders.moreItems", { n: o.items.length - 3 }) }}
      </text>

      <view class="card__foot">
        <text class="card__time sh-num">{{ datetime(o.createdAt) }}</text>
        <text class="card__total sh-num">
          {{ $t("orders.total", { p: money(o.amount.payableMinor) }) }}
        </text>
      </view>

      <view v-if="o.status === 'WAIT_PAY'" class="card__ops">
        <view class="sh-btn card__pay" @tap.stop="pay(o)">{{ $t("orders.pay") }}</view>
      </view>
      <view v-else-if="o.verifyCode && o.status !== 'COMPLETED'" class="codeline">
        <text class="codeline__label">{{ $t("pay.verifyCode") }}</text>
        <text class="codeline__v sh-num">{{ o.verifyCode }}</text>
      </view>
    </view>

    <view v-if="loaded && !shown.length" class="empty">
      <text class="empty__text">{{ $t("orders.empty") }}</text>
      <view class="sh-btn empty__btn" @tap="goShopping">{{ $t("visited.go") }}</view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.card {
  margin-bottom: 20rpx;
}
.card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}
.card__pickup {
  font-size: 24rpx;
  color: var(--sh-sub);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card__status {
  font-size: 24rpx;
  font-weight: 600;
  color: var(--sh-primary);
  flex-shrink: 0;
}
.card__status.is-WAIT_PAY {
  color: var(--sh-warning);
}
.card__status.is-COMPLETED,
.card__status.is-CANCELLED,
.card__status.is-REFUNDED {
  color: var(--sh-sub);
}
.card__status.is-REFUNDING {
  color: var(--sh-danger);
}
.row__right {
  text-align: end;
  flex-shrink: 0;
}
.tiny {
  padding: 4rpx 14rpx;
  font-size: 24rpx;
}
.row__price {
  display: block;
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.row__qty {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 4rpx;
}
.more {
  display: block;
  text-align: center;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 16rpx;
}
.card__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 24rpx;
}
.card__time {
  font-size: 24rpx;
  color: var(--sh-sub);
}
.card__total {
  font-size: 26rpx;
  font-weight: 700;
  color: var(--sh-ink);
}
.card__ops {
  display: flex;
  justify-content: flex-end;
  margin-top: 20rpx;
}
.card__pay {
  padding: 16rpx 48rpx;
  font-size: 26rpx;
}
.codeline {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 20rpx;
  background: var(--sh-primary-tint);
  border-radius: 24rpx;
  padding: 16rpx 24rpx;
}
.codeline__label {
  font-size: 24rpx;
  color: var(--sh-primary);
}
.codeline__v {
  font-size: 30rpx;
  font-weight: 400;
  letter-spacing: 3rpx;
  color: var(--sh-ink);
}
.empty__text {
  display: block;
  color: var(--sh-sub);
  font-size: 26rpx;
  margin-bottom: 40rpx;
}
.empty__btn {
  display: inline-block;
  padding-left: 60rpx;
  padding-right: 60rpx;
}
.empty {
  text-align: center;
  padding: 120rpx 40rpx;
}
</style>
