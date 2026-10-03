<template>
  <sh-scaffold title-key="payoutAccount.title" :denied="!merchant.can('biz:finance')">
    <!-- 使用中的那张。没有就给一句说明，别让这一块空着 -->
    <view class="sh-card">
      <text class="sh-muted">{{ t("payoutAccount.current") }}</text>
      <template v-if="active">
        <text class="txt-mega sh-num acct">{{ active.accountMasked }}</text>
        <text class="sh-hint txt-quiet">{{ active.accountName }}</text>
        <text v-if="active.bankName" class="sh-hint txt-quiet">
          {{ active.bankName }}{{ active.bankBranch ? " · " + active.bankBranch : "" }}
        </text>
      </template>
      <template v-else>
        <text class="txt-strong none">{{ t("payoutAccount.none") }}</text>
        <text class="sh-hint txt-quiet">{{ t("payoutAccount.noneHint") }}</text>
      </template>
    </view>

    <!-- 添加 -->
    <view class="sh-card">
      <text class="txt-strong">{{ t("payoutAccount.add") }}</text>

      <view class="sh-row sh-mt-sm types">
        <view
          v-for="o in TYPES"
          :key="o"
          class="sh-chip type"
          :class="form.accountType === o ? 'sh-chip--solid' : ''"
          @tap="form.accountType = o"
        >
          {{ t(o === "CORPORATE" ? "payoutAccount.typeCorporate" : "payoutAccount.typePersonal") }}
        </view>
      </view>

      <input
        v-model="form.accountName"
        maxlength="64"
        class="field__input sh-mt-sm"
        :placeholder="String(t('payoutAccount.holder'))"
      />
      <text class="sh-hint txt-quiet">{{ t("payoutAccount.holderHint") }}</text>

      <input
        v-model="form.accountNumber"
        type="number"
        maxlength="32"
        class="field__input sh-num sh-mt-sm"
        :placeholder="String(t('payoutAccount.number'))"
      />
      <input
        v-model="form.bankName"
        maxlength="64"
        class="field__input sh-mt-sm"
        :placeholder="String(t('payoutAccount.bank'))"
      />
      <input
        v-model="form.bankBranch"
        maxlength="64"
        class="field__input sh-mt-sm"
        :placeholder="String(t('payoutAccount.branch'))"
      />

      <view
        class="sh-btn sh-mt-sm"
        :class="{ 'is-disabled': !canSubmit || submitting }"
        @tap="submit"
      >
        {{ submitting ? t("payoutAccount.submitting") : t("payoutAccount.submit") }}
      </view>
      <text v-if="blockReason" class="sh-hint is-warning">{{ blockReason }}</text>
    </view>

    <!-- 记录 -->
    <view class="sh-card">
      <text class="txt-strong">{{ t("payoutAccount.records") }}</text>
      <sh-empty
        v-if="!list.length"
        :pending="!loaded"
        :failed="failed"
        @retry="load"
        bare
        :text="String(t('payoutAccount.noRecords'))"
      ></sh-empty>
      <view
        v-for="a in list"
        :key="a.accountNo"
        class="sh-row sh-row--between sh-row--divided"
      >
        <view>
          <text class="txt-strong sh-num rec__no">{{ a.accountMasked }}</text>
          <text class="txt-caption txt-quiet rec__sub">{{ a.accountName }}</text>
        </view>
        <view class="rec__r">
          <text class="txt-caption" :class="statusClass(a.status)">
            {{ t(`payoutAccount.status.${a.status}`) }}
          </text>
          <text v-if="a.auditRemark" class="sh-hint txt-quiet">{{ a.auditRemark }}</text>
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<script setup lang="ts">
// 我的收款账户（V358，ADR-011 自营供应商模式）。
//
// 自营模式下平台按账期把货款转到这张卡。**换卡是提交一张新的等运营核**，
// 不是原地改账号 —— 原地改会让「上一期打给谁」这个问题失去答案。
//
// ⚠️ 说明写成 script 里的 `//` 而不是文件顶部的 HTML 注释：
// 顶部 HTML 注释会让 UnoCSS 的 transformer 报
// 「Cannot split a chunk that has already been edited」，整个模块 500 加载不出来，
// 而 vue-tsc 是通过的 —— 类型检查证明不了能构建。同理这里不用带冒号前缀的变体类，
// 连注释里都不写出那些类名（transformer 不区分代码与注释）。
import { computed, reactive, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { PayoutAccount, PayoutAccountType } from "@/api/contract";

const { t } = useI18n();
/*
 * 页面门禁。收款账户与结算同权限 —— 它答的是「货款打到哪张卡」，
 * 读到的是账户掩码，写进去的是下一期钱的去向，两边都属于钱的事。
 */
const merchant = useMerchantStore();

const TYPES: PayoutAccountType[] = ["CORPORATE", "PERSONAL_BANK_CARD"];

const list = ref<PayoutAccount[]>([]);
const submitting = ref(false);
const form = reactive({
  accountType: "CORPORATE" as PayoutAccountType,
  accountName: "",
  accountNumber: "",
  bankName: "",
  bankBranch: "",
});

/** 使用中的那张。**只认 ACTIVE** —— 待审的收不到这一期的货款 */
const active = computed(() => list.value.find((a) => a.status === "ACTIVE") ?? null);

/** 有一张在审就不能再提。与后端同一条判据，否则点了才知道被拒 */
const hasPending = computed(() => list.value.some((a) => a.status === "PENDING"));

const canSubmit = computed(() =>
  !!form.accountName.trim() && !!form.accountNumber.trim() && !hasPending.value);

/** 按钮为什么点不了。只是灰掉的话，他不知道是没填完还是有单在审 */
const blockReason = computed(() => {
  if (hasPending.value) return t("payoutAccount.blockPending");
  if (!form.accountName.trim() || !form.accountNumber.trim()) {
    return t("payoutAccount.blockIncomplete");
  }
  return "";
});

function statusClass(s: string) {
  if (s === "ACTIVE") return "is-success";
  if (s === "REJECTED") return "is-danger";
  if (s === "DISABLED") return "txt-quiet";
  return "is-warning";
}

const loaded = ref(false);
const failed = ref(false);

async function load() {
  try {
    list.value = await api.mPayoutAccounts();
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

async function submit() {
  if (!canSubmit.value) return;
  submitting.value = true;
  try {
    await api.mSubmitPayoutAccount({
      accountType: form.accountType,
      accountName: form.accountName.trim(),
      accountNumber: form.accountNumber.trim(),
      bankName: form.bankName.trim() || undefined,
      bankBranch: form.bankBranch.trim() || undefined,
    });
    // 账号明文只在这一次请求里存在 —— 提交完就从表单里清掉，不留在内存里
    form.accountNumber = "";
    uni.showToast({ title: t("payoutAccount.submitted"), icon: "none" });
    await load();
  } finally {
    submitting.value = false;
  }
}

onShow(load);
</script>

<style scoped>
.acct {
  display: block;
  margin-top: 8rpx;
}
.none {
  display: block;
  margin-top: 8rpx;
}
.types {
  gap: 16rpx;
}
.rec__no {
  display: block;
}
.rec__sub {
  display: block;
  margin-top: 4rpx;
}
/* 右列贴右。用逻辑属性 —— 阿语下要跟着翻 */
.rec__r {
  text-align: end;
}
</style>
