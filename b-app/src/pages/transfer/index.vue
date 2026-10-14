<script setup lang="ts">
// 调拨（B-7）。
//
// **一定生成两张单**，哪怕骑车十分钟就送到：发出一张出库、收到一张入库。
// 一期允许发出即收到两步连着走，但两张单都要落 —— 省掉一张的话，
// 将来要在途就得改历史数据。
//
// **在途是一个真实的库位**，不是「暂时没有」：货在路上的这几天，合计一件不差。
import { computed, ref, watch } from "vue";
import { ROUTES } from "@/shared/nav";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { Carrier, StockBalance, StockLocation, StockTransfer, TransferStatus } from "@shared/types";
import { confirm, pick, prompt } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

interface Line {
  itemId: string;
  name: string;
  specText?: string;
  available: number;
  qty: number;
}

const transferNo = ref("");
const doc = ref<StockTransfer | null>(null);
const busy = ref(false);

// —— 新建态 ——
const locations = ref<StockLocation[]>([]);
const fromId = ref("");
const toId = ref("");
const lines = ref<Line[]>([]);
const pickable = ref<StockBalance[]>([]);
const showPick = ref(false);
/**
 * 开挑货弹层的同时直接开相机（2026-09-18 店主：「扫码考虑放到外层，减少点击」）。
 *
 * <p>改之前扫码埋在第二层：添加商品 → 弹层 → 扫码，**相机打开前要点两下**。
 * 现在页面上并排一枚扫码钮，一下到相机。
 *
 * <p>扫码的流程本身没搬出来 —— 它仍在 `biz-item-picker` 里，三处共用一份。
 * 页面只负责「开着就扫」：把 scanCode 抄到三个页面上，
 * 「没绑过的码怎么办」那条分支迟早在三处各自漂。
 */
const autoScan = ref(false);

/**
 * 扫到的码在列表里找不到对应的货 —— 带着码去建品（2026-09-18）。
 *
 * <p>先关弹层再跳：留着的话回来时它还开着，而那时列表已经变了
 * （新建的货在里面），他会看到一个「上一次」的界面。
 *
 * <p>本页是 navigateTo 进来的，草稿还在栈上 —— 建完返回，这张单的行原样都在。
 */
function createWithBarcode(barcode: string) {
  showPick.value = false;
  autoScan.value = false;
  uni.navigateTo({ url: `${ROUTES.goodsEdit}?barcode=${encodeURIComponent(barcode)}` });
}

function openPick(scan: boolean) {
  autoScan.value = scan;
  showPick.value = true;
}

/** 可选的两端。**在途不能选** —— 它是系统库位，货停在那儿是过程不是目的地 */
const choosable = computed(() => locations.value.filter((l) => l.kind !== "TRANSIT"));

onLoad(async (q) => {
  transferNo.value = String((q as Record<string, string>)?.no ?? "");
});

/** 这次没取到。**与「确定为空」是两件事** —— 网络不通时不该显示「还没有…」 */
const failed = ref(false);

async function load() {
  try {
    if (transferNo.value) {
      doc.value = await api.mTransferDetail(transferNo.value);
      return;
    }
    /*
     * **先定调出方，再取余额** —— 两件事不能并发。
     *
     * 余额是**按库位**的，而这一页的调出方未必是当前门店：此前这里
     * `Promise.all` 同时发两个请求，余额那一发只能不带库位、拿到当前门店的数，
     * 于是挑货弹层写着「可用 30」，过账时扣的却是另一个库位 ——
     * `INV_INSUFFICIENT`，而商家刚刚亲眼看见 30。
     */
    const locs = await api.mStockLocations();
    locations.value = locs;
    const usable = locs.filter((l) => l.kind !== "TRANSIT");
    if (!fromId.value && usable.length) fromId.value = usable[0]!.locationId;
    if (!toId.value && usable.length > 1) toId.value = usable[1]!.locationId;
    await loadBalances();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 按当前调出方取余额。**没定调出方就别取** —— 那样只会拿到当前门店的数 */
async function loadBalances() {
  if (!fromId.value) return;
  const bal = await api.mStockBalances({
    filter: "all", locationId: fromId.value, size: 200,
  });
  pickable.value = bal.filter((b) => b.available > 0);
}

/*
 * 换了调出方：余额要重取，**已挑的行也要清掉**。
 *
 * 只重取余额是不够的 —— 在 A 库位挑的三件还留在单子里，而 B 库位可能一件都没有，
 * 过账时照样 `INV_INSUFFICIENT`。清空要说出来：单子是他一件件点出来的，
 * 静默清掉比不清更糟。
 */
watch(fromId, async (nv, ov) => {
  if (!ov || nv === ov) return;   // 首次赋默认值不算「换」
  if (lines.value.length) {
    lines.value = [];
    uni.showToast({ title: String(t("transfer.fromChangedCleared")), icon: "none" });
  }
  try {
    await loadBalances();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
});

function nameOf(id?: string): string {
  const loc = locations.value.find((l) => l.locationId === id);
  if (!loc) return id ?? "";
  /*
   * **门店库位的 name 是门店编号，不是门店名。**
   *
   * 库位是懒创建的，创建它的 `InventoryAclServiceImpl.locationIdOf` 只拿得到
   * `storeNo`（进销存模块刻意不认识平台的门店表 —— 那是它能独立交付的前提），
   * 于是 `name` 里存的是 `ST-M0001` 这样的编号。
   * 2026-08-28 线上截图：调拨页的「从 / 到」写着
   * `ST-M0001` 与 `ST202608151144350000684`，商家看不懂自己要往哪调。
   *
   * 显示名归端上解析：这里本来就有商家的门店列表，按 externalRef 对回去。
   * 对不上（门店被删、或不是门店库位）就用库位自己的名字。
   */
  if (loc.kind === "STORE" && loc.externalRef) {
    const store = merchant.allStores.find((s) => s.storeNo === loc.externalRef)
      ?? merchant.stores.find((s) => s.storeNo === loc.externalRef);
    if (store?.name) return store.name;
  }
  return loc.name || (id ?? "");
}

/**
 * 对调两端。
 *
 * <p><b>已经挑好的货不动。</b> 调拨行上的 `available` 是「从这一端还剩多少」——
 * 对调之后那个数不再成立，但**不能因此把行清掉**：商家点对调多半是发现方向填反了，
 * 他要的正是「这些货，反过来送」。清掉等于罚他重挑一遍。
 * 数量真的超了，发出那一步会拒 —— 那时说得出是哪一件、差多少。
 */
function swapEnds() {
  const f = fromId.value;
  fromId.value = toId.value;
  toId.value = f;
}

/*
 * ★ **弹层里要与页面上是同一个名字**（2026-09-18 店主：「点击门店信息，
 * 出来的列表弹框中的内容不清楚是什么」）。
 *
 * <p>改之前这里用的是 `l.name` —— 库位表里存的原始名，即 `ST-M0001`
 * 这样的门店编号（见 `nameOf` 上面那段：进销存不认识平台的门店表）。
 * 于是页面上写着「老张粮油店」，点开弹层却是一串编号，**两个名字对不上**，
 * 他不知道自己在选什么。
 *
 * <p>弹层也给个标题：一个没有标题的选项列表要靠上下文猜它在问什么。
 */
async function pickEnd(which: "from" | "to") {
  const items = choosable.value;
  const cur = which === "from" ? fromId.value : toId.value;
  const res = await pick({
    title: String(t(which === "from" ? "transfer.pickFrom" : "transfer.pickTo")),
    items: items.map((l) => nameOf(l.locationId)),
    // 当前这一端打上勾：不标的话弹层看不出「现在选的是哪个」
    selected: items.findIndex((l) => l.locationId === cur),
  });
  if (res === null) return;
  const id = items[res]!.locationId;
  if (which === "from") fromId.value = id;
  else toId.value = id;
}

function pickQty(b: StockBalance): string {
  return String(t("transfer.availableN", { n: b.available }));
}

function addLine(b: StockBalance) {
  if (lines.value.some((l) => l.itemId === b.itemId)) return;
  lines.value = [...lines.value, {
    itemId: b.itemId, name: b.name, specText: b.specText, available: b.available, qty: 1,
  }];
  showPick.value = false;
}

async function editQty(l: Line) {
  const v = await prompt({
    title: String(t("transfer.qtyTitle", { name: l.name })),
    hint: String(t("transfer.qtyHint", { n: l.available })),
    type: "number",
    value: String(l.qty),
  });
  if (v == null || v === "") return;
  const n = Number(v);
  if (!Number.isInteger(n) || n <= 0 || n > l.available) {
    uni.showToast({ title: String(t("transfer.qtyBad", { n: l.available })), icon: "none" });
    return;
  }
  l.qty = n;
}

const totalQty = computed(() => lines.value.reduce((s, l) => s + l.qty, 0));

/** 建单并发出。**两步都调** —— 单据要有，在途也要有 */
/*
 * 发货信息：承运方（选）+ 运单号（输）。
 *
 * **两者都可空** —— 自己拉一趟货过去也要发得出去。强制填的话商家就学会
 * 乱填一个，那比空着更坏：空着至少诚实地说「没记」。
 *
 * 承运方的**名字要一起回传**：进销存是独立库，读不了主库的 ful_carrier，
 * 那个名字快照只能由端上带过去（见方案 §三②）。
 */
const carriers = ref<Carrier[]>([]);
const pickedCarrier = ref<Carrier | null>(null);
const trackingNo = ref("");
const showShip = ref(false);

async function loadCarriers() {
  try {
    carriers.value = await api.mCarriers();
  } catch {
    // 拿不到就让它空着：发货不该因为承运方列表挂了而做不成
    carriers.value = [];
  }
}

/** 点「发出」先弹这一层，填完再真的发 */
function openShip() {
  if (!lines.value.length || busy.value) return;
  if (fromId.value === toId.value) {
    uni.showToast({ title: String(t("transfer.sameEnds")), icon: "none" });
    return;
  }
  if (!carriers.value.length) void loadCarriers();
  showShip.value = true;
}

async function ship() {
  if (!lines.value.length || busy.value) return;
  showShip.value = false;
  busy.value = true;
  try {
    const no = await api.mTransferCreate({
      fromLocationId: fromId.value,
      toLocationId: toId.value,
      lines: lines.value.map((l) => ({ itemId: l.itemId, qty: l.qty })),
    });
    await api.mTransferShip(no, {
      carrierNo: pickedCarrier.value?.carrier,
      carrierName: pickedCarrier.value?.name,
      trackingNo: trackingNo.value.trim() || undefined,
    });
    transferNo.value = no;
    await load();
    uni.showToast({ title: String(t("transfer.shipped", { no })), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

async function receive() {
  if (!doc.value || busy.value) return;
  const ok = await confirm({
    title: String(t("transfer.receiveTitle")),
    hint: String(t("transfer.receiveBody", {
      n: doc.value.totalQty, to: doc.value.toLocationName ?? "",
    })),
  });
  if (!ok) return;
  busy.value = true;
  try {
    await api.mTransferReceive(doc.value.transferNo);
    await load();
    uni.showToast({ title: String(t("transfer.received")), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

/**
 * 作废一张还没发出的调拨单 —— 「建错了怎么办」的答案。
 *
 * **只在草稿态出现**：已发出的货正停在在途库位上，把它弄回去是「退回」不是作废，
 * 得再走一遍成对的一出一入。画一个点了报错的按钮比没有按钮更糟。
 */
async function voidTransfer() {
  if (!doc.value || busy.value) return;
  const ok = await confirm({
    title: String(t("transfer.voidTitle")),
    // 草稿没动过库存，别说「库存会退回」——那是吓人
    hint: String(t("transfer.voidHint", { no: doc.value.transferNo })),
    confirmText: String(t("transfer.voidConfirm")),
    danger: true,
  });
  if (!ok) return;
  busy.value = true;
  try {
    await api.mTransferVoid(doc.value.transferNo);
    await load();
    uni.showToast({ title: String(t("transfer.voided")), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

/**
 * 状态徽章的色档。**三态，不是二元三目** —— 原来是「不是 SHIPPED 就用主色」，
 * 于是新加的「已作废」跟草稿一样是一枚醒目的主色徽章，看着像个正常状态。
 * 作废是终态，它不需要被注意，用中性的那一档。
 */
// 参数吃 TransferStatus 而不是 string。写死 string 的话，把某个取值拼错一个字母
// 也是合法 TypeScript，vue-tsc 一声不吭，那个分支从此不进 —— 收窄之后消融验证过：
// 改错一处会报 TS2367「两个类型没有重叠」并点到行。
function statusChip(status: TransferStatus): string {
  if (status === "SHIPPED") return "sh-chip--warning";   // 在途：要惦记着收货
  if (status === "VOIDED") return "";                     // 终态，中性
  return "sh-chip--primary";
}

/** 「08-26 07:30」。切片不解析 —— 后端发的是不带时区的 LocalDateTime */
function at(iso?: string): string {
  return iso && iso.length >= 16 ? iso.slice(5, 16).replace("T", " ") : "";
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="transfer.title" :denied="!merchant.can('biz:stock')">
    <!-- ① 已有单：看状态、收货 -->
    <template v-if="doc">
      <view class="sh-card ends sh-row">
        <view class="end">
          <text class="txt-caption">{{ $t("transfer.from") }}</text>
          <text class="txt-title">{{ doc.fromLocationName || doc.fromLocationId }}</text>
        </view>
        <sh-icon class="arrow" name="chevronRight" :size="28" color="var(--sh-sub)"></sh-icon>
        <view class="end end--r">
          <text class="txt-caption">{{ $t("transfer.to") }}</text>
          <text class="txt-title">{{ doc.toLocationName || doc.toLocationId }}</text>
        </view>
      </view>

      <view class="sh-card">
        <view class="hd sh-row sh-row--between">
          <text class="txt-strong sh-num">{{ doc.transferNo }}</text>
          <text
            class="sh-chip"
            :class="statusChip(doc.status)"
          >
            {{ $t(`transfer.status.${doc.status}`) }}
          </text>
        </view>
        <!--
          时间只有**两种状态说得出**。原来这里是个二元三目：不是 SHIPPED 就当成
          RECEIVED，于是草稿单上写着「 已收到」——时间还是空的（2026-09-02 真机
          截图为证）。作废之后同样会这样。状态本身徽章已经说了，这一行没有内容
          时就别出现。
        -->
        <text v-if="doc.status === 'SHIPPED'" class="txt-caption">
          {{ $t("transfer.shippedAt", { at: at(doc.shippedAt) }) }}
        </text>
        <text v-else-if="doc.status === 'RECEIVED'" class="txt-caption">
          {{ $t("transfer.receivedAt", { at: at(doc.receivedAt) }) }}
        </text>

        <!--
          承运方与运单号。**两者都空就整行不出** —— 自己送的单据上挂一句
          「承运方：—」只是噪声；而收货方真正要核对的是有值的那一行。
        -->
        <text v-if="doc.carrierName || doc.trackingNo" class="txt-caption trf__ship">
          {{ [doc.carrierName, doc.trackingNo].filter(Boolean).join(" · ") }}
        </text>
      </view>

      <!-- 草稿态没有行（行在发出的那张出库单上）。说成「还没发出」而不是「空单」 -->
      <sh-empty v-if="!doc.lines.length"
          :failed="failed"
          @retry="load" :text="String($t('transfer.notShipped'))"></sh-empty>

      <view v-for="l in doc.lines" :key="l.itemId" class="sh-card sh-mb-sm">
        <view class="row__top sh-row">
          <view class="sh-fill">
            <text class="txt-strong row__title">{{ l.name }}{{ l.specText ? ` · ${l.specText}` : "" }}</text>
          </view>
          <text class="txt-price sh-num">{{ l.qty }}</text>
        </view>
      </view>

      <!--
        守恒那一句。**不画三个库位的前后数** —— 那要再拉一次每个库位的余额，
        而这一屏真正要说的只有一件事：货在路上的这几天，合计一件不差。
      -->
      <view class="sh-card">
        <sh-kv between :label="String($t('transfer.inTransit'))">
          <text class="sh-num is-warning">
            {{ doc.status === "SHIPPED" ? doc.totalQty : 0 }}
          </text>
        </sh-kv>
        <text class="txt-caption">{{ $t("transfer.conserveHint") }}</text>
      </view>

      <!-- 收货：单据详情一屏放不下时，这枚在最下面，而它是收货人唯一要点的东西 -->
      <sh-actionbar v-if="doc.status === 'SHIPPED'" :pad="180">
        <view class="sh-btn" @tap="receive">{{ $t("transfer.receive") }}</view>
      </sh-actionbar>

      <!--
        作废**只在草稿态**。此前调拨一张撤销路径都没有：单据列表的作废按钮只放行
        出入库单，这一页也没有口子，于是建错一张就永远挂在那儿。
        已发出的不给 —— 那时要的是「退回」，是另一件事。
      -->
      <sh-actionbar v-else-if="doc.status === 'DRAFT'" :pad="180">
        <view class="sh-btn sh-btn--danger" :class="{ 'sh-btn--muted': busy }" @tap="voidTransfer">
          {{ $t("transfer.void") }}
        </view>
      </sh-actionbar>
    </template>

    <!-- ② 新建 -->
    <template v-else>
      <view class="sh-card">
        <!--
          用 `sh-go`（带 › 的那个件）而不是一段红字：**这两行是可以点开选的**，
          而原来除了颜色没有任何提示 —— 商家看到「从：老张粮油店」，
          读起来像一条只读信息，不像一个选择器。
        -->
        <!--
          ★ **整行可点，不是只有那几个字可点**（2026-09-18 店主：「选择不好点击」）。

          线上量到的：改之前两行的可点区是 61×17 和 100×17 ——
          **17px 不到可点下限 44 的一半**，而且只有店名那几个字能点：
          手指落在「从」字上、或右边空白处，都没反应。

          `@tap` 从 sh-go 挪到整行的 view 上，行本身给 88rpx 最小高。
          sh-go 留着 —— 它那个「›」是「这一行点得开」的记号，不是可点区本身。
        -->
        <!--
          ★ **对调钮挪到右侧，与两端并排**（2026-09-18 店主：「头部的空间过大」）。

          改之前是三行摞着：从 88rpx + 对调 88rpx + 到 88rpx = 264rpx，
          再加卡片内边距，一张只说「从哪到哪」的卡占掉了 150px。
          对调本来就不是第三个字段，它是这两行之间的一个动作 ——
          摆在右侧竖跨两行，**一眼就是「把这两行调个个儿」**，还省掉一整行。
        -->
        <view class="ends2 sh-row">
          <view class="sh-fill">
        <view class="end sh-row sh-row--between" @tap="pickEnd('from')">
          <text class="txt-sub">{{ $t("transfer.from") }}</text>
          <sh-go :text="nameOf(fromId) || '—'"></sh-go>
        </view>
        <view class="end sh-row sh-row--between" @tap="pickEnd('to')">
          <text class="txt-sub">{{ $t("transfer.to") }}</text>
          <sh-go :text="nameOf(toId) || '—'"></sh-go>
        </view>
          </view>
        <view class="swap sh-center" @tap="swapEnds">
          <!--
            图标库里没有「对调」那一个（只有 chevronUp/Down 等 23 个），
            用两枚箭头上下叠出 ⇅ —— 不为一处新造图标。
            写 name="transfer" 那种不存在的名字**不会报错，只会画不出东西**。
          -->
          <view class="swap__ic">
            <sh-icon name="chevronUp" :size="22" color="var(--sh-sub)"></sh-icon>
            <sh-icon name="chevronDown" :size="22" color="var(--sh-sub)"></sh-icon>
          </view>
        </view>
        </view>
      </view>

    <!--
      ★ **新建单据时不放空态卡**（2026-09-18 店主：进销存几页「浪费太多空间」）。

      量到的：那张卡 70px + 上下间距 10px，**而它正下方就是「+ 添加商品」** ——
      一句「尚未添加商品」既没告诉他发生了什么，也没告诉他该做什么，
      那枚按钮两件都做到了。空着的位置本身就是「还没加」。

      **已有单据那一侧的空态留着**（见上面 doc 分支）：那里「没有行」是
      「这张单还没发出」，是一条真消息，而且那一屏没有别的东西替它说话。
    -->

      <view v-for="l in lines" :key="l.itemId" class="sh-card sh-mb-sm">
        <view class="row__top sh-row">
          <view class="sh-fill">
            <text class="txt-strong row__title">{{ l.name }}{{ l.specText ? ` · ${l.specText}` : "" }}</text>
            <text class="sh-muted sh-num">{{ $t("transfer.availableN", { n: l.available }) }}</text>
          </view>
          <text class="sh-link sh-num qty" @tap="editQty(l)">{{ l.qty }}</text>
        </view>
      </view>

      <view class="addrow sh-row">
      <sh-add class="sh-fill" :text="String($t('transfer.addItem'))" @tap="openPick(false)"></sh-add>
      <!--
        扫码与「添加商品」并排：**它们是同一件事的两种做法**（找到那件货）。
        藏进弹层的话商家会以为扫码是另一个功能，而且每次要多点一下。
      -->
      <view class="scan sh-center" @tap="openPick(true)">
        <sh-icon name="scan" :size="26" color="var(--sh-on-primary)"></sh-icon>
      </view>
    </view>

      <view v-if="lines.length" class="sh-card hd sh-row sh-row--between">
        <text class="txt-strong">{{ $t("transfer.totalQty") }}</text>
        <text class="txt-display sh-num">{{ totalQty }}</text>
      </view>

      <!-- 主动作贴底：调拨单的行数没有上限 -->
      <sh-actionbar :pad="180">
        <view class="sh-btn" :class="{ 'sh-btn--muted': !lines.length || busy }" @tap="openShip">
          {{ $t("transfer.ship") }}
        </view>
      </sh-actionbar>

      <biz-item-picker
        :visible="showPick"
      :auto-scan="autoScan"
        :title="String($t('transfer.addItem'))"
        :items="pickable"
        :picked="lines.map((l) => l.itemId)"
        :qty-label="pickQty"
        @pick="addLine"
        @close="showPick = false; autoScan = false"
      @create="createWithBarcode"
      ></biz-item-picker>
    </template>

    <!--
      发货信息。**两项都可空** —— 自己送也要发得出去（见 openShip 的注释）。
      承运方是选的（它是实体），运单号是输的（它是一串码，没有可选列表）。
    -->
    <sh-sheet :visible="showShip" :title="String($t('transfer.shipTitle'))" @close="showShip = false">
      <text class="sh-hint">{{ $t("transfer.shipHint") }}</text>

      <view class="carriers sh-row">
        <text
          v-for="c in carriers"
          :key="c.carrier"
          class="carrier"
          :class="{ 'carrier--on': pickedCarrier?.carrier === c.carrier }"
          @tap="pickedCarrier = pickedCarrier?.carrier === c.carrier ? null : c"
        >
          {{ c.name }}
        </text>
      </view>

      <input
        v-model="trackingNo"
        class="field__input ship__no"
        :placeholder="String($t('transfer.trackingPh'))"
        :maxlength="64"
      />

      <view class="sh-btn" :class="{ 'sh-btn--muted': busy }" @tap="ship">
        {{ busy ? $t("common.loading") : $t("transfer.shipConfirm") }}
      </view>
    </sh-sheet>
  </sh-scaffold>
</template>

<style scoped>
.addrow {
  gap: 16rpx;
}
/* 与「添加商品」同高（44px），圆形 —— 形状说明它是另一种做法，不是第三个动作 */
.scan {
  flex: none;
  width: 88rpx;
  height: 88rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
}
/* 整行可点：88rpx（44px）是可点下限，改之前只有 17px 且只有文字那一段 */
.end {
  min-height: 88rpx;
}
/* 两端一列、对调一列：整卡从三行 264rpx 收到两行 176rpx */
.ends2 {
  gap: 16rpx;
}
/* 对调：竖跨两行的圆钮，不占自己的一行 */
.swap {
  flex: none;
  width: 88rpx;
  height: 88rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
}
/* 两枚箭头叠成 ⇅：负间距让它们靠拢，否则中间空一大截像两个独立按钮 */
.swap__ic {
  display: flex;
  flex-direction: column;
  gap: 0;
  margin-block: -8rpx;
}
.ends {
  gap: 20rpx;
}
.end {
  flex: 1;
}
.end > text {
  display: block;
}
.end--r {
  text-align: end;
}

.row__top {
  gap: 20rpx;
}

.row__title {
  display: block;
}
.qty {
  min-width: 72rpx;
  text-align: end;
  flex: none;
}
.hint {
  padding: 0 4rpx;
}
.pick {
  padding: 20rpx 0;
}

.carriers {
  gap: 20rpx;
  flex-wrap: wrap;
  margin-bottom: 20rpx;
}

.carrier {
  padding: 12rpx 28rpx;
  border-radius: 9999px;
  /* 未选中那枚：`--sh-faint` 是真实存在的浅底 token。
     写 `--sh-fill` 会静默失效 —— 背景透明，两枚按钮看不出边界（闸门当场抓到） */
  background: var(--sh-faint);
  color: var(--sh-sub);
}

/* 选中那一枚：走 primary-text 不走 primary —— 后者是块面色，用在文字上对比度不够 */
.carrier--on {
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
}

.ship__no {
  margin-bottom: 24rpx;
}

.trf__ship {
  display: block;
  margin-top: 8rpx;
}
</style>
