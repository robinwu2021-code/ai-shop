<script setup lang="ts">
import { computed, ref, watch } from "vue";
import StorePointStatements from "@/components/points/StorePointStatements.vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { confirm as confirmDialog } from "@ai-shop/ui/prompt";
import { useMerchantStore } from "@/stores/merchant";
import type { PaymentApplyment, StorePointAccount, StorePointAgreement, StorePointTerms, StorePointType } from "@shared/types";

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
const { t } = useI18n();
const canView = computed(() => merchant.can("biz:finance"));
const isOwner = computed(() => merchant.perms.includes("*"));
const types = ref<StorePointType[]>([]);
const selected = ref("");
const account = ref<StorePointAccount | null>(null);
const terms = ref<StorePointTerms | null>(null);
const agreement = ref<StorePointAgreement | null>(null);
const payments = ref<PaymentApplyment[]>([]);
const channel = ref("");
const settleMode = ref<"PER_ORDER" | "PERIODIC">("PER_ORDER");
const accepted = ref(false);
const saving = ref(false);
const failed = ref(false);
const loading = ref(false);
const current = computed(() => types.value.find((x) => x.typeCode === selected.value));
const buckets = computed(() => Object.entries(account.value?.snapshot.buckets ?? {}));
const channels = computed(() => [...new Map(payments.value.filter((p) => p.canReceiveMoney
  && (!p.storeNo || p.storeNo === merchant.storeNo)).map((p) => [p.payChannel, p.channelName])).entries()]);

async function selectType(typeCode: string) {
  const store = merchant.storeNo;
  selected.value = typeCode;
  account.value = null;
  terms.value = null;
  agreement.value = null;
  accepted.value = false;
  failed.value = false;
  try {
    const result = await Promise.all([
      api.mStorePointAccount(typeCode), api.mStorePointTerms(typeCode), api.mStorePointAgreement(typeCode),
    ]);
    if (merchant.storeNo !== store || selected.value !== typeCode) return;
    [account.value, terms.value, agreement.value] = result;
  } catch {
    failed.value = true;
  }
}

async function load() {
  pageReady.value = false;
  account.value = null; terms.value = null; agreement.value = null; types.value = [];
  loading.value = true;
  try {
    if (linkQuery.value) {
      const target = await preparePointStatementLink(merchant, linkQuery.value);
      selected.value = target.typeCode; targetStatement.value = target.statementNo;
    } else {
      await Promise.all([merchant.ensureStores(), merchant.ensureScope()]);
      targetStatement.value = "";
    }
    if (!canView.value || !merchant.storeNo) return;
    const loadedStore = merchant.storeNo;
    types.value = await api.mStorePointTypes();
    if (linkQuery.value && !types.value.some(type => type.typeCode === selected.value)) throw new Error("POINTS_LINK_TYPE_UNKNOWN");
    payments.value = await api.mPayments().catch(() => []);
    if (!channels.value.some(([code]) => code === channel.value)) channel.value = channels.value[0]?.[0] ?? "";
    const choice = types.value.find((x) => x.typeCode === selected.value)
      ?? types.value.find((x) => x.status === "ACTIVE") ?? types.value[0];
    if (choice) await selectType(choice.typeCode);
    else { selected.value = ""; failed.value = false; }
    if (merchant.storeNo !== loadedStore) {
      account.value = null; terms.value = null; agreement.value = null; types.value = [];
      failed.value = true;
      return;
    }
    pageReady.value = true;
  } catch {
    failed.value = true;
  } finally {
    loading.value = false;
  }
}

function confirm(content: string): Promise<boolean> {
  return confirmDialog({ title: String(t("points.title")), hint: content });
}

async function sign() {
  if (!isOwner.value || !terms.value || !selected.value || !channel.value || !accepted.value || saving.value) return;
  if (!await confirm(String(t("points.signConfirm")))) return;
  saving.value = true;
  try {
    agreement.value = await api.mSignStorePointAgreement({ typeCode: selected.value,
      termsVersion: terms.value.version, termsHash: terms.value.hash,
      settleMode: settleMode.value, channel: channel.value });
    accepted.value = false;
  } catch { uni.showToast({ title: String(t("points.actionFailed")), icon: "none" }); }
  finally { saving.value = false; }
}

async function terminate() {
  if (!isOwner.value || !agreement.value || saving.value) return;
  if (!await confirm(String(t("points.terminateConfirm")))) return;
  saving.value = true;
  try {
    await api.mTerminateStorePointAgreement(selected.value);
    agreement.value = await api.mStorePointAgreement(selected.value);
  } catch { uni.showToast({ title: String(t("points.actionFailed")), icon: "none" }); }
  finally { saving.value = false; }
}

function openEntries() {
  if (!selected.value) return;
  uni.navigateTo({ url: `/pages/points-records/index?typeCode=${encodeURIComponent(selected.value)}` });
}

watch(() => merchant.storeNo, () => {
  if (loading.value) return;
  // A manual store change leaves the notification context and reloads only the selected store.
  linkQuery.value = null; targetStatement.value = "";
  pageReady.value = false; account.value = null; terms.value = null; agreement.value = null;
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
      <view v-for="[bucket, value] in buckets" :key="bucket" class="sh-row sh-row--between sh-mt-xs">
        <text class="sh-muted">{{ bucket }}</text><text class="sh-num">{{ value }}</text>
      </view>
      <view class="sh-btn sh-btn--muted sh-mt-sm" @tap="openEntries">{{ $t("points.records") }}</view>
    </view>
    <StorePointStatements v-if="selected && canView" :type-code="selected" :store-no="merchant.storeNo" :target-statement="targetStatement" />
    <view v-if="current" class="sh-card sh-mt-sm">
      <text class="txt-title">{{ $t("points.agreement") }}</text>
      <view v-if="agreement" class="note">
        <text>{{ $t("points.activeAgreement") }} · {{ agreement.termsVersion }} · {{ agreement.settleMode }}</text>
        <view class="terms">{{ agreement.termsText }}</view>
        <view v-if="isOwner" class="sh-btn sh-btn--muted sh-mt-sm" @tap="terminate">{{ $t("points.terminate") }}</view>
      </view>
      <view v-else-if="terms" class="note">
        <text class="txt-caption sh-muted">{{ $t("points.termsVersion") }} {{ terms.version }}</text>
        <view class="terms">{{ terms.text }}</view>
        <template v-if="isOwner && current.status === 'ACTIVE'">
          <text class="txt-caption sh-muted note">{{ $t("points.signHint") }}</text>
          <view class="sh-wrap sh-mt-xs">
            <view v-for="[code, name] in channels" :key="code" class="sh-chip"
              :class="{ 'sh-chip--primary': channel === code }" @tap="channel = code">{{ name }}</view>
          </view>
          <view class="sh-wrap sh-mt-xs">
            <view class="sh-chip" :class="{ 'sh-chip--primary': settleMode === 'PER_ORDER' }"
              @tap="settleMode = 'PER_ORDER'">{{ $t("points.perOrder") }}</view>
            <view class="sh-chip" :class="{ 'sh-chip--primary': settleMode === 'PERIODIC' }"
              @tap="settleMode = 'PERIODIC'">{{ $t("points.periodic") }}</view>
          </view>
          <view class="note" @tap="accepted = !accepted">{{ accepted ? '☑' : '□' }} {{ $t("points.acceptTerms") }}</view>
          <view class="sh-btn sh-mt-sm" :class="{ 'is-dim': !accepted || !channel || saving }" @tap="sign">
            {{ $t("points.sign") }}</view>
        </template>
      </view>
      <text v-else class="sh-muted note">{{ $t("points.noTerms") }}</text>
    </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.note { display: block; margin-top: 12rpx; }
.terms { white-space: pre-wrap; margin-top: 16rpx; }
.is-dim { opacity: 0.45; }
</style>
