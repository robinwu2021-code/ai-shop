<template>
  <view class="sh-card" v-if="devices.length || detail.deliveredSources?.length || history.length || pending">
    <text class="txt-strong">{{ t("bizMall.afterSale.title") }}</text>
    <view v-for="h in devices" :key="h.orderComponentNo" class="sh-row--divided">
      <text>{{ h.hardwareSkuNo }} · {{ t("bizMall.afterSale.shipped", h) }}</text>
      <sh-go v-if="h.left > 0 && !pending && !busy" :text="String(t('bizMall.afterSale.title'))" @tap="device(h.orderComponentNo, h.left)" />
    </view>
    <view v-for="p in detail.deliveredSources" :key="p.periodNo" class="sh-row--divided">
      <text>{{ [p.planVersionNo, p.periodNo, ...p.entitlementCodes, p.lifecycle].filter(Boolean).join(" · ") }}</text>
      <sh-go v-if="p.lifecycle === 'NORMAL' && !pending && !busy" :text="String(t('bizMall.afterSale.title'))" @tap="prepare([p.periodNo])" />
    </view>
    <view v-for="r in history" :key="r.refundNo" class="sh-row--divided">
      <text>{{ r.refundNo }} · {{ r.hardware ? r.hardware.orderComponentNo + ' × ' + r.hardware.quantity : r.sourcePeriodNos?.join(', ') }} · {{ r.reviewStatus }}</text>
    </view>
    <text v-if="pending" class="txt-sub">{{ pending.hardware ? pending.hardware.orderComponentNo + ' × ' + pending.hardware.quantity : pending.sourcePeriodNos.join(', ') }} · {{ pending.reason }}</text>
    <sh-go v-if="pending && !attempted && !busy" :text="String(t('common.cancel'))" @tap="clear" />
    <sh-go v-if="pending" :text="String(t('bizMall.afterSale.submit', { amount: mallMoney(pending.cashAmount) }))" @tap="submit" />
    <text v-if="error" class="sh-hint is-danger">{{ error }}</text>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { prompt } from "@ai-shop/ui/prompt";
import { api } from "@/api";
import type { MallOrderDetail, MallRefundRequest, MallRefundSelection } from "@/api/mall";
import { mallMoney, mallErrorText } from "@/shared/mall";

const props = defineProps<{ storeNo: string; orderNo: string; detail: MallOrderDetail }>();
const emit = defineEmits<{ updated: [] }>();
const { t, te } = useI18n();
const storageKey = `mall-after-sale:${props.storeNo}:${props.orderNo}`;
const pending = ref<MallRefundRequest | undefined>(uni.getStorageSync(storageKey) || undefined);
const attempted = ref(Boolean(uni.getStorageSync(storageKey + ":sent")));
const busy = ref(false);
const error = ref("");
const history = computed(() => (props.detail.refunds ?? []).filter(r => r.reviewStatus !== "PREVIEW"));
const devices = computed(() => (props.detail.hardwareItems ?? []).map(h => {
  const shipped = (props.detail.shipments ?? []).filter(s => s.orderComponentNo === h.orderComponentNo && s.status === "SUCCEEDED")
    .reduce((n, s) => n + (s.quantity ?? 0), 0);
  const refunded = history.value.reduce((n, r) => n + (r.status === "SUCCEEDED" && r.hardware?.orderComponentNo === h.orderComponentNo ? r.hardware.quantity : 0), 0);
  return { ...h, shipped, left: shipped - refunded };
}));
function report(e: unknown) { error.value = mallErrorText(e, (k, v) => String(v ? t(k, v) : t(k)), te); }
async function device(part: string, left: number) {
  const raw = await prompt({ title: String(t("bizMall.afterSale.quantity")), value: "1", maxlength: 10 });
  if (raw == null) return;
  const quantity = Number(raw.trim());
  if (!Number.isSafeInteger(quantity) || quantity < 1 || quantity > left) { error.value = String(t("bizMall.afterSale.quantity")) + " (1–" + left + ")"; return; }
  await prepare([], { orderComponentNo: part, quantity });
}
async function prepare(sourcePeriodNos: string[], hardware?: MallRefundSelection["hardware"]) {
  if (busy.value || pending.value) return;
  busy.value = true;
  error.value = "";
  try {
    const reason = (await prompt({ title: String(t("bizMall.afterSale.reason")), value: "", maxlength: 1024 }))?.trim();
    if (!reason) return;
    const selection = { refundNo: `r-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`, sourcePeriodNos, hardware };
    const preview = await api.mMallRefundPreview(props.storeNo, props.orderNo, selection);
    pending.value = { ...selection, reason, cashAmount: preview.cashLimit };
    uni.setStorageSync(storageKey, pending.value);
  } catch (e) { report(e); } finally { busy.value = false; }
}
function clear() {
  uni.removeStorageSync(storageKey);
  uni.removeStorageSync(storageKey + ":sent");
  attempted.value = false;
  pending.value = undefined;
}
async function submit() {
  if (!pending.value || busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    attempted.value = true;
    uni.setStorageSync(storageKey + ":sent", true);
    await api.mMallRefund(props.storeNo, props.orderNo, pending.value);
    clear();
    uni.showToast({ title: String(t("bizMall.afterSale.sent")), icon: "none" });
    emit("updated");
  } catch (e) {
    if (e instanceof Error && e.message === "QUOTE_EXPIRED") clear();
    report(e);
  } finally { busy.value = false; }
}
</script>
