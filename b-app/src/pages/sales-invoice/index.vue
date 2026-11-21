<template>
  <sh-scaffold title-key="salesInvoice.detailTitle" :denied="!merchant.can('biz:invoice:read')">
    <biz-store-tag readonly></biz-store-tag>
    <sh-empty v-if="!inv" :pending="!loaded" :failed="failed" @retry="load"></sh-empty>

    <template v-else>
      <view class="sh-card">
        <view class="sh-row sh-row--between">
          <text class="txt-title sh-num">{{ inv.fiscalNumber }}</text>
          <text class="sh-chip" :class="inv.status === 'VOIDED' ? 'sh-chip--danger' : 'sh-chip--primary'">{{ t(`salesInvoice.status.${inv.status}`) }}</text>
        </view>
        <view class="sh-mt-md">
          <sh-kv between :label="String(t('salesInvoice.period'))"><text class="sh-num">{{ inv.periodCode }}</text></sh-kv>
          <sh-kv between :label="String(t('salesInvoice.randomCode'))"><text class="sh-num">{{ inv.randomCode }}</text></sh-kv>
          <sh-kv between :label="String(t('salesInvoice.issuedAt'))"><text class="sh-num">{{ inv.issuedAt }}</text></sh-kv>
          <sh-kv between :label="String(t('salesInvoice.source'))"><text class="sh-num">{{ inv.sourceNo }}</text></sh-kv>
          <sh-kv between :label="String(t('salesInvoice.buyer'))"><text>{{ buyerText }}</text></sh-kv>
          <sh-kv v-if="inv.carrierId" between :label="String(t('salesInvoice.carrier'))"><text class="sh-num">{{ inv.carrierType }} · {{ inv.carrierId }}</text></sh-kv>
          <sh-kv v-if="inv.donateCode" between :label="String(t('salesInvoice.donate'))"><text class="sh-num">{{ inv.donateCode }}</text></sh-kv>
          <sh-kv between :label="String(t('salesInvoice.uploadLabel'))">
            <text :class="inv.uploadStatus === 'FAILED' ? 'is-danger' : ''">{{ t(`salesInvoice.upload.${inv.uploadStatus}`) }}</text>
          </sh-kv>
          <sh-kv v-if="inv.voidReason" between :label="String(t('salesInvoice.voidReason'))"><text>{{ inv.voidReason }}</text></sh-kv>
        </view>
        <view class="sh-mt-md">
          <sh-kv between :label="String(t('salesInvoice.sales'))"><text class="sh-num">{{ invoiceMoney(inv.salesMinor, inv.currency) }}</text></sh-kv>
          <sh-kv v-if="inv.zeroTaxSalesMinor" between :label="String(t('salesInvoice.zeroTaxSales'))"><text class="sh-num">{{ invoiceMoney(inv.zeroTaxSalesMinor, inv.currency) }}</text></sh-kv>
          <sh-kv v-if="inv.freeTaxSalesMinor" between :label="String(t('salesInvoice.freeTaxSales'))"><text class="sh-num">{{ invoiceMoney(inv.freeTaxSalesMinor, inv.currency) }}</text></sh-kv>
          <sh-kv between :label="String(t('salesInvoice.tax'))"><text class="sh-num">{{ invoiceMoney(inv.taxMinor, inv.currency) }}</text></sh-kv>
          <sh-kv between :label="String(t('salesInvoice.total'))"><text class="txt-strong sh-num">{{ invoiceMoney(inv.totalMinor, inv.currency) }}</text></sh-kv>
          <sh-kv v-if="inv.allowedMinor" between :label="String(t('salesInvoice.allowed'))"><text class="sh-num">{{ invoiceMoney(inv.allowedMinor, inv.currency) }}</text></sh-kv>
        </view>
        <text v-if="!inv.printMark" class="sh-hint txt-quiet sh-mt-sm">{{ t("salesInvoice.noPrint") }}</text>
      </view>

      <view v-if="inv.lines?.length" class="sh-card">
        <text class="txt-strong">{{ t("salesInvoice.lines") }}</text>
        <view v-for="l in inv.lines" :key="l.lineNo" class="sh-row--divided">
          <view class="sh-row sh-row--between">
            <text class="txt-sub">{{ l.name }} × {{ l.quantity }}{{ l.unit ?? "" }}<text v-if="l.taxType && l.taxType !== 'TAXABLE'" class="txt-caption txt-quiet"> · {{ t(`salesInvoice.taxType.${l.taxType}`) }}</text></text>
            <text class="txt-sub sh-num">{{ invoiceMoney(l.amountMinor, inv.currency) }}</text>
          </view>
        </view>
      </view>

      <view v-if="inv.allowances?.length" class="sh-card">
        <text class="txt-strong">{{ t("salesInvoice.allowances") }}</text>
        <view v-for="a in inv.allowances" :key="a.allowanceNo" class="sh-row--divided">
          <view class="sh-row sh-row--between">
            <text class="txt-sub sh-num">{{ a.allowanceNo }}</text>
            <text class="txt-sub sh-num">{{ invoiceMoney(a.amountMinor, inv.currency) }}</text>
          </view>
          <text class="txt-caption txt-quiet">{{ a.reason }} · {{ a.createdAt }}</text>
        </view>
      </view>

      <view v-if="error" class="sh-notice sh-notice--danger" role="alert">{{ error }}</view>

      <view v-if="actionable" class="sh-card sh-stack">
        <view v-if="canPrint" class="sh-btn" @tap="printProof">{{ t("salesInvoice.print") }}</view>
        <view v-if="merchant.can('biz:print:read')" class="sh-btn sh-btn--soft" @tap="openPrintCenter">{{ t("salesInvoice.printCenter") }}</view>
        <view v-if="canVoid" class="sh-btn sh-btn--soft" @tap="allowance">{{ t("salesInvoice.allowance") }}</view>
        <view v-if="canVoid" class="sh-btn sh-btn--soft is-danger" @tap="voidInvoice">{{ t("salesInvoice.void") }}</view>
      </view>
    </template>
  </sh-scaffold>
</template>

<script setup lang="ts">
// 销售发票详情：号码、期别、随机码、买受人与上传状态；作废（当期）、开折让、打印证明联。
// 证明联与补印走共享打印中心（来源类型 INVOICE，场景 INVOICE_PROOF），补印在打印中心的任务上做。
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { confirm, prompt } from "@ai-shop/ui/prompt";
import { api } from "@/api";
import { PrintCommands } from "@/api/print";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { invoiceMinor, invoiceMoney } from "@/shared/sales-invoice";
import type { SalesInvoice } from "@/api/sales-invoice";

const { t } = useI18n();
const merchant = useMerchantStore();
const prints = new PrintCommands();
const invoiceNo = ref("");
const inv = ref<SalesInvoice | null>(null);
const loaded = ref(false);
const failed = ref(false);
const busy = ref(false);
const error = ref("");

const issued = computed(() => inv.value?.status === "ISSUED");
const canVoid = computed(() => issued.value && merchant.can("biz:invoice:void"));
const canPrint = computed(() => issued.value && !!inv.value?.printMark && merchant.can("biz:print:submit"));
const actionable = computed(() => canPrint.value || canVoid.value || merchant.can("biz:print:read"));
const buyerText = computed(() => {
  const i = inv.value;
  if (!i) return "";
  return i.buyerTaxId ? `${t("salesInvoice.taxId")} ${i.buyerTaxId}` : String(t("salesInvoice.buyerB2C"));
});

function message(e: unknown): string {
  return e instanceof Error && e.message ? e.message : String(t("salesInvoice.failed"));
}

async function load() {
  if (!invoiceNo.value) return;
  const store = merchant.storeNo;
  failed.value = false;
  try {
    const r = await api.mSiDetail(invoiceNo.value);
    if (store !== merchant.storeNo) return;
    inv.value = r;
  } catch {
    failed.value = true;
  } finally {
    loaded.value = true;
  }
}

async function run(action: () => Promise<SalesInvoice | null>, done: string) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    const r = await action();
    if (r) inv.value = r;
    uni.showToast({ title: done, icon: "none" });
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}

async function voidInvoice() {
  const i = inv.value;
  if (!i) return;
  if (!await confirm({ title: String(t("salesInvoice.voidTitle")), hint: String(t("salesInvoice.voidHint")), danger: true })) return;
  const reason = (await prompt({ title: String(t("salesInvoice.voidReason")), maxlength: 60 }))?.trim();
  if (!reason) return;
  await run(() => api.mSiVoid(i.invoiceNo, { reason }), String(t("salesInvoice.done")));
}

async function allowance() {
  const i = inv.value;
  if (!i) return;
  const left = i.totalMinor - i.allowedMinor;
  const raw = (await prompt({
    title: String(t("salesInvoice.allowanceAmount")),
    hint: String(t("salesInvoice.allowanceMax", { amount: invoiceMoney(left, i.currency) })),
    type: "digit",
    maxlength: 12,
  }))?.trim();
  if (!raw) return;
  const amountMinor = invoiceMinor(raw, i.currency);
  if (!Number.isFinite(amountMinor) || amountMinor <= 0 || amountMinor > left) {
    error.value = String(t("salesInvoice.invalidAmount"));
    return;
  }
  const reason = (await prompt({ title: String(t("salesInvoice.allowanceReason")), maxlength: 60 }))?.trim();
  if (!reason) return;
  await run(() => api.mSiAllowance(i.invoiceNo, { amountMinor, reason }), String(t("salesInvoice.done")));
}

async function printProof() {
  const i = inv.value;
  if (!i) return;
  const sourceRef = { type: "INVOICE", no: i.invoiceNo, factNo: i.invoiceNo, revision: "1", scene: "INVOICE_PROOF" };
  await run(async () => {
    await prints.run("J03", {
      storeNo: merchant.storeNo,
      sourceRef,
      scene: sourceRef.scene,
      expectedSourceRevision: sourceRef.revision,
      printMode: "EXISTING_FACT",
      outputOptions: { pdfEnabled: true, pdfProfile: "ORIGINAL_ROLL" },
    });
    return null;
  }, String(t("salesInvoice.printSent")));
}

function openPrintCenter() {
  if (inv.value) uni.navigateTo({ url: `${ROUTES.printCenter}?orderNo=${encodeURIComponent(inv.value.invoiceNo)}` });
}

onLoad((q) => {
  invoiceNo.value = String(q?.invoiceNo ?? "");
});
onShow(load);
</script>
