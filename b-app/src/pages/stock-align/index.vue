<script setup lang="ts">
// 期初对齐（TDD-商品纳入进销存开关 §7 / §18.4）。打开线上库存同步之前做一次：
// 逐件对照进销存实存与线上库存，店主选以哪边为准。
//   以线上库存为准 → 有差额的行把实存调成线上库存（后端开盘点调数）
//   已实地盘点     → 实存本来就是盘出来的数，只记下对齐时间
// 两种都随后按规则把线上库存推一次。**不自动打开同步** —— 看过结果再在设置页打开。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { confirm } from "@ai-shop/ui/prompt";
import type { StockAlignMode, StockAlignRow } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const rows = ref<StockAlignRow[]>([]);
const loaded = ref(false);
const failed = ref(false);
const busy = ref(false);

const storeNo = computed(() => merchant.storeNo);
/** 以线上为准时会被调实存的行：有差额、且能比 */
const diffCount = computed(() => rows.value.filter((r) => !r.note && r.diff !== null && r.diff !== 0).length);

async function load() {
  if (!storeNo.value) return;
  try {
    rows.value = await api.mStockAlignment(storeNo.value);
    failed.value = false;
  } catch {
    failed.value = true;
  } finally {
    loaded.value = true;
  }
}

async function run(mode: StockAlignMode) {
  if (!storeNo.value || busy.value) return;
  const ok = await confirm(mode === "MALL"
    ? { title: String(t("stockAlign.confirmMallTitle")), hint: String(t("stockAlign.confirmMallHint", { n: diffCount.value })) }
    : { title: String(t("stockAlign.confirmCountTitle")), hint: String(t("stockAlign.confirmCountHint")) });
  if (!ok) return;
  busy.value = true;
  try {
    await api.mConfirmAlignment(storeNo.value, mode);
    uni.showToast({ title: String(t("stockAlign.done")), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

function diffText(r: StockAlignRow): string {
  if (r.diff === null) return "";
  return r.diff === 0 ? String(t("stockAlign.same")) : String(t("stockAlign.diff", { n: r.diff > 0 ? `+${r.diff}` : r.diff }));
}

onShow(load);
</script>

<template>
  <sh-scaffold
    title-key="stockAlign.title"
    :title-suffix="merchant.multiStore ? merchant.currentStore?.name : ''"
    :denied="!merchant.can('biz:stock')"
    :failed="failed"
    @retry="load"
  >
    <text class="txt-caption sh-muted intro">{{ $t("stockAlign.intro") }}</text>
    <sh-empty v-if="!rows.length" :pending="!loaded" :text="String($t('stockAlign.empty'))"></sh-empty>
    <view v-for="r in rows" :key="r.skuNo" class="sh-card sh-mb-sm">
      <view class="sh-row sh-row--between">
        <text class="txt-strong sh-fill">{{ r.title }}{{ r.spec ? ` · ${r.spec}` : "" }}</text>
        <text class="txt-caption" :class="r.diff === 0 ? 'sh-muted' : 'is-warning'">{{ diffText(r) }}</text>
      </view>
      <text v-if="r.note" class="txt-caption sh-muted">{{ $t(`stockAlign.note.${r.note}`) }}</text>
      <text v-else class="txt-caption sh-muted">
        {{ $t("stockAlign.onHand", { n: r.onHand ?? 0 }) }} · {{ $t("stockAlign.mall", { n: r.mallStock }) }}
      </text>
    </view>

    <sh-actionbar v-if="rows.length && merchant.can('biz:store:admin')">
      <view class="sh-btn sh-btn--soft" @tap="run('COUNT')">{{ $t("stockAlign.byCount") }}</view>
      <view class="sh-btn" @tap="run('MALL')">{{ $t("stockAlign.byMall") }}</view>
    </sh-actionbar>
  </sh-scaffold>
</template>

<style scoped>
.intro {
  display: block;
  padding: 0 8rpx 16rpx;
}
</style>
