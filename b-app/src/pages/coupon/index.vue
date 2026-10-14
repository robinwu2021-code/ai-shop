<script setup lang="ts">
/*
 * 券详情（原型 s15）。三个数（已领 / 已用 / 已支出）→ 只读信息列表 → 底部「停发 / 发放」。
 * 操作从列表卡挪到这里：卡上放按钮的话，一屏是一排红胶囊，而最常点的只是「看一眼」。
 *
 * 停发的确认框要说清「已领的照常可用」—— 不说的话，商家会以为停发等于作废，
 * 然后去跟已经领了券的顾客解释一件没发生的事。
 */
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { confirm } from "@ai-shop/ui/prompt";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import { couponKind, couponQuantity, couponRule, couponThreshold, couponValidity } from "@/shared/coupon-text";
import type { CouponIssueBatch, MerchantCoupon } from "@shared/types";

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));
const merchant = useMerchantStore();

const couponNo = ref("");
const c = ref<MerchantCoupon | null>(null);
const issues = ref<CouponIssueBatch[]>([]);
const failed = ref(false);
const busy = ref(false);

async function load() {
  if (!couponNo.value) return;
  try {
    const [one, batches] = await Promise.all([api.mCoupon(couponNo.value), api.mCouponIssues(couponNo.value)]);
    c.value = one;
    issues.value = batches;
    failed.value = false;
  } catch {
    failed.value = true;
  }
}

const typeText = computed(() => {
  if (!c.value) return "";
  return `${tt(`couponText.kind.${couponKind(c.value)}`)} ${couponRule(tt, c.value)}`;
});

async function setStatus(next: "ACTIVE" | "PAUSED") {
  if (!c.value || busy.value) return;
  if (next === "PAUSED") {
    const ok = await confirm({
      title: tt("coupon.pauseTitle"),
      hint: tt("coupon.pauseBody", { n: c.value.receivedCount }),
    });
    if (!ok) return;
  }
  busy.value = true;
  try {
    c.value = await api.mSetCouponStatus(c.value.couponNo, next);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

function go(url: string) {
  uni.navigateTo({ url });
}

onLoad((q) => {
  couponNo.value = String(q?.couponNo ?? "");
});
onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold title-key="coupon.title" :denied="!merchant.can('biz:campaign')" :failed="failed" @retry="load">
    <template v-if="c">
      <text class="txt-title name">{{ c.title }}</text>
      <view class="sh-card">
        <sh-stat :items="[
          { value: c.receivedCount, label: tt('coupon.received') },
          { value: c.usedTimes, label: tt('coupon.used') },
          { value: money(c.spentMinor), label: tt('coupon.spent') },
        ]"></sh-stat>
      </view>

      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("coupon.status") }}</text>
          <text class="txt-body">{{ $t(`coupons.status.${c.status}`) }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("coupon.type") }}</text>
          <text class="txt-body sh-num">{{ typeText }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("coupon.threshold") }}</text>
          <text class="txt-body sh-num">{{ couponThreshold(tt, c) }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("coupon.validity") }}</text>
          <text class="txt-body sh-num">{{ couponValidity(tt, c) }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("coupon.quantity") }}</text>
          <text class="txt-body sh-num">{{ couponQuantity(tt, c) }}</text>
        </view>
      </view>

      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between" @tap="go(`${ROUTES.couponIssues}?couponNo=${c.couponNo}`)">
          <text class="txt-body">{{ $t("coupon.issues") }}</text>
          <view class="sh-row">
            <text class="txt-body sh-muted sh-num">{{ $t("coupon.issuesN", { n: issues.length }) }}</text>
            <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
          </view>
        </view>
      </view>

      <sh-actionbar v-if="c.status !== 'ENDED'">
        <view class="sh-row bar">
          <view v-if="c.status === 'ACTIVE'" class="sh-btn sh-btn--muted sh-fill" :class="{ 'is-disabled': busy }"
                @tap="setStatus('PAUSED')">
            {{ $t("coupon.pause") }}
          </view>
          <view v-else class="sh-btn sh-btn--muted sh-fill" :class="{ 'is-disabled': busy }" @tap="setStatus('ACTIVE')">
            {{ $t("coupon.resume") }}
          </view>
          <view class="sh-btn bar__main" :class="{ 'is-disabled': c.status !== 'ACTIVE' }"
                @tap="c.status === 'ACTIVE' && go(`${ROUTES.couponSend}?couponNo=${c.couponNo}`)">
            {{ $t("coupon.send") }}
          </view>
        </view>
      </sh-actionbar>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.name {
  display: block;
  padding: 8rpx 8rpx 0;
}
.bar {
  gap: 16rpx;
  width: 100%;
}
.bar__main {
  flex: 2;
}
</style>
