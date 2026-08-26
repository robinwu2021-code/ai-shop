<script setup lang="ts">
// 出入库单据（B-5）。
//
// **销售出库也是一张单**。「直接扣库存」在这里不存在 —— 会计问「这 200 斤米
// 怎么少的」，要能点开一张单，而不是一行日志。
//
// **单据可作废，不可修改**：已过账的只能整单作废重录，作废写一行反向流水。
// 改单据等于改历史，而历史正是这些表存在的理由。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { StockDocument } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const kind = ref("");
const rows = ref<StockDocument[]>([]);
const loading = ref(false);

const TABS = computed(() => [
  { key: "", label: String(t("common.all")) },
  { key: "IN", label: String(t("stockDocs.kindIn")) },
  { key: "OUT", label: String(t("stockDocs.kindOut")) },
  { key: "COUNT", label: String(t("stockDocs.kindCount")) },
  { key: "TRANSFER", label: String(t("stockDocs.kindTransfer")) },
]);

async function load() {
  loading.value = true;
  try {
    rows.value = await api.mStockDocuments({ kind: kind.value || undefined, size: 50 });
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

/** 点开：盘点与调拨有各自的详情页，出入库单没有单独的一屏，停在列表上 */
function open(d: StockDocument) {
  if (d.kind === "COUNT") {
    uni.navigateTo({ url: `/pages/stock-check/index?no=${encodeURIComponent(d.docNo)}` });
  } else if (d.kind === "TRANSFER") {
    uni.navigateTo({ url: `/pages/transfer/index?no=${encodeURIComponent(d.docNo)}` });
  }
}

/** 「08-26 14:22」。切片不解析 —— 后端发的是不带时区的 LocalDateTime */
function at(iso?: string): string {
  return iso && iso.length >= 16 ? iso.slice(5, 16).replace("T", " ") : "";
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="stockDocs.title" :denied="!merchant.can('biz:stock')">
    <sh-tabs :items="TABS" :active="kind" @change="pick"></sh-tabs>

    <sh-empty v-if="!loading && !rows.length" :text="String($t('stockDocs.empty'))"></sh-empty>

    <view v-for="d in rows" :key="d.docNo" class="sh-card row" @tap="open(d)">
      <view class="row__top">
        <view class="row__main">
          <text class="row__title">{{ $t(`stockDocs.kind.${d.kind}`) }}</text>
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
    </view>

    <view v-if="rows.length" class="sh-card">
      <text class="txt-caption">{{ $t("stockDocs.saleHint") }}</text>
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
  align-items: flex-start;
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
.row__meta {
  display: flex;
  gap: 20rpx;
  margin-top: 6rpx;
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
