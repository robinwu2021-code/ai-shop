<script setup lang="ts">
/*
 * 一次触达的效果（原型 m20）。
 *
 * 大字是成单：「3 单 · ¥186」—— 打开率不是生意。三个数用同一条尺画，宽度就是比例。
 * 底部两个按钮让闭环回到起点：没来的存成人群（下次换个说法再发），
 * 下单的打上标签（下次活动圈他们）。
 */
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import { prompt } from "@ai-shop/ui/prompt";
import type { ReachTask } from "@shared/types";

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));
const merchant = useMerchantStore();

const taskNo = ref("");
const data = ref<ReachTask | null>(null);
const failed = ref(false);
const showTag = ref(false);
const saving = ref(false);

async function load() {
  if (!taskNo.value) return;
  try {
    data.value = await api.mReachTask(taskNo.value);
    failed.value = false;
  } catch {
    failed.value = true;
  }
}

function md(ts: number) {
  const d = new Date(ts);
  return `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/** 三条同一把尺：发出是满格，来了与成单按它的比例 */
const bars = computed(() => {
  const x = data.value;
  if (!x) return [];
  const w = (n: number) => (x.sent > 0 ? Math.min(100, Math.round((n * 100) / x.sent)) : 0);
  return [
    { key: "sent", label: tt("reachTask.sent"), n: x.sent, w: x.sent > 0 ? 100 : 0 },
    { key: "opened", label: tt("reachTask.opened"), n: x.opened, w: w(x.opened) },
    { key: "ordered", label: tt("reachTask.ordered"), n: x.ordered, w: w(x.ordered) },
  ];
});

const orderedNos = computed(() => (data.value?.orderedMembers ?? []).map((m) => m.memberNo));

function openMember(memberNo: string) {
  uni.navigateTo({ url: `/pages/member-detail/index?memberNo=${memberNo}` });
}

/** 没来的存人群：存的是「这一次里没点进店的人」这个条件，名单不会再变 */
async function saveNotOpened() {
  const x = data.value;
  if (!x || !x.notOpened || saving.value) return;
  const name = await prompt({
    title: tt("reachTask.saveTitle"),
    value: tt("reachTask.saveDefault", { t: x.title }),
    maxlength: 32,
  });
  if (!name?.trim()) return;
  saving.value = true;
  try {
    const s = await api.mSaveMemberSegment({
      name: name.trim(),
      rule: { reachTaskNo: x.taskNo, reachOutcome: "NOT_OPENED" },
    });
    uni.navigateTo({ url: `${ROUTES.memberSegment}?segmentNo=${s.segmentNo}` });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    saving.value = false;
  }
}

function onTagged() {
  showTag.value = false;
  uni.showToast({ title: tt("reachTask.tagged"), icon: "none" });
}

onLoad((q) => {
  taskNo.value = (q?.taskNo as string) ?? "";
  void load();
});
</script>

<template>
  <sh-scaffold title-key="reachTask.title" :denied="!merchant.can('biz:customer')"
    :failed="failed" @retry="load">
    <template v-if="data">
      <view class="hero">
        <text class="txt-strong">{{ data.title }}</text>
        <text class="txt-display sh-num hero__big">
          {{ $t("reachTask.hero", { n: data.ordered, m: money(data.orderedAmountMinor) }) }}
        </text>
        <text class="txt-sub sh-muted hero__sub">
          {{ $t("reachTask.sub", { d: md(data.sentAt), a: data.audienceDesc }) }}
        </text>
        <text class="sh-chip hero__chip" :class="data.settled ? '' : 'sh-chip--primary'">
          {{ data.settled ? $t("reachTasks.settled") : $t("reachTask.counting", { d: md(data.statsUntil) }) }}
        </text>
      </view>

      <view class="sh-card">
        <view v-for="b in bars" :key="b.key" class="sh-row meter">
          <text class="txt-sub meter__label">{{ b.label }}</text>
          <view class="meter__bar sh-fill"><view class="meter__in" :style="{ width: b.w + '%' }"></view></view>
          <text class="txt-body sh-num meter__n">{{ b.n }}</text>
        </view>
        <text class="txt-caption sh-muted blk">{{ $t("reachTask.pushedLine", { m: data.pushed }) }}</text>
        <text v-if="data.skipped" class="txt-caption sh-muted blk">
          {{ $t("reachTask.skipped", { n: data.skipped }) }}
        </text>
      </view>

      <sh-section class="sh-mt-sm" :title="tt('reachTask.orderedN', { n: data.ordered })"></sh-section>
      <view v-if="data.orderedMembers.length" class="sh-cells">
        <view v-for="m in data.orderedMembers" :key="m.memberNo" class="sh-cell sh-row sh-row--between"
          @tap="openMember(m.memberNo)">
          <text class="txt-body">
            <text v-if="m.name">{{ m.name }} </text><text class="sh-num" :class="{ 'sh-muted': m.name }">{{ $t("members.phoneTail", { n: m.phoneTail || "----" }) }}</text>
          </text>
          <text class="txt-body sh-num">{{ money(m.amountMinor) }}</text>
        </view>
      </view>
      <sh-empty v-else compact bare :text="tt('reachTask.noOrdered')"></sh-empty>

      <view class="sh-card sh-mt-sm sh-row sh-row--between">
        <text class="txt-body">{{ $t("reachTask.notOpenedN", { n: data.notOpened }) }}</text>
      </view>

      <sh-actionbar>
        <view class="sh-row acts">
          <view class="sh-btn sh-btn--muted sh-fill" :class="{ 'is-disabled': !data.notOpened || saving }"
            @tap="saveNotOpened">{{ $t("reachTask.saveNotOpened") }}</view>
          <view class="sh-btn sh-fill" :class="{ 'is-disabled': !orderedNos.length }"
            @tap="orderedNos.length && (showTag = true)">{{ $t("reachTask.tagOrdered") }}</view>
        </view>
      </sh-actionbar>

      <biz-batch-tag-sheet
        :visible="showTag"
        :member-nos="orderedNos"
        :count="orderedNos.length"
        @close="showTag = false"
        @done="onTagged"
      ></biz-batch-tag-sheet>
    </template>
    <sh-empty v-else :pending="!failed" :failed="failed" @retry="load"></sh-empty>
  </sh-scaffold>
</template>

<style scoped>
.hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16rpx 0 8rpx;
}
.hero__big {
  margin-top: 8rpx;
}
.hero__sub {
  margin-top: 8rpx;
}
.hero__chip {
  margin-top: 12rpx;
}
.meter {
  gap: 16rpx;
  padding: 8rpx 0;
}
.meter__label {
  width: 96rpx;
}
.meter__n {
  width: 64rpx;
  text-align: end;
}
.meter__bar {
  height: 12rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  overflow: hidden;
}
.meter__in {
  height: 100%;
  border-radius: 9999px;
  background: var(--sh-primary);
}
.acts {
  gap: 16rpx;
  width: 100%;
}
.blk {
  display: block;
  margin-top: 8rpx;
}
</style>
