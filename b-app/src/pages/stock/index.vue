<script setup lang="ts">
// 库存（B-1）—— 商家打开这一页只为两件事：**哪件断了、哪件压着**。
//
// 所以默认那一栏是「要处理」而不是「全部」。一屏 200 多个 SKU，按字母排等于没排：
// 全部那一栏永远在，但它不该是第一眼看到的东西。
//
// 三个数字即入口（`sh-stat` 的 boxed 那一档）：点「缺货」就按缺货筛。
// 数字下面点不动的话，商家会去别处找筛选，而这一页并没有别处。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { StockBalance, StockSummary } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const summary = ref<StockSummary | null>(null);
const rows = ref<StockBalance[]>([]);
const loading = ref(false);

/** todo 要处理 · all 全部 · reserved 有预留。**todo 是默认**，理由见文件头 */
const filter = ref<"todo" | "all" | "reserved">("todo");

const TABS = computed(() => [
  { key: "todo", label: `${t("stock.tabTodo")} ${todoCount.value}` },
  { key: "all", label: String(t("stock.tabAll")) },
  { key: "reserved", label: String(t("stock.tabReserved")) },
]);

/** 「要处理」那一栏的角标 = 缺货 + 滞销。两个数字加起来才是他今天要看的量 */
const todoCount = computed(() =>
  summary.value ? summary.value.shortageCount + summary.value.staleCount : 0,
);

async function load() {
  loading.value = true;
  try {
    // 两段各自兜底：总览取不到不该让列表也空着，反之亦然
    const [s, list] = await Promise.all([
      api.mStockSummary().catch(() => null),
      api.mStockBalances({ filter: filter.value }),
    ]);
    if (s) summary.value = s;
    rows.value = list;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}

function pickFilter(key: string) {
  filter.value = key as typeof filter.value;
  void load();
}

/** 点数字格筛选：再点一下回到「要处理」 —— 点了没有退路的筛选很容易困住人 */
function pickStat(key: string) {
  if (key === "shortage") filter.value = filter.value === "todo" ? "all" : "todo";
  else filter.value = "todo";
  void load();
}

/** 滞销多少天。`lastMovedAt` 是后端给的最后动销时间，不在前端再算一遍 90 天的判据 */
function idleDays(b: StockBalance): number | null {
  if (!b.flags.includes("STALE") || !b.lastMovedAt) return null;
  const ms = Date.now() - new Date(b.lastMovedAt).getTime();
  return Math.max(0, Math.floor(ms / 86400000));
}

function openItem(b: StockBalance) {
  uni.navigateTo({ url: `/pages/stock-detail/index?itemId=${encodeURIComponent(b.itemId)}` });
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="stock.title" :denied="!merchant.can('biz:stock')">
    <sh-stat
      boxed
      :items="[
        { key: 'sku', value: summary?.itemCount ?? '—', label: String($t('stock.statSku')) },
        { key: 'shortage', value: summary?.shortageCount ?? '—', label: String($t('stock.statShortage')), tone: 'bad' },
        { key: 'stale', value: summary?.staleCount ?? '—', label: String($t('stock.statStale')) },
      ]"
      @pick="pickStat"
    ></sh-stat>

    <sh-tabs :items="TABS" :active="filter" @change="pickFilter"></sh-tabs>

    <sh-empty v-if="!loading && !rows.length" :text="String($t('stock.empty'))"></sh-empty>

    <view v-for="b in rows" :key="b.itemId" class="sh-card row" @tap="openItem(b)">
      <view class="row__top">
        <view class="row__main">
          <text class="row__title">{{ b.name }}{{ b.specText ? ` · ${b.specText}` : "" }}</text>
          <view class="row__meta">
            <!--
              可用为 0 且缺货：说成「已售罄」而不是「可用 0」——
              商家看到 0 的第一反应是「是不是没录」，看到已售罄才会去补货
            -->
            <text v-if="b.available === 0" class="sh-chip sh-chip--danger">
              {{ $t("stock.soldOut") }}
            </text>
            <text v-else-if="idleDays(b) !== null" class="sh-chip">
              {{ $t("stock.idleDays", { n: idleDays(b) }) }}
            </text>
            <template v-else>
              <text class="sh-muted sh-num">{{ $t("stock.onHandN", { n: b.onHand }) }}</text>
              <text class="sh-muted sh-num">{{ $t("stock.reservedN", { n: b.reserved }) }}</text>
            </template>
          </view>
        </view>
        <view class="row__end">
          <text class="txt-price sh-num" :class="{ 'row__bad': b.available <= 0 }">
            {{ b.available }}
          </text>
          <text class="txt-caption">{{ $t("stock.available") }}</text>
        </view>
      </view>
    </view>

    <!--
      这句不是脚注。**「可用」是这一页唯一会被误读的数** ——
      商家看到实存 5 却只能卖 3 时，第一反应是系统算错了。
    -->
    <view v-if="rows.length" class="sh-card">
      <text class="txt-caption">{{ $t("stock.formulaHint") }}</text>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.row {
  margin-bottom: 14rpx;
}
.row__top {
  display: flex;
  gap: 20rpx;
  align-items: center;
}
.row__main {
  flex: 1;
  min-width: 0;
}
.row__title {
  display: block;
  font-size: 30rpx;
  font-weight: 600;
  color: var(--sh-ink);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.row__meta {
  display: flex;
  gap: 20rpx;
  align-items: baseline;
  margin-top: 8rpx;
}
.row__end {
  text-align: right;
  flex: none;
}
/* uni 的 <text> 默认是 inline —— 不转成 block，数字与「可用」会挤成「3可用」 */
.row__end > text {
  display: block;
}
.row__bad {
  color: var(--sh-danger);
}
</style>
