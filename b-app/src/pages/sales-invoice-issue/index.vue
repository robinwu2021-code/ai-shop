<template>
  <sh-scaffold title-key="salesInvoice.issueTitle" :denied="!merchant.can('biz:invoice:issue')">
    <biz-store-tag readonly></biz-store-tag>
    <view class="sh-cells">
      <view class="sh-cell sh-row sh-row--between">
        <text class="txt-body sh-muted">{{ t("salesInvoice.sourceNo") }}</text>
        <input v-model="sourceNo" maxlength="64" class="txt-body sh-num sh-fill cell-input"
          :placeholder="String(t('salesInvoice.sourceNoPh'))" />
      </view>
    </view>

    <sh-section :title="String(t('salesInvoice.buyer'))" pad>
      <view class="sh-row sh-wrap">
        <text v-for="k in BUYER_KINDS" :key="k" class="sh-chip" :class="kind === k ? 'sh-chip--primary' : ''"
          @tap="kind = k">{{ t(`salesInvoice.kind.${k}`) }}</text>
      </view>
    </sh-section>

    <view v-if="kind !== 'GENERAL'" class="sh-cells sh-mt-sm">
      <template v-if="kind === 'TAX_ID'">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ t("salesInvoice.taxId") }}</text>
          <input v-model="form.taxId" type="number" maxlength="8" class="txt-body sh-num sh-fill cell-input" />
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ t("salesInvoice.buyerName") }}</text>
          <input v-model="form.name" maxlength="60" class="txt-body sh-fill cell-input" :placeholder="String(t('salesInvoice.optional'))" />
        </view>
      </template>
      <view v-else-if="kind === 'MOBILE' || kind === 'CITIZEN'" class="sh-cell sh-row sh-row--between">
        <text class="txt-body sh-muted">{{ t("salesInvoice.carrierId") }}</text>
        <input v-model="form.carrierId" maxlength="16" class="txt-body sh-num sh-fill cell-input"
          :placeholder="String(kind === 'MOBILE' ? t('salesInvoice.mobilePh') : t('salesInvoice.citizenPh'))" />
      </view>
      <view v-else class="sh-cell sh-row sh-row--between">
        <text class="txt-body sh-muted">{{ t("salesInvoice.donate") }}</text>
        <input v-model="form.donateCode" type="number" maxlength="7" class="txt-body sh-num sh-fill cell-input"
          :placeholder="String(t('salesInvoice.donatePh'))" />
      </view>
    </view>

    <view class="sh-card sh-mt-sm">
      <text class="sh-hint txt-quiet">{{ t("salesInvoice.issueHint") }}</text>
      <view v-if="errMsg" class="sh-notice sh-notice--danger sh-mt-sm" role="alert">{{ errMsg }}</view>
      <view class="sh-btn sh-mt-md" :class="busy ? 'is-disabled' : ''" @tap="submit">{{ t("salesInvoice.submit") }}</view>
      <view v-if="kind !== 'GENERAL'" class="sh-btn sh-btn--soft sh-mt-sm" :class="busy ? 'is-disabled' : ''" @tap="savePreference">{{ t("salesInvoice.preferOnly") }}</view>
      <text v-if="kind !== 'GENERAL'" class="sh-hint txt-quiet sh-mt-xs">{{ t("salesInvoice.preferHint") }}</text>
    </view>
  </sh-scaffold>
</template>

<script setup lang="ts">
// 开发票：按来源单（首期 SUB_ORDER 子订单）开立，金额与明细由发票域读冻结事实，这里只给买受人。
// 从订单详情进来时带 sourceNo 预填。同一来源重复提交后端返回原发票，所以不需要幂等键。
import { reactive, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { BUYER_KINDS, buildBuyer, type BuyerKind } from "@/shared/sales-invoice";

const { t } = useI18n();
const merchant = useMerchantStore();
const sourceNo = ref("");
const kind = ref<BuyerKind>("GENERAL");
const form = reactive({ taxId: "", name: "", carrierId: "", donateCode: "" });
const busy = ref(false);
const errMsg = ref("");

async function submit() {
  if (busy.value) return;
  const no = sourceNo.value.trim();
  const buyer = buildBuyer(kind.value, form);
  if (!no || buyer === null) {
    errMsg.value = String(t("salesInvoice.required"));
    return;
  }
  busy.value = true;
  errMsg.value = "";
  try {
    const inv = await api.mSiIssue({ sourceType: "SUB_ORDER", sourceNo: no, ...(buyer ? { buyer } : {}) });
    uni.redirectTo({ url: `${ROUTES.salesInvoice}?invoiceNo=${encodeURIComponent(inv.invoiceNo)}` });
  } catch (e) {
    errMsg.value = e instanceof Error && e.message ? e.message : String(t("salesInvoice.failed"));
  } finally {
    busy.value = false;
  }
}

/** 付款前只预存买受人，不开票：门店开了「付款成功后自动开立」时，付款后按它开立。 */
async function savePreference() {
  if (busy.value) return;
  const no = sourceNo.value.trim();
  const buyer = buildBuyer(kind.value, form);
  if (!no || !buyer) {
    errMsg.value = String(t("salesInvoice.required"));
    return;
  }
  busy.value = true;
  errMsg.value = "";
  try {
    await api.mSiBuyerPreference({ sourceType: "SUB_ORDER", sourceNo: no, buyer });
    uni.showToast({ title: String(t("salesInvoice.saved")), icon: "none" });
  } catch (e) {
    errMsg.value = e instanceof Error && e.message ? e.message : String(t("salesInvoice.failed"));
  } finally {
    busy.value = false;
  }
}

onLoad((q) => {
  sourceNo.value = String(q?.sourceNo ?? "");
});
</script>

<style scoped>
.cell-input {
  text-align: end;
}
</style>
