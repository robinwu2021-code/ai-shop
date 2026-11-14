<template>
  <sh-scaffold title-key="bizPay.flow.title" :denied="!merchant.can('mch:pay:statement')">
    <view class="sh-card">
      <view class="sh-row sh-row--between">
        <text class="sh-link" @tap="shift(-1)">{{ t("bizPay.flow.prevDay") }}</text>
        <text class="txt-strong sh-num">{{ day }}</text>
        <text class="sh-link" :class="{ 'is-disabled': isToday }" @tap="!isToday && shift(1)">{{ t("bizPay.flow.nextDay") }}</text>
      </view>
      <view class="sh-mt-md">
        <sh-kv between :label="String(t('bizPay.flow.paid'))">
          <text class="txt-sub sh-num">{{ money(data?.paidMinor ?? 0, cur) }} · {{ t("bizPay.flow.count", { n: data?.paidCount ?? 0 }) }}</text>
        </sh-kv>
        <sh-kv between :label="String(t('bizPay.flow.refunded'))">
          <text class="txt-sub sh-num is-danger">-{{ money(data?.refundMinor ?? 0, cur) }} · {{ t("bizPay.flow.count", { n: data?.refundCount ?? 0 }) }}</text>
        </sh-kv>
      </view>
    </view>

    <view class="sh-card">
      <sh-empty v-if="!items.length" :pending="!loaded" :failed="failed" @retry="load" bare :text="String(t('bizPay.flow.empty'))"></sh-empty>
      <view v-for="i in items" :key="i.kind + i.no" class="sh-row--divided">
        <view class="sh-row sh-row--between">
          <text class="txt-caption sh-num">{{ i.no }}</text>
          <text class="txt-strong sh-num" :class="{ 'is-danger': i.kind === 'REFUND' }">
            {{ i.kind === "REFUND" ? "-" : "" }}{{ money(i.amountMinor, cur) }}
          </text>
        </view>
        <view class="sh-row sh-row--between sh-mt-xs">
          <text class="txt-caption txt-quiet">{{ i.kind === "REFUND" ? t("bizPay.flow.refundRow") : methodLabel(i.payMethod) }} · {{ timeOf(i.at) }}</text>
          <text v-if="i.kind === 'PAYMENT' && canRefund" class="sh-link" @tap="refund(i)">{{ t("bizPay.flow.refund") }}</text>
        </view>
      </view>
    </view>

    <view v-if="canRefund" class="sh-card">
      <text class="txt-strong">{{ t("bizPay.flow.refundsTitle") }}</text>
      <sh-empty v-if="!refunds.length" bare :text="String(t('bizPay.flow.noRefunds'))"></sh-empty>
      <view v-for="r in refunds" :key="r.payRefundNo" class="sh-row--divided">
        <view class="sh-row sh-row--between">
          <text class="txt-caption sh-num">{{ r.payRefundNo }}</text>
          <text class="txt-strong sh-num is-danger">-{{ money(r.amountMinor, asCurrency(r.currency)) }}</text>
        </view>
        <view class="sh-row sh-row--between sh-mt-xs">
          <text class="txt-caption txt-quiet">{{ r.reason || "" }}</text>
          <text class="txt-caption" :class="r.status === 'FAILED' || r.status === 'REJECTED' ? 'is-danger' : 'txt-quiet'">
            {{ t(`bizPay.refundStatus.${r.status}`) }}
          </text>
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money, toMajor, toMinor } from "@shared/utils/money";
import { prompt, confirm } from "@ai-shop/ui/prompt";
import type { CurrencyCode } from "@shared/types";
import type { PayDailyStatement, PayRefundItem, PayStatementItem } from "@/api/pay";

const { t, te } = useI18n();
const merchant = useMerchantStore();
const canRefund = computed(() => merchant.can("mch:pay:refund"));

/** 业务日由后端按门店市场切；这里只负责在本地日历上前后翻 */
const todayStr = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
};
const day = ref(todayStr());
const isToday = computed(() => day.value >= todayStr());
const data = ref<PayDailyStatement | null>(null);
const refunds = ref<PayRefundItem[]>([]);
const items = computed<PayStatementItem[]>(() => data.value?.items ?? []);
const cur = computed(() => asCurrency(data.value?.currency));
const loaded = ref(false);
const failed = ref(false);

function asCurrency(c: string | null | undefined): CurrencyCode {
  return (c || "CNY") as CurrencyCode;
}

function shift(n: number) {
  const d = new Date(day.value + "T00:00:00");
  d.setDate(d.getDate() + n);
  day.value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  load();
}

function timeOf(at: number | null) {
  if (!at) return "";
  const d = new Date(at);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

function methodLabel(m: string | null) {
  const key = `bizPay.method.${m}`;
  return m && te(key) ? t(key) : t("bizPay.method.other");
}

/**
 * 从收款那一行发起退款：金额默认填这笔的全额，原因必填 ——
 * 退款单上的原因是平台复核时唯一能看到的「为什么」。
 */
async function refund(i: PayStatementItem) {
  if (!i.payOrderNo) return;
  const amount = await prompt({
    title: String(t("bizPay.refund.amount")),
    hint: String(t("bizPay.refund.maxHint", { amount: money(i.amountMinor, cur.value) })),
    value: toMajor(i.amountMinor, cur.value),
    type: "digit",
  });
  if (amount == null) return;
  const minor = toMinor(amount, cur.value);
  if (!Number.isFinite(minor) || minor <= 0 || minor > i.amountMinor) {
    uni.showToast({ title: String(t("bizPay.refund.invalidAmount")), icon: "none" });
    return;
  }
  const reason = await prompt({ title: String(t("bizPay.refund.reason")), maxlength: 120 });
  if (reason == null || !reason.trim()) return;
  const ok = await confirm({
    title: String(t("bizPay.refund.confirmTitle", { amount: money(minor, cur.value) })),
    hint: String(t("bizPay.refund.confirmHint")),
    danger: true,
  });
  if (!ok) return;
  try {
    const r = await api.mPayRefund({ payOrderNo: i.payOrderNo, amountMinor: minor, reason: reason.trim() });
    uni.showToast({ title: String(t(`bizPay.refundStatus.${r.status}`)), icon: "none" });
    await loadRefunds();
  } catch (e) {
    uni.showToast({ title: (e as Error).message || String(t("bizPay.common.failed")), icon: "none" });
  }
}

async function loadRefunds() {
  if (!canRefund.value) return;
  refunds.value = await api.mPayRefunds().catch(() => refunds.value);
}

async function load() {
  try {
    data.value = await api.mPayStatement(day.value);
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
  await loadRefunds();
}

onShow(load);
</script>
