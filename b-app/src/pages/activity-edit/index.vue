<script setup lang="ts">
// 建活动：四步向导（P5）。
//
// **为什么是向导而不是一张长表单**：这四步里每一步都可能让商家改主意
// （「原来长期活动必须设限量，那我改成一周」），而一张 12 个输入框的表单
// 要填到最后一个才知道前面填错了。老的营销页就是那样，它有 8 个字段
// 按活动类型显示/隐藏，商家切一次类型就有一半字段变空。
//
// 四步：① 想干什么（目标）② 优惠什么样 ③ 什么时候有效 ④ 给谁
// 目标那一步不是装饰：它决定后面三步的默认值 —— 拉新默认受众是「非会员」，
// 唤回默认是「沉睡」。商家从来不是先想「触发条件」的。
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money, toMinor } from "@shared/utils/money";
import type { ActivityConflict, StoreActivityDraft } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const step = ref(1);
const activityNo = ref("");
const saving = ref(false);
const conflicts = ref<ActivityConflict[]>([]);

const form = ref({
  goal: "BASKET",
  name: "",
  benefitType: "CUT",
  /** 满多少（元） */
  threshold: "50",
  /** 减多少 / 特价多少（元） */
  amount: "5",
  buyN: "2",
  giftM: "1",
  goodsNos: [] as string[],
  scheduleType: "ONE_OFF",
  days: "7",
  weekdays: [] as number[],
  from: "08:00",
  to: "20:00",
  quota: "100",
  budget: "",
  audienceType: "",
});

/** 目标 → 后面几步的默认值。商家想的是「拉新」，不是「触发条件 = 满额」 */
const GOALS = [
  { key: "ACQUIRE", benefit: "CUT", audience: "NON_MEMBER" },
  { key: "WAKEUP", benefit: "CUT", audience: "LEVEL:SLEEPING" },
  { key: "CLEAR", benefit: "PRICE", audience: "" },
  { key: "BASKET", benefit: "CUT", audience: "" },
];

function pickGoal(key: string) {
  const g = GOALS.find((x) => x.key === key)!;
  form.value.goal = key;
  form.value.benefitType = g.benefit;
  form.value.audienceType = g.audience;
  if (!form.value.name) form.value.name = String(t(`activityEdit.goalName.${key}`));
}

const isItemCost = computed(
  () => form.value.benefitType === "PRICE" || form.value.benefitType === "GIFT",
);

/** 最大敞口。**边填边显示** —— 他填的是份数，要为之负责的是钱 */
const exposure = computed(() => {
  if (form.value.benefitType !== "CUT") return 0;
  const per = toMinor(form.value.amount);
  const n = Number(form.value.quota || 0);
  return per && n ? per * n : 0;
});

/** 长期活动没有限量也没有预算 = 永久敞口。这条与后端一字不差 */
const alwaysOnUncapped = computed(
  () => form.value.scheduleType === "ALWAYS_ON"
    && !Number(form.value.quota || 0) && !toMinor(form.value.budget),
);

async function checkConflicts() {
  if (!form.value.goodsNos.length) {
    conflicts.value = [];
    return;
  }
  conflicts.value = await api.mActivityConflicts(form.value.goodsNos).catch(() => []);
}

function toggleWeekday(d: number) {
  form.value.weekdays = form.value.weekdays.includes(d)
    ? form.value.weekdays.filter((x) => x !== d)
    : [...form.value.weekdays, d];
}

async function loadExisting(no: string) {
  const a = await api.mActivity(no).catch(() => null);
  if (!a) return;
  activityNo.value = a.activityNo;
  form.value.goal = a.goal ?? "BASKET";
  form.value.name = a.name;
  form.value.benefitType = a.benefitType;
  form.value.threshold = String(((a.triggerAmountMinor ?? 0) / 100).toFixed(2));
  form.value.amount = String(((a.benefitAmountMinor ?? 0) / 100).toFixed(2));
  form.value.buyN = String(a.triggerQty ?? 2);
  form.value.giftM = String(a.benefitQty ?? 1);
  form.value.goodsNos = [...a.goodsNos];
  form.value.scheduleType = a.scheduleType;
  form.value.quota = a.quota == null ? "" : String(a.quota);
  form.value.budget = a.budgetMinor ? String((a.budgetMinor / 100).toFixed(2)) : "";
  form.value.audienceType = a.audiences.length
    ? (a.audiences[0]!.type === "LEVEL"
      ? `LEVEL:${a.audiences[0]!.value}` : a.audiences[0]!.type)
    : "";
  if (a.scheduleRule) {
    try {
      const r = JSON.parse(a.scheduleRule) as { weekdays?: number[]; from?: string; to?: string };
      form.value.weekdays = r.weekdays ?? [];
      form.value.from = r.from ?? "08:00";
      form.value.to = r.to ?? "20:00";
    } catch { /* 坏规则读不出来就用默认值，保存时后端会拦 */ }
  }
  await checkConflicts();
}

async function save() {
  if (saving.value) return;
  if (!form.value.name.trim()) {
    uni.showToast({ title: t("activityEdit.needName"), icon: "none" });
    return;
  }
  if (alwaysOnUncapped.value) {
    uni.showToast({ title: t("activityEdit.alwaysOnNeedsCap"), icon: "none" });
    return;
  }
  if (isItemCost.value && !form.value.goodsNos.length) {
    uni.showToast({ title: t("activityEdit.needGoods"), icon: "none" });
    return;
  }
  if (isItemCost.value && !Number(form.value.quota || 0)) {
    uni.showToast({ title: t("activityEdit.needQuota"), icon: "none" });
    return;
  }

  const now = Date.now();
  const audiences = form.value.audienceType
    ? [form.value.audienceType.startsWith("LEVEL:")
      ? { type: "LEVEL", value: form.value.audienceType.slice(6) }
      : { type: form.value.audienceType, value: "*" }]
    : [];

  const draft: StoreActivityDraft = {
    activityNo: activityNo.value || undefined,
    name: form.value.name.trim(),
    goal: form.value.goal,
    benefitType: form.value.benefitType,
    triggerType: form.value.benefitType === "GIFT" ? "QTY"
      : form.value.benefitType === "PRICE" ? "GOODS" : "AMOUNT",
    triggerAmountMinor: form.value.benefitType === "CUT" ? toMinor(form.value.threshold) : null,
    triggerQty: form.value.benefitType === "GIFT" ? Number(form.value.buyN || 0) : null,
    benefitAmountMinor: form.value.benefitType === "GIFT" ? null : toMinor(form.value.amount),
    benefitQty: form.value.benefitType === "GIFT" ? Number(form.value.giftM || 0) : null,
    scheduleType: form.value.scheduleType,
    startAt: form.value.scheduleType === "ONE_OFF" ? now : null,
    endAt: form.value.scheduleType === "ONE_OFF"
      ? now + Number(form.value.days || 7) * 86400_000 : null,
    scheduleRule: form.value.scheduleType === "RECURRING"
      ? JSON.stringify({ weekdays: form.value.weekdays, from: form.value.from, to: form.value.to })
      : null,
    quota: form.value.quota ? Number(form.value.quota) : null,
    budgetMinor: toMinor(form.value.budget) || null,
    audiences,
    goodsNos: form.value.goodsNos,
  };

  saving.value = true;
  try {
    await api.mSaveActivity(draft);
    uni.showToast({ title: t("activityEdit.saved"), icon: "none" });
    setTimeout(() => uni.navigateBack(), 600);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    saving.value = false;
  }
}

onLoad((q) => {
  if (q?.activityNo) void loadExisting(q.activityNo as string);
});
</script>

<template>
  <sh-scaffold title-key="activityEdit.title" :denied="!merchant.can('biz:campaign')">
    <!-- 四步的进度：让他知道还剩几步，而不是面对一屏输入框 -->
    <view class="steps">
      <text
        v-for="s in [1, 2, 3, 4]"
        :key="s"
        class="steps__i"
        :class="{ 'is-on': step === s, 'is-done': step > s }"
        @tap="step = s"
      >{{ s }}. {{ $t(`activityEdit.step${s}`) }}</text>
    </view>

    <!-- ① 想干什么 -->
    <view v-if="step === 1" class="sh-card">
      <text class="field__label">{{ $t("activityEdit.goalQ") }}</text>
      <view class="opts">
        <view
          v-for="g in GOALS"
          :key="g.key"
          class="opt"
          :class="{ 'is-on': form.goal === g.key }"
          @tap="pickGoal(g.key)"
        >
          <text class="opt__t">{{ $t(`activityEdit.goal.${g.key}`) }}</text>
          <text class="sh-muted opt__d">{{ $t(`activityEdit.goalHint.${g.key}`) }}</text>
        </view>
      </view>
      <view class="row mt">
        <text class="row__label">{{ $t("activityEdit.name") }}</text>
        <input v-model="form.name" class="field__input row__input"
               :placeholder="$t('activityEdit.namePh')" />
      </view>
    </view>

    <!-- ② 优惠什么样 -->
    <view v-if="step === 2" class="sh-card">
      <text class="field__label">{{ $t("activityEdit.benefitQ") }}</text>
      <view class="chips">
        <text
          v-for="b in ['CUT', 'PRICE', 'GIFT']"
          :key="b"
          class="sh-chip"
          :class="{ 'sh-chip--primary': form.benefitType === b }"
          @tap="form.benefitType = b"
        >{{ $t(`activityEdit.benefit.${b}`) }}</text>
      </view>

      <template v-if="form.benefitType === 'CUT'">
        <view class="row mt">
          <text class="row__label">{{ $t("activityEdit.threshold") }}</text>
          <input v-model="form.threshold" class="field__input row__input" type="digit" />
        </view>
        <view class="row">
          <text class="row__label">{{ $t("activityEdit.cut") }}</text>
          <input v-model="form.amount" class="field__input row__input" type="digit" />
        </view>
      </template>

      <template v-if="form.benefitType === 'PRICE'">
        <view class="row mt">
          <text class="row__label">{{ $t("activityEdit.price") }}</text>
          <input v-model="form.amount" class="field__input row__input" type="digit" />
        </view>
        <text class="sh-muted hint">{{ $t("activityEdit.priceHint") }}</text>
      </template>

      <template v-if="form.benefitType === 'GIFT'">
        <view class="row mt">
          <text class="row__label">{{ $t("activityEdit.buyN") }}</text>
          <input v-model="form.buyN" class="field__input row__input" type="number" />
        </view>
        <view class="row">
          <text class="row__label">{{ $t("activityEdit.giftM") }}</text>
          <input v-model="form.giftM" class="field__input row__input" type="number" />
        </view>
      </template>

      <!-- 冲突提示：不阻止，但要在保存前说出来 -->
      <view v-if="conflicts.length" class="conflict">
        <text v-for="c in conflicts" :key="c.activityNo + c.goodsNo" class="conflict__l">
          {{ $t("activityEdit.conflict", { g: c.goodsNo, name: c.activityName }) }}
        </text>
        <text class="sh-muted conflict__h">{{ $t("activityEdit.conflictHint") }}</text>
      </view>
    </view>

    <!-- ③ 什么时候有效 -->
    <view v-if="step === 3" class="sh-card">
      <text class="field__label">{{ $t("activityEdit.scheduleQ") }}</text>
      <view class="chips">
        <text
          v-for="s in ['ONE_OFF', 'ALWAYS_ON', 'RECURRING']"
          :key="s"
          class="sh-chip"
          :class="{ 'sh-chip--primary': form.scheduleType === s }"
          @tap="form.scheduleType = s"
        >{{ $t(`activityEdit.schedule.${s}`) }}</text>
      </view>

      <view v-if="form.scheduleType === 'ONE_OFF'" class="row mt">
        <text class="row__label">{{ $t("activityEdit.days") }}</text>
        <input v-model="form.days" class="field__input row__input" type="number" />
      </view>

      <template v-if="form.scheduleType === 'RECURRING'">
        <view class="week mt">
          <text
            v-for="d in [1, 2, 3, 4, 5, 6, 7]"
            :key="d"
            class="sh-chip"
            :class="{ 'sh-chip--primary': form.weekdays.includes(d) }"
            @tap="toggleWeekday(d)"
          >{{ $t(`activities.weekday.${d}`) }}</text>
        </view>
        <view class="row">
          <text class="row__label">{{ $t("activityEdit.timeRange") }}</text>
          <input v-model="form.from" class="field__input row__input" placeholder="08:00" />
          <input v-model="form.to" class="field__input row__input" placeholder="20:00" />
        </view>
        <text class="sh-muted hint">{{ $t("activityEdit.recurringHint") }}</text>
      </template>

      <view class="row mt">
        <text class="row__label">{{ $t("activityEdit.quota") }}</text>
        <input v-model="form.quota" class="field__input row__input" type="number" />
      </view>
      <view class="row">
        <text class="row__label">{{ $t("activityEdit.budget") }}</text>
        <input v-model="form.budget" class="field__input row__input" type="digit"
               :placeholder="$t('activityEdit.budgetPh')" />
      </view>

      <view v-if="exposure > 0" class="exposure">
        {{ $t("activityEdit.exposure", { n: money(exposure) }) }}
      </view>
      <text v-if="alwaysOnUncapped" class="bad">{{ $t("activityEdit.alwaysOnNeedsCap") }}</text>
    </view>

    <!-- ④ 给谁 -->
    <view v-if="step === 4" class="sh-card">
      <text class="field__label">{{ $t("activityEdit.audienceQ") }}</text>
      <view class="opts">
        <view
          v-for="a in ['', 'NON_MEMBER', 'LEVEL:SLEEPING', 'LEVEL:LOYAL']"
          :key="a || 'all'"
          class="opt"
          :class="{ 'is-on': form.audienceType === a }"
          @tap="form.audienceType = a"
        >
          <text class="opt__t">{{ $t(`activityEdit.audience.${a || "ALL"}`) }}</text>
          <text class="sh-muted opt__d">{{ $t(`activityEdit.audienceHint.${a || "ALL"}`) }}</text>
        </view>
      </view>
    </view>

    <view class="nav">
      <text v-if="step > 1" class="sh-btn sh-btn--soft nav__b" @tap="step -= 1">
        {{ $t("activityEdit.prev") }}
      </text>
      <button v-if="step < 4" class="sh-btn sh-btn--primary nav__b" @tap="step += 1">
        {{ $t("activityEdit.next") }}
      </button>
      <button v-else class="sh-btn sh-btn--primary nav__b" :disabled="saving" @tap="save">
        {{ $t("activityEdit.save") }}
      </button>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.steps {
  display: flex;
  flex-wrap: wrap;
  gap: 8rpx;
  margin-bottom: 16rpx;
}
.steps__i {
  font-size: 22rpx;
  color: var(--sh-sub);
  background: var(--sh-faint);
  border-radius: 8rpx;
  padding: 8rpx 12rpx;
}
.steps__i.is-on {
  background: var(--sh-primary);
  color: var(--sh-on-primary);
}
.steps__i.is-done {
  color: var(--sh-primary-text);
}
.opts {
  margin-top: 12rpx;
}
.opt {
  border: 2rpx solid var(--sh-faint);
  border-radius: 12rpx;
  padding: 20rpx;
  margin-top: 12rpx;
}
.opt.is-on {
  border-color: var(--sh-primary);
  background: var(--sh-primary-tint);
}
.opt__t {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
}
.opt__d {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  line-height: 1.5;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 12rpx;
}
.week {
  display: flex;
  flex-wrap: wrap;
  gap: 8rpx;
}
.row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 12rpx;
}
.row__label {
  width: 200rpx;
  font-size: 26rpx;
  color: var(--sh-sub);
}
.row__input {
  flex: 1;
}
.mt {
  margin-top: 16rpx;
}
.hint {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  line-height: 1.6;
}
.conflict {
  margin-top: 16rpx;
  padding: 16rpx;
  border-radius: 12rpx;
  background: var(--sh-warning-tint);
}
.conflict__l {
  display: block;
  font-size: 24rpx;
  line-height: 1.6;
}
.conflict__h {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
}
.exposure {
  margin-top: 16rpx;
  padding: 16rpx;
  border-radius: 12rpx;
  background: var(--sh-primary-tint);
  font-size: 26rpx;
  font-weight: 600;
}
.bad {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: var(--sh-danger);
  line-height: 1.6;
}
.nav {
  display: flex;
  gap: 16rpx;
  margin-top: 24rpx;
}
.nav__b {
  flex: 1;
}
</style>
