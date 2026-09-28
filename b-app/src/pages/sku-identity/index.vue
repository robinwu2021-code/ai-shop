<script setup lang="ts">
/*
 * 商品编码批量导入导出（P4）。
 *
 * <p>条码 / 货号 / 单位是接 ERP、收银秤、供应商的唯一凭据，
 * 而它们此前只能在建品页一件一件填 —— 五件货可以，两百件不行。
 *
 * <p><b>这一页的形状由一件事决定：批量写入的危险不是报错，是它报成功。</b>
 * 所以「导入」不是一个按钮，是三步：先看这份表要改什么 → 确认 → 才写。
 * 与「给会员发消息」同一条规矩（那边叫试算，这边叫核对）。
 */
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { canPickFile, pickCsvFile, saveCsv } from "@/utils/csv-file";
import type { SkuIdentityReport } from "@shared/types";
import { confirm } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

const csv = ref("");
const report = ref<SkuIdentityReport | null>(null);
const busy = ref(false);
/** 试算过之后才允许真写；改了内容就得重算 —— 否则「确认」确认的是上一份表 */
const checked = ref(false);

async function doExport() {
  busy.value = true;
  try {
    const res = await api.mSkuIdentityExport();
    const stamp = new Date().toISOString().slice(0, 10);
    saveCsv(res.csv, `商品编码-${stamp}.csv`, t);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

async function choose() {
  const text = await pickCsvFile();
  if (text === null) return;
  csv.value = text;
  onEdited();
  void check();
}

/** 内容一变，上一次的核对结果就作废 */
function onEdited() {
  checked.value = false;
  report.value = null;
}

async function check() {
  if (!csv.value.trim()) return;
  busy.value = true;
  try {
    report.value = await api.mSkuIdentityPlan(csv.value);
    checked.value = true;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

async function applyImport() {
  const r = report.value;
  if (!r || !r.willSet) return;
  const ok = await confirm({ title: String(t("skuIdentity.confirmTitle")), hint: String(t("skuIdentity.confirmBody", { n: r.willSet })) });
  if (!ok) return;
  busy.value = true;
  try {
    const done = await api.mSkuIdentityImport(csv.value);
    report.value = done;
    checked.value = false;
    csv.value = "";
    uni.showToast({ title: t("skuIdentity.done", { n: done.willSet }), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

const hasProblems = computed(() => (report.value?.problems.length ?? 0) > 0);

/** 一格的前后对照。没变的列显示为「—」，别让他在一堆没动的值里找那一个改动 */
function arrow(from?: string | null, to?: string | null): string {
  const f = from || "";
  const g = to || "";
  if (f === g) return f || "—";
  const blank = String(t("skuIdentity.blank"));
  return `${f || blank} → ${g || blank}`;
}
</script>

<template>
  <sh-scaffold title-key="skuIdentity.title" :denied="!merchant.can('biz:goods')">
    <!-- 第一步：把现状拿下来。**先导出再改**是唯一不会认错行的路 -->
    <view class="sh-card">
      <view class="sh-card__head">
        <text class="txt-title">{{ $t("skuIdentity.step1") }}</text>
        <text class="sh-chip sh-chip--primary" :class="{ 'is-busy': busy }" @tap="doExport">{{ $t("skuIdentity.export") }}</text>
      </view>
      <text class="sh-hint">{{ $t("skuIdentity.exportHint") }}</text>
    </view>

    <!-- 第二步：把改好的表交回来 -->
    <view class="sh-card">
      <view class="sh-card__head">
        <text class="txt-title">{{ $t("skuIdentity.step2") }}</text>
        <text v-if="canPickFile" class="sh-chip sh-chip--primary" @tap="choose">{{ $t("skuIdentity.choose") }}</text>
      </view>
      <!--
        **粘贴这条路两端都留着。**小程序没有 file input，而商家真会
        在电脑上打开这一页（/b/ 就是网页）。少一条路等于少一半的人能用。
      -->
      <textarea
        v-model="csv"
        class="txt-caption field__area paste"
        :placeholder="$t('skuIdentity.pastePh')"
        @input="onEdited"
      />
    </view>

    <!-- 第三步：核对。**这一屏才是这个功能的主体** -->
    <view v-if="report" class="sh-card">
      <view class="sh-card__head">
        <text class="txt-title">{{ $t("skuIdentity.step3") }}</text>
      </view>
      <!--
        四个数各回答一件事。少了「没变化」那一格，商家会把「改 3 行」
        读成「另外 197 行失败了」—— 而那三个数字里最让人安心的恰恰是它。
      -->
      <sh-stat
        :items="[
          { value: report.total, label: String($t('skuIdentity.total')) },
          { value: report.willSet, label: String($t('skuIdentity.willSet')), tone: 'primary' },
          { value: report.noChange, label: String($t('skuIdentity.noChange')) },
          { value: report.problems.length, label: String($t('skuIdentity.problem')),
            tone: hasProblems ? 'bad' : undefined },
        ]"
      ></sh-stat>

      <!--
        问题逐行列，**带行号**。「有 3 行有问题」他无从下手，
        「第 14 行：货号 HX-9 在本店找不到」他一眼就知道去 Excel 里改哪儿。
      -->
      <view v-if="hasProblems" class="sh-notice sh-notice--danger probs">
        <sh-kv
          v-for="p in report.problems"
          :key="p.line"
          class="txt-caption prob"
          :key-width="120"
          :label="String($t('skuIdentity.line', { n: p.line }))"
        >{{ p.reason }}</sh-kv>
      </view>

      <!-- 前后对照：让他确认「改的是不是我想的那些」 -->
      <view v-if="report.samples.length" class="prev">
        <text class="txt-caption sh-muted prev__t">{{ $t("skuIdentity.previewTitle") }}</text>
        <view v-for="s in report.samples" :key="s.skuNo" class="row">
          <text class="txt-body row__t">{{ s.goods }}<text v-if="s.spec" class="sh-muted"> · {{ s.spec }}</text></text>
          <view class="row__cells sh-wrap">
            <text class="txt-caption">{{ $t("skuIdentity.barcode") }} {{ arrow(s.barcodeFrom, s.barcodeTo) }}</text>
            <text class="txt-caption">{{ $t("skuIdentity.code") }} {{ arrow(s.codeFrom, s.codeTo) }}</text>
            <text class="txt-caption">{{ $t("skuIdentity.unit") }} {{ arrow(s.unitFrom, s.unitTo) }}</text>
          </view>
        </view>
      </view>

    </view>

    <!--
      规则：这三条决定了他的表会被怎么读（第二条与「清空输入框就是清空」的直觉相反）。
      此前放在页首，一进来先读一整张规则表才看到要做的两步 —— 现在两步在前，
      规则放在下面当对照：动手前扫一眼左列就够，右边是给存疑的人看的。
    -->
    <view class="sh-card">
      <view class="sh-card__head">
        <text class="txt-title">{{ $t("skuIdentity.howTitle") }}</text>
      </view>
      <view>
        <sh-kv :label="String($t('skuIdentity.ruleMissingK'))" divided :key-width="160">
          <text class="txt-body rule__v">{{ $t("skuIdentity.ruleMissingV") }}</text>
        </sh-kv>
        <sh-kv :label="String($t('skuIdentity.ruleBlankK'))" divided :key-width="160">
          <text class="txt-body rule__v">{{ $t("skuIdentity.ruleBlankV") }}</text>
        </sh-kv>
        <sh-kv :label="String($t('skuIdentity.ruleDashK'))" divided :key-width="160">
          <text class="txt-body rule__v">{{ $t("skuIdentity.ruleDashV") }}</text>
        </sh-kv>
      </view>
    </view>

    <!--
      一页只有一个主动作，贴底。**此前是两个整宽红按钮**，分别埋在第二张和第三张卡里：
      第一个要滚到粘贴框下面，第二个只在核对之后才出现 —— 同一条流水线上的两步，
      却在两个位置各长一个一样重的按钮。这里按当前这一步换文字。
    -->
    <sh-actionbar dock>
      <view v-if="report" class="sh-btn" :class="{ 'sh-btn--muted': busy || !checked || !report.willSet }" @tap="applyImport">
        {{ $t("skuIdentity.apply", { n: report.willSet }) }}
      </view>
      <view v-else class="sh-btn" :class="{ 'sh-btn--muted': busy || !csv.trim() }" @tap="check">
        {{ $t("skuIdentity.check") }}
      </view>
    </sh-actionbar>
  </sh-scaffold>
</template>

<style scoped>
.is-busy {
  opacity: 0.5;
}

.rule:first-child {
  border-top: none;
}

.paste {
  min-height: 200rpx;
}

.probs {
  margin-top: 20rpx;
}

/* 只留本页版面与语义色：排法归 sh-kv。行号是红的 —— 这一块整个是「出错的行」 */
/* 行距归 sh-kv：这个 class 在小程序上宿主与根各吃一遍，覆盖内边距会翻倍 */
.prob .kv__k {
  color: var(--sh-danger);
}

.prev {
  margin-top: 20rpx;
}

.prev__t {
  display: block;
}

.row {
  padding: 16rpx 0;
  border-top: var(--sh-hairline-soft);
}

.row__t {
  display: block;
}

.row__cells {
  gap: 8rpx 20rpx;
  margin-top: 8rpx;
}

</style>
