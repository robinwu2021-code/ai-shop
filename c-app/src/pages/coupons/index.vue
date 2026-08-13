<script setup lang="ts">
// 领券中心 + 我的券包（同一页两 tab）。
// 不做两个页面：券的信息结构完全一样，只是「领没领」不同；
// 分成两页会让用户领完券还要自己找到另一个入口去看。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useI18n } from "vue-i18n";
import { isoDate, money } from "@shared/utils/format";
import type { Coupon } from "@shared/types";

const { t } = useI18n();
const tab = ref<"center" | "mine">("center");
const coupons = ref<Coupon[]>([]);
const busy = ref("");

const now = Date.now();
const available = computed(() => coupons.value.filter((c) => !c.received && c.endAt > now));

/**
 * 券面上的那个大字。**两种券要分开写** ——
 * 满减券是「减 5 元」，折扣券是「八五折」，一个金额字段表达不了后者。
 * 契约此前只有一个 `discountMinor`，于是折扣券要么显示成 ¥0，要么显示成面额。
 */
function faceText(c: Coupon): string {
  return c.type === "DISCOUNT"
    ? String(t("coupon.rate", { n: (c.discountRate / 1000).toFixed(1) }))
    : money(c.faceMinor);
}
const mine = computed(() => coupons.value.filter((c) => c.received));
const shown = computed(() => (tab.value === "center" ? available.value : mine.value));

async function load() {
  coupons.value = await api.couponList();
}

async function receive(c: Coupon) {
  if (c.received || busy.value) return;
  busy.value = c.couponNo;
  try {
    await api.receiveCoupon(c.couponNo);
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = "";
  }
}

function expired(c: Coupon) {
  return c.endAt <= now;
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="coupon.title">
    <sh-tabs
      :items="[
        { key: 'center', label: String($t('coupon.center', { n: available.length })) },
        { key: 'mine', label: String($t('coupon.mine', { n: mine.length })) },
      ]"
      :active="tab"
      @change="(k: string) => (tab = k as typeof tab)"
    ></sh-tabs>

    <!-- 券的形状：左边金额、右边规则，中间用色块分隔而不是虚线（扁平风） -->
    <view
      v-for="c in shown"
      :key="c.couponNo"
      class="ticket"
      :class="{ 'is-expired': expired(c) }"
    >
      <view class="ticket__amount">
        <text class="ticket__v sh-num">{{ faceText(c) }}</text>
        <text class="ticket__cond sh-num">
          {{ c.thresholdMinor
            ? $t("coupon.threshold", { p: money(c.thresholdMinor) })
            : $t("coupon.noThreshold") }}
        </text>
      </view>

      <view class="ticket__main">
        <text class="ticket__name">{{ c.title }}</text>
        <text class="ticket__scope">{{ c.scopeDesc }}</text>
        <text class="ticket__exp sh-num">{{ $t("coupon.until", { d: isoDate(c.endAt) }) }}</text>
      </view>

      <view
        v-if="!c.received"
        class="ticket__btn"
        :class="{ 'is-busy': busy === c.couponNo }"
        @tap="receive(c)"
      >
        {{ $t("coupon.receive") }}
      </view>
      <text v-else-if="expired(c)" class="ticket__state">{{ $t("coupon.expired") }}</text>
      <text v-else class="ticket__state ticket__state--ok">{{ $t("coupon.got") }}</text>
    </view>

    <sh-empty bare v-if="!shown.length" :text='tab === "center" ? $t("coupon.centerEmpty") : $t("coupon.mineEmpty")'></sh-empty>
  </sh-scaffold>
</template>

<style scoped>
.ticket {
  display: flex;
  align-items: center;
  gap: 24rpx;
  background: var(--sh-surface);
  border-radius: 32rpx;
  padding: 28rpx;
  margin-bottom: 20rpx;
}
.ticket.is-expired {
  opacity: 0.5;
}
.ticket__amount {
  flex: 0 0 auto;
  min-width: 150rpx;
  background: var(--sh-danger-tint);
  border-radius: 24rpx;
  padding: 22rpx 16rpx;
  text-align: center;
}
.ticket__v {
  display: block;
  font-size: 40rpx;
  font-weight: 600;
  color: var(--sh-danger);
  line-height: 1.1;
}
.ticket__cond {
  display: block;
  font-size: 24rpx;
  color: var(--sh-danger);
  margin-top: 6rpx;
}
.ticket__main {
  flex: 1;
  min-width: 0;
}
.ticket__name {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.ticket__scope {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 8rpx;
}
.ticket__exp {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 10rpx;
}
.ticket__btn {
  flex: 0 0 auto;
  padding: 16rpx 32rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 24rpx;
  font-weight: 600;
}
.ticket__btn.is-busy {
  opacity: 0.5;
}
.ticket__state {
  flex: 0 0 auto;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.ticket__state--ok {
  color: var(--sh-primary);
}
</style>
