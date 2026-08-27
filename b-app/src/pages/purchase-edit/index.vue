<script setup lang="ts">
// 记一笔进货（B-4）。
//
// **供应商是随手填的一行字，不是一张档案表**：小店的供应商是微信里那个人。
// 建档案要维护、去重、合并，而商家填完一次不会再看第二眼。
//
// **存草稿与过账是两件事**：草稿不动库存，过账才动。分成两个按钮而不是
// 「保存」一个 —— 一个动库存的动作不该和「我先记一半」共用同一个词。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { StockBalance } from "@shared/types";
import { prompt } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

interface Line {
  itemId: string;
  name: string;
  specText?: string;
  uom?: string;
  qty: number;
  /** 进价（分）。**最小货币单位整数** —— 用元存迟早会出现 0.1 + 0.2 */
  unitCostMinor: number;
}

const supplier = ref("");
const occurredAt = ref(today());
const lines = ref<Line[]>([]);
const busy = ref(false);

/** 可选的货。进货是「已经有这件货」才谈得上，所以从库存里挑 */
const pickable = ref<StockBalance[]>([]);
const showPick = ref(false);

const totalMinor = computed(() =>
  lines.value.reduce((s, l) => s + l.qty * l.unitCostMinor, 0),
);

function today(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** 分 → 元。展示用，**不参与计算** */
function yuan(minor: number): string {
  return (minor / 100).toFixed(2);
}

async function load() {
  try {
    pickable.value = await api.mStockBalances({ filter: "all", size: 200 });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

function addLine(b: StockBalance) {
  if (lines.value.some((l) => l.itemId === b.itemId)) return;
  lines.value = [...lines.value, {
    itemId: b.itemId, name: b.name, specText: b.specText, uom: b.baseUom,
    qty: 1, unitCostMinor: 0,
  }];
  showPick.value = false;
}

function removeLine(itemId: string) {
  lines.value = lines.value.filter((l) => l.itemId !== itemId);
}

/** 改数量。整数键盘 —— 进货按件按袋，不按半件 */
async function editQty(l: Line) {
  const v = await prompt({
    title: String(t("purchase.qtyTitle", { name: l.name })),
    type: "number",
    value: String(l.qty),
  });
  if (v == null || v === "") return;
  const n = Number(v);
  if (!Number.isInteger(n) || n <= 0) {
    uni.showToast({ title: String(t("purchase.qtyBad")), icon: "none" });
    return;
  }
  l.qty = n;
}

/** 改进价。**带小数点**（`digit`）—— 价格是元角分 */
async function editCost(l: Line) {
  const v = await prompt({
    title: String(t("purchase.costTitle", { name: l.name })),
    type: "digit",
    value: l.unitCostMinor ? yuan(l.unitCostMinor) : "",
  });
  if (v == null || v === "") return;
  const n = Number(v);
  if (!Number.isFinite(n) || n < 0) {
    uni.showToast({ title: String(t("purchase.costBad")), icon: "none" });
    return;
  }
  // 四舍五入到分：浮点乘 100 会出现 4199.999…
  l.unitCostMinor = Math.round(n * 100);
}

function draftReq() {
  return {
    sourceType: "PURCHASE",
    supplierName: supplier.value || undefined,
    occurredAt: `${occurredAt.value}T00:00:00`,
    lines: lines.value.map((l) => ({
      itemId: l.itemId, qty: l.qty, uom: l.uom, unitCostMinor: l.unitCostMinor,
    })),
  };
}

async function save(post: boolean) {
  if (!lines.value.length || busy.value) return;
  busy.value = true;
  try {
    const no = await api.mInboundCreate(draftReq());
    if (post) await api.mInboundPost(no);
    uni.showToast({
      title: String(post ? t("purchase.posted", { no }) : t("purchase.drafted", { no })),
      icon: "none",
    });
    uni.navigateBack();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="purchase.title" :denied="!merchant.can('biz:stock')">
    <view class="sh-card">
      <text class="field__label">{{ $t("purchase.supplier") }}</text>
      <input maxlength="64" v-model="supplier" class="field__input" :placeholder="String($t('purchase.supplierPh'))" />
      <text class="sh-hint">{{ $t("purchase.supplierHint") }}</text>
    </view>

    <view class="sh-card">
      <sh-kv between :label="String($t('purchase.date'))">
        <picker mode="date" :value="occurredAt" @change="occurredAt = $event.detail.value">
          <text class="sh-link sh-num">{{ occurredAt }}</text>
        </picker>
      </sh-kv>
    </view>

    <sh-empty v-if="!lines.length" :text="String($t('purchase.noLines'))"></sh-empty>

    <view v-for="l in lines" :key="l.itemId" class="sh-card sh-mb-sm">
      <view class="row__top">
        <view class="row__main">
          <text class="row__title">{{ l.name }}{{ l.specText ? ` · ${l.specText}` : "" }}</text>
          <view class="row__meta">
            <text class="sh-link sh-num" @tap="editQty(l)">
              {{ $t("purchase.qtyN", { n: l.qty, uom: l.uom || "" }) }}
            </text>
            <text class="sh-link sh-num" @tap="editCost(l)">
              ¥{{ yuan(l.unitCostMinor) }}
            </text>
          </view>
        </view>
        <view class="row__end">
          <text class="txt-strong sh-num">¥{{ yuan(l.qty * l.unitCostMinor) }}</text>
          <text class="sh-link sh-link--quiet" @tap="removeLine(l.itemId)">
            {{ $t("common.remove") }}
          </text>
        </view>
      </view>
    </view>

    <sh-add :text="String($t('purchase.addItem'))" @tap="showPick = true"></sh-add>

    <view v-if="lines.length" class="sh-card hd">
      <text class="txt-strong">{{ $t("purchase.total") }}</text>
      <text class="txt-price sh-num">¥{{ yuan(totalMinor) }}</text>
    </view>

    <!--
      没有行时两个都灰：`save()` 里本来就 return，而一枚看起来能点的实心按钮
      点下去毫无反应，比没有这枚按钮更让人困惑。
      存草稿常态就是 muted —— 它是次要动作，不该与「过账」争同一个视觉重量。
    -->
    <view class="btns">
      <view class="sh-btn sh-btn--muted flex1" @tap="save(false)">{{ $t("purchase.draft") }}</view>
      <view
        class="sh-btn flex14"
        :class="{ 'sh-btn--muted': !lines.length || busy }"
        @tap="save(true)"
      >
        {{ $t("purchase.post") }}
      </view>
    </view>
    <text class="sh-hint hint">{{ $t("purchase.postHint") }}</text>

    <sh-sheet :visible="showPick" :title="String($t('purchase.addItem'))" @close="showPick = false">
      <view v-for="b in pickable" :key="b.itemId" class="pick" @tap="addLine(b)">
        <text class="txt-body">{{ b.name }}{{ b.specText ? ` · ${b.specText}` : "" }}</text>
        <text class="sh-muted sh-num">{{ $t("purchase.onHandN", { n: b.onHand }) }}</text>
      </view>
    </sh-sheet>
  </sh-scaffold>
</template>

<style scoped>

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
.row__meta {
  display: flex;
  gap: 24rpx;
  margin-top: 8rpx;
}
.row__end {
  text-align: right;
  flex: none;
}
.row__end > text {
  display: block;
}
.hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.btns {
  display: flex;
  gap: 20rpx;
}
.flex1 {
  flex: 1;
}
.flex14 {
  flex: 1.4;
}
.hint {
  padding: 0 4rpx;
}
.pick {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 20rpx 0;
}
</style>
