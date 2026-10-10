<script setup lang="ts">
/*
 * 出示券码（原型 s25）。店员照着这一屏在核销页（B 端 s17）输码或扫码。
 * **码和剩余次数放最上面**：顾客是隔着柜台把手机递过去的，店员一眼要看清这两样。
 * 码四位一组显示（码里去掉了 0/O/1/I/L，照着屏幕手输不会认错）。
 */
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { MyStoreCoupon } from "@shared/types";

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));

const userCouponNo = ref("");
const c = ref<MyStoreCoupon | null>(null);
const failed = ref(false);

async function load() {
  try {
    const all = await api.myStoreCoupons();
    c.value = all.find((x) => x.userCouponNo === userCouponNo.value) ?? null;
    failed.value = !c.value;
    if (c.value) uni.setNavigationBarTitle({ title: c.value.title });
  } catch {
    failed.value = true;
  }
}

/** 「84215937」→「8421 5937」 */
const code = computed(() => (c.value?.redeemCode ?? "").replace(/(.{4})(?=.)/g, "$1 "));

function md(ms: number): string {
  const d = new Date(ms);
  return `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

onLoad((q) => {
  userCouponNo.value = String(q?.userCouponNo ?? "");
});
onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold title-key="coupon.codeTitle" :pending="!c && !failed" :failed="failed" @retry="load">
    <template v-if="c">
      <view class="hero">
        <text class="txt-display sh-num">
          {{ c.timesTotal > 1 ? tt("coupon.state.left", { n: c.remaining }) : c.benefitText }}
        </text>
        <text class="txt-sub sh-muted hero__sub sh-num">
          <template v-if="c.timesTotal > 1">{{ $t("coupon.totalTimes", { n: c.timesTotal }) }} · </template>{{ $t("coupon.until", { d: md(c.expireAt) }) }}
        </text>
      </view>

      <view class="sh-card code">
        <text class="txt-hero sh-num code__v">{{ code }}</text>
      </view>

      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("coupon.shop") }}</text>
          <text class="txt-body">{{ c.merchantName || "—" }}</text>
        </view>
      </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16rpx 0 8rpx;
}
.hero__sub {
  margin-top: 8rpx;
}
.code {
  display: flex;
  justify-content: center;
}
.code__v {
  letter-spacing: 4rpx;
}
</style>
