<template>
  <sh-scaffold title-key="salesInvoice.title" :denied="!merchant.can('biz:invoice:read')">
    <biz-store-tag readonly></biz-store-tag>
    <view class="sh-card">
      <view class="sh-row sh-row--between">
        <input v-model="periodCode" type="number" maxlength="5" class="txt-body sh-num sh-fill"
          :placeholder="String(t('salesInvoice.periodPh'))" @confirm="reload" />
        <view class="sh-btn sh-btn--soft sh-btn--sm" @tap="reload">{{ t("salesInvoice.search") }}</view>
      </view>
      <view class="sh-mt-sm">
        <sh-tabs :items="statusTabs" :active="status" @change="changeStatus"></sh-tabs>
      </view>
      <view v-if="merchant.can('biz:invoice:issue') || merchant.can('biz:invoice:setup') || canSimulate" class="sh-row sh-mt-sm">
        <view v-if="merchant.can('biz:invoice:issue')" class="sh-btn sh-btn--sm" @tap="go(ROUTES.salesInvoiceIssue)">{{ t("salesInvoice.issueTitle") }}</view>
        <view v-if="merchant.can('biz:invoice:setup')" class="sh-btn sh-btn--soft sh-btn--sm" @tap="go(ROUTES.invoiceSetup)">{{ t("salesInvoice.setupTitle") }}</view>
        <view v-if="canSimulate" class="sh-btn sh-btn--soft sh-btn--sm" @tap="go(ROUTES.salesInvoicePrintSim)">{{ t("salesInvoice.printSimTitle") }}</view>
      </view>
    </view>

    <view v-if="backlog && (backlog.overdue || backlog.dueSoon || backlog.held || backlog.needsReview)"
      class="sh-notice" :class="backlog.overdue || backlog.held ? 'sh-notice--danger' : 'sh-notice--warning'" role="alert">
      <text v-if="backlog.overdue">{{ t("salesInvoice.backlogOverdue", { n: backlog.overdue }) }}</text>
      <text v-else-if="backlog.dueSoon">{{ t("salesInvoice.backlogDueSoon", { n: backlog.dueSoon }) }}</text>
      <text v-if="backlog.held"> {{ t("salesInvoice.backlogHeld", { n: backlog.held }) }}</text>
      <text v-if="backlog.needsReview"> {{ t("salesInvoice.backlogReview", { n: backlog.needsReview }) }}</text>
    </view>

    <sh-empty v-if="!rows.length" :pending="!loaded" :failed="failed" @retry="reload"
      :text="String(t('salesInvoice.empty'))"></sh-empty>
    <view v-for="r in rows" :key="r.invoiceNo" class="sh-card" @tap="open(r.invoiceNo)">
      <view class="sh-row sh-row--between">
        <text class="txt-strong sh-num">{{ r.fiscalNumber }}</text>
        <text class="sh-chip" :class="r.status === 'ISSUED' ? 'sh-chip--primary' : r.status === 'REVOKING' ? '' : 'sh-chip--danger'">{{ t(`salesInvoice.status.${r.status}`) }}</text>
      </view>
      <view class="sh-row sh-row--between sh-mt-xs">
        <text class="txt-caption txt-quiet sh-num">{{ r.periodCode }} · {{ r.issuedAt }}</text>
        <text class="txt-strong sh-num">{{ invoiceMoney(r.totalMinor, r.currency) }}</text>
      </view>
      <view class="sh-row sh-row--between sh-mt-xs">
        <text class="txt-caption txt-quiet sh-num">{{ r.sourceNo }}</text>
        <text class="txt-caption" :class="['FAILED', 'HELD', 'REVIEW'].includes(r.uploadStatus) ? 'is-danger' : 'txt-quiet'">{{ t(`salesInvoice.upload.${r.uploadStatus}`) }}</text>
      </view>
    </view>
    <view v-if="rows.length < total" class="sh-btn sh-btn--soft sh-mt-md" @tap="more">{{ t("salesInvoice.more") }}</view>
  </sh-scaffold>
</template>

<script setup lang="ts">
// 本店的销售发票（门店开给顾客的电子发票）。按期别、状态筛，分页往下加载。
import { computed, ref } from "vue";
import { onPullDownRefresh, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { invoiceMoney } from "@/shared/sales-invoice";
import type { SalesInvoice, SiStatus, SiUploadBacklog } from "@/api/sales-invoice";

const { t } = useI18n();
const merchant = useMerchantStore();
const SIZE = 20;
const rows = ref<SalesInvoice[]>([]);
const total = ref(0);
const page = ref(1);
const periodCode = ref("");
const status = ref<"" | SiStatus>("");
const loaded = ref(false);
const failed = ref(false);
let generation = 0;
const backlog = ref<SiUploadBacklog | null>(null);

/** 积压提示失败不影响列表：拿不到就不显示 */
async function loadBacklog() {
  const store = merchant.storeNo;
  try {
    const b = await api.mSiUploadBacklog();
    if (store === merchant.storeNo) backlog.value = b;
  } catch {
    backlog.value = null;
  }
}

const statusTabs = computed(() => [
  { key: "", label: String(t("salesInvoice.allStatus")) },
  { key: "ISSUED", label: String(t("salesInvoice.status.ISSUED")) },
  { key: "VOIDED", label: String(t("salesInvoice.status.VOIDED")) },
  { key: "REVOKING", label: String(t("salesInvoice.status.REVOKING")) },
  { key: "REVOKED", label: String(t("salesInvoice.status.REVOKED")) },
]);

async function load(append: boolean) {
  if (!merchant.can("biz:invoice:read")) return;
  const current = ++generation;
  const store = merchant.storeNo;
  failed.value = false;
  try {
    const r = await api.mSiList({
      periodCode: periodCode.value.trim() || undefined,
      status: status.value || undefined,
      page: page.value,
      size: SIZE,
    });
    if (current !== generation || store !== merchant.storeNo) return;
    rows.value = append ? [...rows.value, ...r.records] : r.records;
    total.value = r.total;
  } catch {
    if (current === generation) failed.value = true;
  } finally {
    if (current === generation) loaded.value = true;
  }
}

function reload() {
  page.value = 1;
  if (merchant.can("biz:invoice:read")) void loadBacklog();
  return load(false);
}
function more() {
  page.value += 1;
  void load(true);
}
function changeStatus(key: string) {
  status.value = key as "" | SiStatus;
  void reload();
}
function open(invoiceNo: string) {
  uni.navigateTo({ url: `${ROUTES.salesInvoice}?invoiceNo=${encodeURIComponent(invoiceNo)}` });
}
/** 发票打印模拟（样例数据）：生成 PDF 看版面，不出纸 */
const canSimulate = computed(() => merchant.can("biz:print:simulate") && merchant.can("biz:print:export"));
function go(url: string) {
  uni.navigateTo({ url });
}

onShow(reload);
onPullDownRefresh(async () => {
  await reload();
  uni.stopPullDownRefresh();
});
</script>
