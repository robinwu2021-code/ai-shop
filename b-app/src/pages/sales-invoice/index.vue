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
            <text :class="uploadTrouble ? 'is-danger' : ''">{{ t(`salesInvoice.upload.${inv.uploadStatus}`) }}</text>
          </sh-kv>
          <text v-if="inv.uploadStatus === 'HELD'" class="sh-hint txt-quiet">{{ t("salesInvoice.heldHint") }}</text>
          <view v-if="inv.uploadStatus === 'NEEDS_REVIEW'" class="sh-mt-sm">
            <text class="sh-hint txt-quiet">{{ t("salesInvoice.reviewHint") }}</text>
            <view v-if="merchant.can('biz:invoice:issue')" class="sh-btn sh-btn--soft sh-btn--sm sh-mt-sm"
              :class="busy ? 'is-disabled' : ''" @tap="retransmit">{{ t("salesInvoice.retransmit") }}</view>
          </view>
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
          <view v-if="canSubmitPrint" class="sh-btn sh-btn--soft sh-btn--sm sh-mt-sm" @tap="printAllowance(a.allowanceNo)">{{ t("salesInvoice.printAllowance") }}</view>
        </view>
      </view>

      <view v-if="error" class="sh-notice sh-notice--danger" role="alert">{{ error }}</view>

      <view v-if="actionable" class="sh-card sh-stack">
        <view v-if="canPrint" class="sh-btn" @tap="printProof">{{ t("salesInvoice.print") }}</view>
        <view v-if="canReprint" class="sh-btn sh-btn--soft" @tap="reprintProof">{{ t("salesInvoice.reprint") }}</view>
        <text v-if="canPrint || canReprint" class="sh-hint txt-caption txt-quiet">{{ t("salesInvoice.proofOnceHint") }}</text>
        <view v-if="canPrintDetail" class="sh-btn sh-btn--soft" @tap="printDetail">{{ t("salesInvoice.printDetail") }}</view>
        <view v-if="merchant.can('biz:print:read')" class="sh-btn sh-btn--soft" @tap="openPrintCenter">{{ t("salesInvoice.printCenter") }}</view>
        <view v-if="canVoid" class="sh-btn sh-btn--soft" @tap="allowance">{{ t("salesInvoice.allowance") }}</view>
        <view v-if="canVoid" class="sh-btn sh-btn--soft is-danger" @tap="voidInvoice">{{ t("salesInvoice.void") }}</view>
      </view>
    </template>
  </sh-scaffold>
</template>

<script setup lang="ts">
// 销售发票详情：号码、期别、随机码、买受人与上传状态；作废（当期）、开折让、打印证明联。
// 证明联、交易明细、折让证明单都走共享打印中心（来源 INVOICE / INVOICE_ALLOWANCE）。
// 证明联法定只有一张正本：重复提交只回原请求，再要一张走补印（票面标「補印」），不提供加印。
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { confirm, pick, prompt } from "@ai-shop/ui/prompt";
import { api } from "@/api";
import { callPrint, PrintCommands, type PrintJob, type PrintOperation, type PrintPage } from "@/api/print";
import { operationFailureText, printErrorText } from "@/shared/print-center";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { invoiceMinor, invoiceMoney } from "@/shared/sales-invoice";
import type { SalesInvoice } from "@/api/sales-invoice";

const { t, te } = useI18n();
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
const canSubmitPrint = computed(() => merchant.can("biz:print:submit"));
const uploadTrouble = computed(() => ["FAILED", "HELD", "NEEDS_REVIEW"].includes(inv.value?.uploadStatus ?? ""));
const canPrint = computed(() => issued.value && !!inv.value?.printMark && canSubmitPrint.value);
const canPrintDetail = computed(() => issued.value && canSubmitPrint.value);
// 补印要先在打印中心找到这张证明联的任务（biz:print:read），再对它补印（biz:print:reprint）
const canReprint = computed(() => issued.value && !!inv.value?.printMark
  && merchant.can("biz:print:reprint") && merchant.can("biz:print:read"));
const actionable = computed(() => canPrint.value || canPrintDetail.value || canReprint.value || canVoid.value || merchant.can("biz:print:read"));
const buyerText = computed(() => {
  const i = inv.value;
  if (!i) return "";
  return i.buyerTaxId ? `${t("salesInvoice.taxId")} ${i.buyerTaxId}` : String(t("salesInvoice.buyerB2C"));
});

function message(e: unknown): string {
  return printErrorText(e, t, te, String(t("salesInvoice.failed")));
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

async function retransmit() {
  const i = inv.value;
  if (!i) return;
  await run(() => api.mSiRetransmit(i.invoiceNo), String(t("salesInvoice.retransmitted")));
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

type PrintSource = { type: string; no: string; scene: string };

/** 按已有业务事实出纸（EXISTING_FACT）；同一事实重复提交只返回原请求，不会多出一张正本。 */
async function submitPrint(source: PrintSource, done: string) {
  const sourceRef = { type: source.type, no: source.no, factNo: source.no, revision: "1", scene: source.scene };
  await run(async () => {
    const op = await prints.run("J03", {
      storeNo: merchant.storeNo,
      sourceRef,
      scene: sourceRef.scene,
      expectedSourceRevision: sourceRef.revision,
      printMode: "EXISTING_FACT",
      outputOptions: { pdfEnabled: true, pdfProfile: "ORIGINAL_ROLL" },
    });
    failIfRejected(op);
    return null;
  }, done);
}

function failIfRejected(op: PrintOperation) {
  const failure = operationFailureText(op, t, te);
  if (failure) throw new Error(failure);
}

async function printProof() {
  const i = inv.value;
  if (i) await submitPrint({ type: "INVOICE", no: i.invoiceNo, scene: "INVOICE_PROOF" }, String(t("salesInvoice.printSent")));
}

async function printDetail() {
  const i = inv.value;
  if (i) await submitPrint({ type: "INVOICE", no: i.invoiceNo, scene: "INVOICE_DETAIL" }, String(t("salesInvoice.printSent")));
}

async function printAllowance(allowanceNo: string) {
  await submitPrint({ type: "INVOICE_ALLOWANCE", no: allowanceNo, scene: "INVOICE_ALLOWANCE" }, String(t("salesInvoice.printSent")));
}

const PROOF_PAGES = 5;
const ACTIVE = ["PENDING", "DISPATCHING", "ACCEPTED", "PRINTING", "RETRY_WAIT", "STALE"];

/** 打印中心按 documentKey（`发票号:INVOICE_PROOF`）认这张证明联；补印沿用原任务的 key，按新到旧排。 */
async function proofJobs(invoiceNo: string): Promise<PrintJob[]> {
  const key = `${invoiceNo}:INVOICE_PROOF`;
  const found: PrintJob[] = [];
  for (let page = 1; page <= PROOF_PAGES; page++) {
    const r = await callPrint<PrintPage<PrintJob>>("J06", { storeNo: merchant.storeNo, scene: "INVOICE_PROOF", page, size: 100 });
    found.push(...r.records.filter((j) => j.documentKey === key));
    if (found.length || page * 100 >= r.total) break;
  }
  return found;
}

const REPRINT_REASONS = ["JAM", "PAPER_OUT", "LOST", "OTHER"] as const;

async function reprintReason(): Promise<string | null> {
  const idx = await pick({
    title: String(t("salesInvoice.reprintTitle")),
    hint: String(t("salesInvoice.reprintHint")),
    items: REPRINT_REASONS.map((r) => String(t(`salesInvoice.reprintReason.${r}`))),
  });
  if (idx === null) return null;
  const code = REPRINT_REASONS[idx];
  if (code !== "OTHER") return String(t(`salesInvoice.reprintReason.${code}`));
  return (await prompt({ title: String(t("salesInvoice.reprintOther")), maxlength: 60 }))?.trim() || null;
}

async function reprintProof() {
  const i = inv.value;
  if (!i || busy.value) return;
  const store = merchant.storeNo;
  error.value = "";
  let jobs: PrintJob[];
  busy.value = true;
  try {
    jobs = await proofJobs(i.invoiceNo);
  } catch (e) {
    error.value = message(e);
    return;
  } finally {
    busy.value = false;
  }
  if (store !== merchant.storeNo) return;
  // 正本或上一次补印还没落地时不能再补（后端同样拒绝）；结果待核实的要先到打印中心核实，免得重复出纸
  const target = jobs.some((j) => ACTIVE.includes(j.status)) ? undefined
    : jobs.find((j) => j.status !== "UNKNOWN" && (j.allowedActions ?? []).includes("REPRINT"));
  if (!target) {
    error.value = String(t(!jobs.length ? "salesInvoice.reprintNoOriginal"
      : jobs.some((j) => ACTIVE.includes(j.status)) ? "salesInvoice.reprintBusy"
      : jobs.some((j) => j.status === "UNKNOWN") ? "salesInvoice.reprintUnknown"
      : "salesInvoice.reprintUnavailable"));
    return;
  }
  const reason = await reprintReason();
  if (!reason) return;
  await run(async () => {
    const op = await prints.run("J13", { expectedRevision: target.revision, reason, riskAcknowledged: false }, { jobNo: target.jobNo });
    failIfRejected(op);
    return null;
  }, String(t("salesInvoice.reprintSent")));
}

function openPrintCenter() {
  // 发票的打印件挂在来源子单上（打印中心的作业列表按订单号同时匹配主单与子单）
  if (inv.value) uni.navigateTo({ url: `${ROUTES.printCenter}?orderNo=${encodeURIComponent(inv.value.sourceNo)}` });
}

onLoad((q) => {
  invoiceNo.value = String(q?.invoiceNo ?? "");
});
onShow(load);
</script>
