<script setup lang="ts">
/*
 * 团（原型 s09）。**一行是一个团，不是一个活动** —— 与活动列表、集单列表同一种卡：
 * 名称 + 状态 / 一行信息 / 一行指标；卡内不放按钮，处理在团详情里。
 *
 * 指标行的进度条是「几人 / 成团人数」。开团在底部操作栏（s34）。
 *
 * 参团 = 买家带团号下单，付款成功才算一人；到期没凑齐、或商家散团，
 * 参团已付款的单自动全额退款 —— 这一页只看结果，不处理钱。
 */
import { computed, onUnmounted, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import type { GroupBuy } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const TABS = [
  { key: "OPEN", label: String(t("groups.tab.OPEN")) },
  { key: "FORMED", label: String(t("groups.tab.FORMED")) },
  { key: "FAILED", label: String(t("groups.tab.FAILED")) },
] as const;
const tab = ref<string>("OPEN");

const list = ref<GroupBuy[]>([]);
const loaded = ref(false);
const failed = ref(false);
const now = ref(Date.now());
const tick = setInterval(() => { now.value = Date.now(); }, 1000);
onUnmounted(() => clearInterval(tick));

async function load() {
  try {
    list.value = await api.mGroupList();
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

/** 待审的团归进「进行中」：对商家来说它们都是还没结果的团 */
function tabOf(g: GroupBuy): string {
  return g.status === "PENDING" ? "OPEN" : g.status;
}

const shown = computed(() => list.value.filter((g) => tabOf(g) === tab.value));

/** 黄 = 还差人；绿 = 已成团；灰 = 已散 / 待审 */
function chipOf(g: GroupBuy): { text: string; cls: string } {
  if (g.status === "FORMED") return { text: String(t("groups.status.FORMED")), cls: "sh-chip--success" };
  if (g.status === "FAILED") return { text: String(t("groups.status.FAILED")), cls: "" };
  if (g.status === "PENDING") return { text: String(t("groups.status.PENDING")), cls: "" };
  return { text: String(t("groups.need", { n: g.need })), cls: "sh-chip--warning" };
}

function metaOf(g: GroupBuy): string {
  const who = g.initiatorNickname
    ? String(t("groups.byBuyer", { name: g.initiatorNickname }))
    : String(t("groups.byMerchant"));
  return g.pickupName ? `${who} · ${g.pickupName}` : who;
}

function left(g: GroupBuy): string {
  const s = Math.max(0, Math.floor((g.expireAt - now.value) / 1000));
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(Math.floor(s / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`;
}

function pct(g: GroupBuy): number {
  return g.minCount ? Math.min(100, Math.round((g.joinedCount / g.minCount) * 100)) : 0;
}

function go(url: string) {
  uni.navigateTo({ url });
}

onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold title-key="groups.title" :denied="!merchant.can('biz:campaign')" :failed="failed" @retry="load">
    <sh-tabs :items="TABS" :active="tab" @change="tab = $event"></sh-tabs>

    <view v-for="g in shown" :key="g.groupNo" class="sh-card card" @tap="go(`${ROUTES.group}?groupNo=${g.groupNo}`)">
      <view class="sh-row sh-row--between">
        <text class="txt-strong">{{ g.title }}</text>
        <text class="sh-chip" :class="chipOf(g).cls">{{ chipOf(g).text }}</text>
      </view>
      <text class="txt-sub sh-muted card__meta">{{ metaOf(g) }}</text>
      <view class="sh-row card__metric">
        <view class="bar sh-fill"><view class="bar__in" :style="{ width: pct(g) + '%' }"></view></view>
        <text class="txt-caption sh-muted sh-num">
          {{ $t("groups.joinedOf", { n: g.joinedCount, m: g.minCount }) }}
          <template v-if="g.status === 'OPEN'"> · {{ $t("groups.left", { t: left(g) }) }}</template>
        </text>
      </view>
    </view>

    <sh-empty
      v-if="!shown.length"
      :pending="!loaded"
      :text="String($t('groups.empty'))"
      :tip="String($t('groups.emptyTip'))"
    ></sh-empty>

    <sh-actionbar>
      <view class="sh-btn" @tap="go(ROUTES.groupOpen)">{{ $t("groups.open") }}</view>
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
