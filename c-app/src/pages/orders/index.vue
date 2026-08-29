<script setup lang="ts">
// 订单列表。tab 是「用户视角的下一步动作」，不是订单状态枚举 ——
// 用户关心的是「我要去付钱 / 我要去取货」，不是 PAID 和 ARRIVED 的区别。
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { ROUTES } from "@shared/utils/constants";
import { datetime, money } from "@shared/utils/format";
import type { Order } from "@shared/types";
import {
  DELIVERY_SHAPE,
  orderView,
  tabQuery,
  type OrderTabSpec,
} from "@shared/strategies/order-view";

/**
 * 页签 = **谓词**（抽象状态 + 交付形态集合），不是状态值。
 *
 * 这一点是刻意的：状态集合封闭（6 个，与履约无关），履约集合开放。
 * 「待取货」不是一个状态，是 `FULFILLING ∧ 自己去取` 这个条件 ——
 * 于是**加一种履约方式不需要加状态**，只要把它归进某个交付形态；
 * 想把两个页签并成一个，改这里的 `shapes`，后端一行不用动。
 *
 * ⚠️ 曾经这里是 `toPick: [PAID, ARRIVED, SHIPPED]` 三个「状态」并成一个页签，
 * 而 `ARRIVED`/`SHIPPED` 本身就是「状态 × 履约」的组合冒充状态。
 * 两层错叠在一起的表现是：**买快递的用户在「待取货」下看到自己的单**。
 *
 * ⚠️「售后」是**唯一不按订单状态筛的页签** —— 售后是挂在订单上的另一张单，
 * 与订单状态并存：一个「已完成」的订单照样可以有一张处理中的售后单。
 * 此前这个页签筛 `["REFUNDING","REFUNDED"]`，而 `REFUNDING` 从来只是售后单的状态、
 * 订单不会是这个值，于是处理中的售后一条也进不来。
 */
const TABS: OrderTabSpec[] = [
  { key: "all" },
  { key: "toPay", status: "WAIT_PAY" },
  /** 已付款、等交付方行动 —— 用户这时什么都不用做 */
  { key: "toShip", status: "PAID" },
  /** 该你了：自提到点了要去取、到店核销的码已出可去用 */
  {
    key: "toPick",
    status: "FULFILLING",
    shapes: [DELIVERY_SHAPE.SELF_PICKUP, DELIVERY_SHAPE.SELF_SERVE],
  },
  /** 等着：实物在路上、服务方按约定时间来 */
  {
    key: "toReceive",
    status: "FULFILLING",
    shapes: [DELIVERY_SHAPE.SHIP_TO_BUYER, DELIVERY_SHAPE.SERVE_TO_BUYER],
  },
  { key: "done", status: "COMPLETED" },
  { key: "afterSale" },
];

/**
 * 后端 `Math.min(size, 50)`。**端上写 100 是自欺**：要 100 拿 50，
 * 而前端还在这 50 条上做筛选 —— 老用户的订单会静默缺失，且不报错。
 * 写成与后端一致的 50，超出部分由 `hiddenCount` 明说。
 */
const PAGE_SIZE = 50;

const { t } = useI18n();
const tab = ref("all");
const orders = ref<Order[]>([]);
/**
 * 有售后单的**子订单**号。「售后」页签靠它筛，而不是靠订单状态。
 * ⚠️ 用 subOrderNo 不是 orderNo —— 列表一行是一张子订单，
 * 而售后单上的 orderNo 是主单号，两个字段同名不同物（见 AfterSale 的注释）
 */
const afterSaleOrderNos = ref<Set<string>>(new Set());
const loaded = ref(false);

/**
 * 状态页签由**后端**筛完了，端上不再二次过滤。
 * 「售后」是例外：它按另一张单的存在与否筛，后端的 status 参数管不着。
 */
const shown = computed(() =>
  tab.value === "afterSale"
    ? orders.value.filter((o) => afterSaleOrderNos.value.has(o.orderNo))
    : orders.value,
);

/** 后端还有、这一页没拿到的条数。**说出来**，不要让它悄悄消失 */
const hiddenCount = ref(0);

async function load() {
  // 售后页签要在全量里找挂了售后单的那些，不能带筛选
  const spec = tab.value === "afterSale"
    ? undefined
    : TABS.find((x) => x.key === tab.value);

  const [res, afterSales] = await Promise.all([
    api.orderList({ size: PAGE_SIZE, ...(spec ? tabQuery(spec) : {}) }),
    // 售后单独取。失败不该拖垮整个订单列表 —— 主列表是这一页的正事
    api.afterSaleList().catch(() => []),
  ]);
  orders.value = res.records;
  hiddenCount.value = Math.max(0, res.total - res.records.length);
  afterSaleOrderNos.value = new Set(afterSales.map((a) => a.subOrderNo));
  loaded.value = true;
}

/** 换页签要重新取数 —— 筛选在后端，不换数据就还是上一个页签的结果 */
watch(tab, load);

/** 状态文案：`(状态 × 履约 × 信息)`。预约单要把时间带进文案，没时间的「待服务」等于没说 */
function statusText(o: Order): string {
  const v = orderView(o.status, o.fulfillment, { appointmentAt: o.appointmentAt });
  return String(v.needsTime ? t(v.labelKey, { t: datetime(o.appointmentAt!) }) : t(v.labelKey));
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
      <view class="card__head sh-row sh-row--between">
        <text class="txt-caption card__pickup">
          {{ o.pickupName || $t(`fulfillment.${o.fulfillment}`) }}
        </text>
        <!--
          状态文案不再是 `orderStatus.<状态>` 一对一 —— 同一个 FULFILLING，
          自提说「已到自提点」、快递说「已发货」、预约说「待服务 · 明天 14:00」。
          由 orderView(状态, 履约, 信息) 决定，三端共用同一份映射。
        -->
        <text class="txt-caption txt-bold card__status" :class="`is-${o.status}`">
          {{ statusText(o) }}
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
            <text v-if="it.isGift" class="txt-caption sh-chip sh-chip--danger tiny">
              {{ $t("promo.gift") }}
            </text>
            <text v-else class="txt-strong row__price sh-num">{{ money(it.price) }}</text>
            <text class="txt-caption row__qty sh-num">×{{ it.qty }}</text>
          </view>
        </template>
      </biz-sku-row>
      <text v-if="o.items.length > 3" class="txt-caption more sh-num">
        {{ $t("orders.moreItems", { n: o.items.length - 3 }) }}
      </text>

      <view class="card__foot sh-row sh-row--between">
        <text class="txt-caption sh-num">{{ datetime(o.createdAt) }}</text>
        <text class="txt-price sh-num">
          {{ $t("orders.total", { p: money(o.amount.payableMinor) }) }}
        </text>
      </view>

      <view v-if="o.status === 'WAIT_PAY'" class="card__ops">
        <view class="txt-sub sh-btn card__pay" @tap.stop="pay(o)">{{ $t("orders.pay") }}</view>
      </view>
      <view v-else-if="o.verifyCode && o.status !== 'COMPLETED'" class="codeline sh-row sh-row--between">
        <text class="txt-caption codeline__label txt-primary">{{ $t("pay.verifyCode") }}</text>
        <text class="txt-body codeline__v sh-num">{{ o.verifyCode }}</text>
      </view>
    </view>

    <!--
      后端还有、这一页没拿到的。**宁可难看也要说** ——
      不说的表现是「我上个月那单不见了」，而用户会以为订单丢了。
    -->
    <text v-if="hiddenCount > 0" class="txt-caption hidden-note">
      {{ $t("orders.hiddenCount", { n: hiddenCount }) }}
    </text>

    <view v-if="loaded && !shown.length" class="empty">
      <text class="txt-sub empty__text">{{ $t("orders.empty") }}</text>
      <view class="sh-btn empty__btn" @tap="goShopping">{{ $t("visited.go") }}</view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.card {
  margin-bottom: 20rpx;
}
.card__head {
  gap: 20rpx;
}
.card__pickup {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card__status {
  color: var(--sh-primary-text);
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
.row__right {
  text-align: end;
  flex-shrink: 0;
}
.tiny {
  padding: 4rpx 14rpx;
}
.row__price {
  display: block;
}
.row__qty {
  display: block;
  margin-top: 4rpx;
}
.more {
  display: block;
  text-align: center;
  margin-top: 16rpx;
}
.card__foot {
  margin-top: 24rpx;
}

.card__ops {
  display: flex;
  justify-content: flex-end;
  margin-top: 20rpx;
}
.card__pay {
  padding: 16rpx 48rpx;
}
.codeline {
  margin-top: 20rpx;
  background: var(--sh-primary-tint);
  border-radius: 24rpx;
  padding: 16rpx 24rpx;
}
.codeline__v {
  letter-spacing: 3rpx;
}
.empty__text {
  display: block;
  margin-bottom: 40rpx;
}
.empty__btn {
  display: inline-block;
  padding-inline: 60rpx;
}
.hidden-note {
  display: block;
  text-align: center;
  padding: 24rpx 32rpx;
}
.empty {
  text-align: center;
  padding: 120rpx 40rpx;
}
</style>
