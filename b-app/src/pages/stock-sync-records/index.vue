<script setup lang="ts">
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { StockSyncOperationRecord } from "@shared/types";

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

const statusText: Record<StockSyncOperationRecord["status"], string> = {
  PROCESSING: "处理中", WAITING: "等待同步", FAILED: "需处理", BLOCKED: "暂不可同步", COMPLETED: "已完成",
};
function resultText(record: StockSyncOperationRecord): string {
  const r = record.result;
  if (!r) return "尚无结果";
  if (r.skipped) return `未执行：${r.skipped}`;
  return r.applied ? `线上数量 ${r.before} → ${r.after}` : `无需调整，线上数量 ${r.after}`;
}
onShow(async () => { await merchant.ensureStores(); await load(); });
</script>

<template>
  <sh-scaffold title="库存同步记录" :denied="!merchant.can('biz:stock')" :failed="failed" @retry="load">
    <text class="txt-caption sh-muted intro">记录本店库存同步操作及处理结果。</text>
    <sh-empty v-if="!records.length" :pending="loading" text="暂无库存同步记录"></sh-empty>
    <view v-for="record in records" :key="record.operationNo" class="sh-card sh-mb-sm">
      <view class="sh-row sh-row--between">
        <text class="txt-strong">{{ record.skuNo }}</text>
        <text class="txt-caption" :class="record.status === 'COMPLETED' ? 'sh-muted' : 'is-warning'">{{ statusText[record.status] }}</text>
      </view>
      <view class="txt-caption sh-muted detail">{{ resultText(record) }}</view>
      <view class="txt-caption sh-muted detail">{{ record.reason }} · {{ record.updatedAt }}</view>
      <view v-if="record.documentNo" class="txt-caption sh-muted detail">操作号：{{ record.documentNo }}</view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.intro { display: block; padding: 0 8rpx 16rpx; }
.detail { display: block; padding-top: 10rpx; overflow-wrap: anywhere; }
</style>
