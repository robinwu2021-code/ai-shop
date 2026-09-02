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
import type { PayMethodItem, PayMethodList } from "@/api/contract";
import { confirm } from "@ai-shop/ui/prompt";

const { t } = useI18n();

const order = ref<Order | null>(null);
const paying = ref(false);
/** 可用支付方式。null = 还没拉到 */
const methodList = ref<PayMethodList | null>(null);
/** 用户选中的通道。默认第一个可用的 */
const chosen = ref<string>("");
const now = ref(Date.now());
let timer: ReturnType<typeof setInterval> | undefined;

const paid = computed(() => !!order.value && order.value.status !== "WAIT_PAY");

/**
 * 能不能点「去支付」。
 *
 * <b>拉不到列表、或者商家还没进件（configured=false）时照常放行</b> ——
 * 只有「确实配过、而一种都不可用」才拦。两者都是空列表，
 * 而端上要做的事正好相反。
 */
const canPay = computed(() => {
  const list = methodList.value;
  if (!list || !list.configured) return true;
  return list.methods.some((m) => m.available);
});

/** 拦住时要说明原因，别只给一个灰按钮 */
const blockedReason = computed(() => {
  const list = methodList.value;
  if (!list || !list.configured || list.methods.some((m) => m.available)) return "";
  return String(t("pay.noUsableMethod"));
});
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
  if (order.value?.status === "WAIT_PAY") {
    await loadMethods(orderNo);
  }
}

/** 拉可用支付方式，并把默认选中放在第一个可用的上 */
async function loadMethods(orderNo: string) {
  try {
    const list = await api.payMethods(orderNo);
    methodList.value = list;
    chosen.value = list.methods.find((m) => m.available)?.payChannel ?? "";
  } catch {
    /*
     * 拉不到就当作「未配置」放行 —— 与后端 configured=false 同一条口径。
     * 拦住的话，一次网络抖动会让用户付不了一个完全正常的单。
     */
    methodList.value = null;
  }
}

async function pay() {
  const o = order.value;
  if (!o || paying.value || expired.value) return;
  paying.value = true;
  try {
    /*
     * **顺序：先向后端下单拿真参数，再唤起收银台。**
     *
     * 此前是反的 —— 先 requestPayment({}) 唤起（传的是空对象），
     * 再调 payOrder。那样端上唤起的是一个没有任何通道参数的收银台，
     * 而 mock 下它「成功」了，于是这条链看起来是通的。
     * 真通道上它一定失败，且失败在用户面前。
     */
    const init = await api.payOrder(o.orderNo, chosen.value || undefined);

    // 参数原样透传：不同通道字段完全不同，端上不该翻译成一套「统一格式」
    const res = await requestPayment(init.payParams);
    if (res.cancelled) {
      uni.showToast({ title: String(t("pay.cancelled")), icon: "none" });
      // 取消不是失败：单还在，用户可以换一种方式再来（后端会换新的商户单号）
      return;
    }
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
        <text class="txt-hero hero__amount sh-num">{{ money(order.amount.payableMinor) }}</text>
        <text class="txt-caption hero__label">{{ $t("pay.payable") }}</text>
        <!--
          这次付款覆盖哪几笔单。**只有跨商家时才出现** —— 单商家时它等于把
          总额又抄了一遍，是噪音。
          放在金额下面而不是折叠起来：用户在这一屏要回答的是「我付的是什么」，
          而拆单是这个问题里最容易意外的那部分。
        -->
        <view v-if="(order.subOrders?.length ?? 0) > 1" class="subs">
          <text class="txt-caption subs__title">{{ $t("pay.covers", { n: order.subOrders!.length }) }}</text>
          <view v-for="s in order.subOrders" :key="s.orderNo" class="subs__row sh-row sh-row--between">
            <text class="txt-sub subs__name txt-ink">{{ s.merchantName }}</text>
            <text class="txt-sub sh-num">{{ money(s.amount.payableMinor) }}</text>
          </view>
        </view>

        <view v-if="order.payDeadlineAt" class="cd" :class="{ 'is-expired': expired }">
          <text class="txt-bold cd__text sh-num is-warning">
            {{ expired
              ? $t("pay.expired")
              : $t("pay.remain", { t: countdown(order.payDeadlineAt - now) }) }}
          </text>
        </view>
      </view>

      <view class="sh-card block">
        <!--
          支付方式来自后端算好的交集，不再写死「微信支付」。
          不可用的也列出来并显示原因 —— 过滤掉的话用户会问
          「为什么别人有支付宝我没有」，而客服答不上来。
        -->
        <view
          v-for="m in methodList?.methods ?? []"
          :key="m.payChannel"
          class="method sh-row"
          :class="{ 'is-on': m.payChannel === chosen, 'is-off': !m.available }"
          @tap="m.available && (chosen = m.payChannel)"
        >
          <text class="method__icon">{{ m.payChannel === "ALIPAY" ? "💙" : "💚" }}</text>
          <view class="method__body">
            <text class="txt-strong method__name">{{ m.name || m.payChannel }}</text>
            <text v-if="!m.available && m.unavailableReason" class="txt-caption method__why">
              {{ m.unavailableReason }}
            </text>
          </view>
          <text v-if="m.payChannel === chosen" class="txt-body method__tick txt-primary">✓</text>
        </view>

        <!-- 列表为空时的两种情况，文案不同：未进件是「照常可付」，无可用是「付不了」 -->
        <view v-if="!(methodList?.methods ?? []).length" class="method sh-row">
          <text class="txt-body method__name">{{ $t("pay.methodFallback") }}</text>
        </view>
      </view>

      <text v-if="blockedReason" class="txt-caption block-reason">{{ blockedReason }}</text>

      <sh-actionbar class="bar-center" :pad="220">
        <view class="sh-btn" :class="{ 'is-disabled': paying || expired || !canPay }" @tap="pay">
          {{ paying ? $t("pay.paying") : $t("pay.payNow") }}
        </view>
        <text class="txt-caption cancel" @tap="cancel">{{ $t("pay.cancel") }}</text>
      </sh-actionbar>
    </template>

    <!-- 支付完成 -->
    <template v-else>
      <view class="sh-card done">
        <text class="done__icon">✓</text>
        <text class="txt-display done__title">{{ $t("pay.done") }}</text>
        <text class="txt-caption done__hint">{{ $t(doneHintKey) }}</text>

        <!-- 各类码：自提码 / 核销码 / 兑换码 -->
        <view v-if="order.verifyCode" class="code">
          <text class="txt-caption code__label">{{ $t("pay.verifyCode") }}</text>
          <text class="txt-hero code__v sh-num">{{ order.verifyCode }}</text>
        </view>
        <view v-if="order.redeemCode" class="code code--redeem">
          <text class="txt-caption code__label">{{ $t("pay.redeemCode") }}</text>
          <text class="txt-hero code__v sh-num">{{ order.redeemCode }}</text>
        </view>
      </view>

      <sh-actionbar class="bar-center" :pad="220">
        <view class="sh-btn" @tap="gotoOrder">{{ $t("pay.viewOrder") }}</view>
        <text class="txt-caption cancel" @tap="gotoHome">{{ $t("pay.keepShopping") }}</text>
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
}
.hero__label {
  display: block;
  margin-top: 12rpx;
}
.subs {
  margin-top: 32rpx;
  padding-top: 24rpx;
  border-top: 1rpx solid var(--sh-line);
}
.subs__title {
  display: block;
  margin-bottom: 12rpx;
}
.subs__row {
  padding: 8rpx 0;
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
.cd.is-expired .cd__text {
  color: var(--sh-danger);
}
.block {
  margin-top: 20rpx;
}
.method {
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
/*
 * 名称与「为什么不可用」竖排。
 *
 * 挤在一行的话读起来是「支付宝本单中有店铺尚未开通这种收款方式」——
 * 一句话，而它其实是两条信息。截图里一眼就看出来了。
 */
.method__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4rpx;
}
.method__name {
  flex: 1;
}
.method__why {
  color: var(--sh-sub);
}
/* 不可用的整块压暗，让「能点的是哪个」不用读文字就看得出来 */
.method.is-off {
  opacity: 0.55;
}
.block-reason {
  display: block;
  padding: 0 32rpx;
  color: var(--sh-warning);
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
  margin-top: 28rpx;
}
.done__hint {
  display: block;
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
  color: var(--sh-primary-text);
}
.code--redeem .code__label {
  color: var(--sh-warning);
}
.code__v {
  display: block;
  letter-spacing: 6rpx;
  margin-top: 12rpx;
}
.cancel {
  display: block;
  margin-top: 24rpx;
}
</style>
