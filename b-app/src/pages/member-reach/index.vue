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
//
// 「发给谁」打开选人面板（原型 m18，与活动、发券同一块）：分层、标签、人群、来源多选，取或。
// 从会员名单「对这批人」、人群详情「发给他们」过来时已经预选好。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { AudienceItem, ReachPlan, ReachResult } from "@shared/types";
import { confirm } from "@ai-shop/ui/prompt";
import { takePendingAudience } from "@/shared/audience";
import { ROUTES } from "@/shared/nav";

const { t } = useI18n();
const tt = (k: string) => String(t(k));
const merchant = useMerchantStore();

const SCENES = ["NOTICE", "WAKEUP", "COUPON"] as const;

const scene = ref<string>("NOTICE");
/** 受众项（取或）。空 = 还没选 —— 群发不给默认对象 */
const items = ref<AudienceItem[]>([]);
const itemsLabel = ref("");
const showPicker = ref(false);
const title = ref("");
const body = ref("");
const plan = ref<ReachPlan | null>(null);
const result = ref<ReachResult | null>(null);
/** 试算没算出来。**与「一个人都发不出去」是两件事** —— 只弹一句提示的话，卡片空着，看起来像 0 人 */
const planFailed = ref(false);
const busy = ref(false);

const canSend = computed(
  () => !!plan.value && plan.value.reachable > 0 && !!title.value.trim() && !busy.value,
);


/** 换场景或换人群都要重算 —— 上一次的数字对这一次没有意义，留着比没有更糟 */
async function recount() {
  plan.value = null;
  result.value = null;
  planFailed.value = false;
  if (busy.value || !items.value.length) return;
  busy.value = true;
  try {
    plan.value = await api.mPlanReach({ audiences: items.value, scene: scene.value });
  } catch {
    planFailed.value = true;
  } finally {
    busy.value = false;
  }
}

/** 发完直接去看这一次的效果（m20）：来了几个、下了几单，7 天内会一直涨 */
function viewEffect() {
  if (result.value) uni.navigateTo({ url: `${ROUTES.reachTask}?taskNo=${result.value.taskNo}` });
}

function pickScene(s: string) {
  scene.value = s;
  void recount();
}

function onPick(next: AudienceItem[], label: string) {
  items.value = next;
  itemsLabel.value = label;
  showPicker.value = false;
  void recount();
}

async function send() {
  const p = plan.value;
  if (!p || !canSend.value) return;
  const ok = await confirm({ title: String(t("reach.confirmTitle", { n: p.reachable })), hint: String(t("reach.confirmBody")), confirmText: String(String(t("reach.confirmBtn"))) });
  if (!ok) return;

  busy.value = true;
  try {
    result.value = await api.mSendReach({
      audiences: items.value,
      scene: scene.value,
      title: title.value.trim(),
      body: body.value.trim(),
      audienceDesc: itemsLabel.value,
    });
    // 发完立刻重算：频次闸已经把这批人挡住了，界面上要立刻反映出来，
    // 否则他会以为「再点一次能再发一遍」
    plan.value = await api.mPlanReach({ audiences: items.value, scene: scene.value });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

onShow(() => {
  const pending = takePendingAudience();
  if (pending) {
    items.value = pending.items;
    itemsLabel.value = pending.label;
  }
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
      <view class="sh-row sh-row--between" @tap="showPicker = true">
        <text class="txt-body">{{ $t("reach.toWhom") }}</text>
        <view class="sh-row">
          <text class="txt-body row__v" :class="items.length ? 'txt-primary' : 'sh-muted'">
            {{ items.length ? itemsLabel : $t("audience.none") }}
          </text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>
    </view>

    <view v-if="planFailed" class="sh-card sh-mt-sm">
      <sh-empty line :failed="planFailed" @retry="recount"></sh-empty>
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
      <view v-if="plan.skips.length" class="reasons sh-wrap">
        <text v-for="s in plan.skips" :key="s.reason" class="txt-caption reason">
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

    <view class="sh-btn send" :class="{ 'is-disabled': !canSend }" @tap="send">
      {{ plan && plan.reachable
        ? $t("reach.sendN", { n: plan.reachable })
        : $t("reach.sendNone") }}
    </view>

    <!-- 结果：与发券结果页同一形状，商家看两处学一次 -->
    <view v-if="result" class="sh-card sh-mt-sm done">
      <text class="txt-strong">{{ $t("reach.doneTitle", { n: result.sent }) }}</text>
      <view v-if="result.skips.length" class="reasons sh-wrap">
        <text v-for="s in result.skips" :key="s.reason" class="txt-caption reason">
          {{ $t(`reach.reason.${s.reason}`, { n: s.count }) }}
        </text>
      </view>
      <text class="sh-muted sh-hint">{{ $t("reach.doneHint") }}</text>
      <sh-go :text="tt('reach.viewEffect')" @tap="viewEffect"></sh-go>
    </view>

    <biz-audience-picker
      :visible="showPicker"
      :model-value="items"
      :scene="scene"
      @close="showPicker = false"
      @confirm="onPick"
    ></biz-audience-picker>
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

.reasons {
  margin-top: 12rpx;
}
.reason {
  background: var(--sh-faint);
  border-radius: 16rpx;
  padding: 8rpx 12rpx;
}
.area {
  height: 160rpx;
  margin-top: 12rpx;
}

.done {
  border: 2rpx solid var(--sh-success);
}
</style>
