<script setup lang="ts">
/**
 * 我的发票 · 详情：哪家开的、号码、期别、随机码、金额、存到哪里、明细。
 *
 * <p>只读。要作废或折让是商家的事（买方没有这个权力），作废了这里显示「已作废」。
 */
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import type { BuyerInvoice } from "@/api/contract";
import {
  destinationKey,
  formatFiscalNumber,
  invoiceMoney,
  invoiceTime,
  periodParts,
  STATUS_KEY,
} from "@/shared/sales-invoice";

const { t } = useI18n();
const invoiceNo = ref("");
const inv = ref<BuyerInvoice | null>(null);
const loaded = ref(false);
const failed = ref(false);

async function load() {
  if (!invoiceNo.value) {
    failed.value = true;
    loaded.value = true;
    return;
  }
  failed.value = false;
  try {
    inv.value = await api.salesInvoiceDetail(invoiceNo.value);
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

const period = computed(() => {
  const code = inv.value?.periodCode ?? "";
  const p = periodParts(code);
  return p ? String(t("salesInvoice.period", p)) : code;
});

/** 存到载具 / 捐赠 / 打统编时，具体是哪一个号码 */
const destinationValue = computed(() => {
  const v = inv.value;
  if (!v) return "";
  return v.donateCode || v.carrierId || v.buyerTaxId || "";
});

const money = (minor: number | null | undefined) => invoiceMoney(minor, inv.value?.currency);

onLoad((q) => {
  invoiceNo.value = typeof q?.invoiceNo === "string" ? q.invoiceNo : "";
  void load();
});
</script>

<template>
  <sh-scaffold title-key="salesInvoice.detailTitle" :pending="!loaded" :failed="failed" @retry="load">
    <template v-if="inv">
      <view class="sh-card block-gap">
        <view class="sh-row sh-row--between top">
          <text class="txt-title sh-fill">{{ inv.sellerName || $t("salesInvoice.unknownSeller") }}</text>
          <text class="txt-caption" :class="inv.status === 'VOIDED' ? 'sh-muted' : 'txt-primary'">{{ $t(STATUS_KEY[inv.status]) }}</text>
        </view>
        <text v-if="inv.sellerTaxId" class="txt-caption sh-muted block sh-num">
          {{ $t("salesInvoice.sellerTaxId") }} {{ inv.sellerTaxId }}
        </text>
        <text class="txt-hero sh-num block ltr">{{ formatFiscalNumber(inv.fiscalNumber) }}</text>
        <text class="txt-title sh-num block ltr">{{ money(inv.totalMinor) }}</text>
        <text v-if="inv.status === 'VOIDED'" class="sh-hint block">{{ $t("salesInvoice.voidedHint") }}</text>
      </view>

      <view class="sh-card block-gap">
        <sh-kv :label="String($t('salesInvoice.periodLabel'))" between><text class="txt-body sh-num">{{ period }}</text></sh-kv>
        <sh-kv :label="String($t('salesInvoice.randomCode'))" between><text class="txt-body sh-num">{{ inv.randomCode }}</text></sh-kv>
        <sh-kv :label="String($t('salesInvoice.issuedAt'))" between><text class="txt-body sh-num">{{ invoiceTime(inv.issuedAt) }}</text></sh-kv>
        <sh-kv :label="String($t('salesInvoice.destination'))" between>
          <text class="txt-body">{{ $t(destinationKey(inv)) }}</text>
        </sh-kv>
        <sh-kv v-if="destinationValue" :label="String($t('salesInvoice.destinationNo'))" between>
          <text class="txt-body sh-num ltr">{{ destinationValue }}</text>
        </sh-kv>
        <text v-if="!inv.printMark" class="txt-caption sh-muted block">{{ $t("salesInvoice.noPaper") }}</text>
      </view>

      <sh-section v-if="inv.lines?.length" :title="String($t('salesInvoice.lines'))">
        <view v-for="l in inv.lines" :key="l.lineNo" class="sh-row sh-row--between line">
          <view class="sh-fill">
            <text class="txt-body block-0">{{ l.name }}</text>
            <text class="txt-caption sh-muted sh-num">{{ l.quantity }}{{ l.unit || "" }} × {{ money(l.unitPriceMinor) }}</text>
          </view>
          <text class="txt-body sh-num ltr">{{ money(l.amountMinor) }}</text>
        </view>
      </sh-section>

      <view class="sh-card block-gap">
        <sh-kv :label="String($t('salesInvoice.sales'))" between><text class="txt-body sh-num ltr">{{ money(inv.salesMinor) }}</text></sh-kv>
        <sh-kv v-if="inv.zeroTaxSalesMinor" :label="String($t('salesInvoice.zeroTaxSales'))" between>
          <text class="txt-body sh-num ltr">{{ money(inv.zeroTaxSalesMinor) }}</text>
        </sh-kv>
        <sh-kv v-if="inv.freeTaxSalesMinor" :label="String($t('salesInvoice.freeTaxSales'))" between>
          <text class="txt-body sh-num ltr">{{ money(inv.freeTaxSalesMinor) }}</text>
        </sh-kv>
        <sh-kv :label="String($t('salesInvoice.tax'))" between><text class="txt-body sh-num ltr">{{ money(inv.taxMinor) }}</text></sh-kv>
        <sh-kv :label="String($t('salesInvoice.total'))" between><text class="txt-strong sh-num ltr">{{ money(inv.totalMinor) }}</text></sh-kv>
        <sh-kv v-if="inv.allowedMinor" :label="String($t('salesInvoice.allowed'))" between>
          <text class="txt-body sh-num ltr">−{{ money(inv.allowedMinor) }}</text>
        </sh-kv>
      </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.block-gap { margin-bottom: var(--sh-gap-block); }
.top { gap: 16rpx; }
.block { display: block; margin-top: 12rpx; }
.block-0 { display: block; }
.line { gap: 16rpx; padding: 12rpx 0; }
.ltr { direction: ltr; }
</style>
