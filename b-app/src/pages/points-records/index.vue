<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { datetime } from "@shared/utils/datetime";
import type { StorePointEntryPage, StorePointType } from "@shared/types";

const merchant = useMerchantStore();
const canView = computed(() => merchant.can("biz:finance"));
const typeCode = ref("");
const types = ref<StorePointType[]>([]);
const current = computed(() => types.value.find(type => type.typeCode === typeCode.value));
let epoch = 0;
const page = ref<StorePointEntryPage>({ rows: [], next: 0, hasMore: false });
const loaded = ref(false);
const failed = ref(false);
const busy = ref(false);

async function load(more = false) {
  if (busy.value) return;
  const token = ++epoch;
  busy.value = true;
  if (!more) { page.value = { rows: [], next: 0, hasMore: false }; loaded.value = false; }
  try {
    await Promise.all([merchant.ensureStores(), merchant.ensureScope()]);
    if (token !== epoch || !canView.value || !merchant.storeNo) return;
    const store = merchant.storeNo;
    if (!more) {
      const result = await api.mStorePointTypes();
      if (token !== epoch || store !== merchant.storeNo) return;
      types.value = result;
      if (!typeCode.value) typeCode.value = (result.find(x => x.status === "ACTIVE") ?? result[0])?.typeCode ?? "";
    }
    if (!typeCode.value) return;
    if (!current.value) throw new Error("POINTS_TYPE_UNKNOWN");
    const next = await api.mStorePointEntries(typeCode.value, more ? page.value.next : 0, 20);
    if (token !== epoch || store !== merchant.storeNo || !canView.value) return;
    page.value = more ? { ...next, rows: [...page.value.rows, ...next.rows.filter(row => !page.value.rows.some(old => old.entryNo === row.entryNo))] } : next;
    failed.value = false;
  } catch { if (token === epoch) failed.value = true; }
  finally { if (token === epoch) { loaded.value = true; busy.value = false; } }
}
onLoad(params => { typeCode.value = String(params?.typeCode ?? ""); });
onShow(() => { void load(); });
watch(() => merchant.storeNo, () => {
  ++epoch; busy.value = false; types.value = []; page.value = { rows: [], next: 0, hasMore: false };
  void load();
});
function openOrder(no: string) {
  uni.navigateTo({url: `/pages/order/index?orderNo=${encodeURIComponent(no)}`});
}
</script>

<template>
  <sh-scaffold title-key="points.recordsTitle" :denied="!canView" :failed="failed" @retry="load()">
    <view class="sh-card"><text class="txt-title">{{ current?.name }}</text>
      <text class="sh-muted"> · {{ merchant.currentStore?.name ?? merchant.storeNo }}</text></view>
    <sh-empty v-if="!page.rows.length" :pending="!loaded" :failed="failed" @retry="load()"
      :text="$t('points.recordsEmpty')" />
    <view v-for="entry in page.rows" :key="entry.id" class="sh-card sh-mt-xs">
      <view class="sh-row sh-row--between"><text>{{ $t(`points.direction${entry.direction || "OTHER"}`) }} · {{ $t(`points.bucket${entry.bucket}`) }}</text>
        <text class="sh-num">{{ entry.deltaPoints > 0 ? '+' : '' }}{{ entry.deltaPoints }}</text></view>
      <view class="sh-row sh-row--between"><text v-if="entry.voucherDocumentNo || entry.voucherNo" class="txt-caption sh-muted">{{ entry.voucherDocumentNo || entry.voucherNo }}</text>
        <text class="txt-caption sh-muted">{{ datetime(entry.at) }}</text></view>
      <view v-if="entry.orderNo && merchant.can('biz:order:view')" class="txt-caption" @tap="openOrder(entry.orderNo)">{{ $t("points.viewOrder") }}</view>
      <text v-if="entry.businessRef" class="txt-caption sh-muted">{{ entry.businessRef }}</text>
      <text class="txt-caption sh-muted">{{ $t("points.posted") }}</text>
      <text class="txt-caption sh-muted">{{ $t("points.balanceAfter") }}: {{ entry.afterPoints }}</text>
    </view>
    <view v-if="page.hasMore" class="sh-btn sh-btn--muted sh-mt-sm" @tap="load(true)">
      {{ $t("points.loadMore") }}
    </view>
  </sh-scaffold>
</template>
