<script setup lang="ts">
// 盘点（B-3）。
//
// **账面数是开单那一刻的快照**（后端 `bookQty`），不是当前余额 ——
// 盘的过程中照常卖，拿当前数算差异会把中间卖掉的量记成盘亏，
// 而那是一笔凭空出现的损失。所以这一页读回 `mCountDetail`，不自己拿余额顶替。
//
// **差异原因是枚举不是自由文本**：自由文本汇总不出「这个月报损了多少」，
// 而那正是盘完之后商家唯一想知道的数。
//
// 过账后盘盈生成入库单、盘亏生成出库单 —— **盘点自己不改库存**，走的是同一个过账口。
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { StockBalance, StockCount } from "@shared/types";
import { confirm } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

/** 差异原因。与后端 `reason_code` 同一套枚举 */
const REASONS = ["BROKEN", "EXPIRED", "GIFT", "OTHER"] as const;

const countNo = ref("");
const doc = ref<StockCount | null>(null);
/** itemId → 实盘数（字符串：输入框里可以是空的，空 ≠ 0） */
const counted = ref<Record<string, string>>({});
const reason = ref<(typeof REASONS)[number]>("BROKEN");
const busy = ref(false);

/** 还没开单时，先选要盘哪几件 */
const picking = ref<StockBalance[]>([]);
const picked = ref<string[]>([]);

onLoad(async (q) => {
  countNo.value = String((q as Record<string, string>)?.no ?? "");
  if (countNo.value) await loadDoc();
  else await loadPick();
});

async function loadDoc() {
  try {
    doc.value = await api.mCountDetail(countNo.value);
    // 已填过的回显。**null 与 0 要分开**：null 是「还没盘」，0 是「盘了，一件都没有」
    const map: Record<string, string> = {};
    for (const l of doc.value.lines) {
      map[l.itemId] = l.countedQty == null ? "" : String(l.countedQty);
    }
    counted.value = map;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

async function loadPick() {
  try {
    picking.value = await api.mStockBalances({ filter: "all", size: 200 });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

function toggle(itemId: string) {
  picked.value = picked.value.includes(itemId)
    ? picked.value.filter((x) => x !== itemId)
    : [...picked.value, itemId];
}

/** 开单。**这一刻锁账面数** —— 之后卖掉多少都不影响差异 */
async function open() {
  if (!picked.value.length) return;
  busy.value = true;
  try {
    countNo.value = await api.mCountOpen(picked.value);
    await loadDoc();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

/** 某一行的差异；还没填实盘数时返回 null（显示成「—」而不是 0） */
function diff(itemId: string, bookQty: number): number | null {
  const v = counted.value[itemId];
  if (v == null || v === "") return null;
  const n = Number(v);
  return Number.isFinite(n) ? n - bookQty : null;
}

const totalDiff = computed(() => {
  if (!doc.value) return 0;
  return doc.value.lines.reduce((sum, l) => sum + (diff(l.itemId, l.bookQty) ?? 0), 0);
});

/** 有差异的行都要带原因。**差异为 0 的不带** —— 让人给没差异的行选原因，那一栏就成了噪音 */
const hasDiff = computed(() =>
  !!doc.value && doc.value.lines.some((l) => (diff(l.itemId, l.bookQty) ?? 0) !== 0),
);

/** 一件都没填就提交，等于把整张单当成「全部盘成 0」—— 那会清空这几件货 */
const filledCount = computed(() =>
  Object.values(counted.value).filter((v) => v !== "").length,
);

async function submit() {
  if (!doc.value || !filledCount.value) return;
  const ok = await confirm({
    title: String(t("stockCheck.confirmTitle")),
    content: String(t("stockCheck.confirmBody", { n: totalDiff.value })),
  });
  if (!ok) return;

  busy.value = true;
  try {
    const lines = doc.value.lines
      .filter((l) => counted.value[l.itemId] !== "")
      .map((l) => {
        const d = diff(l.itemId, l.bookQty) ?? 0;
        return {
          itemId: l.itemId,
          countedQty: Number(counted.value[l.itemId]),
          reasonCode: d === 0 ? undefined : reason.value,
        };
      });
    await api.mCountFill(countNo.value, lines);
    await api.mCountPost(countNo.value);
    uni.showToast({ title: String(t("stockCheck.posted")), icon: "none" });
    uni.navigateBack();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

/**
 * 差异的显示。**带符号** —— 「2」与「−2」在窄列里差一个字符，
 * 而这一栏的正负正是盘盈与盘亏的分界。
 *
 * 写在 `<script setup>` 里而不是另一个 `<script>` 块：普通块的导出
 * **模板里取不到**，而 tsc 不会为此报错 —— 表现是那一列永远空白。
 */
function diffText(n: number): string {
  return n > 0 ? `+${n}` : String(n);
}
function diffClass(n: number | null): string {
  if (n == null || n === 0) return "";
  return n < 0 ? "is-loss" : "is-gain";
}

/** 「08-26 09:02」。切片不解析 —— 后端发的是不带时区的 LocalDateTime */
function at(iso?: string): string {
  return iso && iso.length >= 16 ? iso.slice(5, 16).replace("T", " ") : "";
}
</script>

<template>
  <sh-scaffold title-key="stockCheck.title" :denied="!merchant.can('biz:stock')">
    <!-- ① 还没开单：先选要盘哪几件 -->
    <template v-if="!doc">
      <view class="sh-card">
        <text class="txt-caption">{{ $t("stockCheck.pickHint") }}</text>
      </view>

      <sh-empty v-if="!picking.length" :text="String($t('stockCheck.pickEmpty'))"></sh-empty>

      <view v-for="b in picking" :key="b.itemId" class="sh-card row" @tap="toggle(b.itemId)">
        <view class="row__top">
          <!-- 勾选由整行接管（点一行就选中），sh-check 只负责画 -->
          <sh-check :model-value="picked.includes(b.itemId)"></sh-check>
          <view class="row__main">
            <text class="row__title">{{ b.name }}{{ b.specText ? ` · ${b.specText}` : "" }}</text>
            <text class="sh-muted sh-num">{{ $t("stockCheck.bookN", { n: b.onHand }) }}</text>
          </view>
        </view>
      </view>

      <view
        class="sh-btn"
        :class="{ 'sh-btn--muted': !picked.length || busy }"
        @tap="open"
      >
        {{ $t("stockCheck.start", { n: picked.length }) }}
      </view>
    </template>

    <!-- ② 已开单：填实盘数 -->
    <template v-else>
      <view class="sh-card">
        <view class="hd">
          <text class="txt-strong sh-num">{{ doc.countNo }}</text>
          <text class="sh-chip sh-chip--warning">{{ $t("stockCheck.counting") }}</text>
        </view>
        <text class="txt-caption">{{ $t("stockCheck.lockedAt", { at: at(doc.startedAt) }) }}</text>
      </view>

      <view v-for="l in doc.lines" :key="l.itemId" class="sh-card row">
        <view class="row__top">
          <view class="row__main">
            <text class="row__title">{{ l.name }}{{ l.specText ? ` · ${l.specText}` : "" }}</text>
            <view class="row__meta">
              <text class="sh-muted sh-num">{{ $t("stockCheck.bookN", { n: l.bookQty }) }}</text>
              <text
                v-if="(diff(l.itemId, l.bookQty) ?? 0) !== 0"
                class="sh-chip sh-chip--danger"
              >
                {{ $t("stockCheck.reasonRequired") }}
              </text>
            </view>
          </view>
          <input
            v-model="counted[l.itemId]"
            class="field__input qty sh-num"
            type="number"
            :placeholder="String(l.bookQty)"
          />
          <view class="row__end">
            <text class="txt-strong sh-num" :class="diffClass(diff(l.itemId, l.bookQty))">
              {{ diff(l.itemId, l.bookQty) == null ? "—" : diffText(diff(l.itemId, l.bookQty)!) }}
            </text>
          </view>
        </view>
      </view>

      <!-- 原因只在真有差异时才问 -->
      <view v-if="hasDiff" class="sh-card">
        <text class="field__label">{{ $t("stockCheck.reasonLabel") }}</text>
        <view class="reasons">
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

      <view class="sh-card hd">
        <text class="txt-strong">{{ $t("stockCheck.totalDiff") }}</text>
        <text class="txt-display sh-num" :class="diffClass(totalDiff)">
          {{ diffText(totalDiff) }}
        </text>
      </view>

      <view
        class="sh-btn"
        :class="{ 'sh-btn--muted': !filledCount || busy }"
        @tap="submit"
      >
        {{ $t("stockCheck.submit") }}
      </view>
      <text class="field__hint hint">{{ $t("stockCheck.postHint") }}</text>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.row {
  margin-bottom: 14rpx;
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
.row__meta {
  display: flex;
  gap: 16rpx;
  align-items: center;
  margin-top: 8rpx;
}
.row__end {
  min-width: 72rpx;
  text-align: right;
  flex: none;
}
.qty {
  width: 144rpx;
  text-align: right;
  flex: none;
}
.hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.reasons {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}
.hint {
  padding: 0 4rpx;
}
.is-loss {
  color: var(--sh-danger);
}
.is-gain {
  color: var(--sh-success);
}
</style>
