<script setup lang="ts">
// 报损出库（B-6）。
//
// **报损与盘亏是两件事**：报损是主动的（知道坏了几个），盘亏是被动的
//（盘完才发现少了）。都落出库单，但 `reason_code` 不同，月底汇总分得开。
//
// **出库单只带成本，不带售价**：售价是销售那边的事，同一件货不同渠道价不一样，
// 写进来就有了第二个真源。
//
// 数量填不到超过可用 —— **库存不允许为负**，错误停在这里比流进报表便宜。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { StockBalance } from "@shared/types";
import { prompt } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

/** SCRAP 必填的原因。**枚举不是自由文本** —— 自由文本汇总不出这个月报损了多少 */
const REASONS = ["BROKEN", "EXPIRED", "GIFT", "OTHER"] as const;

interface Line {
  itemId: string;
  name: string;
  specText?: string;
  uom?: string;
  /** 可用量，用来卡上限 */
  available: number;
  qty: number;
}

const occurredAt = ref(today());
const reason = ref<(typeof REASONS)[number]>("EXPIRED");
const lines = ref<Line[]>([]);
const busy = ref(false);
const pickable = ref<StockBalance[]>([]);
const showPick = ref(false);

function today(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

const totalQty = computed(() => lines.value.reduce((s, l) => s + l.qty, 0));

async function load() {
  try {
    // 只给有货的：报损一件可用为 0 的货，唯一的结果是被后端拒绝
    const all = await api.mStockBalances({ filter: "all", size: 200 });
    pickable.value = all.filter((b) => b.available > 0);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

function addLine(b: StockBalance) {
  if (lines.value.some((l) => l.itemId === b.itemId)) return;
  lines.value = [...lines.value, {
    itemId: b.itemId, name: b.name, specText: b.specText, uom: b.baseUom,
    available: b.available, qty: 1,
  }];
  showPick.value = false;
}

function removeLine(itemId: string) {
  lines.value = lines.value.filter((l) => l.itemId !== itemId);
}

async function editQty(l: Line) {
  const v = await prompt({
    title: String(t("stockOut.qtyTitle", { name: l.name })),
    hint: String(t("stockOut.qtyHint", { n: l.available })),
    type: "number",
    value: String(l.qty),
  });
  if (v == null || v === "") return;
  const n = Number(v);
  if (!Number.isInteger(n) || n <= 0) {
    uni.showToast({ title: String(t("stockOut.qtyBad")), icon: "none" });
    return;
  }
  // **在这里就卡住**：错误停在录入处，比让它流到过账再被拒便宜
  if (n > l.available) {
    uni.showToast({ title: String(t("stockOut.qtyOver", { n: l.available })), icon: "none" });
    return;
  }
  l.qty = n;
}

async function post() {
  if (!lines.value.length || busy.value) return;
  busy.value = true;
  try {
    const no = await api.mOutboundCreate({
      purpose: "SCRAP",
      reasonCode: reason.value,
      occurredAt: `${occurredAt.value}T00:00:00`,
      lines: lines.value.map((l) => ({ itemId: l.itemId, qty: l.qty, uom: l.uom })),
    });
    await api.mOutboundPost(no);
    uni.showToast({ title: String(t("stockOut.posted", { no })), icon: "none" });
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
  <sh-scaffold title-key="stockOut.title" :denied="!merchant.can('biz:stock')">
    <view class="sh-card">
      <sh-kv between :label="String($t('stockOut.date'))">
        <picker mode="date" :value="occurredAt" @change="occurredAt = $event.detail.value">
          <text class="sh-link sh-num">{{ occurredAt }}</text>
        </picker>
      </sh-kv>
    </view>

    <sh-empty v-if="!lines.length" :text="String($t('stockOut.noLines'))"></sh-empty>

    <view v-for="l in lines" :key="l.itemId" class="sh-card sh-mb-sm">
      <view class="row__top sh-row">
        <view class="sh-fill">
          <text class="txt-strong row__title">{{ l.name }}{{ l.specText ? ` · ${l.specText}` : "" }}</text>
          <text class="sh-muted sh-num">{{ $t("stockOut.availableN", { n: l.available }) }}</text>
        </view>
        <text class="sh-link sh-num qty" @tap="editQty(l)">{{ l.qty }}</text>
        <text class="sh-link sh-link--quiet" @tap="removeLine(l.itemId)">
          {{ $t("common.remove") }}
        </text>
      </view>
    </view>

    <sh-add :text="String($t('stockOut.addItem'))" @tap="showPick = true"></sh-add>

    <view class="sh-card">
      <text class="field__label">{{ $t("stockOut.reasonLabel") }}</text>
      <view class="reasons sh-wrap">
        <text
          v-for="r in REASONS"
          :key="r"
          class="sh-chip"
          :class="{ 'sh-chip--primary': reason === r }"
          @tap="reason = r"
        >
          {{ $t(`stock.reason.${r}`) }}
        </text>
      </view>
    </view>

    <view v-if="lines.length" class="sh-card hd sh-row sh-row--between">
      <text class="txt-strong">{{ $t("stockOut.totalQty") }}</text>
      <text class="txt-display sh-num is-out">−{{ totalQty }}</text>
    </view>

    <view class="sh-btn" :class="{ 'sh-btn--muted': !lines.length || busy }" @tap="post">
      {{ $t("stockOut.post") }}
    </view>
    <text class="sh-hint hint">{{ $t("stockOut.costHint") }}</text>

    <sh-sheet :visible="showPick" :title="String($t('stockOut.addItem'))" @close="showPick = false">
      <view v-for="b in pickable" :key="b.itemId" class="pick sh-row sh-row--between sh-row--baseline" @tap="addLine(b)">
        <text class="txt-body">{{ b.name }}{{ b.specText ? ` · ${b.specText}` : "" }}</text>
        <text class="sh-muted sh-num">{{ $t("stockOut.availableN", { n: b.available }) }}</text>
      </view>
    </sh-sheet>
  </sh-scaffold>
</template>

<style scoped>
.row__top {
  gap: 20rpx;
}

.row__title {
  display: block;
}
.qty {
  min-width: 72rpx;
  text-align: right;
  flex: none;
}
.hint {
  padding: 0 4rpx;
}
.is-out {
  color: var(--sh-danger);
}
.pick {
  padding: 20rpx 0;
}
</style>
