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
import type { StockBalance, Supplier } from "@shared/types";
import { pick, prompt } from "@ai-shop/ui/prompt";

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

/**
 * 出库去向。**这一页此前写死 SCRAP** —— 于是所有非销售出库都被记成报损，
 * 「退给供应商」这件事记不了，而「这个月退给老周多少货」是应付账款对账的一半。
 *
 * **`SALE` 不在这里，且永远不会在**：销售出库只能由预留 commit 产生
 *（后端 `OutboundServiceImpl` 的闸门）。做成一个选项的话，
 * 商家能凭空造一笔销售出库，而它会进销量榜。
 */
const PURPOSES = ["SCRAP", "RETURN_SUPPLIER", "INTERNAL"] as const;

const occurredAt = ref(today());
const purpose = ref<(typeof PURPOSES)[number]>("SCRAP");
const reason = ref<(typeof REASONS)[number]>("EXPIRED");
/** 退供应商时退给谁。**只有 RETURN_SUPPLIER 用得上** */
const supplier = ref<{ supplierNo: string; name: string } | null>(null);
const suppliers = ref<Supplier[]>([]);
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
    // 供应商与货一起取：切到「退供应商」时再去拉，商家会先看到一个空选择器
    suppliers.value = await api.mSuppliers({ activeOnly: true }).catch(() => []);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 选去向。切走「退供应商」时把已选的那家清掉 —— 留着它，下次切回来会是一家没显示过的 */
async function pickPurpose() {
  const idx = await pick({
    title: String(t("stockOut.purposeLabel")),
    items: PURPOSES.map((p) => String(t(`stockOut.purpose.${p}`))),
  });
  if (idx === null) return;
  purpose.value = PURPOSES[idx]!;
  if (purpose.value !== "RETURN_SUPPLIER") supplier.value = null;
}

async function pickSupplier() {
  if (!suppliers.value.length) {
    // 空态说实话：**没有供应商**与「加载失败」是两件事，
    // 而商家能自己解决前者（去进货页随手建一个）
    uni.showToast({ title: String(t("stockOut.noSupplier")), icon: "none" });
    return;
  }
  const idx = await pick({
    title: String(t("stockOut.supplierLabel")),
    items: suppliers.value.map((s) => s.shortName || s.name),
  });
  if (idx === null) return;
  const s = suppliers.value[idx]!;
  supplier.value = { supplierNo: s.supplierNo, name: s.shortName || s.name };
}

function pickQty(b: StockBalance): string {
  return String(t("stockOut.availableN", { n: b.available }));
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
  // 退供应商必须指得出是哪一家。**在这里就卡住** —— 后端也拦，
  // 但让它走一趟网络再被拒，商家看到的是一句没头没尾的错误
  if (purpose.value === "RETURN_SUPPLIER" && !supplier.value) {
    uni.showToast({ title: String(t("stockOut.needSupplier")), icon: "none" });
    return;
  }
  busy.value = true;
  try {
    const no = await api.mOutboundCreate({
      purpose: purpose.value,
      // 原因只有报损要：退供应商说得出退给谁，不需要再问一次为什么
      reasonCode: purpose.value === "SCRAP" ? reason.value : undefined,
      targetType: purpose.value === "RETURN_SUPPLIER" ? "SUPPLIER" : undefined,
      targetNo: purpose.value === "RETURN_SUPPLIER" ? supplier.value?.supplierNo : undefined,
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
    <!-- 当前门店只读标记：出库扣的是**这家店**的库存（同上）——
         界面上不说清是哪家店，多店店主会在另一家店上动手，而且没有任何症状。
         只在多店时渲染（单店没有歧义可消）；切店入口在工作台，这里不带动作。 -->
    <biz-store-tag readonly></biz-store-tag>

    <view class="sh-card">
      <!--
        去向排在日期上面：它决定了下面那半屏长什么样（报损问原因、退供应商问退给谁），
        而排在后面的话，商家会先填完原因再发现自己要的不是报损。
      -->
      <sh-kv between :label="String($t('stockOut.purposeLabel'))" @tap="pickPurpose">
        <text class="sh-link">{{ $t(`stockOut.purpose.${purpose}`) }}</text>
      </sh-kv>
      <sh-kv
        v-if="purpose === 'RETURN_SUPPLIER'"
        between
        :label="String($t('stockOut.supplierLabel'))"
        @tap="pickSupplier"
      >
        <text class="sh-link">{{ supplier?.name || $t("stockOut.supplierPh") }}</text>
      </sh-kv>
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

    <!-- 原因只有报损要问：退供应商说得出退给谁，再问一次「为什么」是多余的一步 -->
    <view v-if="purpose === 'SCRAP'" class="sh-card">
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
      <text class="txt-display sh-num is-danger">−{{ totalQty }}</text>
    </view>

    <!-- 主动作贴底：报损单的行数没有上限，按钮跟在行后面会被推下去 -->
    <sh-actionbar :pad="180">
      <view class="sh-btn" :class="{ 'sh-btn--muted': !lines.length || busy }" @tap="post">
        {{ $t("stockOut.post") }}
      </view>
    </sh-actionbar>

    <biz-item-picker
      :visible="showPick"
      :title="String($t('stockOut.addItem'))"
      :items="pickable"
      :picked="lines.map((l) => l.itemId)"
      :qty-label="pickQty"
      @pick="addLine"
      @close="showPick = false"
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
</style>
