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
import { urgentStockItems } from "@/shared/stock-urgent";
import { ROUTES } from "@/shared/nav";
import type { StockBalance, StockSummary } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const summary = ref<StockSummary | null>(null);
const rows = ref<StockBalance[]>([]);
const loading = ref(false);

/** todo 要处理 · all 全部 · reserved 有预留。**todo 是默认，但空了会落到 all**，理由见文件头 */
const filter = ref<"todo" | "all" | "reserved" | "shortage" | "stale">("todo");

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
/**
 * 点数字 = 精确筛到它自己。
 *
 * **此前它在说谎**：点「在售 SKU 204」给的是 18 条（要处理），
 * 点「滞销」给出的列表里混着缺货 —— 数字说一个数，点下去给另一个，且不报错。
 *
 * 再点一次回到「全部」：点了没有退路的筛选很容易把人困住。
 */
function pickStat(key: string) {
  // 在途不是本页的筛选 —— 那批货既不在 A 也不在 B，列表里没有它。
  // 点它该去单据页看那几张单，收货也在那儿
  if (key === "transit") {
    uni.navigateTo({ url: `${ROUTES.stockDocs}?kind=TRANSFER` });
    return;
  }
  const want = key === "sku" ? "all" : (key as "shortage" | "stale");
  filter.value = filter.value === want ? "all" : want;
  void load();
}

/** 这一屏里有没有真的预留。没有的话「可用 = 实存 − 预留」那句解释不该占位置 */
const hasReserved = computed(() => rows.value.some((b) => b.reserved > 0));

/** 四个数里哪一个正被筛着。`sku` 对应「全部」—— 它就是「不筛」 */
const activeStat = computed(() =>
  filter.value === "all" ? "sku"
    : filter.value === "shortage" || filter.value === "stale" ? filter.value
    : "",
);

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
/**
 * **写动作**：贴底悬浮条。它们是这一页的主动作 —— 与进货／盘点／报损／调拨
 * 四页自己用 `sh-actionbar` 放主动作是同一个形态。
 *
 * 放到底下不是为了好看：看每天几十次、记一天一到三次，
 * 「看」该占满整屏，「记」该落在拇指够得着的地方。
 * 四条都点走整页（`navigateTo`），不开弹层 —— 所以条不会被自己的弹层压住。
 */
const actions = computed(() => [
  { key: "purchase", route: ROUTES.purchaseEdit, perm: "biz:stock" },
  { key: "check", route: ROUTES.stockCheck, perm: "biz:stock" },
  { key: "out", route: ROUTES.stockOut, perm: "biz:stock" },
  { key: "transfer", route: ROUTES.transfer, perm: "biz:stock" },
].filter((e) => merchant.can(e.perm)));

/**
 * **查与配**：收进总览卡里，细线之下。它们一周／一个月用一次，
 * 与四个写动作等大等色地并排是把频率差两个数量级的东西摆成了同一档。
 *
 * 每一条按**它自己那一页的权限**判，不是按本页的：报表要 `biz:customer`、
 * 库位要 `biz:store:admin`。按本页判的话，店员会看到一道点进去就是
 * 「这页不该你看」的门 —— 那比没有门更让人困惑。
 */
/**
 * 「有人在等」的那几项。**与工作台那张卡共用一份** —— 它们是同一块东西的
 * 全文与前缀，各算一份的下场今天演过：两边各缺对方一半，且都不报错。
 *
 * 这一页**不补空位**：工作台会用「进货」把三格填满，那是「看一眼顺手做一件」；
 * 这一页的写动作在贴底条里，再补一遍就是同一个入口出现两次。
 */
const urgent = computed(() =>
  urgentStockItems(summary.value).map((u) => ({
    key: u.key,
    label: String(t(u.labelKey, u.params ?? {})),
    route: u.route,
  })),
);

const links = computed(() => [
  { key: "docs", route: ROUTES.stockDocs, perm: "biz:stock" },
  { key: "report", route: ROUTES.stockReport, perm: "biz:customer" },
  { key: "locations", route: ROUTES.locations, perm: "biz:store:admin" },
  // 供应商与进货同一个码：能记进货的人就该能建供应商
  { key: "suppliers", route: ROUTES.suppliers, perm: "biz:stock" },
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
    <!--
      总览与去处**同一张卡**。原来是四个数四张卡、七个入口七张卡，
      一屏读下来是一个十一格的网格 —— 分不出哪半边是数、哪半边是动作。
      数是「一个面板的四个读数」，用 `panel` 一张卡分栏；去处收在细线之下，
      与工作台那张「进销存」卡同构（细线 + 几个快捷）。
    -->
    <view class="sh-card ov">
      <sh-stat
        panel
        :active="activeStat"
        :items="[
          { key: 'sku', value: summary?.itemCount ?? '—', label: String($t('stock.statSku')) },
          { key: 'shortage', value: summary?.shortageCount ?? '—', label: String($t('stock.statShortage')), tone: 'bad' },
          { key: 'stale', value: summary?.staleCount ?? '—', label: String($t('stock.statStale')) },
          { key: 'transit', value: summary?.inTransitCount ?? '—', label: String($t('stock.statTransit')), tone: 'warn' },
        ]"
        @pick="pickStat"
      ></sh-stat>
      <!--
        紧急项与工作台那张卡**同一个位置**：数下面、细线之下。
        位置固定，人才会形成「有事就在那儿」的预期。
        **只在真有事时出现** —— 没有在途、没有开着的盘点单时一行都不占。
      -->
      <view v-if="urgent.length" class="ov__go">
        <text
          v-for="u in urgent"
          :key="u.key"
          class="txt-primary txt-bold ov__link"
          @tap="go(u.route)"
        >
          {{ u.label }}
        </text>
      </view>

      <view v-if="links.length" class="ov__go">
        <text
          v-for="e in links"
          :key="e.key"
          class="sh-link ov__link"
          @tap="go(e.route)"
        >
          {{ $t(`stock.entry.${e.key}`) }}
        </text>
      </view>
    </view>

    <sh-tabs :items="TABS" :active="filter" @change="pickFilter"></sh-tabs>

    <sh-empty v-if="!loading && !rows.length" :text="String($t('stock.empty'))"></sh-empty>

    <view v-for="b in rows" :key="b.itemId" class="sh-card sh-mb-sm" @tap="openItem(b)">
      <view class="row__top sh-row">
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
          <text class="txt-price sh-num" :class="{ 'is-danger': b.available <= 0 }">
            {{ b.available }}
          </text>
          <text class="txt-caption">{{ $t("stock.available") }}</text>
        </view>
      </view>
    </view>

    <!--
      这句不是脚注。**「可用」是这一页唯一会被误读的数** ——
      商家看到实存 5 却只能卖 3 时，第一反应是系统算错了。

      但它**只在真有预留时才需要解释**：一行预留都没有时，可用恒等于实存，
      这张卡解释的是一个当天不存在的现象，而它每次都占一整张卡。
      与刚从库存明细撤掉的那张「差异原因」是同一类 —— 常驻的说明等于没有说明。
    -->
    <view v-if="hasReserved" class="sh-card">
      <text class="txt-caption">{{ $t("stock.formulaHint") }}</text>
    </view>

    <!--
      写动作贴底。`sh-actionbar` 自带占位块 —— **条是 fixed，CSS 量不到它的高**，
      不留占位最后一行会被压住，而那不会报错、只是看不见。
    -->
    <sh-actionbar v-if="actions.length" pill="plain" :pad="140">
      <text
        v-for="e in actions"
        :key="e.key"
        class="txt-sub act"
        @tap="go(e.route)"
      >
        {{ $t(`stock.entry.${e.key}`) }}
      </text>
    </sh-actionbar>
  </sh-scaffold>
</template>

<style scoped>
/* 七个入口的定宽网格。四列一行 —— 七条正好两行，一屏全可见 */

/*
 * 用 `sh-card` 而不是 `sh-chip`：**要和下面那排筛选 chip 分得开**。
 * 底色圆角由 sh-card 给，这里只管排布 —— 自己画一套药丸就又多一份要维护的皮。
 */
/* 总览卡：`sh-stat panel` 自己不画底，底由这张卡给 */
.ov {
  padding-bottom: 0;
}
/* 去处那一行：细线之下、等距铺开，与工作台「进销存」卡同构 */
.ov__go {
  display: flex;
  justify-content: space-around;
  border-top: var(--sh-hairline-soft);
  margin-top: 20rpx;
}
.ov__link {
  padding: 20rpx 8rpx;
}
/* 悬浮条里的四个写动作。`plain` 档是「一排东西等距」，
   条壳由 sh-actionbar 给，这里只管每一项 */
.act {
  flex: 1;
  text-align: center;
  padding: 8rpx 0;
  color: var(--sh-primary-text);
}

.row__top {
  gap: 20rpx;
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
  text-align: end;
  flex: none;
}
/* uni 的 <text> 默认是 inline —— 不转成 block，数字与「可用」会挤成「3可用」 */
.row__end > text {
  display: block;
}
</style>
