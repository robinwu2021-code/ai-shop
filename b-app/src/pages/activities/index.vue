<script setup lang="ts">
/*
 * 活动列表（原型 s02 · 约定：顶部分栏筛状态；卡片三行 —— 名称 + 状态 / 一行信息 / 一行指标；
 * 卡内不放按钮，操作都在详情页；新建在底部操作栏）。
 *
 * 「进行中」与「现在真的在减」分开：周期活动在非时段里 status 仍是 RUNNING，
 * 它的状态标签写「不在时段」而不是「进行中」—— 商家问的是顾客现在下单减不减。
 *
 * 玩法**不做标签**，写在信息行里：标签只表达状态，颜色不和状态抢。
 */
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import { playOfActivity } from "@shared/utils/play-templates";
import type { StoreActivity } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const TABS = [
  { key: "live", label: String(t("activities.tab.live")) },
  { key: "pending", label: String(t("activities.tab.pending")) },
  { key: "paused", label: String(t("activities.tab.paused")) },
  { key: "ended", label: String(t("activities.tab.ended")) },
] as const;
const tab = ref<string>("live");

const list = ref<StoreActivity[]>([]);
const loaded = ref(false);
const failed = ref(false);

async function load() {
  try {
    list.value = await api.mActivities(true);
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

/** 未开始：状态是进行中，但开始时刻还在未来 */
function isPending(a: StoreActivity): boolean {
  return a.status === "RUNNING" && !!a.startAt && a.startAt > Date.now();
}

function tabOf(a: StoreActivity): string {
  if (a.status === "ENDED") return "ended";
  if (a.status === "PAUSED") return "paused";
  return isPending(a) ? "pending" : "live";
}

const shown = computed(() => list.value.filter((a) => tabOf(a) === tab.value));

/** 状态标签：绿 = 进行中；黄 = 需要处理（长期未设上限）；灰 = 其它 */
function chipOf(a: StoreActivity): { text: string; cls: string } {
  if (a.status === "ENDED") {
    return { text: String(t(`activities.endedReason.${a.endedReason || "MANUAL"}`)), cls: "" };
  }
  if (a.status === "PAUSED") return { text: String(t("activities.status.paused")), cls: "" };
  if (isPending(a)) return { text: String(t("activities.status.pending")), cls: "" };
  if (a.scheduleType === "ALWAYS_ON" && a.quota == null && !a.budgetMinor) {
    return { text: String(t("activities.status.uncapped")), cls: "sh-chip--warning" };
  }
  if (!a.liveNow) return { text: String(t("activities.status.idle")), cls: "" };
  return { text: String(t("activities.status.live")), cls: "sh-chip--success" };
}

function hhmm(s?: string | null): string {
  return s || "";
}

function day(ms?: number | null): string {
  if (!ms) return "";
  const d = new Date(ms);
  return `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/** 信息行：玩法 · 时间 */
function metaOf(a: StoreActivity): string {
  const play = playOfActivity(a);
  const name = play ? String(t(`plays.name.${play.key}`)) : ruleText(a);
  return `${name} · ${scheduleText(a)}`;
}

function scheduleText(a: StoreActivity): string {
  if (a.triggerType === "CUTOFF") return String(t("activities.batchDaily", { t: hhmm(a.cutoffTime) }));
  if (a.scheduleType === "ALWAYS_ON") return String(t("activities.always"));
  if (a.scheduleType === "RECURRING") {
    try {
      const r = JSON.parse(a.scheduleRule || "{}") as { weekdays?: number[]; from?: string; to?: string };
      const d = (r.weekdays ?? []).length === 7 || !(r.weekdays ?? []).length
        ? String(t("activities.everyday"))
        : (r.weekdays ?? []).map((w) => String(t(`activities.weekday.${w}`))).join("、");
      return String(t("activities.recurring", { d, f: r.from ?? "", e: r.to ?? "" }));
    } catch {
      return String(t("activities.recurringBad"));
    }
  }
  return String(t("activities.range", { s: day(a.startAt), e: day(a.endAt) }));
}

/** 认不出玩法时的兜底一句话 */
function ruleText(a: StoreActivity): string {
  const m = money(a.benefitAmountMinor ?? 0);
  if (a.benefitType === "CUT") {
    if (a.triggerType === "QTY") return String(t("activities.ruleCutQty", { n: a.triggerQty ?? 0, m }));
    if (a.triggerType === "AMOUNT") return String(t("activities.ruleCut", { n: money(a.triggerAmountMinor ?? 0), m }));
    return String(t("activities.ruleCutAny", { m }));
  }
  if (a.triggerType === "GROUP") return String(t("activities.ruleGroup", { n: a.triggerQty ?? 0, m }));
  if (a.triggerType === "CUTOFF") return String(t("activities.ruleBatch", { m }));
  if (a.benefitType === "PRICE") return String(t("activities.rulePrice", { n: m }));
  if (a.benefitType === "GIFT") return String(t("activities.ruleGift", { n: a.triggerQty ?? 0, m: a.benefitQty ?? 0 }));
  return String(t("activities.ruleCoupon"));
}

/** 指标行：有份数上限时画进度，没有时给已用次数与已让利 */
function progressOf(a: StoreActivity): number {
  if (!a.quota) return 0;
  return Math.min(100, Math.round((a.quotaUsed / a.quota) * 100));
}

function go(url: string) {
  uni.navigateTo({ url });
}

onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold title-key="activities.title" :denied="!merchant.can('biz:campaign')" :failed="failed" @retry="load">
    <sh-tabs :items="TABS" :active="tab" @change="tab = $event"></sh-tabs>

    <view
      v-for="a in shown"
      :key="a.activityNo"
      class="sh-card card"
      @tap="go(`/pages/activity-edit/index?activityNo=${a.activityNo}`)"
    >
      <view class="sh-row sh-row--between">
        <text class="txt-strong">{{ a.name }}</text>
        <text class="sh-chip" :class="chipOf(a).cls">{{ chipOf(a).text }}</text>
      </view>
      <text class="txt-sub sh-muted card__meta">{{ metaOf(a) }}</text>
      <view class="sh-row card__metric">
        <template v-if="a.quota">
          <view class="bar sh-fill"><view class="bar__in" :style="{ width: progressOf(a) + '%' }"></view></view>
          <text class="txt-caption sh-muted sh-num">{{ $t("activities.usedOf", { u: a.quotaUsed, q: a.quota }) }}</text>
        </template>
        <text v-else class="txt-caption sh-muted sh-num">
          {{ $t("activities.usedTimes", { n: a.quotaUsed, m: money(a.budgetUsedMinor) }) }}
        </text>
      </view>
    </view>

    <sh-empty
      v-if="!shown.length"
      :pending="!loaded"
      :text="String($t('activities.empty'))"
      :tip="String($t('activities.emptyTip'))"
    ></sh-empty>

    <sh-actionbar>
      <view class="sh-btn" @tap="go('/pages/activity-edit/index')">{{ $t("activities.new") }}</view>
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
