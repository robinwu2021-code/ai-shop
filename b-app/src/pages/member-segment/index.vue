<script setup lang="ts">
/*
 * 人群详情（原型 m11）：条件、此刻人数、用在哪。
 *
 * 顶上的两个数是**当场算**的（不是列表上的 lastCount）：人群存的是条件，名单每天在变。
 * 「用在哪」三类：活动、发过的券。进行中的活动按**发布那一刻**的条件生效（AC-9）——
 * 改这里的条件不影响它们，这一句要写在引用列表下面，否则商家改人群前会犹豫、或者改完以为活动跟着变了。
 */
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { setPendingAudience } from "@/shared/audience";
import { confirm, pick } from "@ai-shop/ui/prompt";
import { monthDay } from "@shared/utils/datetime";
import { money } from "@shared/utils/money";
import type { AudienceRef, MemberSegmentDetail, MemberTag } from "@shared/types";

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));
const merchant = useMerchantStore();

const segmentNo = ref("");
const data = ref<MemberSegmentDetail | null>(null);
const tags = ref<MemberTag[]>([]);
const failed = ref(false);

const sg = computed(() => data.value?.segment ?? null);
const usedCount = computed(() => (data.value?.activities.length ?? 0) + (data.value?.couponIssues.length ?? 0));

/** 条件逐条列出：存的是号，界面要名字 */
const conditions = computed(() => {
  const s = sg.value;
  if (!s) return [];
  const r = s.rule;
  const rows: Array<{ k: string; v: string }> = [];
  if (s.scopeStoreNo) rows.push({ k: tt("memberSegment.store"), v: merchant.stores.find((x) => x.storeNo === s.scopeStoreNo)?.name || s.scopeStoreNo });
  if (r.level) rows.push({ k: tt("memberSegment.level"), v: tt(`members.level.${r.level}`) });
  if (r.source) rows.push({ k: tt("memberSegment.source"), v: tt(`members.source.${r.source}`) });
  if (r.tagNos?.length) {
    rows.push({ k: tt("memberSegment.tags"), v: r.tagNos.map((no) => tags.value.find((x) => x.tagNo === no)?.name ?? no).join(" · ") });
  }
  if (r.lastOrderBefore) rows.push({ k: tt("memberSegment.lastBefore"), v: monthDay(r.lastOrderBefore) });
  if (r.lastOrderAfter) rows.push({ k: tt("memberSegment.lastAfter"), v: monthDay(r.lastOrderAfter) });
  if (r.spentMin != null) rows.push({ k: tt("memberSegment.spentMin"), v: money(r.spentMin) });
  if (r.spentMax != null) rows.push({ k: tt("memberSegment.spentMax"), v: money(r.spentMax) });
  if (!rows.length) rows.push({ k: tt("memberSegment.rule"), v: tt("memberSegments.allMembers") });
  return rows;
});

async function load() {
  if (!segmentNo.value) return;
  try {
    const [d, tg] = await Promise.all([api.mMemberSegmentDetail(segmentNo.value), api.mMemberTags()]);
    data.value = d;
    tags.value = tg;
    failed.value = false;
  } catch {
    failed.value = true;
  }
}

function openActivity(r: AudienceRef) {
  uni.navigateTo({ url: `${ROUTES.activityEdit}?activityNo=${r.refNo}` });
}

async function remove() {
  const s = sg.value;
  if (!s) return;
  const ok = await confirm({ title: tt("memberSegments.removeTitle", { name: s.name }), hint: tt("memberSegments.removeBody"), danger: true });
  if (!ok) return;
  try {
    await api.mRemoveMemberSegment(s.segmentNo);
    uni.navigateBack();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 发给他们：发消息直接去；发券先去券列表挑一张，发放页会把这群人预选上 */
async function sendTo() {
  const s = sg.value;
  if (!s) return;
  const idx = await pick({ items: [tt("members.act.reach"), tt("members.act.coupon")] });
  if (idx === null) return;
  setPendingAudience([{ type: "SEGMENT", value: s.segmentNo }], s.name);
  uni.navigateTo({ url: idx === 0 ? ROUTES.memberReach : ROUTES.coupons });
}

onLoad((q) => {
  segmentNo.value = String(q?.segmentNo ?? "");
  void merchant.ensureStores().catch(() => null);
});
onShow(load);
</script>

<template>
  <sh-scaffold title-key="memberSegment.title" :denied="!merchant.can('biz:customer')" :failed="failed" @retry="load">
    <template v-if="sg && data">
      <text class="txt-title name">{{ sg.name }}</text>
      <sh-stat
        boxed
        :items="[
          { key: 'n', value: data.matched, label: tt('memberSegment.matched') },
          { key: 'r', value: data.reachable, label: tt('memberSegment.reachable') },
          { key: 'u', value: usedCount, label: tt('memberSegment.used') },
        ]"
      ></sh-stat>

      <text class="txt-caption sh-muted grp">{{ $t("memberSegment.rule") }}</text>
      <view class="sh-cells">
        <view v-for="(c, i) in conditions" :key="i" class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ c.k }}</text>
          <text class="txt-body">{{ c.v }}</text>
        </view>
      </view>

      <text class="txt-caption sh-muted grp">{{ $t("memberSegment.usedIn") }}</text>
      <view v-if="usedCount" class="sh-cells">
        <view v-for="a in data.activities" :key="a.refNo" class="sh-cell sh-row sh-row--between" @tap="openActivity(a)">
          <text class="txt-body">{{ $t("memberTag.activity", { name: a.name || a.refNo }) }}</text>
          <view class="sh-row">
            <text class="sh-chip" :class="{ 'sh-chip--success': a.status === 'RUNNING' }">
              {{ a.status ? $t(`activityEdit.status.${a.status}`) : "" }}
            </text>
            <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
          </view>
        </view>
        <view v-for="c in data.couponIssues" :key="c.refNo" class="sh-cell sh-row sh-row--between">
          <text class="txt-body">{{ $t("memberSegment.coupon", { name: c.name || c.refNo }) }}</text>
          <text class="txt-body sh-muted sh-num">{{ c.at ? monthDay(c.at) : "" }}</text>
        </view>
      </view>
      <sh-empty v-else compact bare :text="tt('memberSegment.none')"></sh-empty>
      <text v-if="data.activities.length" class="sh-hint sh-mt-sm">{{ $t("memberSegment.snapshotNote") }}</text>

      <sh-actionbar>
        <view class="sh-row bar">
          <view class="sh-btn sh-btn--danger sh-fill" @tap="remove">{{ $t("memberSegments.remove") }}</view>
          <view class="sh-btn bar__main" :class="{ 'is-disabled': !data.reachable }" @tap="data.reachable && sendTo()">
            {{ $t("memberSegment.sendTo") }}
          </view>
        </view>
      </sh-actionbar>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.name {
  display: block;
  padding: 0 8rpx 16rpx;
}
.grp {
  display: block;
  padding: 16rpx 8rpx 0;
}
.bar {
  gap: 16rpx;
  width: 100%;
}
.bar__main {
  flex: 2;
}
</style>
