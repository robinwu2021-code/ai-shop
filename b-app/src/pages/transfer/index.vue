<script setup lang="ts">
// 调拨（B-7）。
//
// **一定生成两张单**，哪怕骑车十分钟就送到：发出一张出库、收到一张入库。
// 一期允许发出即收到两步连着走，但两张单都要落 —— 省掉一张的话，
// 将来要在途就得改历史数据。
//
// **在途是一个真实的库位**，不是「暂时没有」：货在路上的这几天，合计一件不差。
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { Carrier, StockBalance, StockLocation, StockTransfer } from "@shared/types";
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

/** 可选的两端。**在途不能选** —— 它是系统库位，货停在那儿是过程不是目的地 */
const choosable = computed(() => locations.value.filter((l) => l.kind !== "TRANSIT"));

onLoad(async (q) => {
  transferNo.value = String((q as Record<string, string>)?.no ?? "");
});

async function load() {
  try {
    if (transferNo.value) {
      doc.value = await api.mTransferDetail(transferNo.value);
      return;
    }
    const [locs, bal] = await Promise.all([
      api.mStockLocations(),
      api.mStockBalances({ filter: "all", size: 200 }),
    ]);
    locations.value = locs;
    pickable.value = bal.filter((b) => b.available > 0);
    const usable = locs.filter((l) => l.kind !== "TRANSIT");
    if (!fromId.value && usable.length) fromId.value = usable[0]!.locationId;
    if (!toId.value && usable.length > 1) toId.value = usable[1]!.locationId;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

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

async function pickEnd(which: "from" | "to") {
  const items = choosable.value;
  const res = await pick({ items: items.map((l) => l.name) });
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
            :class="doc.status === 'SHIPPED' ? 'sh-chip--warning' : 'sh-chip--primary'"
          >
            {{ $t(`transfer.status.${doc.status}`) }}
          </text>
        </view>
        <text class="txt-caption">
          {{ doc.status === "SHIPPED"
            ? $t("transfer.shippedAt", { at: at(doc.shippedAt) })
            : $t("transfer.receivedAt", { at: at(doc.receivedAt) }) }}
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
      <sh-empty v-if="!doc.lines.length" :text="String($t('transfer.notShipped'))"></sh-empty>

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
    </template>

    <!-- ② 新建 -->
    <template v-else>
      <view class="sh-card">
        <!--
          用 `sh-go`（带 › 的那个件）而不是一段红字：**这两行是可以点开选的**，
          而原来除了颜色没有任何提示 —— 商家看到「从：老张粮油店」，
          读起来像一条只读信息，不像一个选择器。
        -->
        <sh-kv between :label="String($t('transfer.from'))">
          <sh-go :text="nameOf(fromId) || '—'" @tap="pickEnd('from')"></sh-go>
        </sh-kv>
        <sh-kv between :label="String($t('transfer.to'))">
          <sh-go :text="nameOf(toId) || '—'" @tap="pickEnd('to')"></sh-go>
        </sh-kv>
      </view>

      <sh-empty v-if="!lines.length" :text="String($t('transfer.noLines'))"></sh-empty>

      <view v-for="l in lines" :key="l.itemId" class="sh-card sh-mb-sm">
        <view class="row__top sh-row">
          <view class="sh-fill">
            <text class="txt-strong row__title">{{ l.name }}{{ l.specText ? ` · ${l.specText}` : "" }}</text>
            <text class="sh-muted sh-num">{{ $t("transfer.availableN", { n: l.available }) }}</text>
          </view>
          <text class="sh-link sh-num qty" @tap="editQty(l)">{{ l.qty }}</text>
        </view>
      </view>

      <sh-add :text="String($t('transfer.addItem'))" @tap="showPick = true"></sh-add>

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
        :title="String($t('transfer.addItem'))"
        :items="pickable"
        :picked="lines.map((l) => l.itemId)"
        :qty-label="pickQty"
        @pick="addLine"
        @close="showPick = false"
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
  margin-top: 6rpx;
}
</style>
