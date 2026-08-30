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
import { uomLabel } from "@shared/utils/format";
import { prompt } from "@ai-shop/ui/prompt";
import type { Supplier } from "@shared/types";

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

/*
 * 供应商从**自由输入**换成**从档案里选**。
 * 名字会漂 —— 同一家三种写法，进货报表按名字聚合就成了三个供应商。
 *
 * `supplierName` 仍然发：它是**下单当时的名字快照**，供应商三个月后改名，
 * 这张历史单该显示当时那个名字。两个字段并存不是冗余。
 */
const supplier = ref<Supplier | null>(null);
const suppliers = ref<Supplier[]>([]);
const showSupplier = ref(false);
const supplierBusy = ref(false);
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
    // **挑货读物料，不读余额。** 进货恰恰是给「还没有存货的货」记第一笔，
    // 而余额行是按需建的 —— 读余额的话新货挑不到
    pickable.value = await api.mStockPickable({ size: 200 });
    // 只要在用的：停用的不该出现在新单据里（管理页才传 activeOnly=false）
    suppliers.value = await api.mSuppliers({ activeOnly: true });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 挑货弹层右侧那个数。**放 script 不放模板** —— 模板里的内联箭头推不出参数类型 */
function pickQty(b: StockBalance): string {
  return String(t("purchase.onHandN", { n: b.onHand }));
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

/**
 * 就地建档，建完直接选中 —— 商家来这儿是记进货，不该被赶去档案页再走回来。
 *
 * **重名交给后端拒（10409）**，端上不再自己判一次：两处各判一次迟早分岔，
 * 而分岔的表现是「界面说能建，建出来报错」。弹层里搜到同名时本来就不给「新建」，
 * 这里兜的是并发那一格。
 */
function onPickSupplier(s: Supplier) {
  supplier.value = s;
  showSupplier.value = false;
}

async function createSupplier(name: string) {
  if (supplierBusy.value) return;
  supplierBusy.value = true;
  try {
    const { supplierNo } = await api.mSupplierCreate({ name });
    suppliers.value = await api.mSuppliers({ activeOnly: true });
    supplier.value = suppliers.value.find((s) => s.supplierNo === supplierNo) ?? null;
    showSupplier.value = false;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    supplierBusy.value = false;
  }
}

function draftReq() {
  return {
    sourceType: "PURCHASE",
    supplierNo: supplier.value?.supplierNo,
    supplierName: supplier.value?.name,
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
      <!--
        从输入框换成选择器。**留一行显示当前选的是谁**，而不是把名字塞回输入框：
        塞回去的话它看起来还能改，而改了不会生效（真正生效的是 supplierNo）。
      -->
      <view class="field__input sup-pick sh-row sh-row--between" @tap="showSupplier = true">
        <text :class="supplier ? 'txt-body' : 'sh-muted'">
          {{ supplier ? supplier.name : $t("purchase.supplierPh") }}
        </text>
        <text class="sh-muted">{{ $t("common.change") }}</text>
      </view>
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
      <view class="row__top sh-row">
        <view class="sh-fill">
          <text class="txt-strong row__title">{{ l.name }}{{ l.specText ? ` · ${l.specText}` : "" }}</text>
          <view class="row__meta">
            <text class="sh-link sh-num" @tap="editQty(l)">
              {{ $t("purchase.qtyN", { n: l.qty, uom: uomLabel(l.uom, t) }) }}
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

    <view v-if="lines.length" class="sh-card hd sh-row sh-row--between">
      <text class="txt-strong">{{ $t("purchase.total") }}</text>
      <text class="txt-price sh-num">¥{{ yuan(totalMinor) }}</text>
    </view>

    <!--
      没有行时两个都灰：`save()` 里本来就 return，而一枚看起来能点的实心按钮
      点下去毫无反应，比没有这枚按钮更让人困惑。
      存草稿常态就是 muted —— 它是次要动作，不该与「过账」争同一个视觉重量。
    -->
    <!--
      主动作贴底。进货单的行数没有上限（进一车货三十行是常态），
      按钮跟在行列表后面的话，它会被推到很下面 —— 而它是这一页唯一的出口。
      `sh-actionbar` 同时给条和占位块，两者高度不会再对不上。
    -->
    <text class="sh-hint hint">{{ $t("purchase.postHint") }}</text>
    <sh-actionbar :pad="200">
      <view class="btns">
        <view class="sh-btn sh-btn--muted sh-fill" @tap="save(false)">{{ $t("purchase.draft") }}</view>
        <view
          class="sh-btn flex14"
          :class="{ 'sh-btn--muted': !lines.length || busy }"
          @tap="save(true)"
        >
          {{ $t("purchase.post") }}
        </view>
      </view>
    </sh-actionbar>

    <!-- 挑货走公共件：搜索、已选计数、已选置灰，四处判据一致 -->
    <biz-item-picker
      :visible="showPick"
      :title="String($t('purchase.addItem'))"
      :items="pickable"
      :picked="lines.map((l) => l.itemId)"
      :qty-label="pickQty"
      @pick="addLine"
      @close="showPick = false"
    ></biz-item-picker>

    <biz-supplier-picker
      :visible="showSupplier"
      :items="suppliers"
      :picked="supplier?.supplierNo"
      :busy="supplierBusy"
      @pick="onPickSupplier"
      @create="createSupplier"
      @close="showSupplier = false"
    ></biz-supplier-picker>
  </sh-scaffold>
</template>

<style scoped>
.row__top {
  gap: 20rpx;
}

.row__title {
  display: block;
}
.row__meta {
  display: flex;
  gap: 24rpx;
  margin-top: 8rpx;
}
.row__end {
  text-align: end;
  flex: none;
}
.row__end > text {
  display: block;
}
.btns {
  display: flex;
  gap: 20rpx;
}

.flex14 {
  flex: 1.4;
}
.hint {
  padding: 0 4rpx;
}
.pick {
  padding: 20rpx 0;
}
</style>
