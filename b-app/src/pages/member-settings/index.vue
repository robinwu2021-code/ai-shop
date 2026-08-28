<script setup lang="ts">
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

async function load() {
  setting.value = await api.mMemberSettings().catch(() => null);
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

// 用一个可点的 chip 而不是 <switch>：这个仓库里一处原生 switch 都没有，
// 只有它一个的话，深色皮肤与 rpx 尺寸都要单独调一遍
function toggleAutoJoin() {
  if (!setting.value) return;
  void save({ autoJoinOnOrder: !setting.value.autoJoinOnOrder });
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="memberSettings.title" :denied="!merchant.can('biz:store:admin')">
    <view class="sh-card">
      <text class="field__label">{{ $t("memberSettings.scope") }}</text>

      <sh-option :selected="setting?.memberScope === 'ENTITY'" @tap="pickScope('ENTITY')">
        <text class="txt-strong opt__t">{{ $t("memberSettings.entity") }}</text>
        <text class="txt-caption sh-muted opt__d">{{ $t("memberSettings.entityHint") }}</text>
      </sh-option>

      <sh-option :selected="setting?.memberScope === 'STORE'" @tap="pickScope('STORE')">
        <text class="txt-strong opt__t">{{ $t("memberSettings.store") }}</text>
        <text class="txt-caption sh-muted opt__d">{{ $t("memberSettings.storeHint") }}</text>
      </sh-option>

      <!-- 这一句是这一页最重要的一行：不写它，没人敢动上面那两个 -->
      <text class="sh-hint sh-mt-sm">{{ $t("memberSettings.reversible") }}</text>
    </view>

    <view class="sh-card sh-mt-sm">
      <view class="sh-row sh-row--between row">
        <view class="sh-fill">
          <text class="txt-strong opt__t">{{ $t("memberSettings.autoJoin") }}</text>
          <text class="txt-caption sh-muted opt__d">{{ $t("memberSettings.autoJoinHint") }}</text>
        </view>
        <text
          class="sh-chip"
          :class="{ 'sh-chip--primary': setting?.autoJoinOnOrder !== false }"
          @tap="toggleAutoJoin"
        >
          {{ setting?.autoJoinOnOrder === false ? $t("memberSettings.off") : $t("memberSettings.on") }}
        </text>
      </view>
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
.row {
  gap: 24rpx;
}
</style>
