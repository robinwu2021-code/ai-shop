<script setup lang="ts">
/*
 * 社区集单的期（原型 s31）。**一行是一期，不是一个活动** —— 与团列表同一种卡：
 * 名称 + 状态 / 一行信息 / 一行指标。卡内不放按钮，处理在一期的详情里。
 *
 * 没有「新建」：期是集单活动每天自动开出来的，入口在「活动」。所以这一页没有底部操作栏。
 */
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import type { BatchPeriod } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const TABS = [
  { key: "OPEN", label: String(t("periods.tab.OPEN")) },
  { key: "SHORT", label: String(t("periods.tab.SHORT")) },
  { key: "CONFIRMED", label: String(t("periods.tab.CONFIRMED")) },
  { key: "CANCELLED", label: String(t("periods.tab.CANCELLED")) },
] as const;
const tab = ref<string>("OPEN");

const list = ref<BatchPeriod[]>([]);
const loaded = ref(false);
const failed = ref(false);

async function load() {
  try {
    list.value = await api.mPeriods();
    failed.value = false;
    // 有待处理的期时直接落在那一栏：它有截止时间，过了就自动取消退款
    if (!loaded.value && list.value.some((p) => p.status === "SHORT")) tab.value = "SHORT";
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

const shown = computed(() => list.value.filter((p) => p.status === tab.value));

function hhmm(ms: number): string {
  const d = new Date(ms);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

function md(day: string): string {
  return day.slice(5);
}

/** 绿 = 收单中或已成；黄 = 未达起订待处理；灰 = 已取消 */
function chipCls(p: BatchPeriod): string {
  if (p.status === "SHORT") return "sh-chip--warning";
  if (p.status === "OPEN" || p.status === "CONFIRMED") return "sh-chip--success";
  return "";
}

/** 进度条的分母：有起订量看起订量（够不够），否则看每期上限（满没满） */
function barOf(p: BatchPeriod): { pct: number; text: string } | null {
  if (p.minQty) {
    return { pct: Math.min(100, Math.round((p.qty / p.minQty) * 100)),
      text: String(t("periods.minOf", { q: p.qty, m: p.minQty })) };
  }
  if (p.periodQuota) {
    return { pct: Math.min(100, Math.round((p.qty / p.periodQuota) * 100)),
      text: String(t("periods.quotaOf", { q: p.qty, m: p.periodQuota })) };
  }
  return null;
}

function open(p: BatchPeriod) {
  uni.navigateTo({ url: `${ROUTES.period}?periodNo=${p.periodNo}` });
}

onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold title-key="periods.title" :denied="!merchant.can('biz:campaign')" :failed="failed" @retry="load">
    <sh-tabs :items="TABS" :active="tab" @change="tab = $event"></sh-tabs>

    <view v-for="p in shown" :key="p.periodNo" class="sh-card card" @tap="open(p)">
      <view class="sh-row sh-row--between">
        <text class="txt-strong">{{ $t("periods.item", { d: md(p.periodDate), name: p.activityName || "" }) }}</text>
        <text class="sh-chip" :class="chipCls(p)">{{ $t(`periods.status.${p.status}`) }}</text>
      </view>
      <text class="txt-sub sh-muted card__meta sh-num">
        {{ $t("periods.meta", { t: hhmm(p.cutoffAt), p: md(p.pickupDate) }) }}
      </text>
      <view class="sh-row card__metric">
        <template v-if="barOf(p)">
          <view class="bar sh-fill"><view class="bar__in" :style="{ width: barOf(p)!.pct + '%' }"></view></view>
          <text class="txt-caption sh-muted sh-num">{{ barOf(p)!.text }}</text>
        </template>
        <text v-else class="txt-caption sh-muted sh-num">{{ $t("periods.qty", { q: p.qty }) }}</text>
      </view>
    </view>

    <sh-empty
      v-if="!shown.length"
      :pending="!loaded"
      :text="String($t('periods.empty'))"
      :tip="String($t('periods.emptyTip'))"
    ></sh-empty>
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
