<script setup lang="ts">
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad, onShow, onReachBottom, onUnload } from "@dcloudio/uni-app";
import { api } from "@/api";
import type { StoredValueRecord, StoredValueRecordsQuery, StoredValueRecordsPage, StoredValueType, StoredValueNotificationCapability } from "@/api/contract";
import { createStoredValuePager } from "@/shared/stored-value-pager";
import { requestStoredValueSubscription } from "@/ports/stored-value-subscribe";
import { reportStoredValueSubscription } from "@/shared/stored-value-subscription";
import { money } from "@shared/utils/format";
import { ROUTES } from "@shared/utils/constants";
import { storedValueDateTime } from "@/shared/stored-value-time";
import { readStoredValueWithSession } from "@/shared/stored-value-session";
import { canNativeShare } from "@shared/ports/share";
const { t } = useI18n();
const storeNo = ref("");const issuerRef=ref("");const locating=ref(false),locationFailed=ref(false);let loadEpoch=0;
const focusDocumentNo = ref("");const locatorDocumentNo=ref("");
const type = ref<StoredValueType>("ALL");
const startDate = ref(""); const endDate = ref("");
const draftStart = ref(""); const draftEnd = ref("");
const dateVisible = ref(false); const dateError = ref(false);
const capability = ref<StoredValueNotificationCapability | null>(null);
const capabilityFailed = ref(false);
const notificationVisible = ref(false); const subscribing = ref(false);
const nativeWechat = canNativeShare();
const typeKeys = { ALL: "storedValue.ALL", RECHARGE: "storedValue.RECHARGE", REDEMPTION: "storedValue.REDEMPTION" };
const pager = createStoredValuePager<StoredValueRecord, StoredValueRecordsQuery, StoredValueRecordsPage>(
  (query, cursor) => {
    const { focusDocumentNo: _focus, ...filters } = query;
    const params = cursor ? { ...filters, cursor, size: 20 } : { ...query, size: 20 };
    return readStoredValueWithSession(() => issuerRef.value?api.storedValueIssuerRecords(issuerRef.value,params):api.storedValueRecords(storeNo.value, params), recordsPath);
  },
);
const state = pager.state;
const tabs = computed(() => (["ALL", "RECHARGE", "REDEMPTION"] as const).map(key => ({ key, label: t(typeKeys[key]) })));
const filtered = computed(() => type.value !== "ALL" || !!startDate.value || !!endDate.value);
const unavailable = computed(() => [404, 10404].includes(state.errorCode));
function recordsPath() {
  return focusDocumentNo.value?`${ROUTES.storedValueRecords}?focusDocumentNo=${encodeURIComponent(focusDocumentNo.value)}`:`${ROUTES.storedValueRecords}?${issuerRef.value?'issuerRef='+encodeURIComponent(issuerRef.value):'storeNo='+encodeURIComponent(storeNo.value)}`;
}
async function load() {
  const epoch=++loadEpoch;pager.invalidate();locationFailed.value=false;
  if(locatorDocumentNo.value){
    locating.value=true;
    try{const issuer=await readStoredValueWithSession(()=>api.storedValueLocateIssuer(locatorDocumentNo.value),recordsPath);if(epoch!==loadEpoch)return;issuerRef.value=issuer.issuerRef;}
    catch{if(epoch===loadEpoch)locationFailed.value=true;return;}
    finally{if(epoch===loadEpoch)locating.value=false;}
  }
  if (!storeNo.value&&!issuerRef.value) return;
  void pager.reset({ type: type.value, ...(startDate.value ? { startDate: startDate.value } : {}),
    ...(endDate.value ? { endDate: endDate.value } : {}),
    ...(focusDocumentNo.value ? { focusDocumentNo: focusDocumentNo.value } : {}),
  }).then(() => {
    if(epoch!==loadEpoch)return;
    const applied = state.page?.filter;
    if (applied) { type.value = applied.type; startDate.value = applied.startDate || ""; endDate.value = applied.endDate || ""; }
  });
}
function selectType(next: string) {
  if (!["ALL", "RECHARGE", "REDEMPTION"].includes(next)) return;
  type.value = next as StoredValueType; focusDocumentNo.value = ""; load();
}
function openDates() {
  draftStart.value = startDate.value; draftEnd.value = endDate.value;
  dateError.value = false; dateVisible.value = true;
}
function resetDraft() { draftStart.value = ""; draftEnd.value = ""; dateError.value = false; }
function applyDates() {
  if (draftStart.value && draftEnd.value && draftStart.value > draftEnd.value) { dateError.value = true; return; }
  startDate.value = draftStart.value; endDate.value = draftEnd.value;
  dateVisible.value = false; focusDocumentNo.value = ""; load();
}
function clearFilters() { type.value = "ALL"; startDate.value = ""; endDate.value = ""; focusDocumentNo.value = ""; load(); }
function viewMessages() { uni.navigateTo({ url: ROUTES.messages }); }
function backStores() { uni.redirectTo({ url: ROUTES.storedValue }); }
async function loadCapability() {
  capabilityFailed.value = false;
  try { capability.value = await readStoredValueWithSession(() => api.storedValueNotificationCapability(), recordsPath); }
  catch { capabilityFailed.value = true; capability.value = null; }
}
function showNotifications() { notificationVisible.value = true; if (!capability.value) void loadCapability(); }
function toast(key: string) { uni.showToast({ title: t(key), icon: "none" }); }
function subscribe() {
  const current = capability.value;
  if (!nativeWechat || subscribing.value || !current?.available || current.channel !== "WXSUB" || !current.templateId) return;
  // 必须由这次点击同步调起原生授权，不能先await能力请求。
  const templateId = current.templateId;
  subscribing.value = true;
  const requested = requestStoredValueSubscription({
    tmplIds: [templateId],
    success: async result => {
      try {
        const status = await reportStoredValueSubscription(templateId, result as unknown as Record<string, unknown>, api.subscribeReport);
        toast(status === "accepted" ? "storedValue.subscribeAccepted" : status === "rejected" ? "storedValue.subscribeRejected" : "storedValue.subscribeFailed");
      } catch { toast("storedValue.subscribeReportFailed"); }
      finally { subscribing.value = false; }
    },
    fail: () => { subscribing.value = false; toast("storedValue.subscribeFailed"); },
  });
  if (!requested) { subscribing.value = false; toast("storedValue.subscribeFailed"); }
}
onLoad(query => {
  issuerRef.value = typeof query?.issuerRef === "string" ? query.issuerRef : "";
  storeNo.value = typeof query?.storeNo === "string" ? query.storeNo : "";
  focusDocumentNo.value = typeof query?.focusDocumentNo === "string" ? query.focusDocumentNo : "";locatorDocumentNo.value=focusDocumentNo.value;
});
onShow(() => { load(); void loadCapability(); });
onReachBottom(() => void pager.more());
onUnload(()=>{++loadEpoch;pager.invalidate();});
</script>
<template>
  <sh-scaffold title-key="storedValue.recordsTitle" :pending="locating">
    <sh-empty v-if="locationFailed || (!storeNo && !issuerRef && !locating) || unavailable" bare :text="$t('storedValue.unavailable')" :tip="$t('storedValue.unavailableTip')" />
    <button v-if="locationFailed || (!storeNo && !issuerRef && !locating) || unavailable" class="sh-btn sh-btn--muted" @tap="backStores">{{ $t("storedValue.backStores") }}</button>
    <template v-else>
      <view class="sh-card header">
        <text class="txt-title">{{ state.page?.store.storeName || $t("storedValue.recordsTitle") }}</text>
        <text class="txt-caption sh-muted block">{{ $t(state.page?.issuer?'storedValue.accountLocalTime':'storedValue.storeLocalTime') }}</text>
        <text class="sh-link txt-caption notify" @tap="showNotifications">{{ $t("storedValue.notifications") }} ›</text>
      </view>
      <view class="filters sh-row sh-row--between">
        <sh-tabs :items="tabs" :active="type" @change="selectType" />
        <text class="sh-chip" :class="{ 'sh-chip--primary': startDate || endDate }" @tap="openDates">{{ $t("storedValue.time") }}</text>
      </view>
      <text v-if="startDate || endDate" class="txt-caption sh-muted dates sh-num">{{ startDate || $t("storedValue.unlimited") }} ～ {{ endDate || $t("storedValue.unlimited") }}</text>
      <sh-empty v-if="!state.records.length" bare :pending="state.loading" :failed="state.failed"
        :text="$t(filtered ? 'storedValue.emptyFiltered' : 'storedValue.emptyRecords')" @retry="load" />
      <button v-if="state.loaded && !state.failed && !state.records.length && filtered" class="sh-btn sh-btn--muted" @tap="clearFilters">{{ $t("storedValue.clearFilters") }}</button>
      <view v-for="(record, index) in state.records" :key="`${record.documentNo || record.occurredAt}-${index}`"
        class="sh-card record" :class="{ 'record--focus': focusDocumentNo && record.documentNo === focusDocumentNo }">
        <view class="sh-row sh-row--between record__main">
          <view class="sh-fill"><text class="txt-strong block">{{ record.title }}</text><text class="txt-caption sh-muted block">{{ $t(typeKeys[record.type]) }}</text></view>
          <text class="record__amount sh-num txt-strong txt-bold" :class="{ 'txt-primary': record.signedAmountMinor > 0 }">{{ record.signedAmountMinor > 0 ? '+' : '−' }}{{ money(Math.abs(record.signedAmountMinor), record.currency) }}</text>
        </view>
        <text v-if="record.storeName" class="txt-caption sh-muted block">{{record.storeName}}</text>
        <text class="txt-caption sh-muted block sh-num">{{ storedValueDateTime(record.occurredAt, state.page?.store.businessTimeZone || "UTC", false) }}</text>
        <text v-if="focusDocumentNo && record.documentNo === focusDocumentNo" class="txt-caption txt-primary block">{{ $t("storedValue.focused") }}</text>
      </view>
      <view v-if="state.moreFailed" class="sh-hint more" @tap="pager.more">{{ $t("storedValue.moreFailed") }}</view>
      <button v-else-if="state.hasMore" class="sh-btn sh-btn--muted more" :disabled="state.moreLoading" @tap="pager.more">{{ $t(state.moreLoading ? "storedValue.loadingMore" : "storedValue.loadMore") }}</button>
    </template>
    <sh-sheet :visible="dateVisible" :title="$t('storedValue.timeTitle')" :hint="$t('storedValue.timeHint', { zone: state.page?.store.businessTimeZone || '' })" fit @close="dateVisible = false">
      <view class="date-field"><text class="txt-body">{{ $t("storedValue.startDate") }}</text><picker mode="date" :value="draftStart" @change="draftStart = $event.detail.value"><view class="field__input sh-row sh-num">{{ draftStart || $t("storedValue.unlimited") }}</view></picker></view>
      <view class="date-field"><text class="txt-body">{{ $t("storedValue.endDate") }}</text><picker mode="date" :value="draftEnd" @change="draftEnd = $event.detail.value"><view class="field__input sh-row sh-num">{{ draftEnd || $t("storedValue.unlimited") }}</view></picker></view>
      <text v-if="dateError" class="sh-hint">{{ $t("storedValue.invalidDates") }}</text>
      <template #foot><view class="actions"><button class="sh-btn sh-btn--muted" @tap="resetDraft">{{ $t("storedValue.reset") }}</button><button class="sh-btn sh-btn--muted" @tap="dateVisible = false">{{ $t("storedValue.cancel") }}</button><button class="sh-btn" @tap="applyDates">{{ $t("storedValue.apply") }}</button></view></template>
    </sh-sheet>
    <sh-sheet :visible="notificationVisible" :title="$t('storedValue.notifications')" :hint="$t('storedValue.notificationHint')" fit @close="notificationVisible = false">
      <text class="txt-body block">{{ $t("storedValue.notificationContent") }}</text>
      <text class="sh-hint block">{{ $t("storedValue.notificationOnce") }}</text>
      <text v-if="!nativeWechat" class="sh-hint block">{{ $t("storedValue.notificationOtherPlatform") }}</text>
      <sh-empty v-else-if="capabilityFailed" bare failed @retry="loadCapability" />
      <text v-else-if="!capability" class="sh-hint block">{{ $t("storedValue.loadingMore") }}</text>
      <text v-else-if="capability && !capability.available" class="sh-hint block">{{ $t("storedValue.notificationUnavailable") }}</text>
      <button v-if="nativeWechat && capability?.available && capability.templateId" class="sh-btn" :disabled="subscribing" @tap="subscribe">{{ $t("storedValue.subscribe") }}</button>
      <button class="sh-btn sh-btn--muted" @tap="viewMessages">{{ $t("storedValue.viewMessages") }}</button>
    </sh-sheet>
  </sh-scaffold>
</template>
<style scoped>
.header, .record { margin-bottom: var(--sh-gap-block); }
.block { display: block; margin-top: 8rpx; }
.filters { gap: 16rpx; margin-bottom: var(--sh-gap-block); }
.dates { display: block; margin-bottom: var(--sh-gap-block); }
.record__main { gap: 20rpx; }
.record__amount { direction: ltr; flex-shrink: 0; }
.record--focus { background: var(--sh-primary-tint); }
.notify { display: block; margin-top: 16rpx; }
.more { margin-top: var(--sh-gap-block); text-align: center; }
.date-field { margin-bottom: 24rpx; }
.date-field .field__input { margin-top: 12rpx; }
.actions { display: flex; gap: 16rpx; }
.actions .sh-btn { flex: 1; }
</style>
