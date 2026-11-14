<template>
  <sh-scaffold title-key="bizPay.grant.title" :denied="!merchant.can('mch:pay:app-grant')">
    <view class="sh-card">
      <text class="sh-hint txt-quiet">{{ t("bizPay.grant.hint") }}</text>
      <view class="sh-btn sh-mt-md" @tap="add">{{ t("bizPay.grant.add") }}</view>
    </view>
    <sh-empty v-if="!grants.length" :pending="!loaded" :failed="failed" @retry="load" :text="String(t('bizPay.grant.empty'))"></sh-empty>
    <view v-for="g in grants" :key="g.id" class="sh-card">
      <view class="sh-row sh-row--between">
        <text class="txt-strong">{{ g.appName || g.appNo }}</text>
        <text class="txt-caption" :class="g.status === 'ACTIVE' ? 'txt-quiet' : 'is-danger'">{{ t(`bizPay.grant.st_${g.status}`) }}</text>
      </view>
      <text class="txt-caption txt-quiet sh-num">{{ g.appNo }}</text>
      <view class="sh-row sh-mt-xs chips">
        <text v-for="a in g.actions" :key="a" class="sh-chip">{{ t(`bizPay.grant.action_${a}`) }}</text>
      </view>
      <view v-if="g.status === 'ACTIVE'" class="sh-btn sh-btn--soft is-danger sh-mt-md" @tap="revoke(g)">{{ t("bizPay.grant.revoke") }}</view>
    </view>
  </sh-scaffold>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { confirm, pick, prompt } from "@ai-shop/ui/prompt";
import type { PayAppGrant } from "@/api/pay";

const { t } = useI18n();
const merchant = useMerchantStore();
const grants = ref<PayAppGrant[]>([]);
const loaded = ref(false);
const failed = ref(false);

/** 三档动作组合：只看单 / 代收款 / 代收款并退款。退款单独授予太容易误勾，按组合给 */
const PRESETS: PayAppGrant["actions"][] = [["READ"], ["COLLECT", "READ"], ["COLLECT", "REFUND", "READ"]];

async function add() {
  const appNo = await prompt({ title: String(t("bizPay.grant.appNo")), hint: String(t("bizPay.grant.appNoHint")), maxlength: 64 });
  if (appNo == null || !appNo.trim()) return;
  const i = await pick({
    title: String(t("bizPay.grant.actions")),
    items: PRESETS.map((p) => p.map((a) => String(t(`bizPay.grant.action_${a}`))).join(" + ")),
  });
  if (i == null) return;
  try {
    const g = await api.mPayGrantApp({ appNo: appNo.trim(), actions: PRESETS[i]! });
    grants.value = [g, ...grants.value.filter((x) => x.id !== g.id)];
  } catch (e) {
    uni.showToast({ title: (e as Error).message || String(t("bizPay.common.failed")), icon: "none" });
  }
}

async function revoke(g: PayAppGrant) {
  const ok = await confirm({ title: String(t("bizPay.grant.revokeConfirm", { app: g.appName || g.appNo })), hint: String(t("bizPay.grant.revokeHint")), danger: true });
  if (!ok) return;
  try {
    await api.mPayRevokeGrant(g.id);
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message || String(t("bizPay.common.failed")), icon: "none" });
  }
}

async function load() {
  try {
    grants.value = await api.mPayAppGrants();
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

onShow(load);
</script>

<style scoped>
.chips {
  gap: 8rpx;
  flex-wrap: wrap;
}
</style>
