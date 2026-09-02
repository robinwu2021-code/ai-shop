<template>
  <sh-scaffold title-key="deposit.title">
    <view class="p-4 space-y-4">
      <view class="rounded-2xl bg-white p-5 shadow-sm">
        <text class="text-sm text-gray-500">{{ t("deposit.available") }}</text>
        <view class="mt-1 text-3xl font-semibold">{{ money(acc?.availableMinor ?? 0) }}</view>

        <view class="mt-3 space-y-1">
          <view class="flex justify-between text-sm">
            <text class="text-gray-500">{{ t("deposit.paid") }}</text>
            <text>{{ money(acc?.paidMinor ?? 0) }}</text>
          </view>
          <view v-if="(acc?.frozenMinor ?? 0) > 0" class="flex justify-between text-sm">
            <text class="text-gray-500">{{ t("deposit.frozen") }}</text>
            <text class="text-orange-600">-{{ money(acc?.frozenMinor ?? 0) }}</text>
          </view>
          <view class="flex justify-between text-sm">
            <text class="text-gray-500">{{ t("deposit.required") }}</text>
            <text>{{ money(acc?.requiredMinor ?? 0) }}</text>
          </view>
        </view>

        <view v-if="acc" class="mt-3 rounded-lg p-3" :class="acc.sufficient ? 'bg-green-50' : 'bg-orange-50'">
          <text class="text-sm font-medium" :class="acc.sufficient ? 'text-green-700' : 'text-orange-700'">
            {{ acc.sufficient ? t("deposit.enough") : t("deposit.short", { n: money(shortfall) }) }}
          </text>
          <text class="mt-1 block text-xs leading-relaxed text-gray-500">
            {{ acc.sufficient ? t("deposit.enoughHint") : t("deposit.shortHint") }}
          </text>
        </view>
      </view>

      <view class="rounded-2xl bg-white p-5 shadow-sm">
        <text class="text-sm font-medium">{{ t("deposit.limitTitle") }}</text>
        <view class="mt-2 space-y-1">
          <view class="flex justify-between text-sm">
            <text class="text-gray-500">{{ t("deposit.limitSingle") }}</text>
            <text>{{ limitText(acc?.singleOrderLimitMinor) }}</text>
          </view>
          <view class="flex justify-between text-sm">
            <text class="text-gray-500">{{ t("deposit.limitDaily") }}</text>
            <text>{{ limitText(acc?.dailyAmountLimitMinor) }}</text>
          </view>
        </view>
      </view>

      <view class="rounded-2xl bg-white p-5 shadow-sm">
        <text class="text-sm font-medium">{{ t("deposit.txnTitle") }}</text>
        <sh-empty v-if="!txns.length" :text="t('deposit.txnEmpty')"></sh-empty>
        <view
          v-for="(x, i) in txns"
          :key="x.txnNo"
          class="py-3"
          :class="i === 0 ? '' : 'border-t border-gray-100'"
        >
          <view class="flex justify-between">
            <text class="text-sm">{{ typeText(x.txnType) }}</text>
            <text class="text-sm" :class="increases(x) ? 'text-green-600' : 'text-red-500'">
              {{ increases(x) ? "+" : "-" }}{{ money(Math.abs(x.amountMinor)) }}
            </text>
          </view>
          <view class="mt-1 flex justify-between text-xs text-gray-400">
            <text>{{ x.createdAt }}</text>
            <text>{{ t("deposit.balanceAfter", { n: money(x.balanceAfterMinor) }) }}</text>
          </view>
          <text v-if="x.reason" class="mt-1 block text-xs text-gray-500">{{ x.reason }}</text>
          <text v-if="x.operator" class="mt-1 block text-xs text-gray-400">
            {{ t("deposit.by", { n: x.operator }) }}
          </text>
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<script setup lang="ts">
// ⚠️ 这个文件**不能以 HTML 注释开头**，也不能在任何地方写出带冒号变体的类名
// （连注释里都不行）—— UnoCSS 的 applet transformer 不区分代码与注释，
// 扫到就处理，处理不了就让整个模块 500，而 vue-tsc 一声不吭。
// 条件样式一律用 :class 的三元或对象写法。
//
// **写着这条注释还是踩了一次**：流水列表原本用一个「首行去掉上边框」的变体类，
// transformer 直接抛 "Cannot split a chunk that has already been edited"，
// 页面白屏，而 vue-tsc 全绿。现在改成按下标判断。
// 这条只有**真的把页面打开**才看得见 —— 类型检查看不出来。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { money } from "@shared/utils/money";
import type { DepositAccount, DepositTxn } from "@/api/contract";

const { t } = useI18n();
const acc = ref<DepositAccount | null>(null);
const txns = ref<DepositTxn[]>([]);

/** 还差多少。**不允许为负** —— 超缴时显示「已达标」，不显示一个负的差额 */
const shortfall = computed(() =>
  Math.max((acc.value?.requiredMinor ?? 0) - (acc.value?.availableMinor ?? 0), 0));

/**
 * 0 是「未设置，不拦」，不是「上限为 0」。
 *
 * 直接 money(0) 会显示「¥0.00」—— 商家读成「一分钱都不能收」，
 * 而真实含义正好相反。这两者在页面上必须长得不一样。
 */
function limitText(minor: number | undefined) {
  return !minor ? t("deposit.limitNone") : money(minor);
}

/**
 * 这笔让**可用保证金**变多还是变少。
 *
 * ⚠️ **不能看金额的正负。** 运营端对 FREEZE 与 UNFREEZE 都发正值，
 * 后端原样落库 —— 于是「冻结 300 元」会显示成绿色的 +¥300，
 * 看着像进账，而它恰恰是把这笔钱锁住、可用变少。
 * 这一条是**把页面真的打开**才看见的：类型检查与 mock 都不会报。
 *
 * 判据是类型对「可用」的作用方向：
 *   变多：缴纳、解冻     变少：退还、扣划、冻结
 */
function increases(x: DepositTxn) {
  return x.txnType === "PAY" || x.txnType === "UNFREEZE";
}

function typeText(type: string) {
  const key = `deposit.type${type}`;
  const s = t(key);
  // 没登记的类型显示原码，不显示 key —— 原码至少还能搜
  return s === key ? type : s;
}

async function load() {
  // 两个接口分开取：流水挂了不该让上面那几个数字也看不见
  acc.value = await api.mDeposit();
  txns.value = await api.mDepositTxns().catch(() => []);
}

onShow(load);
</script>
