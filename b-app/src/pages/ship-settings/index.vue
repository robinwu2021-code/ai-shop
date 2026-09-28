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
import { pick } from "@ai-shop/ui/prompt";

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

const carrierName = computed(() => EXPRESS_COMPANIES.find((c) => c.code === form.value.carrier)?.name ?? "");

/** 十几家快递摊成一片药丸太乱：收成一行，点开再选（第一项是「不指定」） */
async function pickCarrier() {
  if (!editable.value) return;
  const items = [String(t("shipSetting.carrierNone")), ...EXPRESS_COMPANIES.map((c) => c.name)];
  const at = EXPRESS_COMPANIES.findIndex((c) => c.code === form.value.carrier);
  const i = await pick({ title: String(t("shipSetting.carrier")), items, selected: at + 1 });
  if (i === null) return;
  form.value.carrier = i === 0 ? "" : (EXPRESS_COMPANIES[i - 1]?.code ?? "");
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

    <!--
      一行一项：名字在左、值在右（与营销、常用功能同一套行）。
      「不填时用什么」不写成文字：**默认值本身就当占位灰字显示在框里**，留空＝用它。
      placeholder 也不重复左边的标签（「寄件电话｜寄件电话」读两遍同一个词）。
    -->
    <view v-if="setting" class="sh-card">
      <view class="sh-card__head">
        <text class="txt-title">{{ $t("shipSetting.sender") }}</text>
      </view>
      <view class="sh-row sh-row--between sh-row--divided">
        <text class="txt-body row__k">{{ $t("shipSetting.senderName") }}</text>
        <input v-model="form.senderName" class="txt-body row__v" maxlength="64" :disabled="!editable"
          :placeholder="setting.defaultSenderName" />
      </view>
      <view class="sh-row sh-row--between sh-row--divided">
        <text class="txt-body row__k">{{ $t("shipSetting.senderPhone") }}</text>
        <input v-model="form.senderPhone" class="txt-body row__v sh-num" type="text" maxlength="20" :disabled="!editable"
          :placeholder="setting.defaultSenderPhone" />
      </view>
      <view class="sh-row sh-row--between sh-row--divided">
        <text class="txt-body row__k">{{ $t("shipSetting.address") }}</text>
        <input v-model="form.address" class="txt-body row__v" maxlength="255" :disabled="!editable"
          :placeholder="setting.defaultAddress" />
      </view>
    </view>

    <view v-if="setting" class="sh-card">
      <view class="sh-card__head">
        <text class="txt-title">{{ $t("shipSetting.defaults") }}</text>
      </view>
      <view class="sh-row sh-row--between sh-row--divided" @tap="pickCarrier">
        <text class="txt-body row__k">{{ $t("shipSetting.carrier") }}</text>
        <view class="sh-row">
          <text class="txt-body" :class="carrierName ? '' : 'sh-muted'">{{ carrierName || $t("shipSetting.carrierNone") }}</text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>
      <view class="sh-row sh-row--between sh-row--divided">
        <text class="txt-body row__k">{{ $t("shipSetting.weight") }}</text>
        <view class="sh-row">
          <input v-model="form.weightKg" class="txt-body row__v sh-num" type="digit" maxlength="5" :disabled="!editable"
            placeholder="" />
          <text class="txt-body sh-muted">kg</text>
        </view>
      </view>
    </view>

    <view v-if="template" class="sh-card">
      <view class="sh-card__head">
        <text class="txt-title">{{ $t("shipSetting.template") }}</text>
      </view>
      <view class="sh-row sh-row--between sh-row--divided">
        <text class="txt-body row__k">{{ $t("shipSetting.tplFirst") }}</text>
        <text class="txt-body sh-num">{{ weightText(template.firstWeightGram) }} {{ money(template.firstFee) }}</text>
      </view>
      <view class="sh-row sh-row--between sh-row--divided">
        <text class="txt-body row__k">{{ $t("shipSetting.tplAdd") }}</text>
        <text class="txt-body sh-num">{{ $t("shipSetting.tplAddValue", { w: weightText(template.addWeightGram), fee: money(template.addFee) }) }}</text>
      </view>
      <view class="sh-row sh-row--between sh-row--divided">
        <text class="txt-body row__k">{{ $t("shipSetting.tplFree") }}</text>
        <text class="txt-body sh-num">{{ template.freeThreshold > 0 ? $t("shipSetting.tplFreeValue", { v: money(template.freeThreshold) }) : $t("shipSetting.tplNoFree") }}</text>
      </view>
      <view v-if="surcharges.length" class="sh-row sh-row--between sh-row--divided">
        <text class="txt-body row__k">{{ $t("shipSetting.tplSurcharge") }}</text>
        <text class="txt-body sh-num row__long">{{ surcharges.map((r) => `${r.region} +${money(r.surcharge)}`).join("、") }}</text>
      </view>
      <view v-if="rejected.length" class="sh-row sh-row--between sh-row--divided">
        <text class="txt-body row__k">{{ $t("shipSetting.tplReject") }}</text>
        <text class="txt-body row__long">{{ rejected.map((r) => r.region).join("、") }}</text>
      </view>
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
/* 行：名字不缩，值占剩下的宽、贴右 */
.row__k {
  flex-shrink: 0;
  margin-inline-end: 24rpx;
}
.row__v {
  flex: 1;
  min-width: 0;
  text-align: end;
}
/* 地区一长串：右对齐、可折行 */
.row__long {
  text-align: end;
}
</style>
