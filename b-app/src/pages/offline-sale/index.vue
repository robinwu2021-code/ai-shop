<script setup lang="ts">
// 线下卖出（TDD-商品纳入进销存开关 §5.2，第三期）。
//
// 柜台把货卖掉了，账上要减 —— 否则线上还按原来的实存放货，**同一袋米会被再卖一次**。
// 记一笔 = 一张出库单当场过账；线上可卖随后按本店规则自动降下来。
//
// **不问价、不收款**：线下收银是另一件事，这一页只回答「货少了几件」。
// 写进来就有了第二个销售真源，而两个数不一样时没人知道该信哪个。
//
// 撤销**不删单**：开一张退回入库单把货加回去，原单留着并标「已撤销」——
// 删掉等于账上从没发生过，对不上账时无从查起。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { confirm, prompt } from "@ai-shop/ui/prompt";
import type { OfflineSaleRow, StockBalance } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

interface Line {
  skuNo: string;
  /** 挑货弹层按物料号认「已选」，与 skuNo 各有各的用处 */
  itemId: string;
  name: string;
  specText?: string;
  /** 可用量，用来卡上限 —— 库存不允许为负，错误停在录入处比流到过账便宜 */
  available: number;
  qty: number;
}

const storeNo = computed(() => merchant.storeNo);
const lines = ref<Line[]>([]);
const rows = ref<OfflineSaleRow[]>([]);
const pickable = ref<StockBalance[]>([]);
const showPick = ref(false);
const autoScan = ref(false);
const busy = ref(false);
const loaded = ref(false);
const failed = ref(false);

const totalQty = computed(() => lines.value.reduce((s, l) => s + l.qty, 0));

async function load() {
  if (!storeNo.value) return;
  try {
    // 只给有货的：卖一件可用为 0 的货，唯一的结果是被后端拒绝
    const all = await api.mStockBalances({ filter: "all", size: 200 });
    pickable.value = all.filter((b) => b.available > 0 && b.skuNo);
    rows.value = await api.mOfflineSales(storeNo.value);
    failed.value = false;
  } catch {
    failed.value = true;
  } finally {
    loaded.value = true;
  }
}

function openPick(scan: boolean) {
  autoScan.value = scan;
  showPick.value = true;
}

function qtyLabel(b: StockBalance): string {
  return String(t("offlineSale.availableN", { n: b.available }));
}

function addLine(b: StockBalance) {
  if (!b.skuNo || lines.value.some((l) => l.skuNo === b.skuNo)) return;
  lines.value = [...lines.value, {
    skuNo: b.skuNo, itemId: b.itemId, name: b.name, specText: b.specText, available: b.available, qty: 1,
  }];
  showPick.value = false;
}

function removeLine(skuNo: string) {
  lines.value = lines.value.filter((l) => l.skuNo !== skuNo);
}

async function editQty(l: Line) {
  const v = await prompt({
    title: String(t("offlineSale.qtyTitle", { name: l.name })),
    hint: String(t("offlineSale.qtyHint", { n: l.available })),
    type: "number",
    value: String(l.qty),
  });
  if (v == null || v === "") return;
  const n = Number(v);
  if (!Number.isInteger(n) || n <= 0) {
    uni.showToast({ title: String(t("offlineSale.qtyBad")), icon: "none" });
    return;
  }
  if (n > l.available) {
    uni.showToast({ title: String(t("offlineSale.qtyOver", { n: l.available })), icon: "none" });
    return;
  }
  l.qty = n;
}

async function submit() {
  if (!storeNo.value || !lines.value.length || busy.value) return;
  busy.value = true;
  try {
    await api.mOfflineSell(storeNo.value, lines.value.map((l) => ({ skuNo: l.skuNo, qty: l.qty })));
    lines.value = [];
    uni.showToast({ title: String(t("offlineSale.done")), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

async function revoke(row: OfflineSaleRow) {
  if (!storeNo.value || row.revoked || busy.value) return;
  const ok = await confirm({
    title: String(t("offlineSale.revokeTitle")),
    hint: String(t("offlineSale.revokeHint", { n: row.totalQty })),
  });
  if (!ok) return;
  busy.value = true;
  try {
    await api.mOfflineSaleRevoke(storeNo.value, row.docNo);
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

function itemsText(row: OfflineSaleRow): string {
  return row.items.map((i) => `${i.title}${i.spec ? ` · ${i.spec}` : ""} ×${i.qty}`).join("，");
}

function timeText(row: OfflineSaleRow): string {
  return row.occurredAt.slice(11, 16);
}

onShow(() => {
  // 标题栏的门店名要门店列表；冷启动时没人拉就只剩标题四个字
  void merchant.ensureStores();
  void load();
});
</script>

<template>
  <sh-scaffold
    title-key="offlineSale.title"
    :title-suffix="merchant.multiStore ? merchant.currentStore?.name : ''"
    :denied="!merchant.can('biz:stock')"
    :failed="failed"
    @retry="load"
  >
    <!-- 卖的是**这家店**的货：多店店主在另一家店上记完，没有任何症状 -->
    <biz-store-tag readonly></biz-store-tag>

    <view v-for="l in lines" :key="l.skuNo" class="sh-card sh-mb-sm">
      <view class="row__top sh-row">
        <view class="sh-fill">
          <text class="txt-strong row__title">{{ l.name }}{{ l.specText ? ` · ${l.specText}` : "" }}</text>
          <text class="sh-muted sh-num">{{ $t("offlineSale.availableN", { n: l.available }) }}</text>
        </view>
        <text class="sh-link sh-num qty" @tap="editQty(l)">{{ l.qty }}</text>
        <text class="sh-link sh-link--quiet" @tap="removeLine(l.skuNo)">{{ $t("common.remove") }}</text>
      </view>
    </view>

    <view class="addrow sh-row">
      <sh-add class="sh-fill" :text="String($t('offlineSale.addItem'))" @tap="openPick(false)"></sh-add>
      <!-- 扫码与「添加商品」并排：柜台最快的一条路是拿起货扫一下 -->
      <view class="scan sh-center" @tap="openPick(true)">
        <sh-icon name="scan" :size="26" color="var(--sh-on-primary)"></sh-icon>
      </view>
    </view>

    <view v-if="lines.length" class="sh-card sh-row sh-row--between">
      <text class="txt-strong">{{ $t("offlineSale.totalQty") }}</text>
      <text class="txt-display sh-num is-danger">−{{ totalQty }}</text>
    </view>

    <!-- 当天已记的：柜台一天记很多笔，记错的那笔要能当场找回来撤掉 -->
    <view class="sh-card sh-mt-md">
      <sh-section :title="String($t('offlineSale.today'))"></sh-section>
      <sh-empty v-if="!rows.length" :pending="!loaded" :text="String($t('offlineSale.empty'))"></sh-empty>
      <view v-for="r in rows" :key="r.docNo" class="line">
        <view class="sh-row sh-row--between">
          <text class="txt-body sh-fill">{{ itemsText(r) }}</text>
          <text v-if="r.revoked" class="txt-caption sh-muted">{{ $t("offlineSale.revoked") }}</text>
          <text v-else class="txt-caption sh-link" @tap="revoke(r)">{{ $t("offlineSale.revoke") }}</text>
        </view>
        <text class="txt-caption sh-muted sh-num">{{ timeText(r) }} · {{ $t("offlineSale.qtyN", { n: r.totalQty }) }}</text>
      </view>
      <text class="txt-caption sh-muted">{{ $t("offlineSale.hint") }}</text>
    </view>

    <sh-actionbar :pad="180">
      <view class="sh-btn" :class="{ 'sh-btn--muted': !lines.length || busy }" @tap="submit">
        {{ $t("offlineSale.submit") }}
      </view>
    </sh-actionbar>

    <biz-item-picker
      :visible="showPick"
      :auto-scan="autoScan"
      :title="String($t('offlineSale.addItem'))"
      :items="pickable"
      :failed="failed"
      @retry="load"
      :picked="lines.map((l) => l.itemId)"
      :qty-label="qtyLabel"
      @pick="addLine"
      @close="showPick = false; autoScan = false"
    ></biz-item-picker>
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
  min-width: 80rpx;
  text-align: right;
}
.addrow {
  gap: 16rpx;
  align-items: stretch;
}
.scan {
  flex: none;
  width: 88rpx;
  height: 88rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
}
.line {
  padding: 20rpx 0;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
</style>
