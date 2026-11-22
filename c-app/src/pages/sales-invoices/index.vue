<script setup lang="ts">
/**
 * 我的发票（台湾电子发票）：各商家开给我的发票，跨店合在一处，新的在前。
 *
 * <p>与订单详情里的「申请发票」（`/mp/invoice/*`，平台事后开票）是两件事。
 * 发票存进载具就不会印纸本 —— 这一页就是他找得到它的地方，所以「载具管理」的门也放在这里。
 */
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import { onReachBottom, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import type { BuyerInvoice } from "@/api/contract";
import {
  destinationKey,
  formatFiscalNumber,
  invoiceMoney,
  periodParts,
  STATUS_KEY,
} from "@/shared/sales-invoice";

const { t } = useI18n();
const SIZE = 20;
const records = ref<BuyerInvoice[]>([]);
const page = ref(0);
const total = ref(0);
const loading = ref(false);
const loaded = ref(false);
/** 首屏没取到 —— 与「确实一张都没有」分开，网络不通时不说「还没有发票」 */
const failed = ref(false);
const moreFailed = ref(false);
let generation = 0;

async function fetchPage(next: number) {
  const request = generation;
  loading.value = true;
  try {
    const res = await api.mySalesInvoices(next, SIZE);
    if (request !== generation) return;
    records.value = next === 1 ? res.records : [...records.value, ...res.records];
    page.value = next;
    total.value = res.total;
    failed.value = false;
    moreFailed.value = false;
  } catch {
    if (request !== generation) return;
    if (next === 1) failed.value = true;
    else moreFailed.value = true;
  } finally {
    if (request === generation) {
      loading.value = false;
      loaded.value = true;
    }
  }
}

function reload() {
  generation += 1;
  void fetchPage(1);
}

function more() {
  if (loading.value || records.value.length >= total.value) return;
  void fetchPage(page.value + 1);
}

function open(inv: BuyerInvoice) {
  uni.navigateTo({ url: `/pages/sales-invoice/index?invoiceNo=${encodeURIComponent(inv.invoiceNo)}` });
}

function periodText(code: string): string {
  const p = periodParts(code);
  return p ? String(t("salesInvoice.period", p)) : code;
}

function gotoCarriers() {
  uni.navigateTo({ url: "/pages/invoice-carriers/index" });
}

onShow(reload);
onReachBottom(more);
</script>

<template>
  <sh-scaffold title-key="salesInvoice.title">
    <view class="sh-cells head">
      <view class="sh-cell sh-row sh-row--between" @tap="gotoCarriers">
        <text class="txt-body">{{ $t("salesInvoice.carriersTitle") }}</text>
        <text class="txt-caption sh-muted">{{ $t("salesInvoice.carriersEntryHint") }} ›</text>
      </view>
    </view>

    <view v-for="inv in records" :key="inv.invoiceNo" class="sh-card inv" @tap="open(inv)">
      <view class="sh-row sh-row--between inv__top">
        <text class="txt-strong sh-fill inv__seller">{{ inv.sellerName || $t("salesInvoice.unknownSeller") }}</text>
        <text class="txt-caption" :class="inv.status === 'VOIDED' ? 'sh-muted' : 'txt-primary'">{{ $t(STATUS_KEY[inv.status]) }}</text>
      </view>
      <view class="sh-row sh-row--between sh-row--baseline inv__mid">
        <text class="txt-body sh-num inv__no">{{ formatFiscalNumber(inv.fiscalNumber) }}</text>
        <text class="txt-title sh-num inv__amt">{{ invoiceMoney(inv.totalMinor, inv.currency) }}</text>
      </view>
      <text class="txt-caption sh-muted block sh-num">
{{ periodText(inv.periodCode) }} · {{ $t(destinationKey(inv)) }}
      </text>
    </view>

    <sh-empty v-if="!records.length" :pending="!loaded" :failed="failed" :text="$t('salesInvoice.empty')"
      :tip="$t('salesInvoice.emptyTip')" @retry="reload" />
    <view v-if="moreFailed" class="sh-hint more" @tap="more">{{ $t("salesInvoice.moreFailed") }}</view>
    <view v-else-if="records.length && records.length < total" class="sh-btn sh-btn--muted more" @tap="more">
      {{ $t("salesInvoice.loadMore") }}
    </view>
  </sh-scaffold>
</template>

<style scoped>
.head, .inv { margin-bottom: var(--sh-gap-block); }
.inv__top, .inv__mid { gap: 16rpx; }
.inv__mid { margin-top: 12rpx; }
.inv__seller { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.inv__no, .inv__amt { direction: ltr; }
.block { display: block; margin-top: 8rpx; }
.more { margin-top: var(--sh-gap-block); text-align: center; }
</style>
