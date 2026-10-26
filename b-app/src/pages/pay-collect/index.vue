<template>
  <sh-scaffold title-key="bizPay.collect.title" :denied="!merchant.can('mch:pay:collect')">
    <view v-if="!issued" class="sh-card">
      <sh-section :title="String(t('bizPay.collect.amount'))">
        <sh-go :text="amountText || String(t('bizPay.collect.amountPlaceholder'))" @tap="askAmount"></sh-go>
      </sh-section>
      <sh-section :title="String(t('bizPay.collect.subject'))">
        <sh-go :text="subject || String(t('bizPay.collect.subjectPlaceholder'))" @tap="askSubject"></sh-go>
      </sh-section>
      <text class="sh-hint txt-quiet">{{ t("bizPay.collect.hint") }}</text>
      <view class="sh-btn sh-mt-md" :class="{ 'is-disabled': !amountMinor || busy }" @tap="generate">
        {{ t("bizPay.collect.generate") }}
      </view>
      <!-- 没生成出来要留在页面上说清，不能只闪一下 toast：商家正对着顾客，回头一看什么都没有 -->
      <text v-if="collectFailed" class="sh-hint is-danger">{{ collectError || t("bizPay.common.failed") }}</text>
    </view>

    <!-- 收款码出来以后：金额与有效期压在码上方，商家把手机翻过去给顾客扫 -->
    <view v-else class="sh-card center">
      <text class="sh-muted">{{ t("bizPay.collect.amount") }}</text>
      <text class="txt-mega sh-num amt">{{ money(issued.amountMinor) }}</text>
      <biz-pay-qr :link="issued.checkoutUrl"></biz-pay-qr>
      <text class="sh-hint txt-quiet">{{ t("bizPay.collect.scanHint") }}</text>
      <text class="txt-caption txt-quiet sh-num">{{ t("bizPay.collect.expiresAt", { time: expireText }) }}</text>
      <view class="sh-row sh-mt-md actions">
        <view class="sh-btn sh-btn--soft" @tap="reset">{{ t("bizPay.collect.again") }}</view>
        <view class="sh-btn sh-btn--soft" @tap="goFlow">{{ t("bizPay.collect.viewFlow") }}</view>
      </view>
    </view>
  </sh-scaffold>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money, toMinor, toMajor } from "@shared/utils/money";
import { prompt } from "@ai-shop/ui/prompt";
import { ROUTES } from "@/shared/nav";
import type { PayCollectResult } from "@/api/pay";

const { t } = useI18n();
const merchant = useMerchantStore();
const amountMinor = ref(0);
const subject = ref("");
const busy = ref(false);
/** 这次没生成出来（与「还没点」是两件事） */
const collectFailed = ref(false);
const collectError = ref("");
const issued = ref<(PayCollectResult & { amountMinor: number }) | null>(null);

const amountText = computed(() => (amountMinor.value ? money(amountMinor.value) : ""));
const expireText = computed(() => {
  if (!issued.value) return "";
  const d = new Date(issued.value.expireAt);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
});

async function askAmount() {
  const v = await prompt({
    title: String(t("bizPay.collect.amount")),
    value: amountMinor.value ? toMajor(amountMinor.value) : "",
    type: "digit",
  });
  if (v == null) return;
  const minor = toMinor(v);
  if (!Number.isFinite(minor) || minor <= 0) {
    uni.showToast({ title: String(t("bizPay.collect.invalidAmount")), icon: "none" });
    return;
  }
  amountMinor.value = minor;
}

async function askSubject() {
  const v = await prompt({ title: String(t("bizPay.collect.subject")), value: subject.value, maxlength: 64 });
  if (v != null) subject.value = v.trim();
}

async function generate() {
  if (!amountMinor.value || busy.value) return;
  busy.value = true;
  try {
    const r = await api.mPayCollect({ amountMinor: amountMinor.value, subject: subject.value || undefined });
    issued.value = { ...r, amountMinor: amountMinor.value };
    collectFailed.value = false;
  } catch (e) {
    collectFailed.value = true;
    collectError.value = (e as Error).message || "";
  } finally {
    busy.value = false;
  }
}

function reset() {
  issued.value = null;
  amountMinor.value = 0;
  subject.value = "";
}

function goFlow() {
  uni.navigateTo({ url: ROUTES.payFlow });
}
</script>

<style scoped>
.center {
  text-align: center;
}
.amt {
  display: block;
  margin-top: 8rpx;
}
.actions {
  gap: 16rpx;
  justify-content: center;
}
</style>
