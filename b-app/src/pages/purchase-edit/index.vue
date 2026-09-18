<script setup lang="ts">
// 记一笔进货（B-4）。
//
// **供应商是随手填的一行字，不是一张档案表**：小店的供应商是微信里那个人。
// 建档案要维护、去重、合并，而商家填完一次不会再看第二眼。
//
// **存草稿与过账是两件事**：草稿不动库存，过账才动。分成两个按钮而不是
// 「保存」一个 —— 一个动库存的动作不该和「我先记一半」共用同一个词。
import { computed, ref } from "vue";
import { ROUTES } from "@/shared/nav";
import { isoDay } from "@/shared/quick-dates";
import { STORAGE } from "@shared/utils/constants";
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
const occurredAt = ref(isoDay());
const lines = ref<Line[]>([]);
const busy = ref(false);

/** 可选的货。进货是「已经有这件货」才谈得上，所以从库存里挑 */
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

const totalMinor = computed(() =>
  lines.value.reduce((s, l) => s + l.qty * l.unitCostMinor, 0),
);


/** 分 → 元。展示用，**不参与计算** */
function yuan(minor: number): string {
  return (minor / 100).toFixed(2);
}

/** 这次没取到。**与「确定为空」是两件事** —— 空的选择器与「一件都没有」长得一样 */
const failed = ref(false);

async function load() {
  try {
    // **挑货读物料，不读余额。** 进货恰恰是给「还没有存货的货」记第一笔，
    // 而余额行是按需建的 —— 读余额的话新货挑不到
    pickable.value = await api.mStockPickable({ size: 200 });
    // 只要在用的：停用的不该出现在新单据里（管理页才传 activeOnly=false）
    suppliers.value = await api.mSuppliers({ activeOnly: true });
    pickDefaultSupplier();
    failed.value = false;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
    // 挑货弹层里空数组既是「一件货都没有」也是「这次没取到」——
    // 前者该去建货，后者该重试
    failed.value = true;
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
/**
 * 开单时给供应商一个**默认值**（2026-09-17 店主提的）。
 *
 * <p>小店多半固定从一两家进货，每开一张单都从空白重选一遍是重复劳动。
 * 顺序：<b>上次用的 → 列表第一个 → 留空</b>。
 *
 * <p><b>上次用的那个要去列表里核一次</b>，不能直接拿来显示：它可能已经停用
 * （列表只取 activeOnly）或被删。核不到就退回第一个 ——
 * 把一个不存在的名字摆在那儿，比留空更糟：他会以为这单记在那家头上。
 *
 * <p><b>一件供应商都还没建时保持空白</b>：那时「默认」无从谈起，
 * 而空白配上「请选择」正好把他推去建第一家。
 *
 * <p>只在还没选过时填 —— 已经选过就别覆盖他的选择。
 */
function pickDefaultSupplier() {
  if (supplier.value || !suppliers.value.length) return;
  let last: string | null = null;
  try {
    last = uni.getStorageSync(STORAGE.lastSupplierNo) || null;
  } catch {
    // 读不到不是错：隐私模式、清过数据都会这样，退回列表第一个就行
    last = null;
  }
  supplier.value = suppliers.value.find((s) => s.supplierNo === last) ?? suppliers.value[0] ?? null;
}

function onPickSupplier(s: Supplier) {
  supplier.value = s;
  showSupplier.value = false;
  // 记成下次的默认值。**写失败不该打断开单** —— 它只是个习惯，不是数据
  try {
    uni.setStorageSync(STORAGE.lastSupplierNo, s.supplierNo);
  } catch {
    /* 存不了就下次再从列表第一个开始，没有任何后果 */
  }
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
    <!--
      ★ **供应商与日期合成一张卡，标题挪到行首**（2026-09-18 店主：
      「供应商和发生日期占用空间过多，考虑标题和空间放到同一行」）。

      改之前两张卡、每个标题独占一行，两个字段吃掉近 240px ——
      而这一屏真正的主角是下面那张货单。标题左、控件右是全站
      「一行一个字段」的既有写法（sh-kv），这里不新造件。

      「发生日期」缩成「日期」：四枚快捷钮要和标题挤在同一行里，
      少两个字正好把余量留出来（量过：263 → 291px 可用，四枚需 262px）。
    -->
    <view class="sh-card">
      <sh-kv between :label="String($t('purchase.supplier'))" @tap="showSupplier = true">
        <!--
          右边是「›」而不是「更换」：全站「点进去选」的行都用这个记号，
          不用读字就知道它会弹东西。写「更换」的毛病是**还没选过时它是句空话** ——
          于是这一行读起来像个能打字的框（2026-09-17 店主：「供应商要选择列表」）。
        -->
        <view class="sup sh-row">
          <text :class="supplier ? 'txt-body' : 'sh-muted'">
            {{ supplier ? supplier.name : $t("purchase.supplierPh") }}
          </text>
          <text class="sh-muted">›</text>
        </view>
      </sh-kv>

      <!--
        ★ **日期直接显示，不再给快捷钮**（2026-09-18 店主：「日期去掉今天、昨天、
        前天，就直接显示日期信息即可，只要容易点击即可」）。

        上一版是三枚快捷 + 兜底滚轮。店主要的是更简单的东西：这一行多数时候
        不用动，看一眼就够；真要改时有一个**好点的**目标就行。
        所以整块 88rpx（44px，可点下限）、日期用等宽数字排，右边「›」说明它点得开。
      -->
      <sh-kv between :label="String($t('purchase.date'))">
        <picker mode="date" :value="occurredAt" @change="occurredAt = $event.detail.value">
          <view class="date sh-row">
            <text class="txt-body sh-num">{{ occurredAt }}</text>
            <text class="sh-muted">›</text>
          </view>
        </picker>
      </sh-kv>
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

    <view class="addrow sh-row">
      <sh-add class="sh-fill" :text="String($t('purchase.addItem'))" @tap="openPick(false)"></sh-add>
      <!--
        扫码与「添加商品」并排：**它们是同一件事的两种做法**（找到那件货）。
        藏进弹层的话商家会以为扫码是另一个功能，而且每次要多点一下。
      -->
      <view class="scan sh-center" @tap="openPick(true)">
        <sh-icon name="scan" :size="26" color="var(--sh-on-primary)"></sh-icon>
      </view>
    </view>

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
          class="sh-btn sh-fill"
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
      :auto-scan="autoScan"
      :title="String($t('purchase.addItem'))"
      :items="pickable"
      :failed="failed"
      @retry="load"
      :picked="lines.map((l) => l.itemId)"
      :qty-label="pickQty"
      @pick="addLine"
      @close="showPick = false; autoScan = false"
      @create="createWithBarcode"
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

/* 44px：整块是可点目标，不是一行字。「›」与日期之间留一点，别贴着 */
.date {
  min-height: 88rpx;
  gap: 8rpx;
  align-items: center;
}
/* 供应商名与「›」之间留一点，别贴着 */
.sup {
  gap: 8rpx;
}
.hint {
  padding: 0 4rpx;
}
.pick {
  padding: 20rpx 0;
}
</style>
