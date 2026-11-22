<template>
  <sh-scaffold title-key="salesInvoice.printSimTitle" :denied="!allowed">
    <biz-store-tag readonly></biz-store-tag>
    <view class="sh-notice sh-notice--muted" role="note">{{ t("salesInvoice.printSimHint") }}</view>

    <view class="sh-cells">
      <view v-if="invoiceNo" class="sh-cell sh-row sh-row--between">
        <text class="txt-body sh-muted">{{ t("salesInvoice.printSimSource") }}</text>
        <view class="sh-row">
          <text class="sh-chip" :class="mode === 'INVOICE' ? 'sh-chip--primary' : ''" @tap="mode = 'INVOICE'">{{ t("salesInvoice.printSimThis") }}</text>
          <text class="sh-chip" :class="mode === 'SAMPLE' ? 'sh-chip--primary' : ''" @tap="mode = 'SAMPLE'">{{ t("salesInvoice.printSimSample") }}</text>
        </view>
      </view>

      <view v-if="mode === 'INVOICE'" class="sh-cell sh-stack">
        <text class="txt-body sh-muted">{{ t("salesInvoice.printSimDoc") }}</text>
        <sh-empty v-if="!facts.length" compact :pending="!factsLoaded" :failed="factsFailed" :text="t('salesInvoice.printSimNoDocs')" @retry="loadFacts"></sh-empty>
        <view class="sh-row sh-wrap">
          <text v-for="(f, i) in facts" :key="f.scene + f.docNo" class="sh-chip" :class="factIndex === i ? 'sh-chip--primary' : ''"
            @tap="factIndex = i">{{ docLabel(f.scene) }}<text v-if="f.scene === 'INVOICE_ALLOWANCE'" class="sh-num"> {{ f.docNo }}</text></text>
        </view>
      </view>
      <view v-else class="sh-cell sh-stack">
        <text class="txt-body sh-muted">{{ t("salesInvoice.printSimDoc") }}</text>
        <view class="sh-row sh-wrap">
          <text v-for="s in SCENES" :key="s" class="sh-chip" :class="sampleScene === s ? 'sh-chip--primary' : ''"
            @tap="sampleScene = s">{{ docLabel(s) }}</text>
        </view>
      </view>

      <view class="sh-cell sh-row sh-row--between">
        <text class="txt-body sh-muted">{{ t("salesInvoice.printSimWidth") }}</text>
        <view class="sh-row">
          <text v-for="w in widths" :key="w" class="sh-chip" :class="width === w ? 'sh-chip--primary' : ''" @tap="width = w">{{ w }} mm</text>
        </view>
      </view>
      <view class="sh-cell sh-row sh-row--between">
        <text class="txt-body sh-muted">{{ t("salesInvoice.printSimProfile") }}</text>
        <view class="sh-row">
          <text v-for="p in PROFILES" :key="p" class="sh-chip" :class="profile === p ? 'sh-chip--primary' : ''" @tap="profile = p">{{ t(`salesInvoice.printSimProfiles.${p}`) }}</text>
        </view>
      </view>
    </view>

    <view class="sh-card sh-stack sh-mt-sm">
      <view v-if="error" class="sh-notice sh-notice--danger" role="alert">{{ error }}</view>
      <view class="sh-btn" :class="busy || !canGenerate ? 'is-disabled' : ''" @tap="generate">{{ busy ? t("salesInvoice.printSimGenerating") : t("salesInvoice.printSimGenerate") }}</view>
      <text v-if="ready" class="sh-hint txt-caption txt-quiet">{{ t("salesInvoice.printSimReady", { pages: ready.pageCount }) }}</text>
      <view v-if="ready" class="sh-btn sh-btn--soft" :class="busy ? 'is-disabled' : ''" @tap="openPdf">{{ t("salesInvoice.printSimOpen") }}</view>
    </view>

    <view v-if="pdfUrl"><biz-pdf-frame :src="pdfUrl" :title="String(t('salesInvoice.printSimTitle'))"></biz-pdf-frame></view>
  </sh-scaffold>
</template>

<script setup lang="ts">
// 发票打印模拟（TDD-发票模块 §21）：证明联 / 交易明细 / 折让证明单生成 PDF 直接看，不出纸、不建正式打印任务。
// 数据：从发票详情进来时默认用这张票冻结的打印内容（factRef 来自 /biz/invoices/{no}/print-facts），否则用场景样例。
// 流程：POST /biz/print/simulations（pdfEnabled）→ 轮询导出就绪 → 申请 60 秒下载授权 → 显示（端差异在 ports/pdf-view）。
import { computed, ref, watch } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { callPrint, decodeFrozen, PrintCommands, type ExportView } from "@/api/print";
import type { SiPrintFact } from "@/api/sales-invoice";
import { openPdf as showPdf, releasePdf } from "@/ports/pdf-view";
import { operationFailureText, printErrorText } from "@/shared/print-center";
import { useMerchantStore } from "@/stores/merchant";

const SCENES = ["INVOICE_PROOF", "INVOICE_DETAIL", "INVOICE_ALLOWANCE"] as const;
/** 证明联法定规格 5.7 cm，只有 58 mm 模板；明细与折让证明单两种纸宽 */
const WIDTHS: Record<string, readonly number[]> = { INVOICE_PROOF: [58], INVOICE_DETAIL: [58, 80], INVOICE_ALLOWANCE: [58, 80] };
const PROFILES = ["ORIGINAL_ROLL", "A4_SHEET"] as const;
const POLL_TIMES = 20, POLL_MS = 500;

const { t, te } = useI18n();
const merchant = useMerchantStore();
const commands = new PrintCommands();
const invoiceNo = ref("");
const mode = ref<"SAMPLE" | "INVOICE">("SAMPLE");
const facts = ref<SiPrintFact[]>([]);
const factsLoaded = ref(false);
const factsFailed = ref(false);
const factIndex = ref(0);
const sampleScene = ref<string>("INVOICE_PROOF");
const width = ref(58);
const profile = ref<typeof PROFILES[number]>("ORIGINAL_ROLL");
const busy = ref(false);
const error = ref("");
const ready = ref<ExportView | null>(null);
const pdfUrl = ref("");

const allowed = computed(() => merchant.can("biz:print:simulate") && merchant.can("biz:print:export"));
const fact = computed(() => (mode.value === "INVOICE" ? facts.value[factIndex.value] ?? null : null));
const scene = computed(() => fact.value?.scene ?? sampleScene.value);
const widths = computed(() => WIDTHS[scene.value] ?? [58]);
const canGenerate = computed(() => mode.value === "SAMPLE" || !!fact.value);

// 取第一项用 find 而不用下标：这一行原先的下标写法被 UnoCSS 预转换误当成类名改写，vite 报 Cannot split a chunk
function keepWidth(list: readonly number[]) {
  if (!list.includes(width.value)) width.value = list.find(() => true) ?? 58;
}
watch(widths, keepWidth, { immediate: true });
function clearPdf() {
  releasePdf(pdfUrl.value);
  pdfUrl.value = "";
}
watch([scene, width, profile, mode], () => { ready.value = null; clearPdf(); });

function docLabel(s: string) {
  return String(t(`salesInvoice.printSimDocs.${s}`));
}

function message(e: unknown) {
  return printErrorText(e, t, te, String(t("salesInvoice.failed")));
}

async function loadFacts() {
  factsLoaded.value = false;
  factsFailed.value = false;
  try {
    facts.value = await api.mSiPrintFacts(invoiceNo.value);
  } catch (e) {
    factsFailed.value = true;
    error.value = message(e);
  } finally {
    factsLoaded.value = true;
  }
}

onLoad(async (q) => {
  invoiceNo.value = String(q?.invoiceNo ?? "");
  if (!invoiceNo.value) return;
  // 刷新 / 深链进来时权限还没拉：先等 scope，否则 can() 恒为 false，会误退回样例
  await merchant.ensureScope().catch(() => null);
  if (!merchant.can("biz:invoice:read")) return;
  mode.value = "INVOICE";
  loadFacts();
});

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** 模拟提交后导出立即开始生成；这里等它就绪，最多约 10 秒 */
async function waitReady(exportNo: string): Promise<ExportView> {
  for (let i = 0; i < POLL_TIMES; i++) {
    const v = await callPrint<ExportView>("E03", undefined, { exportNo });
    if (v.status === "READY") return v;
    if (v.status === "FAILED") throw new Error(String(t("salesInvoice.printSimFailed")));
    await sleep(POLL_MS);
  }
  throw new Error(String(t("salesInvoice.printSimSlow")));
}

async function generate() {
  if (busy.value || !canGenerate.value) return;
  busy.value = true;
  error.value = "";
  ready.value = null;
  clearPdf();
  try {
    const f = fact.value;
    const op = await commands.run("S01", {
      storeNo: merchant.storeNo,
      scene: scene.value,
      variantCode: `STANDARD_${width.value}`,
      fixtureCode: "SUCCESS",
      source: { type: f ? "FACT" : "SAMPLE", no: null, factRef: f?.factRef ?? null, expectedRevision: null },
      templateSelection: null,
      outputOptions: { pdfEnabled: true, pdfProfile: profile.value },
    });
    const failure = operationFailureText(op, t, te);
    if (failure) throw new Error(failure);
    if (!op.result) throw new Error(String(t("salesInvoice.printSimFailed")));
    const exportNo = decodeFrozen<{ exportNos: string[] }>(op.result).exportNos[0];
    if (!exportNo) throw new Error(String(t("salesInvoice.printSimFailed")));
    ready.value = await waitReady(exportNo);
    await openPdf();
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}

/** 下载授权只有 60 秒：每次打开都重新申请 */
async function openPdf() {
  const v = ready.value;
  if (!v) return;
  try {
    const grant = await callPrint<{ url: string }>("E04", undefined, { exportNo: v.exportNo });
    const base = import.meta.env.VITE_API_BASE || "";
    const url = grant.url.startsWith("/") ? base + grant.url : grant.url;
    let shown: string;
    try {
      shown = await showPdf(url);
    } catch {
      throw new Error(String(t("printCenter.downloadFailed")));
    }
    clearPdf();
    pdfUrl.value = shown;
  } catch (e) {
    error.value = message(e);
  }
}
</script>
