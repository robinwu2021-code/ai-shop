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
import { confirm } from "@ai-shop/ui/prompt";

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
  const p = (q as Record<string, string>) ?? {};
  onlyNo.value = String(p.no ?? "");
  // 带 kind 进来的（库存页点「在途」→ 调拨）要**真的落到那个页签上**。
  // 只跳不筛的话人会以为筛坏了 —— 而它不报错，列表照常显示全部
  if (p.kind && TABS.value.some((t) => t.key === p.kind)) kind.value = String(p.kind);
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
/**
 * 单据状态 → 库里的 chip 变体。**与调拨页同一套**（那边 SHIPPED 用 warning、
 * 到货用 primary）—— 此前这里是页面自己的四个 `.is-*` 裸色文字类，
 * 于是同一个「单据状态」在两个页面上长得完全不一样。
 *
 * 草稿与作废都用「不着色」的默认 chip：它们不是要引起注意的态，
 * 只是「还没生效」和「已经没了」。作废另外保留删除线（见样式）。
 */
function stateChip(status: string): string {
  if (status === "SHIPPED" || status === "COUNTING") return "sh-chip--warning";
  if (status === "VOIDED") return "is-void";
  if (status === "DRAFT") return "";
  return "sh-chip--primary";
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

/**
 * 能不能作废这一张。
 *
 * - **出入库单**：草稿与已过账都能 —— 已过账的作废会写一行反向流水；
 * - **调拨单**：**只有还没发出的**。已发出的货正停在在途库位上，把它弄回去是
 *   「退回」不是作废，得再走一遍成对的一出一入 —— 在这里画一个点了报错的按钮
 *   比没有按钮更糟；
 * - **盘点单**：后端没有作废端点，它有自己的口子。
 *
 * 已作废的不再给：作废是幂等的，但按钮还在会让人以为没生效。
 */
function canVoid(d: StockDocument): boolean {
  if (d.status === "VOIDED") return false;
  if (d.kind === "IN" || d.kind === "OUT") return true;
  return d.kind === "TRANSFER" && d.status === "DRAFT";
}

/**
 * 作废。**这是「录错了怎么办」的唯一答案** —— 单据不可修改，
 * 已过账的只能整单作废重录（作废会写一行反向流水，库存回到这张单之前）。
 * 端点 08 月就做好了，而界面上一直没有入口，商家录错一张就卡死在那儿。
 */
async function voidDoc(d: StockDocument) {
  const posted = d.status === "POSTED";
  const ok = await confirm({
    title: String(t("stockDocs.voidTitle")),
    // 过账与草稿的后果不一样，别用同一句话糊过去：
    // 草稿本来就没动库存，说「库存会退回」是吓人
    hint: String(t(posted ? "stockDocs.voidHintPosted" : "stockDocs.voidHintDraft", { no: d.docNo })),
    confirmText: String(t("stockDocs.voidConfirm")),
    danger: true,
  });
  if (!ok) return;
  try {
    await (d.kind === "IN" ? api.mInboundVoid(d.docNo)
      : d.kind === "TRANSFER" ? api.mTransferVoid(d.docNo)
        : api.mOutboundVoid(d.docNo));
    uni.showToast({ title: String(t("stockDocs.voided")), icon: "none" });
    openedNo.value = "";
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 回边：从一张单走到它动过的那件货 */
function openItem(r: StockLedgerRow) {
  uni.navigateTo({ url: `/pages/stock-detail/index?itemId=${encodeURIComponent(r.itemId)}` });
}

/** 「08-26 14:22」。切片不解析 —— 后端发的是不带时区的 LocalDateTime */
/**
 * 单据上那个码的文案。**查不到就原样显示** —— 后端加了新取值而端上没跟时，
 * 商家看到 `RETURN_SUPPLIER` 是难看，但比显示空白强：空白会让人以为这张单没有类型。
 */
function docLabel(d: StockDocument): string {
  if (!d.label) return "";
  const key = `stockDocs.label.${d.label}`;
  const got = t(key);
  return got && got !== key ? got : d.label;
}

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

    <view v-for="d in rows" :key="d.docNo" class="sh-card">
      <view class="row__top" @tap="open(d)">
        <view class="sh-fill">
          <!--
            状态紧挨着类型，而不是压在右边的数量下面：它修饰的是**这张单**，
            不是那个数。原来叠在右上角时，右列一小块里挤了两种颜色
            （数量红/绿 + 状态灰/橙），而卡片右下角空着一大片。
            用 `sh-chip` 而不是裸彩色文字，与调拨页的单据状态**同一种画法** ——
            此前同一个「单据状态」两个页面各画各的。
          -->
          <view class="row__head sh-row">
            <text class="txt-strong row__title">{{ $t(`stockDocs.kind.${d.kind}`) }}</text>
            <text class="sh-chip" :class="stateChip(d.status)">
              {{ $t(`stockDocs.status.${d.status}`) }}
            </text>
          </view>
          <view class="row__meta">
            <text class="sh-muted sh-num">{{ d.docNo }}</text>
            <text v-if="d.operator" class="sh-muted">{{ d.operator }}</text>
          </view>
          <text class="txt-caption">
            <!--
              码与自由文本分开：`label` 查 i18n，`subtitle` 直接显示。
              由后端拼成一句的话，枚举会原样漏到商家眼前 —— 此前正是这样。
            -->
            {{ [docLabel(d), d.subtitle, at(d.occurredAt)].filter(Boolean).join(" · ") }}
          </text>
        </view>
        <text class="txt-strong sh-num row__qty" :class="d.totalQty < 0 ? 'is-danger' : 'is-success'">
          {{ d.totalQty > 0 ? `+${d.totalQty}` : d.totalQty }}
        </text>
      </view>

      <!--
        就地展开的单行。**每一行可点** —— 这是「单据 → 库存明细」那条回边：
        看到一张单动了 3 件货，直接走到其中一件的库存明细。
      -->
      <view v-if="openedNo === d.docNo" class="lines">
        <text class="txt-caption lines__head">{{ $t("stockDocs.lines") }}</text>
        <text v-if="linesLoading" class="sh-muted">…</text>
        <text v-else-if="!lines.length" class="sh-muted">{{ $t("stockDocs.linesEmpty") }}</text>
        <view v-for="r in lines" :key="r.id" class="line sh-row" @tap="openItem(r)">
          <text class="sh-fill">{{ r.itemName }}</text>
          <text class="sh-num" :class="r.qtyDelta < 0 ? 'is-danger' : 'is-success'">
            {{ r.qtyDelta > 0 ? `+${r.qtyDelta}` : r.qtyDelta }}
          </text>
          <sh-icon name="chevronRight" :size="18" color="var(--sh-sub)"></sh-icon>
        </view>

        <!--
          作废放在展开区里，不放在行上：**得先看见这张单动了哪几件货再决定**。
          放在列表行上，一次误触就是一笔反向流水。
        -->
        <!-- 用原档不另造 `--danger`：这套皮肤的主色本来就是红，再加一个红档
             区分不出来。危险感由确认框的 `danger: true` 承担（红实心确定键）。 -->
        <text v-if="canVoid(d)" class="sh-link voidbtn" @tap="voidDoc(d)">
          {{ $t("stockDocs.voidAction") }}
        </text>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>

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
  padding: 12rpx 0;
}
.voidbtn {
  display: block;
  text-align: center;
  padding: 16rpx 0 4rpx;
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
.row__head {
  margin-bottom: 4rpx;
}
/* 数量单独占右侧一列，不再与状态叠在一起 */
.row__qty {
  flex: none;
}
/* 作废：chip 不着色，但把字划掉 —— 「已经没了」比「注意我」更要紧 */
.is-void {
  text-decoration: line-through;
}
</style>
