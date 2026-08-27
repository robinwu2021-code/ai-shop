<script setup lang="ts">
// 给会员发消息（P7）。
//
// **这一页每一步都在给商家踩刹车**，因为它是整个系统里唯一会打扰真实用户的动作：
//
//   ① 先选场景 —— 频次闸按场景分档，选错档位比发错内容更伤人
//   ② 试算在前，发送在后 —— 先看见「能发多少、谁被拦下、为什么」
//   ③ 发送按钮上写着人数，确认框里再写一遍「发出去撤不回来」
//
// 没有「全部会员」这个默认选项：群发的默认对象不该是所有人。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { MemberSegment, ReachPlan, ReachResult } from "@shared/types";
import { confirm, pick } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

const SCENES = ["NOTICE", "WAKEUP", "COUPON"] as const;

const scene = ref<string>("NOTICE");
const segmentNo = ref("");
const title = ref("");
const body = ref("");
const segments = ref<MemberSegment[]>([]);
const plan = ref<ReachPlan | null>(null);
const result = ref<ReachResult | null>(null);
const busy = ref(false);

const canSend = computed(
  () => !!plan.value && plan.value.reachable > 0 && !!title.value.trim() && !busy.value,
);

async function load() {
  segments.value = await api.mMemberSegments().catch(() => []);
}

/** 换场景或换人群都要重算 —— 上一次的数字对这一次没有意义，留着比没有更糟 */
async function recount() {
  plan.value = null;
  result.value = null;
  if (busy.value) return;
  busy.value = true;
  try {
    plan.value = await api.mPlanReach({
      segmentNo: segmentNo.value || undefined,
      scene: scene.value,
    });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

function pickScene(s: string) {
  scene.value = s;
  void recount();
}

async function pickSegment() {
  const items = [String(t("reach.allMembers")), ...segments.value.map((s) => s.name)];
  const idx = await pick({ items, selected: segmentNo.value
    ? segments.value.findIndex((s) => s.segmentNo === segmentNo.value) + 1 : 0 });
  if (idx === null) return;
  segmentNo.value = idx === 0 ? "" : segments.value[idx - 1]?.segmentNo ?? "";
  void recount();
}

const segmentName = computed(() => {
  if (!segmentNo.value) return t("reach.allMembers");
  return segments.value.find((s) => s.segmentNo === segmentNo.value)?.name ?? segmentNo.value;
});

async function send() {
  const p = plan.value;
  if (!p || !canSend.value) return;
  const ok = await confirm({ title: String(t("reach.confirmTitle", { n: p.reachable })), hint: String(t("reach.confirmBody")), confirmText: String(String(t("reach.confirmBtn"))) });
  if (!ok) return;

  busy.value = true;
  try {
    result.value = await api.mSendReach({
      segmentNo: segmentNo.value || undefined,
      scene: scene.value,
      title: title.value.trim(),
      body: body.value.trim(),
    });
    // 发完立刻重算：频次闸已经把这批人挡住了，界面上要立刻反映出来，
    // 否则他会以为「再点一次能再发一遍」
    plan.value = await api.mPlanReach({
      segmentNo: segmentNo.value || undefined,
      scene: scene.value,
    });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

onShow(() => {
  void load();
  void recount();
});
</script>

<template>
  <sh-scaffold title-key="reach.title" :denied="!merchant.can('biz:customer')">
    <!-- ① 场景。频次闸按它分档，所以它排在最前面 -->
    <view class="sh-card">
      <text class="field__label">{{ $t("reach.sceneQ") }}</text>
      <view class="chips">
        <text
          v-for="s in SCENES"
          :key="s"
          class="sh-chip"
          :class="{ 'sh-chip--primary': scene === s }"
          @tap="pickScene(s)"
        >{{ $t(`reach.scene.${s}`) }}</text>
      </view>
      <text class="sh-muted sh-hint">{{ $t(`reach.sceneHint.${scene}`) }}</text>
    </view>

    <!-- ② 发给谁 -->
    <view class="sh-card sh-mt-sm">
      <view class="sh-row sh-row--between" @tap="pickSegment">
        <text class="row__label">{{ $t("reach.toWhom") }}</text>
        <text class="row__v">{{ segmentName }} ▾</text>
      </view>
    </view>

    <!-- ③ 试算：三个数字在写内容之前就摆出来 -->
    <view v-if="plan" class="sh-card sh-mt-sm">
      <sh-stat
        :items="[
          { value: plan.matched, label: String($t('reach.matched')) },
          { value: plan.reachable, label: String($t('reach.reachable')), tone: 'ok' },
          { value: plan.matched - plan.reachable, label: String($t('reach.skipped')),
            tone: plan.matched > plan.reachable ? 'warn' : undefined },
        ]"
      ></sh-stat>
      <view v-if="plan.skips.length" class="reasons">
        <text v-for="s in plan.skips" :key="s.reason" class="reason">
          {{ $t(`reach.reason.${s.reason}`, { n: s.count }) }}
        </text>
      </view>
      <text v-if="!plan.reachable" class="sh-muted sh-hint">{{ $t("reach.noneHint") }}</text>
    </view>

    <!-- ④ 内容 -->
    <view class="sh-card sh-mt-sm">
      <text class="field__label">{{ $t("reach.content") }}</text>
      <input maxlength="64" v-model="title" class="field__input mt2" :placeholder="$t('reach.titlePh')" />
      <textarea v-model="body" class="field__input area" :placeholder="$t('reach.bodyPh')" />
    </view>

    <button class="sh-btn sh-btn--primary send" :disabled="!canSend" @tap="send">
      {{ plan && plan.reachable
        ? $t("reach.sendN", { n: plan.reachable })
        : $t("reach.sendNone") }}
    </button>

    <!-- 结果：与发券结果页同一形状，商家看两处学一次 -->
    <view v-if="result" class="sh-card sh-mt-sm done">
      <text class="done__t">{{ $t("reach.doneTitle", { n: result.sent }) }}</text>
      <view v-if="result.skips.length" class="reasons">
        <text v-for="s in result.skips" :key="s.reason" class="reason">
          {{ $t(`reach.reason.${s.reason}`, { n: s.count }) }}
        </text>
      </view>
      <text class="sh-muted sh-hint">{{ $t("reach.doneHint") }}</text>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.mt2 {
  margin-top: 12rpx;
}
.chips {
  display: flex;
  gap: 12rpx;
  margin-top: 12rpx;
}

.row__label {
  font-size: 26rpx;
  color: var(--sh-sub);
}
.row__v {
  font-size: 28rpx;
  color: var(--sh-primary-text);
}
.reasons {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 12rpx;
}
.reason {
  font-size: 24rpx;
  color: var(--sh-sub);
  background: var(--sh-faint);
  border-radius: 16rpx;
  padding: 6rpx 12rpx;
}
.area {
  height: 160rpx;
  margin-top: 12rpx;
}
.send {
  margin-top: 24rpx;
}
.done {
  border: 2rpx solid var(--sh-success);
}
.done__t {
  font-size: 28rpx;
  font-weight: 600;
}
</style>
