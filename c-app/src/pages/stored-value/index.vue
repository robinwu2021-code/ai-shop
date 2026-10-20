<script setup lang="ts">
import { onShow, onReachBottom, onUnload } from "@dcloudio/uni-app";
import { api } from "@/api";
import type { StoredValueStore, StoredValueStoresPage } from "@/api/contract";
import { createStoredValuePager } from "@/shared/stored-value-pager";
import { storedValueDirectoryTime } from "@/shared/stored-value-time";
import { readStoredValueWithSession } from "@/shared/stored-value-session";
import { ROUTES } from "@shared/utils/constants";
const pager = createStoredValuePager<StoredValueStore, {}, StoredValueStoresPage>(
  (_, cursor) => readStoredValueWithSession(() => api.storedValueStores({ size: 20, ...(cursor ? { cursor } : {}) }), () => ROUTES.storedValue),
);
const state = pager.state;
const typeKeys = { RECHARGE: "storedValue.RECHARGE", REDEMPTION: "storedValue.REDEMPTION" };
function load() { void pager.reset({}); }
function open(storeNo: string) {
  uni.navigateTo({ url: `${ROUTES.storedValueRecords}?storeNo=${encodeURIComponent(storeNo)}` });
}
onShow(load);
onReachBottom(() => void pager.more());
onUnload(pager.invalidate);
</script>
<template>
  <sh-scaffold title-key="storedValue.title">
    <text class="sh-hint intro">{{ $t("storedValue.storesHint") }}</text>
    <sh-empty v-if="!state.records.length" bare :pending="state.loading" :failed="state.failed"
      :text="$t('storedValue.emptyStores')" :tip="$t('storedValue.emptyStoresTip')" @retry="load" />
    <view v-for="store in state.records" :key="store.storeNo" class="sh-card store" @tap="open(store.storeNo)">
      <view class="sh-row sh-row--between">
        <text class="txt-title">{{ store.storeName }}</text><text class="txt-caption">{{ $t("storedValue.viewRecords") }} ›</text>
      </view>
      <text class="txt-caption sh-muted store__last">{{ $t(typeKeys[store.lastType]) }} · {{ storedValueDirectoryTime(store.lastOccurredAt) }}</text>
    </view>
    <view v-if="state.moreFailed" class="sh-hint more" @tap="pager.more">{{ $t("storedValue.moreFailed") }}</view>
    <button v-else-if="state.hasMore" class="sh-btn sh-btn--muted more" :disabled="state.moreLoading" @tap="pager.more">
      {{ $t(state.moreLoading ? "storedValue.loadingMore" : "storedValue.loadMore") }}
    </button>
  </sh-scaffold>
</template>
<style scoped>
.intro { display: block; margin-bottom: var(--sh-gap-block); }
.store { margin-bottom: var(--sh-gap-block); }
.store__last { display: block; margin-top: 12rpx; }
.more { margin-top: var(--sh-gap-block); text-align: center; }
</style>
