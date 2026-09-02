<template>
  <sh-scaffold title-key="statement.title">
    <view class="p-4 space-y-4">
      <view class="rounded-2xl bg-white p-5 shadow-sm">
        <view class="flex justify-between">
          <text class="text-sm font-medium">{{ t("statement.periodLabel") }}</text>
          <text class="text-sm text-blue-600" @tap="pickPeriod">
            {{ period || t("statement.allPeriods") }}
          </text>
        </view>
        <text class="mt-1 block text-xs leading-relaxed text-gray-500">
          {{ t("statement.voucherHint") }}
        </text>
      </view>

      <view class="rounded-2xl bg-white p-5 shadow-sm">
        <text class="text-xs text-gray-500">{{ t("statement.net") }}</text>
        <view class="mt-1 text-3xl font-semibold">{{ money(data?.netMinor ?? 0) }}</view>
        <view class="mt-3 space-y-1">
          <view class="flex justify-between text-sm">
            <text class="text-gray-500">{{ t("statement.gross") }}</text>
            <text>{{ money(data?.grossMinor ?? 0) }}</text>
          </view>
          <view class="flex justify-between text-sm">
            <text class="text-gray-500">{{ t("statement.commission") }}</text>
            <text class="text-red-500">-{{ money(data?.commissionMinor ?? 0) }}</text>
          </view>
          <view class="flex justify-between text-sm">
            <text class="text-gray-500">{{ t("statement.serviceFee") }}</text>
            <text class="text-red-500">-{{ money(data?.serviceFeeMinor ?? 0) }}</text>
          </view>
          <view class="flex justify-between text-sm">
            <text class="text-gray-500">{{ t("statement.billCount") }}</text>
            <text>{{ t("statement.billCountValue", { n: data?.billCount ?? 0 }) }}</text>
          </view>
        </view>

        <button
          class="mt-4 w-full rounded-lg border border-gray-200 py-2 text-sm"
          :class="{ 'opacity-40': !lines.length }"
          :disabled="!lines.length"
          @click="exportCsv"
        >
          {{ t("statement.export") }}
        </button>
        <text class="mt-1 block text-xs text-gray-400">{{ t("statement.exportHint") }}</text>
      </view>

      <view class="rounded-2xl bg-white p-5 shadow-sm">
        <text class="text-sm font-medium">{{ t("statement.linesTitle") }}</text>
        <sh-empty v-if="!lines.length" :text="t('statement.empty')"></sh-empty>
        <view
          v-for="(l, i) in lines"
          :key="l.settleNo"
          class="py-3"
          :class="i === 0 ? '' : 'border-t border-gray-100'"
        >
          <view class="flex justify-between">
            <text class="text-sm">{{ l.orderNo }}</text>
            <text class="text-sm font-medium">{{ money(l.netMinor) }}</text>
          </view>
          <view class="mt-1 flex justify-between text-xs text-gray-400">
            <text>{{ t("statement.lineBreak", {
              gross: money(l.grossMinor),
              rate: pct(l.commissionRate),
              commission: money(l.commissionMinor),
              fee: money(l.serviceFeeMinor),
            }) }}</text>
          </view>
          <view class="mt-1 flex justify-between text-xs">
            <text class="text-gray-400">{{ t(`statement.st_${l.status}`) }}</text>
            <text :class="l.voucherNo ? 'text-gray-500' : 'text-orange-600'">
              {{ l.voucherNo || t("statement.noVoucher") }}
            </text>
          </view>
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<script setup lang="ts">
// ⚠️ 不能以 HTML 注释开头，也不能写带冒号变体的类名（连注释里都不行）——
// UnoCSS 的 applet transformer 扫到就处理，处理不了整个模块 500，而 vue-tsc 全绿。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { money } from "@shared/utils/money";
import { saveCsv } from "@/utils/csv-file";
import type { Statement, StatementLine } from "@/api/contract";

const { t } = useI18n();
const data = ref<Statement | null>(null);
const period = ref("");

const lines = computed<StatementLine[]>(() => data.value?.lines ?? []);

/** 万分比 → 百分比文本。5 00 是 5.00% */
function pct(bp: number) {
  return `${(bp / 100).toFixed(2)}%`;
}

/**
 * 最近 12 个月 + 「全部」。
 *
 * 用 picker 而不是让他敲 YYYY-MM：敲错一个字符拿回来的是一张空单，
 * 而空单与「这个月确实没有结算」长得一模一样。
 */
const MONTHS = 12;
function pickPeriod() {
  const opts = [t("statement.allPeriods")];
  const now = new Date();
  for (let i = 0; i < MONTHS; i++) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
    opts.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`);
  }
  uni.showActionSheet({
    itemList: opts,
    success: (r) => {
      period.value = r.tapIndex === 0 ? "" : (opts[r.tapIndex] ?? "");
      load();
    },
  });
}

/**
 * 导出成 CSV。**逐行给费率与凭证号** —— 这份表要能与银行流水勾对，
 * 只给合计的话它就只是一个数字，说明不了「这笔钱是怎么来的」。
 *
 * 平台差异（H5 存盘 / 其余复制到剪贴板）关在 saveCsv 里，页面不分叉。
 */
function exportCsv() {
  const head = [
    t("statement.csvSettleNo"), t("statement.csvOrderNo"), t("statement.gross"),
    t("statement.csvRate"), t("statement.commission"), t("statement.serviceFee"),
    t("statement.net"), t("statement.csvStatus"), t("statement.csvVoucher"),
  ].join(",");
  const rows = lines.value.map((l) => [
    l.settleNo, l.orderNo,
    (l.grossMinor / 100).toFixed(2), pct(l.commissionRate),
    (l.commissionMinor / 100).toFixed(2), (l.serviceFeeMinor / 100).toFixed(2),
    (l.netMinor / 100).toFixed(2),
    t(`statement.st_${l.status}`),
    // 空凭证号留空格子，不写「无」—— 导进 Excel 后「无」会变成一个要人去筛的值
    l.voucherNo ?? "",
  ].join(","));
  saveCsv([head, ...rows].join("\n"), `statement-${period.value || "all"}.csv`, t);
}

async function load() {
  data.value = await api.mStatement(period.value || undefined).catch(() => null);
}

onShow(load);
</script>
