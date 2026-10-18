<script setup lang="ts">
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { datetime } from "@shared/utils/datetime";
import type { StorePointEntryPage } from "@shared/types";

const merchant = useMerchantStore();
const canView = computed(() => merchant.can("biz:finance"));
const typeCode = ref("");
const page = ref<StorePointEntryPage>({ rows: [], next: 0, hasMore: false });
const loaded = ref(false);
const failed = ref(false);
const busy = ref(false);

async function load(more = false) {
  if (!canView.value || !typeCode.value || busy.value) return;
  busy.value = true;
  try {
    const next = await api.mStorePointEntries(typeCode.value, more ? page.value.next : 0, 20);
    page.value = more ? { ...next, rows: [...page.value.rows, ...next.rows] } : next;
    failed.value = false;
  } catch {
    failed.value = true;
  } finally {
    loaded.value = true;
    busy.value = false;
  }
}

onLoad(async (params) => {
  const requested = decodeURIComponent(String(params?.typeCode ?? ""));
  if (requested) typeCode.value = requested;
  else {
    try {
      const types = await api.mStorePointTypes();
      typeCode.value = (types.find((x) => x.status === "ACTIVE") ?? types[0])?.typeCode ?? "";
    } catch { failed.value = true; }
  }
  if (typeCode.value) await load();
  else loaded.value = true;
});
</script>

<template>
  <sh-scaffold title-key="points.recordsTitle" :denied="!canView" :failed="failed" @retry="load">
    <view class="sh-card"><text class="txt-title">{{ typeCode }}</text>
      <text class="sh-muted"> · {{ merchant.currentStore?.name ?? merchant.storeNo }}</text></view>
    <sh-empty v-if="!page.rows.length" :pending="!loaded" :failed="failed" @retry="load"
      :text="$t('points.recordsEmpty')" />
    <view v-for="entry in page.rows" :key="entry.id" class="sh-card sh-mt-xs">
      <view class="sh-row sh-row--between"><text>{{ entry.bucket }}</text>
        <text class="sh-num">{{ entry.deltaPoints > 0 ? '+' : '' }}{{ entry.deltaPoints }}</text></view>
      <view class="sh-row sh-row--between"><text class="txt-caption sh-muted">{{ entry.voucherNo }}</text>
        <text class="txt-caption sh-muted">{{ datetime(entry.at) }}</text></view>
      <text class="txt-caption sh-muted">{{ $t("points.balanceAfter") }}: {{ entry.afterPoints }}</text>
    </view>
    <view v-if="page.hasMore" class="sh-btn sh-btn--muted sh-mt-sm" @tap="load(true)">
      {{ $t("points.loadMore") }}
    </view>
  </sh-scaffold>
</template>
