<script setup lang="ts">
/**
 * 发票载具管理：手机条码 / 自然人凭证存几个常用的，挑一个当默认。
 *
 * <p>**会员载具不在这里** —— 它的号码就是登录账号，由服务端填，不让人报号码
 *（报号码等于能把发票存进别人的会员载具）。
 * 格式在端上只做提前提示；最终以服务端为准（26004）。
 */
import { computed, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onShow } from "@dcloudio/uni-app";
import { api, ApiError } from "@/api";
import type { InvoiceCarrier, InvoiceCarrierType } from "@/api/contract";
import { confirm } from "@ai-shop/ui/prompt";
import {
  CARRIER_CITIZEN,
  CARRIER_FORMAT_ERROR,
  CARRIER_MOBILE,
  CARRIER_PH_KEY,
  CARRIER_TYPE_KEY,
  carrierIdValid,
  normalizeCarrierId,
} from "@/shared/sales-invoice";

const { t } = useI18n();
const carriers = ref<InvoiceCarrier[]>([]);
const loaded = ref(false);
const failed = ref(false);
const busy = ref(false);

async function load() {
  try {
    carriers.value = await api.invoiceCarriers();
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

const TYPES: readonly InvoiceCarrierType[] = [CARRIER_MOBILE, CARRIER_CITIZEN];
const adding = ref(false);
const draft = reactive({ type: CARRIER_MOBILE as InvoiceCarrierType, id: "", label: "", makeDefault: false });
const normalized = computed(() => normalizeCarrierId(draft.id));
/** 填了但格式不对时才提示；空着不提示（还没开始填） */
const formatWrong = computed(() => !!normalized.value && !carrierIdValid(draft.type, normalized.value));
const canSave = computed(() => !!normalized.value && !formatWrong.value && !busy.value);

function openAdd() {
  Object.assign(draft, { type: CARRIER_MOBILE, id: "", label: "", makeDefault: !carriers.value.length });
  adding.value = true;
}

function toast(title: string) {
  uni.showToast({ title, icon: "none" });
}

function errText(e: unknown): string {
  if (e instanceof ApiError && e.code === CARRIER_FORMAT_ERROR) return String(t("salesInvoice.formatWrong"));
  return (e as Error)?.message || String(t("salesInvoice.failed"));
}

async function save() {
  if (!canSave.value) return;
  busy.value = true;
  try {
    await api.saveInvoiceCarrier({
      carrierType: draft.type,
      carrierId: normalized.value,
      ...(draft.label.trim() ? { label: draft.label.trim() } : {}),
      makeDefault: draft.makeDefault,
    });
    adding.value = false;
    toast(String(t("salesInvoice.saved")));
    await load();
  } catch (e) {
    toast(errText(e));
  } finally {
    busy.value = false;
  }
}

async function makeDefault(c: InvoiceCarrier) {
  if (c.isDefault || busy.value) return;
  busy.value = true;
  try {
    // 同类别同号码再存一次 = 更新；label 要原样带回，否则会被清掉
    await api.saveInvoiceCarrier({
      carrierType: c.carrierType,
      carrierId: c.carrierId,
      ...(c.label ? { label: c.label } : {}),
      makeDefault: true,
    });
    await load();
  } catch (e) {
    toast(errText(e));
  } finally {
    busy.value = false;
  }
}

async function remove(c: InvoiceCarrier) {
  const ok = await confirm({
    title: String(t("salesInvoice.deleteTitle")),
    hint: String(t("salesInvoice.deleteHint", { id: c.carrierId })),
    confirmText: String(t("salesInvoice.delete")),
    danger: true,
  });
  if (!ok) return;
  busy.value = true;
  try {
    await api.deleteInvoiceCarrier(c.carrierNo);
    await load();
  } catch (e) {
    toast(errText(e));
  } finally {
    busy.value = false;
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="salesInvoice.carriersTitle">
    <text class="sh-hint block-gap">{{ $t("salesInvoice.carriersHint") }}</text>

    <view v-for="c in carriers" :key="c.carrierNo" class="sh-card carrier">
      <view class="sh-row sh-row--between carrier__top">
        <view class="sh-fill">
          <text class="txt-strong block-0">{{ c.label || $t(CARRIER_TYPE_KEY[c.carrierType]) }}</text>
          <text class="txt-body sh-num ltr block-0">{{ c.carrierId }}</text>
          <text v-if="c.label" class="txt-caption sh-muted">{{ $t(CARRIER_TYPE_KEY[c.carrierType]) }}</text>
        </view>
        <text v-if="c.isDefault" class="sh-chip sh-chip--primary">{{ $t("salesInvoice.default") }}</text>
      </view>
      <view class="sh-row carrier__ops">
        <text v-if="!c.isDefault" class="sh-link txt-caption" @tap="makeDefault(c)">{{ $t("salesInvoice.setDefault") }}</text>
        <text class="sh-link txt-caption" @tap="remove(c)">{{ $t("salesInvoice.delete") }}</text>
      </view>
    </view>

    <sh-empty v-if="!carriers.length" :pending="!loaded" :failed="failed" :text="$t('salesInvoice.carriersEmpty')" @retry="load" />

    <view v-if="loaded && !failed" class="sh-btn add" @tap="openAdd">{{ $t("salesInvoice.addCarrier") }}</view>

    <sh-sheet :visible="adding" :title="String($t('salesInvoice.addCarrier'))" fit @close="adding = false">
      <view class="sh-row types">
        <sh-option v-for="ty in TYPES" :key="ty" class="sh-fill" :selected="draft.type === ty" @tap="draft.type = ty">
          <text class="txt-body">{{ $t(CARRIER_TYPE_KEY[ty]) }}</text>
        </sh-option>
      </view>
      <input v-model="draft.id" class="field__input field" maxlength="16" :placeholder="String($t(CARRIER_PH_KEY[draft.type]))" />
      <text v-if="formatWrong" class="sh-hint block-0">{{ $t("salesInvoice.formatWrong") }}</text>
      <input v-model="draft.label" class="field__input field" maxlength="32" :placeholder="String($t('salesInvoice.labelPh'))" />
      <view class="sh-row sh-row--between field" @tap="draft.makeDefault = !draft.makeDefault">
        <text class="txt-body">{{ $t("salesInvoice.makeDefault") }}</text>
        <sh-switch :model-value="draft.makeDefault"></sh-switch>
      </view>
      <template #foot>
        <view class="sh-btn" :class="{ 'is-disabled': !canSave }" @tap="save">{{ $t("salesInvoice.save") }}</view>
      </template>
    </sh-sheet>
  </sh-scaffold>
</template>

<style scoped>
.block-gap { display: block; margin-bottom: var(--sh-gap-block); }
.block-0 { display: block; }
.carrier { margin-bottom: var(--sh-gap-block); }
.carrier__top { gap: 16rpx; }
.carrier__ops { gap: 32rpx; margin-top: 16rpx; }
.ltr { direction: ltr; }
.add { margin-top: var(--sh-gap-block); }
.types { gap: 16rpx; }
.field { margin-top: 24rpx; }
</style>
