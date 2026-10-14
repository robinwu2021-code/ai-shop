<script setup lang="ts">
/*
 * 发出去的（原型 m19）：消息与券两种批次合在一张列表里，新的在前。
 *
 * 消息：发出 · 来了 · 成单（满 7 天「已统计」，没满「统计中」—— 数字还会涨，
 * 不写的话商家第二天看到 2 单就判定这次失败了）。
 * 券：发出 · 已用 · 金额。券只是放进券包、不推送，所以没有「来了」，按已用算。
 * 进度条是成单率 / 使用率，不是发送进度。
 */
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { audienceLabel } from "@/shared/audience";
import { money } from "@shared/utils/money";
import type { CouponIssueBatch, MemberSegment, MemberTag, MerchantCoupon, ReachTask } from "@shared/types";

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));
const merchant = useMerchantStore();

const TABS = [
  { key: "ALL", label: tt("reachTasks.tab.ALL") },
  { key: "MSG", label: tt("reachTasks.tab.MSG") },
  { key: "COUPON", label: tt("reachTasks.tab.COUPON") },
] as const;
const tab = ref<string>("ALL");

const tasks = ref<ReachTask[]>([]);
const issues = ref<CouponIssueBatch[]>([]);
const coupons = ref<MerchantCoupon[]>([]);
const tags = ref<MemberTag[]>([]);
const segments = ref<MemberSegment[]>([]);
const loaded = ref(false);
const failed = ref(false);

async function load() {
  try {
    const [ts, is, cs, tg, sg] = await Promise.all([
      api.mReachTasks(),
      api.mCouponIssues(),
      api.mCoupons(true),
      api.mMemberTags().catch(() => []),
      api.mMemberSegments().catch(() => []),
    ]);
    tasks.value = ts;
    issues.value = is;
    coupons.value = cs;
    tags.value = tg;
    segments.value = sg;
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

interface Row {
  key: string;
  at: number;
  title: string;
  meta: string;
  chip: { text: string; cls: string };
  line: string;
  /** 成单率 / 使用率，0–100 */
  rate: number;
  url: string;
}

function pct(n: number, d: number) {
  return d > 0 ? Math.min(100, Math.round((n * 100) / d)) : 0;
}

function md(ts: number) {
  const d = new Date(ts);
  return `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function taskRow(x: ReachTask): Row {
  return {
    key: x.taskNo,
    at: x.sentAt,
    title: x.title,
    meta: tt("reachTasks.msgMeta", { s: tt(`reach.scene.${x.scene}`), a: x.audienceDesc, d: md(x.sentAt) }),
    chip: x.settled
      ? { text: tt("reachTasks.settled"), cls: "" }
      : { text: tt("reachTasks.counting"), cls: "sh-chip--primary" },
    line: tt("reachTasks.msgLine", { s: x.sent, o: x.opened, r: x.ordered }),
    rate: pct(x.ordered, x.sent),
    url: `${ROUTES.reachTask}?taskNo=${x.taskNo}`,
  };
}

function issueRow(b: CouponIssueBatch): Row {
  const c = coupons.value.find((x) => x.couponNo === b.couponNo);
  const used = b.usedCount ?? 0;
  const whom = b.audiences?.length
    ? audienceLabel(b.audiences, { tags: tags.value, segments: segments.value }, tt)
    : tt("couponSend.preset.ALL");
  return {
    key: b.issueNo,
    at: b.issuedAt,
    title: c?.title || b.couponNo,
    meta: tt("reachTasks.couponMeta", { a: whom, d: md(b.issuedAt) }),
    chip: c && c.status === "ACTIVE"
      ? { text: tt("reachTasks.couponLive"), cls: "sh-chip--success" }
      : { text: tt("reachTasks.couponEnded"), cls: "" },
    line: tt("reachTasks.couponLine", { s: b.issued, u: used, m: money(b.usedAmountMinor ?? 0) }),
    rate: pct(used, b.issued),
    url: `${ROUTES.couponIssues}?issueNo=${b.issueNo}&couponNo=${b.couponNo}`,
  };
}

const rows = computed<Row[]>(() => {
  const out: Row[] = [];
  if (tab.value !== "COUPON") out.push(...tasks.value.map(taskRow));
  if (tab.value !== "MSG") out.push(...issues.value.map(issueRow));
  return out.sort((a, b) => b.at - a.at);
});

function open(r: Row) {
  uni.navigateTo({ url: r.url });
}

onShow(async () => {
  await merchant.ensureStores().catch(() => null);
  if (merchant.can("biz:customer")) void load();
});
</script>

<template>
  <sh-scaffold title-key="reachTasks.title" :denied="!merchant.can('biz:customer')" :failed="failed" @retry="load">
    <sh-tabs :items="TABS" :active="tab" @change="tab = $event"></sh-tabs>

    <view v-for="r in rows" :key="r.key" class="sh-card card" @tap="open(r)">
      <view class="sh-row sh-row--between">
        <text class="txt-strong">{{ r.title }}</text>
        <text class="sh-chip" :class="r.chip.cls">{{ r.chip.text }}</text>
      </view>
      <text class="txt-sub sh-muted card__meta">{{ r.meta }}</text>
      <view class="sh-row card__metric">
        <view class="bar sh-fill"><view class="bar__in" :style="{ width: r.rate + '%' }"></view></view>
        <text class="txt-caption sh-muted sh-num">{{ r.line }}</text>
      </view>
    </view>

    <sh-empty
      v-if="!rows.length"
      :pending="!loaded"
      :text="tt('reachTasks.empty')"
      :tip="tt('reachTasks.emptyTip')"
    ></sh-empty>
  </sh-scaffold>
</template>

<style scoped>
.card__meta {
  display: block;
  margin-top: 8rpx;
}
.card__metric {
  gap: 16rpx;
  margin-top: 16rpx;
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
