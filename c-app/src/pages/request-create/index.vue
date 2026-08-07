<script setup lang="ts">
// 发起求团。
//
// 表单刻意做短：邻里发帖不是填工单，字段多一个，发起率就低一截。
// 只留四个：想买什么、说明、要几件、心理价位（选填）。
// 不要求绑定商品 —— 这正是求团区别于商家团的地方：**发起时商品还不存在**。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useCommunityStore } from "@/stores/community";
import { ROUTES } from "@shared/utils/constants";
import { toMinor } from "@shared/utils/money";

const { t } = useI18n();
const community = useCommunityStore();

const title = ref("");
const desc = ref("");
const qty = ref(1);
const budget = ref("");
const submitting = ref(false);

const valid = computed(() => title.value.trim().length >= 4 && !submitting.value);

function step(d: number) {
  qty.value = Math.max(1, qty.value + d);
}

async function submit() {
  if (!valid.value) return;
  const pickupNo = community.pickup?.pickupNo;
  if (!pickupNo) {
    uni.showToast({ title: String(t("request.needPickup")), icon: "none" });
    return;
  }
  submitting.value = true;
  try {
    const r = await api.createRequest({
      pickupNo,
      title: title.value.trim(),
      desc: desc.value.trim(),
      expectQty: qty.value,
      budgetMinor: budget.value ? toMinor(budget.value) : undefined,
    });
    uni.redirectTo({ url: `${ROUTES.request}?requestNo=${r.requestNo}` });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <sh-scaffold title-key="request.createTitle">
    <view class="sh-card">
      <text class="sh-muted tip">{{ $t("request.createTip", { p: community.pickup?.name ?? "" }) }}</text>

      <input v-model="title" class="field" :placeholder="$t('request.titlePh')" maxlength="30" />
      <textarea v-model="desc" class="ta" :placeholder="$t('request.descPh')" maxlength="200" />

      <view class="row">
        <text class="row__k">{{ $t("request.expect") }}</text>
        <view class="stepper">
          <view class="stepper__btn" @tap="step(-1)"><text>−</text></view>
          <text class="stepper__num sh-num">{{ qty }}</text>
          <view class="stepper__btn" @tap="step(1)"><text>＋</text></view>
        </view>
      </view>

      <view class="row">
        <text class="row__k">{{ $t("request.budget") }}</text>
        <input
          v-model="budget"
          class="row__input sh-num"
          type="digit"
          :placeholder="$t('request.budgetPh')"
        />
      </view>
    </view>

    <view class="sh-card block note">
      <text class="note__text">{{ $t("request.createNote") }}</text>
    </view>

    <view class="actionbar">
      <view class="sh-btn" :class="{ 'is-disabled': !valid }" @tap="submit">
        {{ submitting ? $t("confirm.submitting") : $t("request.createSubmit") }}
      </view>
    </view>
    <view class="spacer" />
  </sh-scaffold>
</template>

<style scoped>
.tip {
  display: block;
  line-height: 1.6;
}
.field {
  background: var(--sh-faint);
  border-radius: 24rpx;
  padding: 26rpx 28rpx;
  font-size: 28rpx;
  color: var(--sh-ink);
  margin-top: 24rpx;
}
.ta {
  width: 100%;
  box-sizing: border-box;
  min-height: 180rpx;
  background: var(--sh-faint);
  border-radius: 24rpx;
  padding: 24rpx 28rpx;
  font-size: 26rpx;
  color: var(--sh-ink);
  margin-top: 16rpx;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24rpx;
  margin-top: 28rpx;
}
.row__k {
  font-size: 26rpx;
  color: var(--sh-ink);
}
.row__input {
  flex: 1;
  text-align: end;
  font-size: 26rpx;
  color: var(--sh-ink);
}
.stepper {
  display: flex;
  align-items: center;
  gap: 8rpx;
  background: var(--sh-faint);
  border-radius: 9999px;
  padding: 6rpx;
}
.stepper__btn {
  width: 56rpx;
  height: 56rpx;
  border-radius: 9999px;
  background: var(--sh-surface);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--sh-ink);
  font-size: 30rpx;
}
.stepper__num {
  min-width: 56rpx;
  text-align: center;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.block {
  margin-top: 20rpx;
}
.note {
  background: var(--sh-faint);
}
.note__text {
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.7;
}
.actionbar {
  position: fixed;
  inset-inline: 28rpx;
  bottom: calc(28rpx + env(safe-area-inset-bottom));
}
.is-disabled {
  opacity: 0.45;
}
.spacer {
  height: 180rpx;
}
</style>
