<script setup lang="ts">
// 收银台 + 支付结果（同一页两态）。
//
// ⚠️ **端侧不自判支付成功**。requestPayment 返回的只是「用户完成了交互」，
// 真正的成功以后端支付回调为准 —— 所以唤起支付后要回查订单状态，而不是直接跳成功页。
// 这条在 ports/payment.ts 里也写了，是所有支付接入最容易错的地方。
import { computed, onUnmounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import { requestPayment } from "@shared/ports/payment";
import { requestSubscribe, SUBSCRIBE_TMPL } from "@shared/ports/push";
import { CATEGORY_TYPE, ROUTES } from "@shared/utils/constants";
import { countdown, money } from "@shared/utils/format";
import type { Order } from "@shared/types";
import { confirm } from "@ai-shop/ui/prompt";

const { t } = useI18n();

const order = ref<Order | null>(null);
const paying = ref(false);
const now = ref(Date.now());
let timer: ReturnType<typeof setInterval> | undefined;

const paid = computed(() => !!order.value && order.value.status !== "WAIT_PAY");
const expired = computed(
  () =>
    !!order.value?.payDeadlineAt &&
    order.value.status === "WAIT_PAY" &&
    now.value > order.value.payDeadlineAt,
);

/** 支付成功后的引导语按品类分：自提码 / 兑换码 / 卡包 / 物流 */
const doneHintKey = computed(() => {
  const o = order.value;
  if (!o) return "pay.doneGeneric";
  const type = o.items[0]?.type;
  if (type === CATEGORY_TYPE.VIRTUAL) return "pay.doneVirtual";
  if (type === CATEGORY_TYPE.CARD) return "pay.doneCard";
  if (o.verifyCode) return "pay.donePickup";
  return "pay.doneGeneric";
});

async function load(orderNo: string) {
  order.value = await api.orderDetail(orderNo);
}

async function pay() {
  const o = order.value;
  if (!o || paying.value || expired.value) return;
  paying.value = true;
  try {
    // 真实链路：后端下单拿支付参数 → 唤起 → 回查。这里 mock 直接推进状态
    const res = await requestPayment({});
    if (res.cancelled) {
      uni.showToast({ title: String(t("pay.cancelled")), icon: "none" });
      return;
    }
    await api.payOrder(o.orderNo);
    // 以回查为准，不用端侧返回值判成功
    order.value = await api.orderDetail(o.orderNo);

    // 订阅消息必须由用户点击行为触发，支付成功这一刻是收集授权的最佳时机。
    // 收集与上报是两步：不上报的话后端额度永远是 0，到货/退款一条都发不出
    void requestSubscribe([SUBSCRIBE_TMPL.arrived, SUBSCRIBE_TMPL.refunded]).then((r) => {
      if (r.accepted.length) void api.subscribeReport(r.accepted, true);
      if (r.rejected.length) void api.subscribeReport(r.rejected, false);
    });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    paying.value = false;
  }
}

async function cancel() {
  const o = order.value;
  if (!o) return;
  const ok = await confirm({ title: String(t("pay.cancelTitle")), hint: String(t("pay.cancelTip")) });
  if (!ok) return;
  await api.cancelOrder(o.orderNo);
  uni.redirectTo({ url: `${ROUTES.orders}` });
}

function gotoOrder() {
  uni.redirectTo({ url: `${ROUTES.order}?orderNo=${order.value?.orderNo}` });
}

function gotoHome() {
  uni.switchTab({ url: ROUTES.home });
}

onLoad((q) => {
  const no = (q?.orderNo as string) || "";
  if (no) load(no);
  timer = setInterval(() => (now.value = Date.now()), 1000);
});

onUnmounted(() => clearInterval(timer));
</script>

<template>
  <sh-scaffold v-if="order" title-key="pay.title">
    <!-- 待支付 -->
    <template v-if="!paid">
      <view class="sh-card hero">
        <text class="hero__amount sh-num">{{ money(order.amount.payableMinor) }}</text>
        <text class="hero__label">{{ $t("pay.payable") }}</text>
        <!--
          这次付款覆盖哪几笔单。**只有跨商家时才出现** —— 单商家时它等于把
          总额又抄了一遍，是噪音。
          放在金额下面而不是折叠起来：用户在这一屏要回答的是「我付的是什么」，
          而拆单是这个问题里最容易意外的那部分。
        -->
        <view v-if="(order.subOrders?.length ?? 0) > 1" class="subs">
          <text class="subs__title">{{ $t("pay.covers", { n: order.subOrders!.length }) }}</text>
          <view v-for="s in order.subOrders" :key="s.orderNo" class="subs__row">
            <text class="subs__name">{{ s.merchantName }}</text>
            <text class="subs__amount sh-num">{{ money(s.amount.payableMinor) }}</text>
          </view>
        </view>

        <view v-if="order.payDeadlineAt" class="cd" :class="{ 'is-expired': expired }">
          <text class="cd__text sh-num">
            {{ expired
              ? $t("pay.expired")
              : $t("pay.remain", { t: countdown(order.payDeadlineAt - now) }) }}
          </text>
        </view>
      </view>

      <view class="sh-card block">
        <view class="method is-on">
          <text class="method__icon">💚</text>
          <text class="method__name">{{ $t("pay.wechat") }}</text>
          <text class="method__tick">✓</text>
        </view>
      </view>

      <sh-actionbar class="bar-center" :pad="220">
        <view class="sh-btn" :class="{ 'is-disabled': paying || expired }" @tap="pay">
          {{ paying ? $t("pay.paying") : $t("pay.payNow") }}
        </view>
        <text class="cancel" @tap="cancel">{{ $t("pay.cancel") }}</text>
      </sh-actionbar>
    </template>

    <!-- 支付完成 -->
    <template v-else>
      <view class="sh-card done">
        <text class="done__icon">✓</text>
        <text class="done__title">{{ $t("pay.done") }}</text>
        <text class="done__hint">{{ $t(doneHintKey) }}</text>

        <!-- 各类码：自提码 / 核销码 / 兑换码 -->
        <view v-if="order.verifyCode" class="code">
          <text class="code__label">{{ $t("pay.verifyCode") }}</text>
          <text class="code__v sh-num">{{ order.verifyCode }}</text>
        </view>
        <view v-if="order.redeemCode" class="code code--redeem">
          <text class="code__label">{{ $t("pay.redeemCode") }}</text>
          <text class="code__v sh-num">{{ order.redeemCode }}</text>
        </view>
      </view>

      <sh-actionbar class="bar-center" :pad="220">
        <view class="sh-btn" @tap="gotoOrder">{{ $t("pay.viewOrder") }}</view>
        <text class="cancel" @tap="gotoHome">{{ $t("pay.keepShopping") }}</text>
      </sh-actionbar>
    </template>
  </sh-scaffold>
</template>

<style scoped>
/* 条里除了按钮还有一行说明/取消，居中对齐 —— 定位归 `sh-actionbar`，
   这一条是这一页自己的排布。收编时它一度被连着定位一起删掉了。 */
.bar-center {
  text-align: center;
}

.hero {
  text-align: center;
  padding-top: 56rpx;
  padding-bottom: 48rpx;
}
.hero__amount {
  display: block;
  font-size: 48rpx;
  font-weight: 700;
  color: var(--sh-ink);
}
.hero__label {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 12rpx;
}
.subs {
  margin-top: 32rpx;
  padding-top: 24rpx;
  border-top: 1rpx solid var(--sh-line);
}
.subs__title {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-bottom: 12rpx;
}
.subs__row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8rpx 0;
}
.subs__name {
  font-size: 26rpx;
  color: var(--sh-ink);
}
.subs__amount {
  font-size: 26rpx;
  color: var(--sh-sub);
}
.cd {
  display: inline-block;
  margin-top: 28rpx;
  background: var(--sh-warning-tint);
  border-radius: 9999px;
  padding: 12rpx 28rpx;
}
.cd.is-expired {
  background: var(--sh-danger-tint);
}
.cd__text {
  font-size: 24rpx;
  color: var(--sh-warning);
  font-weight: 600;
}
.cd.is-expired .cd__text {
  color: var(--sh-danger);
}
.block {
  margin-top: 20rpx;
}
.method {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 24rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
}
.method.is-on {
  background: var(--sh-primary-tint);
}
.method__icon {
  font-size: 40rpx;
}
.method__name {
  flex: 1;
  font-size: 28rpx;
  color: var(--sh-ink);
  font-weight: 600;
}
.method__tick {
  font-size: 30rpx;
  color: var(--sh-primary-text);
  font-weight: 400;
}
.done {
  text-align: center;
  padding-top: 56rpx;
  padding-bottom: 48rpx;
}
.done__icon {
  display: block;
  width: 104rpx;
  height: 104rpx;
  line-height: 104rpx;
  margin: 0 auto;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 52rpx;
  font-weight: 700;
}
.done__title {
  display: block;
  font-size: 40rpx;
  font-weight: 600;
  color: var(--sh-ink);
  margin-top: 28rpx;
}
.done__hint {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
  margin-top: 16rpx;
}
.code {
  margin-top: 36rpx;
  background: var(--sh-primary-tint);
  border-radius: 32rpx;
  padding: 28rpx;
}
.code--redeem {
  background: var(--sh-warning-tint);
}
.code__label {
  display: block;
  font-size: 24rpx;
  color: var(--sh-primary-text);
}
.code--redeem .code__label {
  color: var(--sh-warning);
}
.code__v {
  display: block;
  font-size: 48rpx;
  font-weight: 600;
  letter-spacing: 6rpx;
  color: var(--sh-ink);
  margin-top: 12rpx;
}
.cancel {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 24rpx;
}
</style>
