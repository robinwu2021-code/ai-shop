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
import type { StockBalance, StockLocation, StockTransfer } from "@shared/types";
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
  return locations.value.find((l) => l.locationId === id)?.name ?? (id ?? "");
}

async function pickEnd(which: "from" | "to") {
  const items = choosable.value;
  const res = await pick({ items: items.map((l) => l.name) });
  if (res === null) return;
  const id = items[res]!.locationId;
  if (which === "from") fromId.value = id;
  else toId.value = id;
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
async function ship() {
  if (!lines.value.length || busy.value) return;
  if (fromId.value === toId.value) {
    uni.showToast({ title: String(t("transfer.sameEnds")), icon: "none" });
    return;
  }
  busy.value = true;
  try {
    const no = await api.mTransferCreate({
      fromLocationId: fromId.value,
      toLocationId: toId.value,
      lines: lines.value.map((l) => ({ itemId: l.itemId, qty: l.qty })),
    });
    await api.mTransferShip(no);
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
      <view class="sh-card ends">
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
        <view class="hd">
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
      </view>

      <!-- 草稿态没有行（行在发出的那张出库单上）。说成「还没发出」而不是「空单」 -->
      <sh-empty v-if="!doc.lines.length" :text="String($t('transfer.notShipped'))"></sh-empty>

      <view v-for="l in doc.lines" :key="l.itemId" class="sh-card sh-mb-sm">
        <view class="row__top">
          <view class="row__main">
            <text class="row__title">{{ l.name }}{{ l.specText ? ` · ${l.specText}` : "" }}</text>
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
          <text class="sh-num is-transit">
            {{ doc.status === "SHIPPED" ? doc.totalQty : 0 }}
          </text>
        </sh-kv>
        <text class="txt-caption">{{ $t("transfer.conserveHint") }}</text>
      </view>

      <view v-if="doc.status === 'SHIPPED'" class="sh-btn" @tap="receive">
        {{ $t("transfer.receive") }}
      </view>
    </template>

    <!-- ② 新建 -->
    <template v-else>
      <view class="sh-card">
        <sh-kv between :label="String($t('transfer.from'))">
          <text class="sh-link" @tap="pickEnd('from')">{{ nameOf(fromId) || "—" }}</text>
        </sh-kv>
        <sh-kv between :label="String($t('transfer.to'))">
          <text class="sh-link" @tap="pickEnd('to')">{{ nameOf(toId) || "—" }}</text>
        </sh-kv>
      </view>

      <sh-empty v-if="!lines.length" :text="String($t('transfer.noLines'))"></sh-empty>

      <view v-for="l in lines" :key="l.itemId" class="sh-card sh-mb-sm">
        <view class="row__top">
          <view class="row__main">
            <text class="row__title">{{ l.name }}{{ l.specText ? ` · ${l.specText}` : "" }}</text>
            <text class="sh-muted sh-num">{{ $t("transfer.availableN", { n: l.available }) }}</text>
          </view>
          <text class="sh-link sh-num qty" @tap="editQty(l)">{{ l.qty }}</text>
        </view>
      </view>

      <sh-add :text="String($t('transfer.addItem'))" @tap="showPick = true"></sh-add>

      <view v-if="lines.length" class="sh-card hd">
        <text class="txt-strong">{{ $t("transfer.totalQty") }}</text>
        <text class="txt-display sh-num">{{ totalQty }}</text>
      </view>

      <view class="sh-btn" :class="{ 'sh-btn--muted': !lines.length || busy }" @tap="ship">
        {{ $t("transfer.ship") }}
      </view>
      <text class="sh-hint hint">{{ $t("transfer.twoDocsHint") }}</text>

      <sh-sheet
        :visible="showPick"
        :title="String($t('transfer.addItem'))"
        @close="showPick = false"
      >
        <view v-for="b in pickable" :key="b.itemId" class="pick" @tap="addLine(b)">
          <text class="txt-body">{{ b.name }}{{ b.specText ? ` · ${b.specText}` : "" }}</text>
          <text class="sh-muted sh-num">{{ $t("transfer.availableN", { n: b.available }) }}</text>
        </view>
      </sh-sheet>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.ends {
  display: flex;
  align-items: center;
  gap: 20rpx;
}
.end {
  flex: 1;
}
.end > text {
  display: block;
}
.end--r {
  text-align: right;
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
}
.qty {
  min-width: 72rpx;
  text-align: right;
  flex: none;
}
.hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.hint {
  padding: 0 4rpx;
}
.is-transit {
  color: var(--sh-warning);
}
.pick {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 20rpx 0;
}
</style>
