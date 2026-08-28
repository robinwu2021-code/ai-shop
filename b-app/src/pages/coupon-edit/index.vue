<script setup lang="ts">
// 建券（P4）。五段式：**权益 → 门槛 → 范围 → 有效期 → 发行量与预算**。
//
// 为什么分段而不是一张长表单：这五段里每一段都可能让商家改主意
// （「原来折扣券必须封顶，那我改成满减」），而一张 12 个输入框的表单
// 要填到最后一个才知道前面填错了。
//
// ⚠️ 这一页的校验**与后端一字不差**（mock 里也一样）。页面放宽的话，
// 商家在演示环境填得过、连真后端就被拒，而那时没人记得是哪一条拦的。
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money, toMinor } from "@shared/utils/money";
import type { MerchantCouponDraft } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const couponNo = ref("");
const saving = ref(false);

const form = ref({
  title: "",
  benefitMode: "CASH",
  /** CASH：元；PERCENT：几折（8.5），存的时候换成万分比 */
  value: "",
  cap: "",
  minAmount: "",
  validDays: "7",
  redeemMode: "ORDER",
  timesTotal: "1",
  totalCount: "100",
  perUserLimit: "1",
  budget: "",
});

const isPercent = computed(() => form.value.benefitMode === "PERCENT");

/**
 * 单张最大优惠 × 发行量。**边填边显示** ——
 * 商家填的是张数，而他要为之负责的是钱。
 */
const exposure = computed(() => {
  const per = isPercent.value ? toMinor(form.value.cap) : toMinor(form.value.value);
  const n = Number(form.value.totalCount || 0);
  const times = Number(form.value.timesTotal || 1);
  if (!per || !n) return 0;
  return per * n * times;
});

const budgetTooLow = computed(() => {
  const b = toMinor(form.value.budget);
  return b > 0 && exposure.value > 0 && b < exposure.value;
});

async function loadExisting(no: string) {
  const c = await api.mCoupon(no).catch(() => null);
  if (!c) return;
  couponNo.value = c.couponNo;
  form.value = {
    title: c.title,
    benefitMode: c.benefitMode,
    value: c.benefitMode === "PERCENT"
      ? String(c.benefitValue / 1000)
      : String((c.benefitValue / 100).toFixed(2)),
    cap: c.benefitCapMinor ? String((c.benefitCapMinor / 100).toFixed(2)) : "",
    minAmount: c.minAmountMinor ? String((c.minAmountMinor / 100).toFixed(2)) : "",
    validDays: String(c.validDays ?? 7),
    redeemMode: c.redeemMode,
    timesTotal: String(c.timesTotal),
    totalCount: c.totalCount == null ? "" : String(c.totalCount),
    perUserLimit: String(c.perUserLimit),
    budget: c.budgetMinor ? String((c.budgetMinor / 100).toFixed(2)) : "",
  };
}

async function save() {
  if (saving.value) return;
  if (!form.value.title.trim()) {
    uni.showToast({ title: t("couponEdit.needTitle"), icon: "none" });
    return;
  }
  /*
   * 折扣按「几折」输入、按万分比提交：8.5 折 → 8500。
   * 让商家直接填 8500 的话，他迟早会填 85 或 88 —— 那在这个口径里是
   * 「顾客付 0.88%」，等于白送。单位转换放在端上，出错的机会就少一次。
   */
  const benefitValue = isPercent.value
    ? Math.round(Number(form.value.value || 0) * 1000)
    : toMinor(form.value.value);
  if (isPercent.value && (benefitValue < 1000 || benefitValue >= 10000)) {
    uni.showToast({ title: t("couponEdit.badRate"), icon: "none" });
    return;
  }
  if (isPercent.value && !toMinor(form.value.cap)) {
    uni.showToast({ title: t("couponEdit.needCap"), icon: "none" });
    return;
  }
  if (!isPercent.value && !benefitValue) {
    uni.showToast({ title: t("couponEdit.needValue"), icon: "none" });
    return;
  }
  if (budgetTooLow.value) {
    uni.showToast({ title: t("couponEdit.budgetTooLow"), icon: "none" });
    return;
  }

  const draft: MerchantCouponDraft = {
    couponNo: couponNo.value || undefined,
    title: form.value.title.trim(),
    benefitMode: form.value.benefitMode,
    benefitValue,
    benefitCapMinor: isPercent.value ? toMinor(form.value.cap) : null,
    minAmountMinor: toMinor(form.value.minAmount) || null,
    scopeType: "ALL",
    scopeRefs: [],
    validityMode: "RELATIVE",
    validDays: Number(form.value.validDays || 7),
    issueMode: "TARGETED",
    redeemMode: form.value.redeemMode,
    timesTotal: Number(form.value.timesTotal || 1),
    totalCount: form.value.totalCount ? Number(form.value.totalCount) : null,
    perUserLimit: Number(form.value.perUserLimit || 1),
    budgetMinor: toMinor(form.value.budget) || null,
  };

  saving.value = true;
  try {
    await api.mSaveCoupon(draft);
    uni.showToast({ title: t("couponEdit.saved"), icon: "none" });
    setTimeout(() => uni.navigateBack(), 600);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    saving.value = false;
  }
}

onLoad((q) => {
  if (q?.couponNo) void loadExisting(q.couponNo as string);
});
</script>

<template>
  <sh-scaffold title-key="couponEdit.title" :denied="!merchant.can('biz:campaign')">
    <view class="sh-card">
      <text class="field__label">{{ $t("couponEdit.name") }}</text>
      <input maxlength="64" v-model="form.title" class="field__input" :placeholder="$t('couponEdit.namePh')" />
    </view>

    <!-- ① 权益 -->
    <view class="sh-card sh-mt-sm">
      <text class="field__label">{{ $t("couponEdit.benefit") }}</text>
      <view class="chips">
        <text
          v-for="m in ['CASH', 'PERCENT']"
          :key="m"
          class="sh-chip"
          :class="{ 'sh-chip--primary': form.benefitMode === m }"
          @tap="form.benefitMode = m"
        >
          {{ $t(`couponEdit.mode.${m}`) }}
        </text>
      </view>

      <view class="sh-row mt2 sh-mt-xs">
        <text class="txt-sub row__label">
          {{ isPercent ? $t("couponEdit.rate") : $t("couponEdit.face") }}
        </text>
        <input maxlength="10" v-model="form.value" class="field__input row__input" type="digit"
               :placeholder="isPercent ? '8.5' : '5.00'" />
      </view>
      <!-- 折扣券必须封顶：不封顶的敞口随订单金额无限放大 -->
      <view v-if="isPercent" class="sh-row sh-mt-xs">
        <text class="txt-sub row__label">{{ $t("couponEdit.cap") }}</text>
        <input maxlength="10" v-model="form.cap" class="field__input row__input" type="digit" placeholder="20.00" />
      </view>
      <text v-if="isPercent" class="sh-muted sh-hint">{{ $t("couponEdit.capHint") }}</text>
    </view>

    <!-- ② 门槛 -->
    <view class="sh-card sh-mt-sm">
      <view class="sh-row sh-mt-xs">
        <text class="txt-sub row__label">{{ $t("couponEdit.minAmount") }}</text>
        <input maxlength="10" v-model="form.minAmount" class="field__input row__input" type="digit"
               :placeholder="$t('couponEdit.minAmountPh')" />
      </view>
    </view>

    <!-- ③ 核销方式（范围一期只做全店，见 contract 里的说明） -->
    <view class="sh-card sh-mt-sm">
      <text class="field__label">{{ $t("couponEdit.redeem") }}</text>
      <view class="chips">
        <text
          v-for="m in ['ORDER', 'STORE_CODE']"
          :key="m"
          class="sh-chip"
          :class="{ 'sh-chip--primary': form.redeemMode === m }"
          @tap="form.redeemMode = m"
        >
          {{ $t(`couponEdit.redeemMode.${m}`) }}
        </text>
      </view>
      <text class="sh-muted sh-hint">{{ $t("couponEdit.redeemHint") }}</text>

      <view v-if="form.redeemMode === 'STORE_CODE'" class="sh-row mt2 sh-mt-xs">
        <text class="txt-sub row__label">{{ $t("couponEdit.times") }}</text>
        <input maxlength="6" v-model="form.timesTotal" class="field__input row__input" type="number" />
      </view>
    </view>

    <!-- ④ 有效期 -->
    <view class="sh-card sh-mt-sm">
      <view class="sh-row sh-mt-xs">
        <text class="txt-sub row__label">{{ $t("couponEdit.validDays") }}</text>
        <input maxlength="4" v-model="form.validDays" class="field__input row__input" type="number" />
      </view>
      <text class="sh-muted sh-hint">{{ $t("couponEdit.validHint") }}</text>
    </view>

    <!-- ⑤ 发行量与预算 -->
    <view class="sh-card sh-mt-sm">
      <view class="sh-row sh-mt-xs">
        <text class="txt-sub row__label">{{ $t("couponEdit.total") }}</text>
        <input maxlength="6" v-model="form.totalCount" class="field__input row__input" type="number" />
      </view>
      <view class="sh-row sh-mt-xs">
        <text class="txt-sub row__label">{{ $t("couponEdit.perUser") }}</text>
        <input maxlength="6" v-model="form.perUserLimit" class="field__input row__input" type="number" />
      </view>
      <view class="sh-row sh-mt-xs">
        <text class="txt-sub row__label">{{ $t("couponEdit.budget") }}</text>
        <input maxlength="10" v-model="form.budget" class="field__input row__input" type="digit"
               :placeholder="$t('couponEdit.budgetPh')" />
      </view>

      <!-- 他填的是张数，要为之负责的是钱 -->
      <view v-if="exposure > 0" class="txt-strong exposure" :class="{ 'is-bad': budgetTooLow }">
        {{ $t("couponEdit.exposure", { n: money(exposure) }) }}
      </view>
      <text v-if="budgetTooLow" class="txt-caption bad">{{ $t("couponEdit.budgetTooLow") }}</text>
    </view>

    <button class="sh-btn sh-btn--primary save" :disabled="saving" @tap="save">
      {{ $t("couponEdit.save") }}
    </button>
  </sh-scaffold>
</template>

<style scoped>
.mt2 {
  margin-top: 16rpx;
}
.chips {
  display: flex;
  gap: 12rpx;
  margin-top: 12rpx;
}

.row__label {
  width: 180rpx;
}
.row__input {
  flex: 1;
}

.exposure {
  margin-top: 16rpx;
  padding: 16rpx;
  border-radius: 16rpx;
  background: var(--sh-primary-tint);
}
.exposure.is-bad {
  background: var(--sh-danger-tint);
}
.bad {
  display: block;
  margin-top: 8rpx;
  color: var(--sh-danger);
}

</style>
