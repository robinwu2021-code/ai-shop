<script setup lang="ts">
import { hourMinute, monthDay } from "@shared/utils/datetime";
// 会员经营口径（P3）。
//
// 这一页只有两个开关，但其中一个会**改变「新客」的含义**：
// 按门店经营时，在别的店买过的人在这家店仍算新客 —— 十公里外那家店要的正是这个判断。
//
// ⚠️ 所以文案要把两件事说全：**改了会变什么**，以及**随时可以切回来、一个数都不少**。
// 不写第二句，没有一个商家敢点第一个开关；而实际上两份指标一直都在算。
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { MemberSetting } from "@shared/types";
import { confirm } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

const setting = ref<MemberSetting | null>(null);
const busy = ref(false);

/** 这次没取到。**与「这儿本来就没有」是两件事** —— 整页内容都挂在拉来的数据后面，
 *  拉不到就是一个只有标题栏的空白页。交给 `sh-scaffold` 的 `failed` 说出来 */
const failed = ref(false);

async function load() {
  // `.catch(() => null)` 脱掉：这一页整屏的内容都挂在 `setting` 后面，
  // 兜成 null 的结果是一个只有标题栏的空白页 —— 不解释、不能重试
  try {
    setting.value = await api.mMemberSettings();
    failed.value = false;
  } catch {
    failed.value = true;
  }
}

async function save(patch: { memberScope?: string; autoJoinOnOrder?: boolean }) {
  if (busy.value) return;
  busy.value = true;
  try {
    setting.value = await api.mSaveMemberSettings(patch);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

/** 切口径前先确认一次：它影响全主体的分层与所有活动受众 */
async function pickScope(v: string) {
  if (!setting.value || setting.value.memberScope === v) return;
  if (
    await confirm({
      title: String(t("memberSettings.confirmTitle")),
      hint: String(t(v === "STORE" ? "memberSettings.confirmStore" : "memberSettings.confirmEntity")),
    })
  ) {
    void save({ memberScope: v });
  }
}

// 开关用库件 sh-switch（与积分、自提点设置同一个件），整行可点
function toggleAutoJoin() {
  if (!setting.value) return;
  void save({ autoJoinOnOrder: !setting.value.autoJoinOnOrder });
}

onShow(load);

function stamp(ts: number) {
  return `${monthDay(ts)} ${hourMinute(ts)}`;
}
</script>

<template>
  <!--
    三张卡同一个结构：txt-title 标题 → 内容 → txt-caption 说明（与会员详情、积分页一致）。
    此前三张各一种：两张用灰色小字 field__label 当标题、一张用加粗正文，开关是一个「已开」胶囊 —— 看着像三个人写的。
  -->
  <sh-scaffold title-key="memberSettings.title" :denied="!merchant.can('biz:store:admin')"
    :failed="failed"
    @retry="load"
  >
    <view class="sh-card">
      <text class="txt-title">{{ $t("memberSettings.scope") }}</text>

      <view class="opts">
        <sh-option :selected="setting?.memberScope === 'ENTITY'" @tap="pickScope('ENTITY')">
          <text class="txt-strong opt__t">{{ $t("memberSettings.entity") }}</text>
          <text class="txt-caption sh-muted opt__d">{{ $t("memberSettings.entityHint") }}</text>
        </sh-option>
        <sh-option :selected="setting?.memberScope === 'STORE'" @tap="pickScope('STORE')">
          <text class="txt-strong opt__t">{{ $t("memberSettings.store") }}</text>
          <text class="txt-caption sh-muted opt__d">{{ $t("memberSettings.storeHint") }}</text>
        </sh-option>
      </view>

      <!-- 这一句是这一页最重要的一行：不写它，没人敢动上面那两个 -->
      <text class="txt-caption sh-muted note">{{ $t("memberSettings.reversible") }}</text>
    </view>

    <view class="sh-card sh-mt-sm">
      <view class="sh-row sh-row--between" @tap="toggleAutoJoin">
        <text class="txt-title">{{ $t("memberSettings.autoJoin") }}</text>
        <sh-switch :model-value="setting?.autoJoinOnOrder !== false" :disabled="!setting"></sh-switch>
      </view>
      <text class="txt-caption sh-muted note">{{ $t("memberSettings.autoJoinHint") }}</text>
    </view>

    <!--
      分层口径：平台统一设定、商家只读，所以没有任何可点的东西。
      写出来是因为商家一定会问「他明明买了很多次，为什么是沉睡」—— 答案在「先判沉睡」那一行。
    -->
    <view v-if="setting" class="sh-card sh-mt-sm">
      <view class="sh-row sh-row--between">
        <text class="txt-title">{{ $t("memberSettings.levelTitle") }}</text>
        <text v-if="setting.levelComputedAt" class="txt-caption sh-muted">
          {{ $t("memberSettings.levelComputedAt", { t: stamp(setting.levelComputedAt) }) }}
        </text>
      </view>
      <sh-kv between class="sh-mt-sm" :label="String($t('members.level.SLEEPING'))">
        <text class="txt-sub">{{ $t("memberSettings.ruleSleep", { n: setting.sleepDays }) }}</text>
      </sh-kv>
      <sh-kv between :label="String($t('members.level.LOYAL'))">
        <text class="txt-sub">{{ $t("memberSettings.ruleLoyal", { n: setting.loyalD90Orders }) }}</text>
      </sh-kv>
      <sh-kv between :label="String($t('members.level.REGULAR'))">
        <text class="txt-sub">
          {{ $t("memberSettings.ruleRegular", { a: setting.regularD90Orders, b: setting.loyalD90Orders - 1 }) }}
        </text>
      </sh-kv>
      <sh-kv between :label="String($t('members.level.NEW'))">
        <text class="txt-sub">{{ $t("memberSettings.ruleNew", { n: setting.regularD90Orders - 1 }) }}</text>
      </sh-kv>
      <text class="txt-caption sh-muted note">{{ $t("memberSettings.sleepFirst", { n: setting.sleepDays }) }}</text>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.opt__t {
  display: block;
}
.opt__d {
  display: block;
  margin-top: 8rpx;
}
.opts {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  margin-top: 16rpx;
}
.note {
  display: block;
  margin-top: 16rpx;
}
</style>
