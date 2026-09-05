<template>
  <sh-scaffold title-key="invoice.title" :denied="!merchant.can('biz:finance')">
    <view class="sh-card">
      <text class="txt-strong">{{ t("invoice.pendingTitle") }}</text>

      <text v-if="!pending || pending.billCount === 0" class="sh-muted none">
        {{ t("invoice.pendingNone") }}
      </text>

      <view v-else class="sh-mt-sm">
        <text class="txt-caption txt-quiet">{{ t("invoice.amountLabel") }}</text>
        <text class="txt-mega sh-num amt">{{ money(pending.payableMinor) }}</text>
        <text class="sh-hint txt-quiet">{{ t("invoice.amountHint") }}</text>

        <view class="sh-mt-md">
          <sh-kv between :label="String(t('invoice.billCount'))">
            <text class="txt-sub sh-num">{{ t("invoice.billCountValue", { n: pending.billCount }) }}</text>
          </sh-kv>
          <sh-kv between :label="String(t('invoice.periods'))">
            <text class="txt-sub sh-num">{{ pending.periods.join("、") }}</text>
          </sh-kv>
        </view>
        <text v-if="pending.periods.length > 1" class="sh-hint is-warning">
          {{ t("invoice.crossPeriod") }}
        </text>
      </view>
    </view>

    <view class="sh-card">
      <sh-section :title="String(t('invoice.titleBlock'))">
        <sh-go :text="String(t('invoice.copy'))" @tap="copyTitle"></sh-go>
      </sh-section>
      <text class="sh-hint txt-quiet">{{ t("invoice.titleHint") }}</text>
      <view v-if="titleReady" class="sh-mt-sm">
        <sh-kv v-for="f in titleFields" :key="f.k" between :label="String(t(`invoice.f_${f.k}`))">
          <text class="txt-sub">{{ f.v }}</text>
        </sh-kv>
      </view>
      <text v-else class="sh-hint is-warning">{{ t("invoice.titleMissing") }}</text>
    </view>

    <view v-if="pending && pending.billCount > 0" class="sh-card">
      <text class="txt-strong">{{ t("invoice.submitBlock") }}</text>
      <input
        v-model="invoiceNumber"
        maxlength="32"
        class="field__input sh-num sub__f"
        :placeholder="t('invoice.numberPlaceholder')"
      />
      <input
        v-model="titleName"
        maxlength="64"
        class="field__input sub__f"
        :placeholder="t('invoice.titleNamePlaceholder')"
      />
      <view
        class="sh-btn sh-mt-sm"
        :class="{ 'is-disabled': !canSubmit || submitting }"
        @tap="submit"
      >
        {{ submitting ? t("invoice.submitting") : t("invoice.submit") }}
      </view>
      <text v-if="blockReason" class="sh-hint is-warning">{{ blockReason }}</text>
    </view>

    <view class="sh-card">
      <text class="txt-strong">{{ t("invoice.mineBlock") }}</text>
      <sh-empty v-if="!mine.length" bare :text="String(t('invoice.mineEmpty'))"></sh-empty>
      <view v-for="inv in mine" :key="inv.invoiceNo" class="sh-row--divided">
        <view class="sh-row sh-row--between">
          <text class="txt-sub sh-num">{{ inv.invoiceNumber }}</text>
          <text class="txt-sub" :class="statusClass(inv.status)">
            {{ t(`invoice.status${inv.status}`) }}
          </text>
        </view>
        <view class="sh-row sh-row--between sh-mt-xs">
          <text class="txt-caption txt-quiet sh-num">{{ inv.period }}</text>
          <text class="txt-caption txt-quiet sh-num">{{ money(inv.amountMinor) }}</text>
        </view>
        <text v-if="inv.rejectReason" class="sh-hint is-danger">
          {{ inv.rejectReason }}
        </text>
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
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import type { PendingInvoice, PlatformInvoiceTitle, PurchaseInvoice } from "@/api/contract";

const { t } = useI18n();
/*
 * 页面门禁。**不加的话店员/理货员/配送员进得了这一页，而页面里每个请求都是 403** ——
 * 他看到的是一片空白加几个「网络异常」，读不出「这一页不归我管」。
 * 判据由 packages/shared 的 biz-page-perm 闸门盯着：它比对
 * 「这一页调了哪些接口」与「这一页的门禁覆盖了哪些码」。
 */
const merchant = useMerchantStore();
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

/** 状态色走全局语义类，字号由调用点给 —— 这里只回颜色那一档 */
function statusClass(s: string) {
  if (s === "VERIFIED") return "is-success";
  if (s === "REJECTED") return "is-danger";
  return "is-warning";
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

<style scoped>
.amt {
  display: block;
  margin-top: 8rpx;
}
.none {
  display: block;
  margin-top: 16rpx;
}
/* 一列输入框之间的缝。形态归 .field__input */
.sub__f {
  margin-top: 16rpx;
}
</style>
