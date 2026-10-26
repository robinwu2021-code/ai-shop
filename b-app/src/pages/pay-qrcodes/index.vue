<template>
  <sh-scaffold title-key="bizPay.qr.title" :denied="!merchant.can('mch:pay:qr')">
    <view class="sh-card">
      <text class="sh-hint txt-quiet">{{ t("bizPay.qr.hint") }}</text>
      <view class="sh-btn sh-mt-md" @tap="create">{{ t("bizPay.qr.add") }}</view>
    </view>
    <sh-empty v-if="!codes.length" :pending="!loaded" :failed="failed" @retry="load" :text="String(t('bizPay.qr.empty'))"></sh-empty>
    <view v-for="c in codes" :key="c.qrCode" class="sh-card">
      <view class="sh-row sh-row--between">
        <text class="txt-strong">{{ c.kind === "TABLE" ? t("bizPay.qr.table", { ref: c.tableRef }) : t("bizPay.qr.store") }}</text>
        <text class="txt-caption" :class="c.status === 'ACTIVE' ? 'txt-quiet' : 'is-danger'">{{ t(`bizPay.qr.st_${c.status}`) }}</text>
      </view>
      <text class="txt-caption txt-quiet sh-num">{{ t("bizPay.qr.limits", { single: money(c.singleLimitMinor, cur(c)), daily: money(c.dailyLimitMinor, cur(c)) }) }}</text>
      <biz-pay-qr v-if="c.status === 'ACTIVE' && shown === c.qrCode" :link="c.url"></biz-pay-qr>
      <view v-if="c.status === 'ACTIVE'" class="sh-row sh-mt-md actions">
        <view class="sh-btn sh-btn--soft" @tap="shown = shown === c.qrCode ? '' : c.qrCode">
          {{ t(shown === c.qrCode ? "bizPay.qr.hide" : "bizPay.qr.show") }}
        </view>
        <view class="sh-btn sh-btn--soft is-danger" @tap="disable(c)">{{ t("bizPay.qr.disable") }}</view>
      </view>
    </view>
  </sh-scaffold>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money, toMinor } from "@shared/utils/money";
import { confirm, pick, prompt } from "@ai-shop/ui/prompt";
import type { CurrencyCode } from "@shared/types";
import type { PayQrCode } from "@/api/pay";

const { t } = useI18n();
const merchant = useMerchantStore();
const codes = ref<PayQrCode[]>([]);
const shown = ref("");
const loaded = ref(false);
const failed = ref(false);

const cur = (c: PayQrCode) => c.currency as CurrencyCode;

async function askMoney(title: string, value: string): Promise<number | null> {
  const v = await prompt({ title, value, type: "digit" });
  if (v == null) return null;
  const minor = toMinor(v);
  if (!Number.isFinite(minor) || minor <= 0) {
    uni.showToast({ title: String(t("bizPay.common.invalidAmount")), icon: "none" });
    return null;
  }
  return minor;
}

/** 门店码贴收银台、桌台码贴桌上；限额必填 —— 静态码谁都能扫，限额是这张码唯一的护栏 */
async function create() {
  const kind = await pick({ title: String(t("bizPay.qr.add")), items: [String(t("bizPay.qr.store")), String(t("bizPay.qr.tableKind"))] });
  if (kind == null) return;
  let tableRef: string | undefined;
  if (kind === 1) {
    const v = await prompt({ title: String(t("bizPay.qr.tableRef")), maxlength: 32 });
    if (v == null || !v.trim()) return;
    tableRef = v.trim();
  }
  const single = await askMoney(String(t("bizPay.qr.single")), "500");
  if (single == null) return;
  const daily = await askMoney(String(t("bizPay.qr.daily")), "5000");
  if (daily == null) return;
  if (daily < single) {
    uni.showToast({ title: String(t("bizPay.qr.dailyBelowSingle")), icon: "none" });
    return;
  }
  try {
    const c = await api.mPayCreateQrCode({ kind: kind === 1 ? "TABLE" : "STORE", tableRef, singleLimitMinor: single, dailyLimitMinor: daily });
    codes.value = [c, ...codes.value];
    shown.value = c.qrCode;
  } catch (e) {
    uni.showToast({ title: (e as Error).message || String(t("bizPay.common.failed")), icon: "none" });
  }
}

async function disable(c: PayQrCode) {
  const ok = await confirm({ title: String(t("bizPay.qr.disableConfirm")), hint: String(t("bizPay.qr.disableHint")), danger: true });
  if (!ok) return;
  try {
    await api.mPayDisableQrCode(c.qrCode);
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message || String(t("bizPay.common.failed")), icon: "none" });
  }
}

async function load() {
  try {
    codes.value = await api.mPayQrCodes();
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

onShow(load);
</script>

<style scoped>
.actions {
  gap: 16rpx;
}
</style>
