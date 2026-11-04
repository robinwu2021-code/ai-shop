<script setup lang="ts">
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { StockSyncOperationRecord } from "@shared/types";

const { t, te } = useI18n();
const merchant = useMerchantStore();
const records = ref<StockSyncOperationRecord[]>([]);
const loading = ref(false);
const failed = ref(false);
const storeNo = computed(() => merchant.storeNo);
let requestGeneration = 0;

async function load() {
  const generation = ++requestGeneration;
  const requestedStore = storeNo.value;
  if (!requestedStore) return;
  loading.value = true;
  try {
    const result = await api.mStockSyncRecords(requestedStore, { page: 0, size: 50 });
    if (generation !== requestGeneration || requestedStore !== storeNo.value) return;
    records.value = result;
    failed.value = false;
  } catch {
    if (generation === requestGeneration) failed.value = true;
  } finally {
    if (generation === requestGeneration) loading.value = false;
  }
}

function statusText(status: string): string {
  const key = `stockSync.operationStatus.${status}`;
  return String(t(te(key) ? key : "stockSync.operationStatus.UNKNOWN"));
}
function resultText(record: StockSyncOperationRecord): string {
  const r = record.result;
  if (!r) return String(t("stockSync.noResult"));
  if (r.skipped) {
    const key = `stockSync.skip.${r.skipped}`;
    return String(t(te(key) ? key : "stockSync.notApplied"));
  }
  return r.applied ? String(t("stockSync.appliedQty", { before: r.before, after: r.after })) : String(t("stockSync.unchangedQty", { n: r.after }));
}
onShow(async () => { await merchant.ensureStores(); await load(); });
</script>

<template>
  <sh-scaffold title-key="stockSync.recordsTitle" :denied="!merchant.can('biz:stock')" :failed="failed" @retry="load">
    <sh-empty v-if="!records.length" :pending="loading" :text="String($t('stockSync.recordsEmpty'))"></sh-empty>
    <view v-for="record in records" :key="record.operationNo" class="sh-card sh-mb-sm">
      <view class="sh-row sh-row--between">
        <text class="txt-strong">{{ record.skuNo }}</text>
        <text class="txt-caption" :class="record.status === 'COMPLETED' ? 'sh-muted' : 'is-warning'">{{ statusText(record.status) }}</text>
      </view>
      <view class="txt-caption sh-muted detail">{{ resultText(record) }}</view>
      <view class="txt-caption sh-muted detail">{{ record.reason }} · {{ record.updatedAt }}</view>
      <view v-if="record.documentNo" class="txt-caption sh-muted detail">{{ $t("stockSync.operationNo", { no: record.documentNo }) }}</view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.detail { display: block; padding-top: 12rpx; overflow-wrap: anywhere; }
</style>
