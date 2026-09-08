<script setup lang="ts">
// 预约排期（B-11.5）。
//
// 这一页回答的是「我这周能接几单上门」。在它之前，买家在结算页自己填一个时间戳，
// 商家只能事后看见 —— 同一个师傅可以被约到十个人手里，而系统里没有任何地方
// 看得出来，直到当天有九个人白等。
//
// ⚠️ **只有开与停，没有改容量。** 把容量调到比已约数小，那几个已经约上的单
// 立刻处于「超卖」状态，而没有任何地方会报错。要支持得先有一套
// 「挤出去谁、怎么通知」的规则 —— 在那之前，正确的做法是停掉旧的、开一个新的。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { datetime } from "@shared/utils/datetime";
import type { AppointmentSlot } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

/** 与门店送货方式同一个权限：都是「这家店怎么履约」的配置 */
const canEdit = computed(() => merchant.can("biz:store"));

const slots = ref<AppointmentSlot[]>([]);
const busy = ref(false);

/** 往后看两周。再长的排期店主不会提前开，列表却会长到翻不动 */
const WINDOW_DAYS = 14;
const DAY = 86_400_000;

/** 新时段表单。默认明天上午 9 点、一小时、一个名额 —— 最常见的那一档 */
const form = ref({ dayOffset: 1, hour: 9, hours: 1, capacity: 1 });

/** 首屏到过没有。**不是 `loading`** —— 那个含下拉刷新，刷新时把列表换成空态是另一个 bug */
const loaded = ref(false);
/** 这次没取到。**与「确定为空」是两件事** —— 整页内容都挂在拉来的数据后面 */
const failed = ref(false);

async function load() {
  const storeNo = merchant.storeNo || "default";
  const from = Date.now();
  try {
    slots.value = await api.mAppointmentSlots(storeNo, from, from + WINDOW_DAYS * DAY);
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

function startAtOf(): number {
  const d = new Date();
  d.setDate(d.getDate() + form.value.dayOffset);
  d.setHours(form.value.hour, 0, 0, 0);
  return d.getTime();
}

async function open() {
  if (busy.value || !canEdit.value) return;
  const startAt = startAtOf();
  // 过去的时段开出来也没人约得上，只会让列表变长。后端也拒，早点拦住比让他撞一次好
  if (startAt <= Date.now()) {
    uni.showToast({ title: t("schedule.pastSlot"), icon: "none" });
    return;
  }
  busy.value = true;
  try {
    await api.mOpenAppointmentSlot(merchant.storeNo || "default", {
      startAt,
      endAt: startAt + form.value.hours * 3_600_000,
      capacity: form.value.capacity,
    });
    await load();
    uni.showToast({ title: t("schedule.opened"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

async function close(slot: AppointmentSlot) {
  if (busy.value || !canEdit.value) return;
  busy.value = true;
  try {
    await api.mCloseAppointmentSlot(slot.slotNo);
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

/**
 * 一行的状态字。**约满与停约要分得开** ——
 * 都显示「不可约」的话，店主不知道该加名额还是该重新开。
 */
function stateText(s: AppointmentSlot): string {
  if (s.status === "CLOSED") return t("schedule.closed");
  if (s.remaining <= 0) return t("schedule.full");
  return t("schedule.remaining", { n: s.remaining });
}

onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold title-key="schedule.title" :denied="!canEdit">
    <view class="sh-card">
      <text class="txt-title">{{ $t("schedule.newSlot") }}</text>
      <view class="sh-row sh-mt-sm">
        <text class="sh-muted">{{ $t("schedule.day") }}</text>
        <input maxlength="3" v-model.number="form.dayOffset" class="field__input row__in sh-num" type="number" />
        <text class="sh-muted">{{ $t("schedule.dayUnit") }}</text>
      </view>
      <view class="sh-row sh-mt-sm">
        <text class="sh-muted">{{ $t("schedule.hour") }}</text>
        <input maxlength="3" v-model.number="form.hour" class="field__input row__in sh-num" type="number" />
        <text class="sh-muted">{{ $t("schedule.hours") }}</text>
        <input maxlength="3" v-model.number="form.hours" class="field__input row__in sh-num" type="number" />
      </view>
      <view class="sh-row sh-mt-sm">
        <text class="sh-muted">{{ $t("schedule.capacity") }}</text>
        <input maxlength="6" v-model.number="form.capacity" class="field__input row__in sh-num" type="number" />
      </view>
      <text class="sh-muted sh-hint">{{ $t("schedule.capacityHint") }}</text>
      <view class="sh-btn sh-mt-sm" @tap="open">{{ $t("schedule.open") }}</view>
    </view>

    <!--
      列表**连约满的和停掉的一起列**。只给「还能约的」的话，
      店主看不出「这周没人约」到底是「没开时段」还是「开的都满了」——
      而这两件事该做的动作完全相反。
    -->
    <view class="sh-card sh-mt-sm">
      <text class="txt-title">{{ $t("schedule.list") }}</text>
      <!-- 两行裸 `text` 换成 `sh-empty`：三种态（还不知道 / 没取到 / 确定没有）
           归它一个件管，也就顺带有了那颗重试按钮 -->
      <sh-empty
        bare
        v-if="!slots.length"
        :pending="!loaded"
        :failed="failed"
        @retry="load"
        :text="String($t('schedule.empty'))"
        :tip="String($t('schedule.emptyTip'))"
      ></sh-empty>
      <view v-for="s in slots" :key="s.slotNo" class="slot sh-row">
        <view class="sh-fill">
          <text class="txt-body slot__when sh-num">{{ datetime(s.startAt) }}</text>
          <text class="sh-muted">{{ $t("schedule.booked", { b: s.booked, c: s.capacity }) }}</text>
        </view>
        <text class="sh-chip" :class="{ 'sh-chip--primary': s.status === 'OPEN' && s.remaining > 0 }">
          {{ stateText(s) }}
        </text>
        <!-- 停约不删行也不赶人：已经约进来的单还指着它 -->
        <text v-if="s.status === 'OPEN'" class="txt-sub slot__act txt-primary" @tap="close(s)">
          {{ $t("schedule.close") }}
        </text>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.row__in {
  width: 120rpx;
  text-align: center;
}

.slot {
  gap: 20rpx;
  padding: 16rpx 0;
  border-top: var(--sh-hairline);
}

.slot__when {
  display: block;
}
</style>
