<script setup lang="ts">
import {ref} from "vue";
import StoredValueServiceSheet from "./StoredValueServiceSheet.vue";
import { onShow, onReachBottom, onUnload } from "@dcloudio/uni-app";
import { api } from "@/api";
import type { StoredValueService,StoredValueIssuer, StoredValueIssuersPage } from "@/api/contract";
import { createStoredValuePager } from "@/shared/stored-value-pager";
import { storedValueDirectoryTime } from "@/shared/stored-value-time";
import { readStoredValueWithSession } from "@/shared/stored-value-session";
import { ROUTES } from "@shared/utils/constants";
const pager = createStoredValuePager<StoredValueIssuer, {}, StoredValueIssuersPage>(
  (_, cursor) => readStoredValueWithSession(() => api.storedValueIssuers({ size: 20, ...(cursor ? { cursor } : {}) }), () => ROUTES.storedValue),
);
const state = pager.state;const pendingServices=ref<StoredValueService[]>([]),confirmOrder=ref(""),servicesFailed=ref(false);
async function services(){servicesFailed.value=false;try{pendingServices.value=(await readStoredValueWithSession(()=>api.storedValuePendingServices(),()=>ROUTES.storedValue)).filter(s=>s.payment?.assetKind==="STORED_VALUE"&&s.confirmation&&["WAIT_AUTH","WAIT_SUBMIT","PROCESSING"].includes(s.status));}catch{servicesFailed.value=true;}}
const typeKeys = { RECHARGE: "storedValue.RECHARGE", REDEMPTION: "storedValue.REDEMPTION" };
function load() { void pager.reset({});void services(); }
function open(issuerRef: string) {
  uni.navigateTo({ url: `${ROUTES.storedValueRecords}?issuerRef=${encodeURIComponent(issuerRef)}` });
}
onShow(load);
onReachBottom(() => void pager.more());
onUnload(pager.invalidate);
</script>
<template>
  <sh-scaffold title-key="storedValue.title">
    <view v-for="service in pendingServices" :key="service.serviceOrderNo" class="sh-card store" role="button" @tap="confirmOrder=service.serviceOrderNo"><text class="txt-strong">{{service.storeName}}</text><text class="txt-caption sh-muted store__last">{{$t(service.status==='WAIT_AUTH'?'storedValue.confirmConsumption':'storedValue.serviceState.'+service.status)}}</text></view>
    <view v-if="servicesFailed" role="button" class="sh-btn sh-btn--muted store" @tap="services">{{$t('storedValue.refreshConfirmation')}}</view>
    <StoredValueServiceSheet :order-no="confirmOrder" @close="confirmOrder=''" @confirmed="services"/>
    <text class="sh-hint intro">{{ $t("storedValue.storesHint") }}</text>
    <sh-empty v-if="!state.records.length" bare :pending="state.loading" :failed="state.failed"
      :text="$t('storedValue.emptyStores')" :tip="$t('storedValue.emptyStoresTip')" @retry="load" />
    <view v-for="store in state.records" :key="store.issuerRef" class="sh-card store" @tap="open(store.issuerRef)">
      <view class="sh-row sh-row--between">
        <text class="txt-title">{{ store.name }}</text><text class="txt-caption">{{ $t("storedValue.viewRecords") }} ›</text>
      </view>
      <text class="txt-caption sh-muted store__last">{{ $t(store.scopeType === "BRAND" ? "storedValue.brandScope" : "storedValue.storeScope") }} · {{ $t(typeKeys[store.lastType]) }} · {{ storedValueDirectoryTime(store.lastOccurredAt) }}</text>
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
