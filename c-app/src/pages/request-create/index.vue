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

      <input v-model="title" class="field__input" :placeholder="$t('request.titlePh')" maxlength="30" />
      <textarea v-model="desc" class="field__area ta" :placeholder="$t('request.descPh')" maxlength="200" />

      <view class="sh-row sh-row--between row sh-mt-md">
        <text class="txt-sub row__k txt-ink">{{ $t("request.expect") }}</text>
        <sh-stepper v-model="qty"></sh-stepper>
      </view>

      <view class="sh-row sh-row--between row sh-mt-md">
        <text class="txt-sub row__k txt-ink">{{ $t("request.budget") }}</text>
        <input
          maxlength="10"
          v-model="budget"
          class="txt-sub row__input sh-num"
          type="digit"
          :placeholder="$t('request.budgetPh')"
        />
      </view>
    </view>

    <view class="sh-card block note">
      <text class="txt-caption">{{ $t("request.createNote") }}</text>
    </view>

    <sh-actionbar :pad="180">
      <view class="sh-btn" :class="{ 'is-disabled': !valid }" @tap="submit">
        {{ submitting ? $t("confirm.submitting") : $t("request.createSubmit") }}
      </view>
    </sh-actionbar>
  </sh-scaffold>
</template>

<style scoped>
.tip {
  display: block;
}
/* 只留纵向间距。此前这里的字号是 28rpx —— 同一个输入框，
   六个页面写出了 26 / 28 / 30 三种字号 */
.field__input {
  margin-top: 24rpx;
}
/* 盒子归 .field__area，这里只说「这一个框多高」—— 尺寸是版面，不是件的属性 */
.ta {
  min-height: 180rpx;
  margin-top: 16rpx;
}
.row {
  gap: 24rpx;
}
.row__input {
  flex: 1;
  text-align: end;
  color: var(--sh-ink);
}
.note {
  background: var(--sh-faint);
}
</style>
