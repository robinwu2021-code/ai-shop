<script setup lang="ts">
// 出入库单据（B-5）。
//
// **销售出库也是一张单**。「直接扣库存」在这里不存在 —— 会计问「这 200 斤米
// 怎么少的」，要能点开一张单，而不是一行日志。
//
// **单据可作废，不可修改**：已过账的只能整单作废重录，作废写一行反向流水。
// 改单据等于改历史，而历史正是这些表存在的理由。
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { StockDocument, StockLedgerRow } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const kind = ref("");
const rows = ref<StockDocument[]>([]);
const loading = ref(false);

/**
 * 单号定位。从库存明细的某一行点「看这张单」过来时带上 —— **回边的落点**：
 * 此前从一件货的流水看到单号，只能记住它再回列表里翻。
 */
const onlyNo = ref("");

/**
 * 展开的那张单的行。**行就是台账** —— 单据的明细本来就存在流水里，
 * 没必要再开一屏；点开在原地展开，每一行还能再点进那件货。
 */
const openedNo = ref("");
const lines = ref<StockLedgerRow[]>([]);
const linesLoading = ref(false);

const TABS = computed(() => [
  { key: "", label: String(t("common.all")) },
  { key: "IN", label: String(t("stockDocs.kindIn")) },
  { key: "OUT", label: String(t("stockDocs.kindOut")) },
  { key: "COUNT", label: String(t("stockDocs.kindCount")) },
  { key: "TRANSFER", label: String(t("stockDocs.kindTransfer")) },
]);

onLoad((q) => {
  onlyNo.value = String((q as Record<string, string>)?.no ?? "");
});

async function load() {
  loading.value = true;
  try {
    rows.value = await api.mStockDocuments({
      kind: onlyNo.value ? undefined : kind.value || undefined,
      no: onlyNo.value || undefined,
      size: 50,
    });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}

function pick(k: string) {
  kind.value = k;
  void load();
}

/** 退出单号定位，回到整张列表 */
function showAll() {
  onlyNo.value = "";
  openedNo.value = "";
  void load();
}

/**
 * 状态的色。**已过账用中性色而不是绿色** —— 绿在这一列会读成「好」，
 * 而已过账只是「生效了」；真正要人看一眼的是草稿（还没生效）与在途（还没到）。
 */
function stateClass(status: string): string {
  if (status === "DRAFT") return "is-draft";
  if (status === "SHIPPED") return "is-transit";
  if (status === "VOIDED") return "is-void";
  return "is-done";
}

/**
 * 点开。盘点与调拨有各自的详情页；出入库单**就地展开**它动了哪几件货 ——
 * 那些行在台账里已经有了，为它们再开一屏只是多一层。
 */
async function open(d: StockDocument) {
  if (d.kind === "COUNT") {
    uni.navigateTo({ url: `/pages/stock-check/index?no=${encodeURIComponent(d.docNo)}` });
    return;
  }
  if (d.kind === "TRANSFER") {
    uni.navigateTo({ url: `/pages/transfer/index?no=${encodeURIComponent(d.docNo)}` });
    return;
  }
  if (openedNo.value === d.docNo) {
    openedNo.value = "";
    return;
  }
  openedNo.value = d.docNo;
  lines.value = [];
  linesLoading.value = true;
  try {
    const page = await api.mStockLedger({ docNo: d.docNo, size: 50 });
    lines.value = page.entries;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    linesLoading.value = false;
  }
}

/** 回边：从一张单走到它动过的那件货 */
function openItem(r: StockLedgerRow) {
  uni.navigateTo({ url: `/pages/stock-detail/index?itemId=${encodeURIComponent(r.itemId)}` });
}

/** 「08-26 14:22」。切片不解析 —— 后端发的是不带时区的 LocalDateTime */
function at(iso?: string): string {
  return iso && iso.length >= 16 ? iso.slice(5, 16).replace("T", " ") : "";
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="stockDocs.title" :denied="!merchant.can('biz:stock')">
    <!-- 单号定位时不给分类页签：此刻只有一张单，四个页签点了都一样 -->
    <view v-if="onlyNo" class="sh-card sh-row sh-row--between only">
      <text class="txt-strong sh-num">{{ $t("stockDocs.onlyOne", { no: onlyNo }) }}</text>
      <text class="sh-link" @tap="showAll">{{ $t("stockDocs.showAll") }}</text>
    </view>
    <template v-else>
      <sh-tabs :items="TABS" :active="kind" @change="pick"></sh-tabs>
      <!-- 说清楚这一屏的分工，以及另一半在哪 —— 两个入口都讲变动，不说的话人得试 -->
      <text class="sh-hint hint">{{ $t("stockDocs.hint") }}</text>
    </template>

    <sh-empty v-if="!loading && !rows.length" :text="String($t('stockDocs.empty'))"></sh-empty>

    <view v-for="d in rows" :key="d.docNo" class="sh-card sh-mb-sm">
      <view class="row__top" @tap="open(d)">
        <view class="sh-fill">
          <text class="txt-strong row__title">{{ $t(`stockDocs.kind.${d.kind}`) }}</text>
          <view class="row__meta">
            <text class="sh-muted sh-num">{{ d.docNo }}</text>
            <text v-if="d.operator" class="sh-muted">{{ d.operator }}</text>
          </view>
          <text class="txt-caption">
            {{ d.subtitle ? `${d.subtitle} · ` : "" }}{{ at(d.occurredAt) }}
          </text>
        </view>
        <view class="row__end">
          <text class="txt-strong sh-num" :class="d.totalQty < 0 ? 'is-out' : 'is-in'">
            {{ d.totalQty > 0 ? `+${d.totalQty}` : d.totalQty }}
          </text>
          <text class="txt-caption" :class="stateClass(d.status)">
            {{ $t(`stockDocs.status.${d.status}`) }}
          </text>
        </view>
      </view>

      <!--
        就地展开的单行。**每一行可点** —— 这是「按单查 → 按货查」那条回边：
        看到一张单动了 3 件货，直接走到其中一件的库存明细。
      -->
      <view v-if="openedNo === d.docNo" class="lines">
        <text class="txt-caption lines__head">{{ $t("stockDocs.lines") }}</text>
        <text v-if="linesLoading" class="sh-muted">…</text>
        <text v-else-if="!lines.length" class="sh-muted">{{ $t("stockDocs.linesEmpty") }}</text>
        <view v-for="r in lines" :key="r.id" class="line" @tap="openItem(r)">
          <text class="sh-fill">{{ r.itemName }}</text>
          <text class="sh-num" :class="r.qtyDelta < 0 ? 'is-out' : 'is-in'">
            {{ r.qtyDelta > 0 ? `+${r.qtyDelta}` : r.qtyDelta }}
          </text>
          <sh-icon name="chevronRight" :size="18" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.only {
  margin-bottom: 16rpx;
}
.hint {
  display: block;
  padding: 0 26rpx 12rpx;
}
.lines {
  margin-top: 16rpx;
  padding-top: 12rpx;
  border-top: var(--sh-hairline-soft);
}
.lines__head {
  display: block;
  margin-bottom: 4rpx;
}
.line {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 12rpx 0;
}
.line + .line {
  border-top: var(--sh-hairline-soft);
}
.row__top {
  display: flex;
  gap: 20rpx;
  align-items: flex-start;
}

.row__title {
  display: block;
}
.row__meta {
  display: flex;
  gap: 20rpx;
  margin-top: 8rpx;
}
.row__end {
  text-align: right;
  flex: none;
}
.row__end > text {
  display: block;
}
.is-in {
  color: var(--sh-success);
}
.is-out {
  color: var(--sh-danger);
}
.is-draft {
  color: var(--sh-sub);
}
.is-transit {
  color: var(--sh-warning);
}
.is-void {
  color: var(--sh-sub);
  text-decoration: line-through;
}
.is-done {
  color: var(--sh-sub);
}
</style>
