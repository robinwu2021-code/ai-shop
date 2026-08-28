<script setup lang="ts">
// 库存（B-1）—— 商家打开这一页只为两件事：**哪件断了、哪件压着**。
//
// 所以默认那一栏是「要处理」而不是「全部」。一屏 200 多个 SKU，按字母排等于没排：
// 全部那一栏永远在，但它不该是第一眼看到的东西。
//
// **但「要处理」为空时要落到「全部」**（2026-08-28 补）。上面那半只在店里
// 有问题时成立；店好好的时候 shortage 与 stale 都是 0，于是首屏是一句
// 「空着是好事」加一屏空白 —— 而这家店其实有 17 个 SKU，一件都看不见。
// 用户的原话是「在 b 端 app 上没看到入口」，看到的正是这一幕。
//
// 判据放在**数据回来之后**、且**只认第一次**：之后商家自己选了哪一栏就是哪一栏，
// 不能因为他把缺货处理完了就把他的选择挪走。
//
// 三个数字即入口（`sh-stat` 的 boxed 那一档）：点「缺货」就按缺货筛。
// 数字下面点不动的话，商家会去别处找筛选，而这一页并没有别处。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import type { StockBalance, StockSummary } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const summary = ref<StockSummary | null>(null);
const rows = ref<StockBalance[]>([]);
const loading = ref(false);

/** todo 要处理 · all 全部 · reserved 有预留。**todo 是默认，但空了会落到 all**，理由见文件头 */
const filter = ref<"todo" | "all" | "reserved">("todo");

/** 只在第一次数据回来时纠正默认栏。**之后不再动** —— 见文件头 */
const settled = ref(false);

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

    /*
     * 「要处理」空了就落到「全部」。**要重新取一次数** —— 上面那次取的是
     * filter=todo 的结果（空的），直接改 filter 只会换个高亮，列表还是空的。
     */
    if (!settled.value) {
      settled.value = true;
      if (filter.value === "todo" && todoCount.value === 0) {
        filter.value = "all";
        rows.value = await api.mStockBalances({ filter: "all" });
      }
    }
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

/**
 * 这一块的其余五屏从这里进。
 *
 * **每屏各在工作台/我的上摆一个门是错的** —— 那就回到「同一件事三个入口，
 * 人记不住走哪个」。工作台只开一道门到库存，库存页当枢纽。
 *
 * 每一条按**它自己那一页的权限**判，不是按本页的：报表要 `biz:customer`、
 * 库位要 `biz:store:admin`。按本页判的话，店员会看到一道点进去就是
 * 「这页不该你看」的门 —— 那比没有门更让人困惑。
 */
const entries = computed(() => [
  { key: "purchase", route: ROUTES.purchaseEdit, perm: "biz:stock" },
  { key: "check", route: ROUTES.stockCheck, perm: "biz:stock" },
  { key: "out", route: ROUTES.stockOut, perm: "biz:stock" },
  { key: "docs", route: ROUTES.stockDocs, perm: "biz:stock" },
  { key: "transfer", route: ROUTES.transfer, perm: "biz:stock" },
  { key: "report", route: ROUTES.stockReport, perm: "biz:customer" },
  { key: "locations", route: ROUTES.locations, perm: "biz:store:admin" },
].filter((e) => merchant.can(e.perm)));

function go(route: string) {
  uni.navigateTo({ url: route });
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

    <!--
      这一块的其余五屏从这里进；每条按它自己那一页的权限判。

      **不用横滚。** 原来是 `scroll-view scroll-x`，七条里永远有一条被切在屏幕外：
      初始态最右的「库位与仓」只露半个字，滑到底最左的「记一笔进货」又只剩一个
      「货」—— 最常用的那条反而滚没了。而且它与下面那排筛选 chip 长得一模一样，
      一排是「去另一页」、一排是「改本页」，读不出区别。
      改成定宽网格：一屏全可见，四列一行自动换行，与筛选栏也不再撞脸。
    -->
    <view class="entries">
      <text
        v-for="e in entries"
        :key="e.key"
        class="txt-sub sh-card entries__item"
        @tap="go(e.route)"
      >
        {{ $t(`stock.entry.${e.key}`) }}
      </text>
    </view>

    <sh-tabs :items="TABS" :active="filter" @change="pickFilter"></sh-tabs>

    <sh-empty v-if="!loading && !rows.length" :text="String($t('stock.empty'))"></sh-empty>

    <view v-for="b in rows" :key="b.itemId" class="sh-card sh-mb-sm" @tap="openItem(b)">
      <view class="row__top">
        <view class="sh-fill">
          <text class="txt-strong row__title">{{ b.name }}{{ b.specText ? ` · ${b.specText}` : "" }}</text>
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
/* 七个入口的定宽网格。四列一行 —— 七条正好两行，一屏全可见 */
.entries {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-bottom: 16rpx;
}
/*
 * 用 `sh-card` 而不是 `sh-chip`：**要和下面那排筛选 chip 分得开**。
 * 底色圆角由 sh-card 给，这里只管排布 —— 自己画一套药丸就又多一份要维护的皮。
 */
.entries__item {
  /* 四列：(100% − 三条 12rpx 缝) / 4 */
  width: calc((100% - 36rpx) / 4);
  box-sizing: border-box;
  /* 20rpx 而不是 18：间距要落在 4rpx 网格上（check-page-spec） */
  padding: 20rpx 0;
  text-align: center;
}

.row__top {
  display: flex;
  gap: 20rpx;
  align-items: center;
}

.row__title {
  display: block;
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
