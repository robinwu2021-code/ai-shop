<script setup lang="ts">
// 领券条（原型 s36）：一行摆出前两张券的规则，点开是领券面板。
//
// 商品页、店铺页、商家页共用这一份（优惠券全链路梳理 批 1）。此前只有商品页有，
// 而且读的是老券表 —— 商家在 B 端建的券顾客在哪都领不到。后端合流后三处同一个入口。
//
// 自己取数、取不到就整条不出：领券是锦上添花，不能拖垮所在的页面。
// 状态只说一个字 —— 能领是「领取」，领过是灰字「已领」；不做成按钮，避免一屏一排红胶囊。
import { ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { money } from "@shared/utils/format";
import type { Coupon } from "@shared/types";

/**
 * @param merchantNo 这家店。券 = 本店的 + 平台出资的
 * @param preset     调用方已经取好、过滤好的券。**商品页用它**：那一页把券与详情并发取，
 *                   等齐了才首次渲染 —— 组件自己晚 200ms 取回来的话，这一行会把整页往下顶一下
 *                   （商品页注释里记着那 61px）。不传则组件自己取。
 */
const props = defineProps<{ merchantNo: string; preset?: Coupon[] }>();

const { t } = useI18n();
const coupons = ref<Coupon[]>([]);
const open = ref(false);
const claiming = ref("");

async function load(no: string) {
  if (!no) {
    coupons.value = [];
    return;
  }
  try {
    const all = await api.couponList();
    coupons.value = all.filter((c) => c.endAt > Date.now()
      && (c.merchantNo === no || c.funder === "PLATFORM"));
  } catch {
    coupons.value = [];
  }
}
watch(() => [props.merchantNo, props.preset] as const, ([no, preset]) => {
  if (preset) coupons.value = preset;
  else void load(no);
}, { immediate: true });

/** 「满 50 减 5」「9 折 · 封顶 ¥20」 */
function ruleText(c: Coupon): string {
  if (c.type === "DISCOUNT") {
    return String(t("goods.couponRate", { n: (c.discountRate / 1000).toFixed(1).replace(/\.0$/, ""),
      cap: money(c.maxDiscountMinor) }));
  }
  return c.thresholdMinor
    ? String(t("goods.couponCut", { m: money(c.thresholdMinor), n: money(c.faceMinor) }))
    : String(t("goods.couponCutAny", { n: money(c.faceMinor) }));
}

function untilText(c: Coupon): string {
  const d = new Date(c.endAt);
  return String(t("goods.couponUntil", {
    d: `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`,
  }));
}

async function claim(c: Coupon) {
  if (c.received || claiming.value) return;
  claiming.value = c.couponNo;
  try {
    await api.receiveCoupon(c.couponNo);
    c.received = true;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    claiming.value = "";
  }
}
</script>

<template>
  <view v-if="coupons.length" class="sh-card block cstrip">
    <view class="sh-row sh-row--divided cstrip__row" @tap="open = true">
      <text class="txt-sub sh-muted cstrip__label">{{ $t("goods.couponRow") }}</text>
      <view class="sh-fill sh-row cstrip__chips">
        <text v-for="c in coupons.slice(0, 2)" :key="c.couponNo" class="txt-caption sh-chip sh-chip--danger sh-num">
          {{ ruleText(c) }}
        </text>
      </view>
      <text class="txt-sub is-danger">{{ $t("goods.couponClaim") }}</text>
      <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
    </view>

    <sh-sheet :visible="open" :title="String($t('goods.couponRow'))" @close="open = false">
      <view class="sh-cells">
        <view v-for="c in coupons" :key="c.couponNo" class="sh-cell sh-row sh-row--between" @tap="claim(c)">
          <text class="txt-body sh-num">{{ ruleText(c) }} <text class="sh-muted">· {{ untilText(c) }}</text></text>
          <text class="txt-body" :class="c.received ? 'sh-muted' : 'txt-primary'">
            {{ c.received ? $t("goods.couponGot") : $t("goods.couponClaim") }}
          </text>
        </view>
      </view>
      <view class="sh-btn cstrip__done" @tap="open = false">{{ $t("goods.couponDone") }}</view>
    </sh-sheet>
  </view>
</template>

<style scoped>
/* 一行一件事，行高够一根手指（与商品页配送卡同一档） */
.cstrip__row {
  min-height: 56rpx;
  gap: 16rpx;
}
.cstrip__label {
  flex-shrink: 0;
  min-width: 72rpx;
}
.cstrip__chips {
  gap: 12rpx;
  overflow: hidden;
}
.cstrip__done {
  margin-top: 24rpx;
}
</style>
