<template>
  <sh-scaffold title-key="deposit.title" :denied="!merchant.can('biz:finance')">
    <view class="sh-card">
      <text class="sh-muted">{{ t("deposit.available") }}</text>
      <text class="txt-mega sh-num amt">{{ money(acc?.availableMinor ?? 0) }}</text>

      <view class="sh-mt-md">
        <sh-kv between :label="String(t('deposit.paid'))">
          <text class="txt-sub sh-num">{{ money(acc?.paidMinor ?? 0) }}</text>
        </sh-kv>
        <sh-kv v-if="(acc?.frozenMinor ?? 0) > 0" between :label="String(t('deposit.frozen'))">
          <text class="txt-sub sh-num is-warning">-{{ money(acc?.frozenMinor ?? 0) }}</text>
        </sh-kv>
        <sh-kv between :label="String(t('deposit.required'))">
          <text class="txt-sub sh-num">{{ money(acc?.requiredMinor ?? 0) }}</text>
        </sh-kv>
      </view>

      <!-- 够不够那一块：语义色的 tint 底 + 同色字，与 .sh-chip 的做法同源 -->
      <view v-if="acc" class="verdict" :class="acc.sufficient ? 'is-ok' : 'is-short'">
        <text class="txt-strong" :class="acc.sufficient ? 'is-success' : 'is-warning'">
          {{ acc.sufficient ? t("deposit.enough") : t("deposit.short", { n: money(shortfall) }) }}
        </text>
        <text class="sh-hint txt-quiet">
          {{ acc.sufficient ? t("deposit.enoughHint") : t("deposit.shortHint") }}
        </text>
      </view>
    </view>

    <view class="sh-card">
      <text class="txt-strong">{{ t("deposit.limitTitle") }}</text>
      <view class="sh-mt-sm">
        <sh-kv between :label="String(t('deposit.limitSingle'))">
          <text class="txt-sub sh-num">{{ limitText(acc?.singleOrderLimitMinor) }}</text>
        </sh-kv>
        <sh-kv between :label="String(t('deposit.limitDaily'))">
          <text class="txt-sub sh-num">{{ limitText(acc?.dailyAmountLimitMinor) }}</text>
        </sh-kv>
      </view>
    </view>

    <view class="sh-card">
      <text class="txt-strong">{{ t("deposit.txnTitle") }}</text>
      <sh-empty v-if="!txns.length" :pending="!loaded" :failed="failed" @retry="load" bare :text="String(t('deposit.txnEmpty'))"></sh-empty>
      <!-- 行距与分隔线归 .sh-row--divided（线画在相邻的后一行上，不用判首行） -->
      <view v-for="x in txns" :key="x.txnNo" class="sh-row--divided">
        <view class="sh-row sh-row--between">
          <text class="txt-sub">{{ typeText(x.txnType) }}</text>
          <text class="txt-sub sh-num" :class="increases(x) ? 'is-success' : 'is-danger'">
            {{ increases(x) ? "+" : "-" }}{{ money(Math.abs(x.amountMinor)) }}
          </text>
        </view>
        <view class="sh-row sh-row--between sh-mt-xs">
          <text class="txt-caption txt-quiet sh-num">{{ x.createdAt }}</text>
          <text class="txt-caption txt-quiet sh-num">
            {{ t("deposit.balanceAfter", { n: money(x.balanceAfterMinor) }) }}
          </text>
        </view>
        <text v-if="x.reason" class="sh-hint">{{ x.reason }}</text>
        <text v-if="x.operator" class="sh-hint txt-quiet">
          {{ t("deposit.by", { n: x.operator }) }}
        </text>
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
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import type { DepositAccount, DepositTxn } from "@/api/contract";

const { t } = useI18n();
/*
 * 页面门禁。**不加的话店员/理货员/配送员进得了这一页，而页面里每个请求都是 403** ——
 * 他看到的是一片空白加几个「网络异常」，读不出「这一页不归我管」。
 * 判据由 packages/shared 的 biz-page-perm 闸门盯着：它比对
 * 「这一页调了哪些接口」与「这一页的门禁覆盖了哪些码」。
 */
const merchant = useMerchantStore();
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

/** 首屏到过没有。**不是 `loading`** —— 那个含下拉刷新，刷新时把列表换成空态是另一个 bug */
const loaded = ref(false);
/** 流水这次没取到。**与「确定没有流水」是两件事** */
const failed = ref(false);

async function load() {
  // 两个接口分开取：流水挂了不该让上面那几个数字也看不见
  acc.value = await api.mDeposit();
  // 反过来也要成立：流水挂了要让**流水那一格自己说出来**。
  // 此前这里是 `.catch(() => [])`，把「没取到」折叠成了「还没有流水」——
  // 两种情况在界面上一模一样，而该给的东西正相反（重试 vs 去充值）。
  try {
    txns.value = await api.mDepositTxns();
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

onShow(load);
</script>

<style scoped>
.amt {
  display: block;
  margin-top: 8rpx;
}
/* 够不够那一块。tint 底取语义色的 14%，与 .sh-chip 的 tint 同源；
   圆角 md（24rpx），在五档上 */
.verdict {
  margin-top: 28rpx;
  padding: 20rpx 24rpx;
  border-radius: 24rpx;
}
.verdict.is-ok {
  background: var(--sh-success-tint);
}
.verdict.is-short {
  background: var(--sh-warning-tint);
}
</style>
