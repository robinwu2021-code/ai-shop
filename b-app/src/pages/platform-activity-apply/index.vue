<script setup lang="ts">
/*
 * 活动报名（原型 s28）。两组按信息来源分：
 *   「活动规则」只读（行尾没有 ›）—— 平台定好的；
 *   「报名信息」可填 —— 报哪几件货、报多少份，底下算出「最多承担」。
 * 出资写成**每单金额**（平台补贴 ¥10 / 单、商家承担 ¥10 / 单），不写比例：商家看的是一单自己出多少。
 *
 * 报过名的：待审的可以改了再提交、也可以撤回；已通过的只读；被驳回的显示理由、可以改了再报。
 */
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { confirm } from "@ai-shop/ui/prompt";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import type { PlatformActivity } from "@shared/types";

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));
const merchant = useMerchantStore();

const activityNo = ref("");
const a = ref<PlatformActivity | null>(null);
const goods = ref<Array<{ goodsNo: string; title: string }>>([]);
const picked = ref<string[]>([]);
const quota = ref("");
const showGoods = ref(false);
const failed = ref(false);
const busy = ref(false);

async function load() {
  try {
    const [one, g] = await Promise.all([api.mPlatformActivity(activityNo.value), api.mGoodsList({ size: 100 })]);
    a.value = one;
    goods.value = g.records.filter((x) => x.onSale).map((x) => ({ goodsNo: x.goodsNo, title: x.title }));
    if (one.mine) {
      picked.value = [...one.mine.goodsNos];
      quota.value = String(one.mine.quota);
    }
    failed.value = false;
  } catch {
    failed.value = true;
  }
}

const status = computed(() => a.value?.mine?.status ?? null);
/** 能填：没报过、被驳回、撤回了、或还在待审（审核前可改），且没过截止 */
const editable = computed(() => {
  const x = a.value;
  if (!x || x.status !== "RUNNING" || (x.enrollDeadline ?? 0) < Date.now()) return false;
  return status.value !== "APPROVED";
});

const maxMerchant = computed(() => (a.value?.perOrderMerchantMinor ?? 0) * (Number(quota.value) || 0));

function md(ms?: number | null): string {
  if (!ms) return "";
  const d = new Date(ms);
  return `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

const ruleText = computed(() => {
  const x = a.value;
  if (!x) return "";
  const cut = money(x.benefitAmountMinor ?? 0);
  if (x.triggerType === "AMOUNT") return tt("platformActs.ruleAmount", { m: money(x.triggerAmountMinor ?? 0), n: cut });
  if (x.triggerType === "QTY") return tt("platformActs.ruleQty", { m: x.triggerQty ?? 0, n: cut });
  return tt("platformActs.ruleAny", { n: cut });
});

function toggle(no: string) {
  const i = picked.value.indexOf(no);
  if (i >= 0) picked.value.splice(i, 1);
  else picked.value.push(no);
}

async function submit() {
  if (!a.value || busy.value || !editable.value) return;
  if (!picked.value.length) {
    uni.showToast({ title: tt("platformApply.needGoods"), icon: "none" });
    return;
  }
  if (!(Number(quota.value) > 0)) {
    uni.showToast({ title: tt("platformApply.needQuota"), icon: "none" });
    return;
  }
  busy.value = true;
  try {
    await api.mEnroll(a.value.activityNo, { goodsNos: picked.value, quota: Number(quota.value) });
    uni.showToast({ title: tt("platformApply.submitted"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

async function withdraw() {
  if (!a.value || busy.value) return;
  const ok = await confirm({ title: tt("platformApply.withdrawTitle"), hint: tt("platformApply.withdrawBody") });
  if (!ok) return;
  busy.value = true;
  try {
    await api.mWithdrawEnrollment(a.value.activityNo);
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

function back() {
  uni.navigateBack();
}

onLoad((q) => {
  activityNo.value = String(q?.activityNo ?? "");
  void load();
});
</script>

<template>
  <sh-scaffold title-key="platformApply.title" :denied="!merchant.can('biz:campaign')" :failed="failed" @retry="load">
    <template v-if="a">
      <text class="txt-caption sh-muted grp">{{ $t("platformApply.groupRule") }}</text>
      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("platformApply.activity") }}</text>
          <text class="txt-body">{{ a.name }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("platformApply.time") }}</text>
          <text class="txt-body sh-num">{{ md(a.startAt) }} {{ $t("platformActs.to") }} {{ md(a.endAt) }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("platformApply.rule") }}</text>
          <text class="txt-body sh-num">{{ ruleText }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("platformApply.platformPer") }}</text>
          <text class="txt-body sh-num">{{ $t("platformApply.perOrder", { n: money(a.perOrderPlatformMinor) }) }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("platformApply.merchantPer") }}</text>
          <text class="txt-body sh-num">{{ $t("platformApply.perOrder", { n: money(a.perOrderMerchantMinor) }) }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("platformApply.settle") }}</text>
          <text class="txt-body">{{ $t("platformApply.settleValue") }}</text>
        </view>
      </view>

      <text class="txt-caption sh-muted grp">{{ $t("platformApply.groupEnroll") }}</text>
      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between" @tap="editable && (showGoods = true)">
          <text class="txt-body sh-muted cell__k">{{ $t("platformApply.goods") }}</text>
          <view class="sh-row">
            <text class="txt-body" :class="{ 'sh-muted': !picked.length }">
              {{ picked.length ? $t("platformApply.goodsN", { n: picked.length }) : $t("platformApply.pick") }}
            </text>
            <sh-icon v-if="editable" name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
          </view>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted cell__k">{{ $t("platformApply.quota") }}</text>
          <input v-model="quota" type="number" maxlength="6" :disabled="!editable"
                 class="txt-body cell__input sh-num" :placeholder="$t('platformApply.quotaPh')" />
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("platformApply.maxMerchant") }}</text>
          <text class="txt-body sh-num">{{ money(maxMerchant) }}</text>
        </view>
      </view>

      <view v-if="status === 'REJECTED'" class="sh-notice sh-notice--danger">
        <text class="txt-caption">{{ $t("platformApply.rejected", { r: a.mine?.rejectReason || "" }) }}</text>
      </view>
      <view v-else-if="status === 'APPROVED'" class="sh-notice sh-notice--success">
        <text class="txt-caption">{{ $t("platformApply.approved") }}</text>
      </view>
      <view v-else class="sh-notice">
        <text class="txt-caption">{{ status === "SUBMITTED" ? $t("platformApply.pending") : $t("platformApply.note") }}</text>
      </view>

      <sh-actionbar v-if="editable">
        <view class="sh-row bar">
          <view v-if="status === 'SUBMITTED'" class="sh-btn sh-btn--muted sh-fill" :class="{ 'is-disabled': busy }"
                @tap="withdraw">{{ $t("platformApply.withdraw") }}</view>
          <view v-else class="sh-btn sh-btn--muted sh-fill" @tap="back">{{ $t("platformApply.cancel") }}</view>
          <view class="sh-btn bar__main" :class="{ 'is-disabled': busy }" @tap="submit">
            {{ status === "SUBMITTED" ? $t("platformApply.resubmit") : $t("platformApply.submit") }}
          </view>
        </view>
      </sh-actionbar>

      <sh-sheet :visible="showGoods" :title="tt('platformApply.goods')" @close="showGoods = false">
        <view class="sh-cells">
          <view v-for="g in goods" :key="g.goodsNo" class="sh-cell sh-row sh-row--between" @tap="toggle(g.goodsNo)">
            <text class="txt-body" :class="{ 'txt-primary': picked.includes(g.goodsNo) }">{{ g.title }}</text>
            <sh-icon v-if="picked.includes(g.goodsNo)" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
          </view>
        </view>
        <sh-empty v-if="!goods.length" line :text="tt('platformApply.noGoods')"></sh-empty>
      </sh-sheet>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.grp {
  display: block;
  padding: 0 8rpx;
}
.cell__k {
  flex-shrink: 0;
}
.cell__input {
  flex: 1;
  text-align: right;
}
.bar {
  gap: 16rpx;
  width: 100%;
}
.bar__main {
  flex: 2;
}
</style>
