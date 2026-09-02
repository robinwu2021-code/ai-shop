<template>
  <sh-scaffold title-key="invoice.title">
    <view class="p-4 space-y-4">
      <view class="rounded-2xl bg-white p-5 shadow-sm">
        <text class="text-sm font-medium">{{ t("invoice.pendingTitle") }}</text>

        <view v-if="!pending || pending.billCount === 0" class="mt-2">
          <text class="text-sm text-gray-500">{{ t("invoice.pendingNone") }}</text>
        </view>

        <view v-else class="mt-2">
          <text class="text-xs text-gray-500">{{ t("invoice.amountLabel") }}</text>
          <view class="mt-1 text-3xl font-semibold">{{ money(pending.payableMinor) }}</view>
          <text class="mt-1 block text-xs leading-relaxed text-gray-500">
            {{ t("invoice.amountHint") }}
          </text>

          <view class="mt-3 space-y-1">
            <view class="flex justify-between text-sm">
              <text class="text-gray-500">{{ t("invoice.billCount") }}</text>
              <text>{{ t("invoice.billCountValue", { n: pending.billCount }) }}</text>
            </view>
            <view class="flex justify-between text-sm">
              <text class="text-gray-500">{{ t("invoice.periods") }}</text>
              <text>{{ pending.periods.join("、") }}</text>
            </view>
          </view>
          <text v-if="pending.periods.length > 1" class="mt-2 block text-xs text-orange-600">
            {{ t("invoice.crossPeriod") }}
          </text>
        </view>
      </view>

      <view class="rounded-2xl bg-white p-5 shadow-sm">
        <view class="flex justify-between">
          <text class="text-sm font-medium">{{ t("invoice.titleBlock") }}</text>
          <text class="text-sm text-blue-600" @tap="copyTitle">{{ t("invoice.copy") }}</text>
        </view>
        <text class="mt-1 block text-xs leading-relaxed text-gray-500">
          {{ t("invoice.titleHint") }}
        </text>
        <view v-if="titleReady" class="mt-2 space-y-1">
          <view v-for="f in titleFields" :key="f.k" class="flex justify-between text-sm">
            <text class="text-gray-500">{{ t(`invoice.f_${f.k}`) }}</text>
            <text class="ml-3 flex-1 text-right">{{ f.v }}</text>
          </view>
        </view>
        <text v-else class="mt-2 block text-sm text-orange-600">{{ t("invoice.titleMissing") }}</text>
      </view>

      <view v-if="pending && pending.billCount > 0" class="rounded-2xl bg-white p-5 shadow-sm space-y-3">
        <text class="text-sm font-medium">{{ t("invoice.submitBlock") }}</text>
        <input
          v-model="invoiceNumber"
          class="w-full rounded-lg border border-gray-200 px-3 py-2"
          :placeholder="t('invoice.numberPlaceholder')"
        />
        <input
          v-model="titleName"
          class="w-full rounded-lg border border-gray-200 px-3 py-2"
          :placeholder="t('invoice.titleNamePlaceholder')"
        />
        <button
          class="w-full rounded-lg bg-blue-600 py-3 text-white"
          :class="{ 'opacity-40': !canSubmit || submitting }"
          :disabled="!canSubmit || submitting"
          @click="submit"
        >
          {{ submitting ? t("invoice.submitting") : t("invoice.submit") }}
        </button>
        <text v-if="blockReason" class="block text-xs text-orange-600">{{ blockReason }}</text>
      </view>

      <view class="rounded-2xl bg-white p-5 shadow-sm">
        <text class="text-sm font-medium">{{ t("invoice.mineBlock") }}</text>
        <sh-empty v-if="!mine.length" :text="t('invoice.mineEmpty')"></sh-empty>
        <view
          v-for="(inv, i) in mine"
          :key="inv.invoiceNo"
          class="py-3"
          :class="i === 0 ? '' : 'border-t border-gray-100'"
        >
          <view class="flex justify-between">
            <text class="text-sm">{{ inv.invoiceNumber }}</text>
            <text class="text-sm" :class="statusClass(inv.status)">
              {{ t(`invoice.status${inv.status}`) }}
            </text>
          </view>
          <view class="mt-1 flex justify-between text-xs text-gray-400">
            <text>{{ inv.period }}</text>
            <text>{{ money(inv.amountMinor) }}</text>
          </view>
          <text v-if="inv.rejectReason" class="mt-1 block text-xs text-red-500">
            {{ inv.rejectReason }}
          </text>
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<script setup lang="ts">
// ⚠️ 不能以 HTML 注释开头，也不能在任何地方写出带冒号变体的类名（连注释里都不行）——
// UnoCSS 的 applet transformer 扫到就处理，处理不了让整个模块 500，而 vue-tsc 全绿。
// 上一页刚踩过一次：条件样式一律用 :class 的三元或对象写法。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { money } from "@shared/utils/money";
import type { PendingInvoice, PlatformInvoiceTitle, PurchaseInvoice } from "@/api/contract";

const { t } = useI18n();
const pending = ref<PendingInvoice | null>(null);
const title = ref<PlatformInvoiceTitle>({});
const mine = ref<PurchaseInvoice[]>([]);
const invoiceNumber = ref("");
const titleName = ref("");
const submitting = ref(false);

const FIELDS = ["companyName", "taxNo", "address", "phone", "bankAccount"] as const;

/** 只显示配了的字段 —— 空字段占一行「—」会让人以为平台漏填了 */
const titleFields = computed(() =>
  FIELDS.map((k) => ({ k, v: title.value[k] ?? "" })).filter((f) => f.v !== ""));

/**
 * 公司全称与税号缺一个，供应商就开不出票。
 * 这时要说「平台还没配」，而不是显示一张残缺的抬头让他照着开。
 */
const titleReady = computed(() => !!title.value.companyName && !!title.value.taxNo);

const canSubmit = computed(() =>
  !!invoiceNumber.value.trim() && !!titleName.value.trim() && titleReady.value);

/** 按钮为什么点不了 —— 三种原因他该做的事完全不同 */
const blockReason = computed(() => {
  if (!titleReady.value) return t("invoice.blockNoTitle");
  if (!invoiceNumber.value.trim()) return t("invoice.blockNoNumber");
  if (!titleName.value.trim()) return t("invoice.blockNoTitleName");
  return "";
});

function statusClass(s: string) {
  if (s === "VERIFIED") return "text-green-600";
  if (s === "REJECTED") return "text-red-500";
  return "text-orange-500";
}

function copyTitle() {
  const text = titleFields.value.map((f) => `${t(`invoice.f_${f.k}`)}：${f.v}`).join("\n");
  uni.setClipboardData({ data: text });
}

async function load() {
  // 三个接口分开取：任何一个挂了，其余两块仍要看得见
  pending.value = await api.mPendingInvoice().catch(() => null);
  title.value = await api.mInvoiceTitle().catch(() => ({}));
  mine.value = await api.mMyInvoices().catch(() => []);
}

async function submit() {
  if (!canSubmit.value || !pending.value) return;
  submitting.value = true;
  try {
    await api.mSubmitInvoice({
      // 周期取覆盖到的最后一个月：它只是票据上的标签，不参与选单
      period: pending.value.periods.at(-1) ?? "",
      invoiceNumber: invoiceNumber.value.trim(),
      invoiceType: "GENERAL",
      titleName: titleName.value.trim(),
      // **金额不让他填**：必须等于应付合计，让他填只会填错然后被拒
      amountMinor: pending.value.payableMinor,
    });
    invoiceNumber.value = "";
    uni.showToast({ title: t("invoice.submitted"), icon: "none" });
    await load();
  } finally {
    submitting.value = false;
  }
}

onShow(load);
</script>
