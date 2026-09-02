<template>
  <sh-scaffold title-key="withdraw.title" :denied="!merchant.can('biz:finance')">
    <view class="p-4 space-y-4">
      <!-- 可提余额：这一页最重要的数字，单独一块 -->
      <view class="rounded-2xl bg-white p-5 shadow-sm">
        <text class="text-sm text-gray-500">{{ t("withdraw.canWithdraw") }}</text>
        <view class="mt-1 text-3xl font-semibold">{{ money(page?.withdrawableMinor ?? 0) }}</view>
        <!--
          下限写出来，别让他点了才知道太少。
          后端也拦，但那时他已经填过一遍金额了。
        -->
        <text class="mt-2 block text-xs text-gray-400">
          {{ t("withdraw.minHint", { min: money(page?.minAmountMinor ?? 0) }) }}
        </text>
      </view>

      <!-- 申请 -->
      <view class="rounded-2xl bg-white p-5 shadow-sm space-y-3">
        <input
          v-model="amountText"
          type="digit"
          maxlength="12"
          class="w-full rounded-lg border border-gray-200 px-3 py-2 text-lg"
          :placeholder="t('withdraw.amountPlaceholder')"
        />
        <button
          class="w-full rounded-lg bg-blue-600 py-3 text-white"
          :class="{ 'opacity-40': !canSubmit || submitting }"
          :disabled="!canSubmit || submitting"
          @click="submit"
        >
          {{ submitting ? t("withdraw.submitting") : t("withdraw.submit") }}
        </button>
        <!--
          **按钮禁用时要说明原因。**只是灰掉的话，商家不知道是钱不够、
          低于下限、还是有一笔在审 —— 三种情况他该做的事完全不同。
        -->
        <text v-if="blockReason" class="block text-xs text-orange-600">{{ blockReason }}</text>
      </view>

      <!-- 记录 -->
      <view class="rounded-2xl bg-white p-5 shadow-sm">
        <text class="text-sm font-medium">{{ t("withdraw.records") }}</text>
        <view v-if="!page?.records?.length" class="py-6 text-center text-sm text-gray-400">
          {{ t("withdraw.noRecords") }}
        </view>
        <view
          v-for="(r, i) in page?.records ?? []"
          :key="r.withdrawNo"
          class="flex items-center justify-between py-3"
          :class="{ 'border-b border-gray-100': i < (page?.records?.length ?? 0) - 1 }"
        >
          <view>
            <view class="font-medium">{{ money(r.amount) }}</view>
            <text class="text-xs text-gray-400">{{ r.appliedAt }}</text>
          </view>
          <view class="text-right">
            <text :class="statusClass(r.status)">{{ t(`withdraw.status.${r.status}`) }}</text>
            <!-- 驳回理由要显示：不显示的话商家只知道被拒，不知道为什么 -->
            <text v-if="r.remark" class="mt-1 block text-xs text-gray-400">{{ r.remark }}</text>
          </view>
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<script setup lang="ts">
// 商家提现（V288）。
//
// **这个页面此前不存在，而后端的提现单也从没被创建过** ——
// 两端各缺一半，于是运营端的审批页永远是空的。
// 后端 2026-09-02 补了申请入口，这一页是它的另一半：
// 没有它，那条能力没有任何商家能触达。
//
// ⚠️ 说明写成 script 里的 `//` 而不是文件顶部的 HTML 注释：
// 顶部 HTML 注释会让 UnoCSS 的 transformer 报
// 「Cannot split a chunk that has already been edited」，
// 整个模块 500 加载不出来 —— 而 **vue-tsc 是通过的**。
// 类型检查证明不了能构建，这一条撞过。
//
// ⚠️ 同理：**这里不能用带冒号前缀的变体类**（禁用态、末项去边框那一类）。
// @unocss-applet/preset-applet 处理它们时崩在同一个地方，
// 仓库里其他页面一个都没用过 —— 那不是巧合。用 :class 绑定表达同样的意思。
//
// 而且**连注释里都不能写出那些类名**：transformer 不区分代码与注释，
// 照样去处理它扫到的每一个类名字面量。写进注释同样让模块 500 ——
// 这与「守卫也扫注释」是同一个坑，只是这次踩的是构建工具。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import type { WithdrawPage } from "@/api/contract";

const { t } = useI18n();
/*
 * 页面门禁。**不加的话店员/理货员/配送员进得了这一页，而页面里每个请求都是 403** ——
 * 他看到的是一片空白加几个「网络异常」，读不出「这一页不归我管」。
 * 判据由 packages/shared 的 biz-page-perm 闸门盯着：它比对
 * 「这一页调了哪些接口」与「这一页的门禁覆盖了哪些码」。
 */
const merchant = useMerchantStore();
const page = ref<WithdrawPage | null>(null);
const amountText = ref("");
const submitting = ref(false);

/** 元 → 分。**只在这一处转换**，别让分散的乘 100 在各处漂移 */
const amountMinor = computed(() => {
  const n = Number(amountText.value);
  return Number.isFinite(n) && n > 0 ? Math.round(n * 100) : 0;
});

const canSubmit = computed(() =>
  amountMinor.value >= (page.value?.minAmountMinor ?? 0)
  && amountMinor.value <= (page.value?.withdrawableMinor ?? 0)
  && !hasPending.value);

const hasPending = computed(() =>
  (page.value?.records ?? []).some((r) => r.status === "PENDING" || r.status === "APPROVED"));

/** 按钮为什么点不了 —— 三种原因，商家该做的事完全不同 */
const blockReason = computed(() => {
  if (hasPending.value) return t("withdraw.blockPending");
  if (!amountMinor.value) return "";
  if (amountMinor.value < (page.value?.minAmountMinor ?? 0)) return t("withdraw.blockTooSmall");
  if (amountMinor.value > (page.value?.withdrawableMinor ?? 0)) return t("withdraw.blockNotEnough");
  return "";
});

function statusClass(s: string) {
  if (s === "PAID") return "text-green-600 text-sm";
  if (s === "REJECTED" || s === "FAILED") return "text-red-500 text-sm";
  return "text-orange-500 text-sm";
}

async function load() {
  page.value = await api.mWithdrawPage();
}

async function submit() {
  if (!canSubmit.value) return;
  submitting.value = true;
  try {
    await api.mApplyWithdraw(amountMinor.value);
    amountText.value = "";
    uni.showToast({ title: t("withdraw.submitted"), icon: "none" });
    await load();
  } finally {
    // finally 里收：失败时按钮不解锁的话，页面看起来卡死了
    submitting.value = false;
  }
}

onShow(load);
</script>
