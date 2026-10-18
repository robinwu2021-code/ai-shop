<script setup lang="ts">
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { StorePointAccount, StorePointType } from "@shared/types";

const merchant = useMerchantStore();
const canView = computed(() => merchant.can("biz:finance"));
const types = ref<StorePointType[]>([]);
const selected = ref("");
const account = ref<StorePointAccount | null>(null);
const failed = ref(false);
const loading = ref(false);
const current = computed(() => types.value.find((x) => x.typeCode === selected.value));
const buckets = computed(() => Object.entries(account.value?.snapshot.buckets ?? {}));

async function selectType(typeCode: string) {
  selected.value = typeCode;
  account.value = null;
  failed.value = false;
  try {
    account.value = await api.mStorePointAccount(typeCode);
  } catch {
    failed.value = true;
  }
}

async function load() {
  if (!canView.value || !merchant.storeNo) return;
  loading.value = true;
  try {
    types.value = await api.mStorePointTypes();
    const choice = types.value.find((x) => x.typeCode === selected.value)
      ?? types.value.find((x) => x.status === "ACTIVE") ?? types.value[0];
    if (choice) await selectType(choice.typeCode);
    else { selected.value = ""; account.value = null; failed.value = false; }
  } catch {
    failed.value = true;
  } finally {
    loading.value = false;
  }
}

function openEntries() {
  if (!selected.value) return;
  uni.navigateTo({ url: `/pages/points-records/index?typeCode=${encodeURIComponent(selected.value)}` });
}

onShow(() => { void load(); });
</script>

<template>
  <sh-scaffold title-key="points.title" :denied="!canView" :failed="failed" @retry="load">
    <view class="sh-card">
      <text class="txt-title">{{ $t("points.storeLedger") }}</text>
      <text class="sh-muted">{{ merchant.currentStore?.name ?? merchant.storeNo }}</text>
      <text class="txt-caption sh-muted note">{{ $t("points.storeLedgerHint") }}</text>
    </view>
    <sh-empty v-if="!loading && !types.length" :text="$t('points.noTypes')" />
    <view v-if="types.length" class="sh-card sh-mt-sm">
      <text class="sh-muted">{{ $t("points.type") }}</text>
      <view class="choices">
        <view v-for="p in types" :key="p.typeCode" class="sh-chip"
          :class="{ 'sh-chip--primary': selected === p.typeCode }" @tap="selectType(p.typeCode)">
          {{ p.typeCode }} · {{ p.market }}
        </view>
      </view>
      <text v-if="current" class="txt-caption sh-muted note">
        {{ current.currency }} · {{ current.rate.pointsUnit }} {{ $t("points.pointsUnit") }} =
        {{ current.rate.minorUnit }} {{ $t("points.minorUnit") }} · {{ current.status }}
      </text>
    </view>
    <view v-if="account" class="sh-card sh-mt-sm">
      <text class="txt-title">{{ $t("points.accountBuckets") }}</text>
      <text v-if="!account.exists" class="sh-muted note">{{ $t("points.emptyAccount") }}</text>
      <view v-for="[bucket, value] in buckets" :key="bucket" class="sh-row sh-row--between row">
        <text class="sh-muted">{{ bucket }}</text><text class="sh-num">{{ value }}</text>
      </view>
      <view class="sh-btn sh-btn--muted sh-mt-sm" @tap="openEntries">{{ $t("points.records") }}</view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.note { display: block; margin-top: 12rpx; }
.choices { display: flex; flex-wrap: wrap; gap: 12rpx; margin-top: 12rpx; }
.row { margin-top: 12rpx; }
</style>
