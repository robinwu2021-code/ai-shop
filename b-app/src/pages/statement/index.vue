<template>
  <sh-scaffold title-key="statement.title" :denied="!merchant.can('biz:finance')">
    <view class="sh-card">
      <sh-section :title="String(t('statement.periodLabel'))">
        <sh-go :text="period || String(t('statement.allPeriods'))" @tap="pickPeriod"></sh-go>
      </sh-section>
      <text class="sh-hint txt-quiet">{{ t("statement.voucherHint") }}</text>
    </view>

    <view class="sh-card">
      <text class="sh-muted">{{ t("statement.net") }}</text>
      <text class="txt-mega sh-num amt">{{ money(data?.netMinor ?? 0) }}</text>
      <view class="sh-mt-md">
        <sh-kv between :label="String(t('statement.gross'))">
          <text class="txt-sub sh-num">{{ money(data?.grossMinor ?? 0) }}</text>
        </sh-kv>
        <sh-kv between :label="String(t('statement.commission'))">
          <text class="txt-sub sh-num is-danger">-{{ money(data?.commissionMinor ?? 0) }}</text>
        </sh-kv>
        <sh-kv between :label="String(t('statement.serviceFee'))">
          <text class="txt-sub sh-num is-danger">-{{ money(data?.serviceFeeMinor ?? 0) }}</text>
        </sh-kv>
        <sh-kv between :label="String(t('statement.billCount'))">
          <text class="txt-sub sh-num">{{ t("statement.billCountValue", { n: data?.billCount ?? 0 }) }}</text>
        </sh-kv>
      </view>

      <!-- 导出是次操作（这一页的主体是对账，不是导出）：tint 胶囊，不是实心 -->
      <view
        class="sh-btn sh-btn--soft sh-mt-md"
        :class="{ 'is-disabled': !lines.length }"
        @tap="exportCsv"
      >
        {{ t("statement.export") }}
      </view>
      <text class="sh-hint txt-quiet">{{ t("statement.exportHint") }}</text>
    </view>

    <view class="sh-card">
      <text class="txt-strong">{{ t("statement.linesTitle") }}</text>
      <sh-empty v-if="!lines.length" :pending="!loaded" :failed="failed" @retry="load" bare :text="String(t('statement.empty'))"></sh-empty>
      <view v-for="l in lines" :key="l.settleNo" class="sh-row--divided">
        <view class="sh-row sh-row--between">
          <text class="txt-caption sh-num">{{ l.orderNo }}</text>
          <text class="txt-strong sh-num">{{ money(l.netMinor) }}</text>
        </view>
        <text class="txt-caption txt-quiet sh-num line__break">{{ t("statement.lineBreak", {
          gross: money(l.grossMinor),
          rate: pct(l.commissionRate),
          commission: money(l.commissionMinor),
          fee: money(l.serviceFeeMinor),
        }) }}</text>
        <view class="sh-row sh-row--between sh-mt-xs">
          <text class="txt-caption txt-quiet">{{ t(`statement.st_${l.status}`) }}</text>
          <!-- 没有凭证号要看得出来：那一行对不上银行流水 -->
          <text class="txt-caption sh-num" :class="l.voucherNo ? 'txt-quiet' : 'is-warning'">
            {{ l.voucherNo || t("statement.noVoucher") }}
          </text>
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
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import { pick } from "@ai-shop/ui/prompt";
import { saveCsv } from "@/utils/csv-file";
import type { Statement, StatementLine } from "@/api/contract";

const { t } = useI18n();
/*
 * 页面门禁。**不加的话店员/理货员/配送员进得了这一页，而页面里每个请求都是 403** ——
 * 他看到的是一片空白加几个「网络异常」，读不出「这一页不归我管」。
 * 判据由 packages/shared 的 biz-page-perm 闸门盯着：它比对
 * 「这一页调了哪些接口」与「这一页的门禁覆盖了哪些码」。
 */
const merchant = useMerchantStore();
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
async function pickPeriod() {
  const opts = [t("statement.allPeriods")];
  const now = new Date();
  for (let i = 0; i < MONTHS; i++) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
    opts.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`);
  }
  // 走库里的 sh-pick 而不是 uni.showActionSheet：系统面板在四个端上长相各不相同，
  // 也拿不到皮肤与明暗。顺带多了一样系统面板给不了的东西 —— 当前选中项打勾。
  const i = await pick({
    title: String(t("statement.periodLabel")),
    items: opts,
    selected: period.value ? opts.indexOf(period.value) : 0,
  });
  if (i == null) return;
  period.value = i === 0 ? "" : (opts[i] ?? "");
  load();
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

/** 首屏到过没有。**不是 `loading`** —— 那个含下拉刷新，刷新时把列表换成空态是另一个 bug */
const loaded = ref(false);
/** 这次没取到。**与「确定为空」是两件事** —— 网络不通时不该显示「还没有…」 */
const failed = ref(false);

async function load() {
  try {
    data.value = await api.mStatement(period.value || undefined);
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
.line__break {
  display: block;
  margin-top: 8rpx;
}
</style>
