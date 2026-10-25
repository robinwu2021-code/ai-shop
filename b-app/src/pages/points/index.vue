<script setup lang="ts">
import { computed, ref, watch } from "vue";
import StorePointStatements from "@/components/points/StorePointStatements.vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { pointsTypeStatusKey } from "@shared/utils/points-status";
import { datetime } from "@shared/utils/datetime";
import { useMerchantStore } from "@/stores/merchant";
import type { StorePointAccount, StorePointAgreement, StorePointType } from "@shared/types";

import { preparePointStatementLink } from "@/shared/points-statement-link";
const merchant = useMerchantStore();
const pageReady = ref(false);
const linkQuery = ref<{ storeNo?: string; typeCode?: string; statementNo?: string } | null>(null);
const targetStatement = ref("");
onLoad((query) => {
  if (query && (query.storeNo || query.typeCode || query.statementNo)) {
    linkQuery.value = { storeNo: query.storeNo, typeCode: query.typeCode, statementNo: query.statementNo };
  }
});

const canView = computed(() => merchant.can("biz:finance"));
const showStatements = ref(false);
const showHistory = ref(false);
let epoch = 0;
let selectionEpoch = 0;
const types = ref<StorePointType[]>([]);
const selected = ref("");
const account = ref<StorePointAccount | null>(null);
const agreement = ref<StorePointAgreement | null>(null);
const failed = ref(false);
const loading = ref(false);
const current = computed(() => types.value.find((x) => x.typeCode === selected.value));
const buckets = computed(() => ["AVAILABLE", "PENDING"].map(key => [key, account.value?.snapshot.buckets[key] ?? 0] as const));

async function selectType(typeCode: string) {
  const token = ++selectionEpoch;
  const store = merchant.storeNo;
  selected.value = typeCode;
  account.value = null; agreement.value = null; showHistory.value = false;
  showStatements.value = !!targetStatement.value;
  failed.value = false;
  try {
    const result = await api.mStorePointAccount(typeCode);
    if (token !== selectionEpoch || merchant.storeNo !== store || !canView.value) return;
    account.value = result;
  } catch { if (token === selectionEpoch && merchant.storeNo === store) failed.value = true; }
}
async function history() {
  const token = selectionEpoch; const store = merchant.storeNo;
  showHistory.value = !showHistory.value;
  if (!showHistory.value) return;
  try {
    const result = await api.mStorePointAgreement(selected.value);
    if (token === selectionEpoch && merchant.storeNo === store && canView.value) agreement.value = result;
  } catch { if (token === selectionEpoch && merchant.storeNo === store) failed.value = true; }
}
async function load() {
  const token = ++epoch;
  ++selectionEpoch;
  pageReady.value = false; account.value = null; agreement.value = null; types.value = [];
  loading.value = true; failed.value = false;
  try {
    if (linkQuery.value) {
      const target = await preparePointStatementLink(merchant, linkQuery.value);
      if (token !== epoch) return;
      selected.value = target.typeCode; targetStatement.value = target.statementNo;
    } else {
      await Promise.all([merchant.ensureStores(), merchant.ensureScope()]);
      targetStatement.value = "";
    }
    if (token !== epoch || !canView.value || !merchant.storeNo) return;
    const store = merchant.storeNo;
    const result = await api.mStorePointTypes();
    if (token !== epoch || store !== merchant.storeNo || !canView.value) return;
    types.value = result;
    if (linkQuery.value && !result.some(type => type.typeCode === selected.value)) throw new Error("POINTS_LINK_TYPE_UNKNOWN");
    const choice = result.find(x => x.typeCode === selected.value) ?? result.find(x => x.status === "ACTIVE") ?? result[0];
    if (choice) await selectType(choice.typeCode);
    else selected.value = "";
    if (token === epoch && store === merchant.storeNo) pageReady.value = true;
  } catch { if (token === epoch) failed.value = true; }
  finally { if (token === epoch) loading.value = false; }
}

function openEntries() {
  if (!selected.value) return;
  uni.navigateTo({ url: `/pages/points-records/index?typeCode=${encodeURIComponent(selected.value)}` });
}

watch(() => merchant.storeNo, () => {
  if (loading.value && linkQuery.value) return;
  linkQuery.value = null; targetStatement.value = "";
  void load();
});
onShow(() => { void load(); });
</script>

<template>
  <sh-scaffold title-key="points.title" :denied="!canView" :failed="failed" @retry="load">
    <template v-if="pageReady">
    <view class="sh-card">
      <text class="txt-title">{{ $t("points.storeLedger") }}</text>
      <text class="sh-muted">{{ merchant.currentStore?.name ?? merchant.storeNo }}</text>
      <text class="txt-caption sh-muted note">{{ $t("points.storeLedgerHint") }}</text>
    </view>
    <sh-empty v-if="!loading && !types.length" :text="$t('points.noTypes')" />
    <view v-if="types.length" class="sh-card sh-mt-sm">
      <text class="sh-muted">{{ $t("points.type") }}</text>
      <view class="sh-wrap sh-mt-xs">
        <view v-for="p in types" :key="p.typeCode" class="sh-chip"
          :class="{ 'sh-chip--primary': selected === p.typeCode }" @tap="selectType(p.typeCode)">
          {{ p.name }}
        </view>
      </view>
      <text v-if="current" class="txt-caption sh-muted note">
        {{ current.typeCode }} · {{ current.currency }} · {{ current.rate.pointsUnit }} {{ $t("points.pointsUnit") }} =
        {{ current.rate.minorUnit }} {{ $t("points.minorUnit") }}
      </text>
      <text v-if="current" class="txt-caption sh-muted note">{{ $t("points.typeStatus") }}: {{ $t(pointsTypeStatusKey(current.status, current.earningPaused)) }}</text>
      <text v-if="current?.status === 'ACTIVE' && current.earningPaused" class="txt-caption sh-muted note">{{ $t("points.earningPausedHint") }}</text>
    </view>
    <view v-if="account" class="sh-card sh-mt-sm">
      <text class="txt-title">{{ $t("points.accountBuckets") }}</text>
      <text v-if="!account.exists" class="sh-muted note">{{ $t("points.emptyAccount") }}</text>
      <view v-for="[bucket, value] in buckets" :key="bucket" class="sh-row sh-row--between sh-mt-xs">
        <text class="sh-muted">{{ $t(`points.bucket${bucket}`) }}</text><text class="sh-num">{{ value }}</text>
      </view>
      <view class="sh-btn sh-btn--muted sh-mt-sm" @tap="openEntries">{{ $t("points.records") }}</view>
    </view>
    <view v-if="account?.snapshot.frozen" class="sh-card sh-mt-sm">{{ $t("points.accountFrozen") }}</view>
    <view v-if="account?.snapshot.expireAt" class="sh-card sh-mt-sm">{{ $t("points.accountExpiry") }}: {{ datetime(account.snapshot.expireAt) }}</view>
    <view v-if="selected" class="sh-btn sh-btn--muted sh-mt-sm" @tap="showStatements = !showStatements">{{ $t("points.statements") }}</view>
    <StorePointStatements v-if="showStatements && selected && canView" :key="`${merchant.storeNo}:${selected}`" :type-code="selected" :type-name="current?.name" :store-no="merchant.storeNo" :target-statement="targetStatement" />
    <view v-if="selected" class="sh-btn sh-btn--muted sh-mt-sm" @tap="history">{{ $t("points.historyTerms") }}</view>
    <view v-if="showHistory" class="sh-card sh-mt-sm">
      <template v-if="agreement"><text>{{ agreement.termsVersion }} · {{ agreement.settleMode }}</text><view class="terms">{{ agreement.termsText }}</view></template>
      <text v-else>{{ $t("points.noHistoryTerms") }}</text>
    </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.note { display: block; margin-top: 12rpx; }
.terms { white-space: pre-wrap; margin-top: 16rpx; }
.is-dim { opacity: 0.45; }
</style>
