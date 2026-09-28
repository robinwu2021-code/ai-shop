<script setup lang="ts">
// 发货设置（TDD-快递100商家寄件 §7 AC12）：寄件人、电话、寄件地址、默认快递公司、默认重量。
//
// 一次填好，发货时自动带出：叫快递上门用它当寄件人，两种发货方式都先选中默认快递公司。
// **五项都可以不填**：不填就用门店名 / 店主手机 / 门店地址 —— 每项下面写明「不填时用什么」，
// 不写的话商家以为空着就寄不了，会去随便填一个。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { EXPRESS_COMPANIES } from "@shared/utils/express-companies";
import type { ShipSetting, StoreFreightTemplate } from "@/api/contract";
import { money } from "@shared/utils/money";

const { t } = useI18n();
const merchant = useMerchantStore();

const setting = ref<ShipSetting | null>(null);
/**
 * 本店适用的运费模板（TDD-快递100商家寄件 §8 AC19）：买家下快递单按它付运费。只读 —— 模板由平台运营维护。
 * 拉不到不挡这一页：发货设置照样能改。
 */
const template = ref<StoreFreightTemplate | null>(null);
const surcharges = computed(() => template.value?.rules.filter((r) => r.action === "SURCHARGE") ?? []);
const rejected = computed(() => template.value?.rules.filter((r) => r.action === "REJECT") ?? []);
/** 克 → 「1kg」「500g」：整公斤写 kg，其余写 g */
function weightText(g: number): string {
  return g % 1000 === 0 ? `${g / 1000}kg` : `${g}g`;
}
const failed = ref(false);
const saving = ref(false);
const form = ref({ senderName: "", senderPhone: "", address: "", carrier: "", weightKg: "" });
/** 改要 biz:store:admin（寄件地址改错，快递员就去了别处）；看只要 biz:store */
const editable = computed(() => merchant.can("biz:store:admin"));

function fill(s: ShipSetting) {
  form.value = {
    senderName: s.senderName ?? "",
    senderPhone: s.senderPhone ?? "",
    address: s.address ?? "",
    carrier: s.carrier ?? "",
    weightKg: s.weightG ? String(s.weightG / 1000) : "",
  };
}

function weightG(kg: string): number | null {
  const n = Number(kg);
  return kg.trim() && n > 0 ? Math.round(n * 1000) : null;
}

const dirty = computed(() => {
  const s = setting.value;
  if (!s) return false;
  const f = form.value;
  return f.senderName.trim() !== (s.senderName ?? "")
    || f.senderPhone.trim() !== (s.senderPhone ?? "")
    || f.address.trim() !== (s.address ?? "")
    || f.carrier !== (s.carrier ?? "")
    || weightG(f.weightKg) !== (s.weightG ?? null);
});

function pickCarrier(code: string) {
  if (!editable.value) return;
  form.value.carrier = form.value.carrier === code ? "" : code;
}

async function load() {
  try {
    setting.value = await api.mShipSetting(merchant.storeNo || "default");
    fill(setting.value);
    failed.value = false;
    template.value = await api.mFreightTemplate(setting.value.storeNo).catch(() => null);
  } catch {
    failed.value = true;
  }
}

async function save() {
  const s = setting.value;
  if (!s || saving.value) return;
  if (!editable.value) {
    uni.showToast({ title: t("shipSetting.adminOnly"), icon: "none" });
    return;
  }
  const f = form.value;
  const w = weightG(f.weightKg);
  if (f.weightKg.trim() && (w === null || w < 100 || w > 30000)) {
    uni.showToast({ title: t("shipSetting.weightRange"), icon: "none" });
    return;
  }
  saving.value = true;
  try {
    setting.value = await api.mSaveShipSetting(s.storeNo, {
      senderName: f.senderName.trim(),
      senderPhone: f.senderPhone.trim(),
      address: f.address.trim(),
      carrier: f.carrier,
      weightG: w,
    });
    fill(setting.value);
    uni.showToast({ title: t("shipSetting.saved"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    saving.value = false;
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="shipSetting.title" :denied="!merchant.can('biz:store')" :failed="failed" @retry="load">
    <biz-store-tag readonly></biz-store-tag>

    <view v-if="setting" class="sh-card">
      <text class="txt-title">{{ $t("shipSetting.sender") }}</text>

      <view class="field">
        <text class="field__label">{{ $t("shipSetting.senderName") }}</text>
        <input v-model="form.senderName" class="field__input" maxlength="64" :disabled="!editable"
          :placeholder="$t('shipSetting.senderName')" />
        <text class="sh-hint">{{ $t("shipSetting.fallback", { v: setting.defaultSenderName }) }}</text>
      </view>

      <view class="field">
        <text class="field__label">{{ $t("shipSetting.senderPhone") }}</text>
        <input v-model="form.senderPhone" class="field__input sh-num" type="text" maxlength="20" :disabled="!editable"
          :placeholder="$t('shipSetting.senderPhone')" />
        <text class="sh-hint">{{ $t("shipSetting.fallback", { v: setting.defaultSenderPhone || "—" }) }}</text>
      </view>

      <view class="field">
        <text class="field__label">{{ $t("shipSetting.address") }}</text>
        <textarea v-model="form.address" class="field__area" maxlength="255" auto-height :disabled="!editable"
          :placeholder="$t('shipSetting.address')" />
        <text class="sh-hint">{{ $t("shipSetting.fallback", { v: setting.defaultAddress || "—" }) }}</text>
      </view>
    </view>

    <view v-if="setting" class="sh-card sh-mt-sm">
      <text class="txt-title">{{ $t("shipSetting.defaults") }}</text>

      <view class="field">
        <text class="field__label">{{ $t("shipSetting.carrier") }}</text>
        <view class="sh-wrap">
          <text
            v-for="c in EXPRESS_COMPANIES"
            :key="c.code"
            class="sh-chip"
            :class="{ 'sh-chip--primary': form.carrier === c.code }"
            @tap="pickCarrier(c.code)"
          >{{ c.name }}</text>
        </view>
      </view>

      <view class="field">
        <text class="field__label">{{ $t("shipSetting.weight") }}</text>
        <input v-model="form.weightKg" class="field__input sh-num" type="digit" maxlength="5" :disabled="!editable"
          :placeholder="$t('shipSetting.weightPh')" />
      </view>
    </view>

    <view v-if="template" class="sh-card sh-mt-sm">
      <text class="txt-title">{{ $t("shipSetting.template") }}</text>
      <text class="sh-hint">{{ $t("shipSetting.templateHint") }}</text>
      <sh-kv between :label="String($t('shipSetting.tplFirst'))">
        <text class="sh-num">{{ weightText(template.firstWeightGram) }} {{ money(template.firstFee) }}</text>
      </sh-kv>
      <sh-kv between :label="String($t('shipSetting.tplAdd'))">
        <text class="sh-num">{{ $t("shipSetting.tplAddValue", { w: weightText(template.addWeightGram), fee: money(template.addFee) }) }}</text>
      </sh-kv>
      <sh-kv between :label="String($t('shipSetting.tplFree'))">
        <text class="sh-num">{{ template.freeThreshold > 0 ? $t("shipSetting.tplFreeValue", { v: money(template.freeThreshold) }) : $t("shipSetting.tplNoFree") }}</text>
      </sh-kv>
      <sh-kv v-if="surcharges.length" between :label="String($t('shipSetting.tplSurcharge'))">
        <text class="sh-num tpl__v">{{ surcharges.map((r) => `${r.region} +${money(r.surcharge)}`).join("、") }}</text>
      </sh-kv>
      <sh-kv v-if="rejected.length" between :label="String($t('shipSetting.tplReject'))">
        <text class="tpl__v">{{ rejected.map((r) => r.region).join("、") }}</text>
      </sh-kv>
    </view>

    <sh-savebar
      :visible="dirty && editable"
      :text="String($t('shipSetting.unsaved'))"
      :discard-text="String($t('store.discard'))"
      :save-text="String($t('common.save'))"
      @discard="setting && fill(setting)"
      @save="save"
    ></sh-savebar>
  </sh-scaffold>
</template>

<style scoped>
.field + .field {
  margin-top: 24rpx;
}
/* 地区一长串：右对齐、可折行 */
.tpl__v {
  text-align: end;
}
</style>
