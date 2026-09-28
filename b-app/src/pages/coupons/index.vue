<script setup lang="ts">
/*
 * 券（原型 s12）。与活动列表同一种卡：名称 + 状态 / 一行信息 / 一行指标；
 * 卡内不放按钮，停发与发放都在券详情里（s15）；新建在底部操作栏。
 *
 * 指标行：次卡的进度按**核销次数**算，不按张数 —— 次卡发出去一张，要紧的是还剩多少次没核；
 * 其余按领取张数。
 */
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { couponKind, couponProgress, couponRule, couponValidity } from "@/shared/coupon-text";
import type { MerchantCoupon } from "@shared/types";

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));
const merchant = useMerchantStore();

const TABS = [
  { key: "ACTIVE", label: tt("coupons.tab.ACTIVE") },
  { key: "PAUSED", label: tt("coupons.tab.PAUSED") },
  { key: "ENDED", label: tt("coupons.tab.ENDED") },
] as const;
const tab = ref<string>("ACTIVE");

const list = ref<MerchantCoupon[]>([]);
const loaded = ref(false);
const failed = ref(false);

async function load() {
  try {
    list.value = await api.mCoupons(true);
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

const shown = computed(() => list.value.filter((c) => c.status === tab.value));

/** 快发完：剩下不到一成。黄 = 该补货或该停了；绿 = 在发；灰 = 其它 */
function chipOf(c: MerchantCoupon): { text: string; cls: string } {
  if (c.status === "ACTIVE") {
    const low = c.totalCount != null && c.totalCount - c.receivedCount <= Math.max(1, c.totalCount / 10);
    return low
      ? { text: tt("coupons.status.LOW"), cls: "sh-chip--warning" }
      : { text: tt("coupons.status.ACTIVE"), cls: "sh-chip--success" };
  }
  return { text: tt(`coupons.status.${c.status}`), cls: "" };
}

/** 信息行：类型 · 规则或有效期（次卡带次数，折扣带封顶，现金带有效期） */
function metaOf(c: MerchantCoupon): string {
  // 「满减券 · 满 ¥50 减 ¥5 · 领后 7 天」：类型是第一个词 —— 商家扫列表先认类型
  return `${tt(`couponText.kind.${couponKind(c)}`)} · ${couponRule(tt, c)} · ${couponValidity(tt, c)}`;
}

function go(url: string) {
  uni.navigateTo({ url });
}

onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold title-key="coupons.title" :denied="!merchant.can('biz:campaign')" :failed="failed" @retry="load">
    <sh-tabs :items="TABS" :active="tab" @change="tab = $event"></sh-tabs>

    <view v-for="c in shown" :key="c.couponNo" class="sh-card card" @tap="go(`${ROUTES.coupon}?couponNo=${c.couponNo}`)">
      <view class="sh-row sh-row--between">
        <text class="txt-strong">{{ c.title }}</text>
        <text class="sh-chip" :class="chipOf(c).cls">{{ chipOf(c).text }}</text>
      </view>
      <text class="txt-sub sh-muted card__meta">{{ metaOf(c) }}</text>
      <view class="sh-row card__metric">
        <template v-if="couponProgress(tt, c).pct != null">
          <view class="bar sh-fill"><view class="bar__in" :style="{ width: couponProgress(tt, c).pct + '%' }"></view></view>
        </template>
        <text class="txt-caption sh-muted sh-num">{{ couponProgress(tt, c).text }}</text>
      </view>
    </view>

    <sh-empty
      v-if="!shown.length"
      :pending="!loaded"
      :text="tt('coupons.empty')"
      :tip="tt('coupons.emptyTip')"
    ></sh-empty>

    <sh-actionbar>
      <view class="sh-btn" @tap="go(ROUTES.couponEdit)">{{ $t("coupons.new") }}</view>
    </sh-actionbar>
  </sh-scaffold>
</template>

<style scoped>
.card__meta {
  display: block;
  margin-top: 8rpx;
}
.card__metric {
  margin-top: 12rpx;
  gap: 16rpx;
}
.bar {
  height: 8rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  overflow: hidden;
}
.bar__in {
  height: 100%;
  border-radius: 9999px;
  background: var(--sh-primary);
}
</style>
