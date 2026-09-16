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
import { stockEntries, countUsableLocations, type StockEntry } from "@/shared/stock-entries";
import { ROUTES } from "@/shared/nav";
import type { StockBalance, StockSummary } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const summary = ref<StockSummary | null>(null);
const rows = ref<StockBalance[]>([]);
const loading = ref(false);

/**
 * 「更多」那一层开着没有。
 *
 * **它开在页内而不是跳一页** —— 六条里没有一条是「点进去还要点一次」的，
 * 跳一页等于在路上多加一个只有链接的中转屏。
 */
const moreOpen = ref(false);

/**
 * 除在途外能放货的地方有几个。**`null` 是「还没取到」不是 0** ——
 * 当成 0 的话，页面一打开调拨就是灰的，等数回来又变回可点。
 */
const usableLocations = ref<number | null>(null);

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

/** 首屏到过没有。**不是 `loading`** —— 那个含下拉刷新，刷新时把列表换成空态是另一个 bug */
const loaded = ref(false);
/** 这次没取到。**与「确定为空」是两件事** —— 网络不通时不该显示「还没有…」。
 *  这一页本来就弹了吐司，但吐司两秒就没，而空态一直挂在那儿说「还没有」 */
const failed = ref(false);

async function load() {
  loading.value = true;
  try {
    /*
     * 三段各自兜底：总览取不到不该让列表也空着，反之亦然。
     *
     * 库位跟着一起取：**判据要在弹层打开的那一刻就成立**，
     * 开的时候才去取的话，那一瞬间的「调拨」是可点的，一秒后才灰掉。
     * 它也必须跟着 `onShow` 重取 —— 「去添加」加完一个回来，灰的那条要变回可点。
     */
    const [s, list, locs] = await Promise.all([
      api.mStockSummary().catch(() => null),
      api.mStockBalances({ filter: filter.value }),
      api.mStockLocations().catch(() => null),
    ]);
    if (s) summary.value = s;
    rows.value = list;
    // 取不到就保留上一次的数 —— 一次网络抖动不该把调拨灰掉
    if (locs) usableLocations.value = countUsableLocations(locs);

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
    failed.value = false;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
    failed.value = true;
  } finally {
    loading.value = false;
    loaded.value = true;
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
 * 这一块的其余八个入口从这里排。**怎么排的判据只有一个：多久用一次。**
 *
 * 进货、报损几乎每天 → 贴底那条；其余六个一周到一月、甚至建好就不动 → 「更多」里。
 * 此前是四个写动作在贴底条、另外五个在总览卡里排成一行**等大的文字链接**，
 * 而那一行里的五个在语义上是三类东西，长得却完全一样。
 *
 * **每屏各在工作台/我的上摆一个门是错的** —— 那就回到「同一件事三个入口，
 * 人记不住走哪个」。工作台只开一道门到库存，库存页当枢纽。
 *
 * 排布的规则全在 `shared/stock-entries.ts` 里，这一层只管画 ——
 * 那边是纯函数，`tests/stock-entries.test.ts` 直接断言得到。
 */
const entries = computed(() =>
  stockEntries({
    can: (perm) => merchant.can(perm),
    multiStore: merchant.multiStore,
    usableLocations: usableLocations.value,
  }),
);

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

function go(route: string) {
  uni.navigateTo({ url: route });
}

/**
 * 点「更多」里的一条。
 *
 * **用不了的那条不是点不动，是点了去补** —— 调拨缺的是第二个放货的地方，
 * 把人送到库位页比让他对着一个灰按钮猜有用。没有权限去补的人才真的点不动，
 * 而原因就写在那一行上，不用他去猜。
 */
function pickEntry(e: StockEntry) {
  const to = e.blocked ? e.blocked.route : e.route;
  // 没有去处就什么都不做：原因已经写在那一行上，弹层留着，别把人弹回去
  if (!to) return;
  moreOpen.value = false;
  uni.navigateTo({ url: to });
}

function openItem(b: StockBalance) {
  uni.navigateTo({ url: `/pages/stock-detail/index?itemId=${encodeURIComponent(b.itemId)}` });
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="stock.title" :denied="!merchant.can('biz:stock')">
    <!-- 库存是这家店的：不标出来，人会照着另一家的数去补货 -->
    <biz-store-tag readonly></biz-store-tag>
    <!--
      总览卡：**只有数**。原来它下面还挂着一排等大的文字链接（单据/跨店/报表/库位/供应商），
      那五个一周到一月才用一次，却常年占着这一屏 —— 已经全部收进贴底条的「更多」里。
      「看各店的货」留下来，因为它不是一件要办的事，是把上面那四个数按门店再拆一遍。
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
      <!-- 跨店贴在数字下沿：人才在该找它的地方找到它。只给开了不止一家店的商家 -->
      <view v-if="entries.cross" class="ov__cross" @tap="go(entries.cross.route)">
        <sh-go :text="String($t('stock.crossGo'))"></sh-go>
      </view>
    </view>

    <!--
      「有人在等」**单独一张卡**。此前它和那排链接共用同一个容器，
      真有事的时候两排长得一模一样 —— 而上面那排是等着人去处理的事。
      **只在真有事时出现** —— 没有在途、没有开着的盘点单时一行都不占。
    -->
    <view v-if="urgent.length" class="sh-card wait">
      <text
        v-for="u in urgent"
        :key="u.key"
        class="txt-primary txt-bold wait__link"
        @tap="go(u.route)"
      >
        {{ u.label }}
      </text>
    </view>

    <sh-tabs :items="TABS" :active="filter" @change="pickFilter"></sh-tabs>

    <sh-empty v-if="!rows.length" :pending="!loaded" :failed="failed" @retry="load" :text="String($t('stock.empty'))"></sh-empty>

    <view v-for="b in rows" :key="b.itemId" class="sh-card sh-mb-sm" @tap="openItem(b)">
      <view class="row__top sh-row">
        <view class="sh-fill">
          <text class="txt-strong row__title">{{ b.name }}{{ b.specText ? ` · ${b.specText}` : "" }}</text>
          <view class="row__meta sh-row sh-row--baseline">
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

      **这里不给 `pill`**：那一档是「一排东西等距」的药丸壳，而这条要能往上长出一段菜单。
      壳自己画，形状从药丸换成圆角矩形 —— 药丸长高了就成了一颗胶囊，不像一块面板。

      **图标是现成的**：`plus` 往库存里加、`minus` 从库存里减、`chevronUp/Down` 开合。
      库里 23 个图标没有一个是业务图标，但这几个的意思正好对得上，不用新画。
    -->
    <!-- 菜单开着时，点别处收起来。透明，不压暗内容 —— 伸缩菜单不是弹层，
         它没有「把你圈在这件事里」的意思 -->
    <view v-if="moreOpen" class="catch" @tap="moreOpen = false"></view>

    <sh-actionbar v-if="entries.primary.length || entries.more.length" :pad="164">
      <view class="bar">
        <!--
          伸缩菜单：**从这条自己往上长**，不是另开一层。
          用 `max-height` 过渡而不是 `v-if` —— `v-if` 的元素没有可过渡的起点，
          菜单会「啪」地整块出现，看不出它是从这个按钮长出来的。

          **里面只有六个名字**：没有分组标题，也没有每条一句的说明。
          顺序仍按「多久用一次」排（盘点/调拨 → 单据/报表 → 库位/供应商），
          只是不把档名写出来。
        -->
        <view class="menu" :class="{ 'is-open': moreOpen }">
          <view
            v-for="e in entries.more"
            :key="e.key"
            class="opt"
            :class="{ 'is-off': !!e.blocked }"
            @tap="pickEntry(e)"
          >
            <view class="sh-fill">
              <text class="txt-strong opt__t">{{ $t(`stock.entry.${e.key}`) }}</text>
              <!--
                **只有用不了的时候才有这一句**。常驻的说明已经去掉了（2026-09-17）——
                这六个名字是这一行通用的说法，给每条配一句解释反而像在教人认字。
                而「为什么点不了」不解释就只剩一个灰名字，看着像坏了。

                它**另起一行**而不是跟在名字后面：挤在同一行时，名字被压成两行、
                「去添加」也断成两行 —— 一整行里没有一处是完整的。
              -->
              <text v-if="e.blocked" class="txt-caption opt__why">
                {{ $t(e.blocked.reasonKey, e.blocked.params ?? {}) }}
              </text>
            </view>
            <!-- 用不了但补得上：给去处。补不上（没有库位权限）就只剩上面那句原因。
                 包一层是为了 `flex: none` —— 不包的话原因一长就把「去添加」挤断行 -->
            <view class="opt__end">
              <sh-go v-if="e.blocked?.route" :text="String($t('stock.goAddLocation'))"></sh-go>
              <sh-icon
                v-else-if="!e.blocked"
                name="chevronRight"
                :size="22"
                color="var(--sh-sub)"
              ></sh-icon>
            </view>
          </view>
        </view>

        <view class="bar__row">
          <view
            v-for="e in entries.primary"
            :key="e.key"
            class="sh-btn sh-btn--sm act"
            :class="{ 'sh-btn--soft': e.key === 'out' }"
            @tap="go(e.route)"
          >
            <sh-icon
              :name="e.key === 'purchase' ? 'plus' : 'minus'"
              :size="26"
              :color="e.key === 'purchase' ? 'var(--sh-on-primary)' : 'var(--sh-primary-text)'"
            ></sh-icon>
            <text>{{ $t(`stock.entry.${e.key}`) }}</text>
          </view>
          <view
            v-if="entries.more.length"
            class="sh-btn sh-btn--sm sh-btn--muted act act--more"
            @tap="moreOpen = !moreOpen"
          >
            <text>{{ $t("stock.more") }}</text>
            <sh-icon
              :name="moreOpen ? 'chevronDown' : 'chevronUp'"
              :size="26"
              color="var(--sh-sub)"
            ></sh-icon>
          </view>
        </view>
      </view>
    </sh-actionbar>
  </sh-scaffold>
</template>

<style scoped>
/* 总览卡：`sh-stat panel` 自己不画底，底由这张卡给 */
.ov {
  padding-bottom: 0;
}
/* 跨店贴在数字下沿、靠行尾 —— 它是这四个数的另一个切法，不是第五个数 */
.ov__cross {
  display: flex;
  justify-content: flex-end;
  padding: 16rpx 0 20rpx;
}

/* 「有人在等」：单独一张卡，几项并排 */
.wait {
  display: flex;
  justify-content: space-around;
}
.wait__link {
  padding: 4rpx 8rpx;
}

/*
 * 贴底那条的壳。**自己画而不用 `sh-actionbar` 的 `pill` 档** ——
 * 那一档是药丸（`border-radius: 9999px`），而这条要能往上长出一段菜单，
 * 药丸长高了就成了一颗胶囊。圆角矩形才像一块面板。
 */
.bar {
  background: var(--sh-surface);
  border-radius: 32rpx;
  padding: 16rpx;
  box-shadow: var(--sh-shadow-up);
}
.bar__row {
  display: flex;
  align-items: center;
  gap: 16rpx;
}
/* 两个写动作等分，「更多」不等分 —— 它是开另一段的口子，不是第三个动作。
   `.sh-btn` 是 display:block，这里要图标与字并排，所以显式接回 flex
   （与 base.css 里 `.sh-btn.sh-center` 同一个道理，那儿写着为什么） */
.act {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
}
.act--more {
  flex: none;
}

/*
 * 伸缩菜单。**`max-height` 过渡，不是 `v-if`** —— `v-if` 的元素没有可过渡的起点，
 * 菜单会「啪」地整块出现，看不出它是从下面那个按钮长出来的。
 * 收起时 `max-height: 0` + `overflow: hidden`，里面的行仍在 DOM 里但一个像素都不占。
 */
.menu {
  max-height: 0;
  overflow: hidden;
  /* 时长走档（`--sh-t-fast` = 0.18s）—— 裸写一个「感觉差不多」的数，
     下一个人会再挑一个，两个差 0.02s 的过渡没人分得出，档就是这么没的 */
  transition: max-height var(--sh-t-fast) ease;
}
/*
 * 三组六条实测 848rpx（中文、三个码全有的店长），这里留到 900rpx。
 *
 * **要给一个够大的定值，不能写 `none`** —— `max-height: none` 之间没有可插值的
 * 中间态，过渡整条失效，菜单会「啪」地出现。
 *
 * **同时开 `overflow-y`**：定值总有估不准的时候（阿语更长、将来多一条），
 * 估小了的症状是最后一行被静默切掉一半 —— 而切掉的那半没有人会来报。
 * 能滚就只是要多划一下，看得见。
 */
.menu.is-open {
  max-height: 900rpx;
  overflow-y: auto;
}
@media (prefers-reduced-motion: reduce) {
  .menu {
    transition: none;
  }
}
.opt {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 20rpx 12rpx;
  border-bottom: var(--sh-hairline-soft);
}
/* 最后一条不画线：它下面紧挨着的是按钮那一排，多一条线像是把按钮也框进了菜单 */
.opt:last-child {
  border-bottom: none;
}
/* 用不了的那条：名字压暗，但**原因那句不压** —— 压掉了就没人看得见为什么 */
.opt.is-off .opt__t {
  color: var(--sh-sub);
}
.opt__t {
  display: block;
}
/* 只在用不了时出现的那句原因：名字下面另起一行 */
.opt__why {
  display: block;
  margin-top: 4rpx;
}
.opt__end {
  flex: none;
  display: flex;
  align-items: center;
}
/*
 * 点别处收起来用的透明层。**不压暗** —— 伸缩菜单不是弹层，
 * 它没有「把你圈在这件事里」的意思，压暗会让人以为下面点不动了。
 *
 * 层高写成「比贴底条低一档」而不是一个数字：它必须盖住内容、又不能盖住条自己，
 * 否则第一下点「更多」收起菜单、第二下才点得到按钮 —— 而那看着像按钮失灵。
 */
.catch {
  position: fixed;
  inset: 0;
  z-index: calc(var(--sh-z-actionbar) - 1);
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
