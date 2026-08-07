<script setup lang="ts">
// 订单详情：码 → 状态时间线 → 商品 → 金额 → 履约信息 → 操作。
// 码放最上面：待取货的用户打开订单，十有八九就是来看码的。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onShow, onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import { CATEGORY_TYPE, ROUTES } from "@shared/utils/constants";
import { datetime, money } from "@shared/utils/format";
import type { Order } from "@shared/types";

const { t } = useI18n();

const order = ref<Order | null>(null);
const orderNo = ref("");

const canCancel = computed(
  () => order.value?.status === "WAIT_PAY" || order.value?.status === "PAID",
);
const canAfterSale = computed(
  () =>
    !!order.value &&
    ["PAID", "PREPARING", "ARRIVED", "SHIPPED", "COMPLETED"].includes(order.value.status),
);
const canReview = computed(
  () => order.value?.status === "COMPLETED" && !order.value?.reviewed,
);

const isVirtualOrCard = computed(() => {
  const type = order.value?.items[0]?.type;
  return type === CATEGORY_TYPE.VIRTUAL || type === CATEGORY_TYPE.CARD;
});

async function load() {
  if (!orderNo.value) return;
  order.value = await api.orderDetail(orderNo.value);
}

async function cancel() {
  const o = order.value;
  if (!o) return;
  const ok = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: String(t("pay.cancelTitle")),
      content: String(t("pay.cancelTip")),
      success: (r) => resolve(!!r.confirm),
      fail: () => resolve(false),
    });
  });
  if (!ok) return;
  try {
    order.value = await api.cancelOrder(o.orderNo);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

function pay() {
  uni.navigateTo({ url: `${ROUTES.pay}?orderNo=${orderNo.value}` });
}

function afterSale() {
  uni.navigateTo({ url: `${ROUTES.afterSale}?orderNo=${orderNo.value}` });
}

function review() {
  const first = order.value?.items.find((it) => !it.isGift);
  uni.navigateTo({
    url: `${ROUTES.reviewWrite}?orderNo=${orderNo.value}&goodsNo=${first?.goodsNo ?? ""}`,
  });
}

/**
 * 售后进行中的两个后续动作（C-FF 售后闭环后半段）。
 * 放在订单页而不是售后页：用户回头找的是「我那个订单」，不是「那次申请」。
 */
async function fillExpress() {
  const o = order.value;
  if (!o) return;
  const no = await new Promise<string>((resolve) => {
    uni.showModal({
      title: String(t("afterSale.returnExpressTitle")),
      editable: true,
      placeholderText: String(t("afterSale.returnExpressPh")),
      success: (r) => resolve(r.confirm ? (r.content || "").trim() : ""),
      fail: () => resolve(""),
    });
  });
  if (!no) return;
  try {
    order.value = await api.fillReturnExpress(o.afterSale!.afterSaleNo, no);
    uni.showToast({ title: String(t("afterSale.returnExpressOk")), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

async function dispute() {
  const o = order.value;
  if (!o) return;
  const reason = await new Promise<string>((resolve) => {
    uni.showModal({
      title: String(t("afterSale.disputeTitle")),
      editable: true,
      placeholderText: String(t("afterSale.disputePh")),
      success: (r) => resolve(r.confirm ? (r.content || "").trim() : ""),
      fail: () => resolve(""),
    });
  });
  if (!reason) return;
  try {
    order.value = await api.raiseDispute(o.afterSale!.afterSaleNo, reason);
    uni.showToast({ title: String(t("afterSale.disputeOk")), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

function buyAgain() {
  const first = order.value?.items.find((it) => !it.isGift);
  if (first) uni.navigateTo({ url: `${ROUTES.goods}?goodsNo=${first.goodsNo}` });
}

onLoad((q) => {
  orderNo.value = (q?.orderNo as string) || "";
});

// 用 onShow 而非 onLoad 加载：从售后页返回时状态会变，必须重新拉
onShow(load);
</script>

<template>
  <sh-scaffold v-if="order" title-key="order.title">
    <!-- 码：待取货的用户主要就是来看这个 -->
    <view v-if="order.verifyCode && order.status !== 'COMPLETED'" class="sh-card codecard">
      <text class="codecard__label">{{ $t("pay.verifyCode") }}</text>
      <text class="codecard__v sh-num">{{ order.verifyCode }}</text>
      <text class="codecard__hint">{{ $t("order.codeHint") }}</text>
    </view>
    <view v-if="order.redeemCode" class="sh-card codecard codecard--redeem">
      <text class="codecard__label">{{ $t("pay.redeemCode") }}</text>
      <text class="codecard__v sh-num">{{ order.redeemCode }}</text>
      <text class="codecard__hint">
        {{ isVirtualOrCard ? $t("order.redeemHint") : "" }}
      </text>
    </view>

    <!-- 状态 + 时间线 -->
    <view class="sh-card block">
      <text class="status" :class="`is-${order.status}`">
        {{ $t(`orderStatus.${order.status}`) }}
      </text>

      <view class="timeline">
        <view v-for="(n, i) in order.timeline" :key="i" class="node">
          <view class="node__dot" :class="{ 'is-last': i === order.timeline.length - 1 }" />
          <view class="node__body">
            <text class="node__label">{{ n.label }}</text>
            <text class="node__at sh-num">{{ datetime(n.at) }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 商品 -->
    <view class="sh-card block">
      <biz-sku-row
        v-for="(it, i) in order.items"
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
    </view>

    <!-- 金额 -->
    <view class="sh-card block">
      <view class="amt">
        <text class="amt__k">{{ $t("confirm.goods") }}</text>
        <text class="amt__v sh-num">{{ money(order.amount.goodsMinor) }}</text>
      </view>
      <view class="amt">
        <text class="amt__k">{{ $t("confirm.freight") }}</text>
        <text class="amt__v sh-num">
          {{ order.amount.freightMinor ? money(order.amount.freightMinor) : $t("confirm.free") }}
        </text>
      </view>
      <view v-if="order.amount.discountMinor" class="amt">
        <text class="amt__k">{{ $t("confirm.discount") }}</text>
        <text class="amt__v amt__v--off sh-num">-{{ money(order.amount.discountMinor) }}</text>
      </view>
      <view v-if="order.amount.weighAdjustMinor" class="amt">
        <text class="amt__k">{{ $t("order.weighAdjust") }}</text>
        <text class="amt__v sh-num">{{ money(order.amount.weighAdjustMinor) }}</text>
      </view>
      <view class="amt amt--total">
        <text class="amt__k">{{ $t("order.paid") }}</text>
        <text class="amt__total sh-num">
          {{ money(order.amount.paidMinor || order.amount.payableMinor) }}
        </text>
      </view>
    </view>

    <!-- 商家披露：分账场景下必须让用户知道钱付给了谁（ADR-002 §5）。
         购物车跨商家会拆成多笔子订单，一单只对应一家 —— 不说清楚，
         用户看到账单上出现陌生商户名会直接当成盗刷。 -->
    <view v-if="order.merchantName" class="sh-card block">
      <text class="disclose">{{ $t("order.providedBy", { m: order.merchantName }) }}</text>
      <text v-if="order.payGroupNo" class="sh-muted disclose__hint">
        {{ $t("order.splitHint") }}
      </text>
    </view>

    <!-- 履约信息 -->
    <view class="sh-card block">
      <view class="fact">
        <text class="fact__k">{{ $t("goods.fulfillment") }}</text>
        <text class="fact__v">{{ $t(`fulfillment.${order.fulfillment}`) }}</text>
      </view>
      <view v-if="order.pickupName" class="fact">
        <text class="fact__k">{{ $t("order.pickup") }}</text>
        <text class="fact__v">{{ order.pickupName }}</text>
      </view>
      <view v-if="order.appointmentAt" class="fact">
        <text class="fact__k">{{ $t("order.appointment") }}</text>
        <text class="fact__v sh-num">{{ datetime(order.appointmentAt) }}</text>
      </view>
      <view v-if="order.expressNo" class="fact">
        <text class="fact__k">{{ $t("order.express") }}</text>
        <text class="fact__v sh-num">{{ order.expressNo }}</text>
      </view>
      <view class="fact">
        <text class="fact__k">{{ $t("order.orderNo") }}</text>
        <text class="fact__v sh-num">{{ order.orderNo }}</text>
      </view>
      <view class="fact">
        <text class="fact__k">{{ $t("order.createdAt") }}</text>
        <text class="fact__v sh-num">{{ datetime(order.createdAt) }}</text>
      </view>
    </view>

    <!-- 售后进行中：把「下一步该我做什么」直接摆出来，别让用户自己找入口 -->
    <view v-if="order.afterSale && order.status === 'REFUNDING'" class="sh-card as">
      <text class="as__title">
        {{ $t(`afterSale.status.${order.afterSale.status}`) }}
      </text>
      <text v-if="order.afterSale.merchantReply" class="as__reply">
        {{ $t("afterSale.merchantReply") }}{{ order.afterSale.merchantReply }}
      </text>
      <text class="as__hint">{{ $t(`afterSale.statusHint.${order.afterSale.status}`) }}</text>
      <view v-if="order.afterSale.status === 'AGREED'" class="sh-btn as__btn" @tap="fillExpress">
        {{ $t("afterSale.fillExpress") }}
      </view>
      <view
        v-else-if="order.afterSale.status === 'REJECTED'"
        class="sh-btn sh-btn--soft as__btn"
        @tap="dispute"
      >
        {{ $t("afterSale.raiseDispute") }}
      </view>
    </view>

    <view class="ops">
      <view v-if="order.status === 'WAIT_PAY'" class="sh-btn op" @tap="pay">
        {{ $t("orders.pay") }}
      </view>
      <view v-if="canCancel" class="sh-btn sh-btn--muted op" @tap="cancel">
        {{ $t("order.cancel") }}
      </view>
      <view v-if="canReview" class="sh-btn op" @tap="review">
        {{ $t("review.writeTitle") }}
      </view>
      <view v-if="canAfterSale" class="sh-btn sh-btn--soft op" @tap="afterSale">
        {{ $t("order.afterSale") }}
      </view>
      <view class="sh-btn sh-btn--muted op" @tap="buyAgain">{{ $t("order.buyAgain") }}</view>
    </view>
    <view class="spacer" />
  </sh-scaffold>
</template>

<style scoped>
.as {
  margin-top: 20rpx;
}
.as__title {
  display: block;
  font-size: 28rpx;
  font-weight: 400;
  color: var(--sh-ink);
}
.as__reply,
.as__hint {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
  margin-top: 10rpx;
}
.as__btn {
  margin-top: 24rpx;
}

.disclose {
  display: block;
  font-size: 26rpx;
  color: var(--sh-ink);
  line-height: 1.6;
}
.disclose__hint {
  display: block;
  margin-top: 10rpx;
  line-height: 1.6;
}

.codecard {
  text-align: center;
  background: var(--sh-primary-tint);
}
.codecard--redeem {
  background: var(--sh-warning-tint);
  margin-top: 20rpx;
}
.codecard__label {
  display: block;
  font-size: 24rpx;
  color: var(--sh-primary);
}
.codecard--redeem .codecard__label {
  color: var(--sh-warning);
}
.codecard__v {
  display: block;
  font-size: 48rpx;
  font-weight: 600;
  letter-spacing: 8rpx;
  color: var(--sh-ink);
  margin-top: 14rpx;
}
.codecard__hint {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 14rpx;
}
.block {
  margin-top: 20rpx;
}
.status {
  display: block;
  font-size: 34rpx;
  font-weight: 600;
  color: var(--sh-primary);
}
.status.is-WAIT_PAY {
  color: var(--sh-warning);
}
.status.is-COMPLETED,
.status.is-CANCELLED,
.status.is-REFUNDED {
  color: var(--sh-sub);
}
.status.is-REFUNDING {
  color: var(--sh-danger);
}
.timeline {
  margin-top: 28rpx;
}
.node {
  display: flex;
  gap: 20rpx;
  padding-bottom: 24rpx;
}
.node__dot {
  width: 16rpx;
  height: 16rpx;
  border-radius: 9999px;
  background: var(--sh-line);
  margin-top: 10rpx;
  flex-shrink: 0;
}
.node__dot.is-last {
  background: var(--sh-primary);
}
.node__body {
  flex: 1;
  min-width: 0;
}
.node__label {
  display: block;
  font-size: 26rpx;
  color: var(--sh-ink);
}
.node__at {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 4rpx;
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
.amt {
  display: flex;
  justify-content: space-between;
  padding: 12rpx 0;
}
.amt--total {
  margin-top: 12rpx;
}
.amt__k {
  font-size: 24rpx;
  color: var(--sh-sub);
}
.amt__v {
  font-size: 24rpx;
  color: var(--sh-ink);
}
.amt__v--off {
  color: var(--sh-danger);
}
.amt__total {
  font-size: 34rpx;
  font-weight: 700;
  color: var(--sh-ink);
}
.fact {
  display: flex;
  justify-content: space-between;
  gap: 32rpx;
  padding: 12rpx 0;
}
.fact__k {
  font-size: 24rpx;
  color: var(--sh-sub);
  flex-shrink: 0;
}
.fact__v {
  font-size: 24rpx;
  color: var(--sh-ink);
  text-align: end;
}
.ops {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-top: 28rpx;
}
.op {
  flex: 1 0 calc(50% - 16rpx);
  padding-top: 24rpx;
  padding-bottom: 24rpx;
  font-size: 26rpx;
}
.spacer {
  height: 60rpx;
}
</style>
