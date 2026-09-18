<script setup lang="ts">
/*
 * 平台活动（原型 s27）。与活动列表同一种卡：名称 + 状态 / 一行信息。
 * **没有「新建」**：平台活动由平台发起，商家只能报名 —— 所以这一页没有底部操作栏。
 *
 * 信息行写「玩法 · 活动时间 · 平台补贴 50%」：商家决定报不报，看的就是这三样。
 * 状态格在「可报名」里写截止日，在「已报名」里写审核结果。
 */
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import type { PlatformActivity } from "@shared/types";

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));
const merchant = useMerchantStore();

const TABS = [
  { key: "ENROLLABLE", label: tt("platformActs.tab.ENROLLABLE") },
  { key: "ENROLLED", label: tt("platformActs.tab.ENROLLED") },
  { key: "ENDED", label: tt("platformActs.tab.ENDED") },
] as const;
const tab = ref<string>("ENROLLABLE");

const list = ref<PlatformActivity[]>([]);
const loaded = ref(false);
const failed = ref(false);

async function load() {
  try {
    list.value = await api.mPlatformActivities(tab.value);
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

function pick(k: string) {
  tab.value = k;
  loaded.value = false;
  void load();
}

const shown = computed(() => list.value);

function md(ms?: number | null): string {
  if (!ms) return "";
  const d = new Date(ms);
  return `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/** 「满 99 减 20」「立减 5」「满 3 件减 5」 */
function ruleOf(a: PlatformActivity): string {
  const cut = money(a.benefitAmountMinor ?? 0);
  if (a.triggerType === "AMOUNT") return tt("platformActs.ruleAmount", { m: money(a.triggerAmountMinor ?? 0), n: cut });
  if (a.triggerType === "QTY") return tt("platformActs.ruleQty", { m: a.triggerQty ?? 0, n: cut });
  return tt("platformActs.ruleAny", { n: cut });
}

function metaOf(a: PlatformActivity): string {
  const share = tt("platformActs.share", { p: a.platformShareBp / 100 });
  return `${ruleOf(a)} · ${md(a.startAt)} ${tt("platformActs.to")} ${md(a.endAt)} · ${share}`;
}

/** 状态格：可报名写截止日；已报名写审核结果（黄 = 待审，绿 = 已通过）；已结束写灰 */
function chipOf(a: PlatformActivity): { text: string; cls: string } {
  if (tab.value === "ENROLLED" && a.mine) {
    return a.mine.status === "APPROVED"
      ? { text: tt("platformActs.status.APPROVED"), cls: "sh-chip--success" }
      : { text: tt("platformActs.status.SUBMITTED"), cls: "sh-chip--warning" };
  }
  if (tab.value === "ENDED") return { text: tt("platformActs.ended"), cls: "" };
  return { text: tt("platformActs.deadline", { d: md(a.enrollDeadline) }), cls: "" };
}

function open(a: PlatformActivity) {
  uni.navigateTo({ url: `${ROUTES.platformApply}?activityNo=${a.activityNo}` });
}

onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold title-key="platformActs.title" :denied="!merchant.can('biz:campaign')" :failed="failed" @retry="load">
    <sh-tabs :items="TABS" :active="tab" @change="pick"></sh-tabs>

    <view v-for="a in shown" :key="a.activityNo" class="sh-card card" @tap="open(a)">
      <view class="sh-row sh-row--between">
        <text class="txt-strong">{{ a.name }}</text>
        <text class="sh-chip sh-num" :class="chipOf(a).cls">{{ chipOf(a).text }}</text>
      </view>
      <text class="txt-sub sh-muted card__meta sh-num">{{ metaOf(a) }}</text>
    </view>

    <sh-empty v-if="!shown.length" :pending="!loaded" :text="tt('platformActs.empty')"></sh-empty>
  </sh-scaffold>
</template>

<style scoped>
.card__meta {
  display: block;
  margin-top: 8rpx;
}
</style>
