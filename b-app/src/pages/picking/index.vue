<script setup lang="ts">
// 分拣单（B-10.3）。到货当日的高频页面。
//
// 两个视图不是花哨：**按商品**是分货时用的（一箱苹果拆成 12 份，按商品走一遍最快），
// **按用户**是装袋时用的（每人一袋，按人走一遍不会漏）。同一批货要走两遍不同的顺序，
// 只做一个视图就等于让店主自己在纸上抄一遍。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { Order, PickingRow } from "@shared/types";

const { t } = useI18n();

const view = ref<"goods" | "buyer">("goods");
const rows = ref<PickingRow[]>([]);
const orders = ref<Order[]>([]);

/** 按用户聚合：从分拣行反查，避免再拉一次订单接口 */
const byBuyer = computed(() => {
  const map = new Map<string, { nickname: string; items: { title: string; spec: string; qty: number }[] }>();
  for (const r of rows.value) {
    for (const b of r.buyers) {
      const cur = map.get(b.orderNo) ?? { nickname: b.nickname, items: [] };
      cur.items.push({ title: r.title, spec: r.spec, qty: b.qty });
      map.set(b.orderNo, cur);
    }
  }
  return [...map.entries()].map(([orderNo, v]) => ({ orderNo, ...v }));
});

const totalQty = computed(() => rows.value.reduce((s, r) => s + r.totalQty, 0));
/** 备货中的单 = 还没标到货的，标记后用户才收到到货通知 */
const preparing = computed(() => orders.value.filter((o) => o.status === "PREPARING"));

async function load() {
  [rows.value, orders.value] = await Promise.all([api.mPickingList(), api.mPickupOrders()]);
}

/**
 * 破损 / 短少上报（B-10.4.2）。
 * **只留痕并通知用户，不自动退款** —— 责任在供货方还是自提点承接方尚未定（矩阵 M4），
 * 自动退等于默认平台兜底。
 */
async function report(orderNo: string, skuNo: string) {
  const kinds = [t("picking.shortage"), t("picking.damage")] as const;
  const res = await new Promise<number>((resolve) => {
    uni.showActionSheet({
      itemList: [...kinds],
      success: (r) => resolve(r.tapIndex),
      fail: () => resolve(-1),
    });
  });
  if (res < 0) return;

  const note = await new Promise<string>((resolve) => {
    uni.showModal({
      title: kinds[res]!,
      editable: true,
      placeholderText: t("picking.notePh"),
      success: (r) => resolve(r.confirm ? (r.content ?? "") : ""),
      fail: () => resolve(""),
    });
  });
  if (!note.trim()) return;

  await api.mReportShortage(orderNo, {
    skuNo,
    kind: res === 0 ? "SHORTAGE" : "DAMAGE",
    note: note.trim(),
  });
  uni.showToast({ title: t("picking.reported"), icon: "none" });
}

async function markAllArrived() {
  if (!preparing.value.length) return;
  const changed = await api.mMarkArrived(preparing.value.map((o) => o.orderNo));
  uni.showToast({ title: `已通知 ${changed.length} 位邻居`, icon: "none" });
  await load();
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="picking.title">
    <view class="head">
      <text class="sh-h1">{{ $t("picking.title") }}</text>
      <text class="sh-muted">{{ $t("picking.total", { n: totalQty }) }}</text>
    </view>

    <text class="sh-muted reporthint">{{ $t("picking.reportHint") }}</text>

    <view class="tabs">
      <text
        class="sh-chip"
        :class="{ 'sh-chip--primary': view === 'goods' }"
        @tap="view = 'goods'"
      >
        {{ $t("picking.byGoods") }}
      </text>
      <text
        class="sh-chip"
        :class="{ 'sh-chip--primary': view === 'buyer' }"
        @tap="view = 'buyer'"
      >
        {{ $t("picking.byBuyer") }}
      </text>
    </view>

    <sh-empty v-if="!rows.length" :text='$t("picking.empty")'></sh-empty>

    <!-- 按商品：分货用 -->
    <template v-if="view === 'goods'">
      <view v-for="r in rows" :key="r.skuNo" class="sh-card row">
        <view class="row__head">
          <text class="row__cover">{{ r.cover }}</text>
          <view class="row__main">
            <text class="row__title">{{ r.title }}</text>
            <text class="sh-muted">{{ r.spec }}</text>
          </view>
          <text class="row__qty sh-num">×{{ r.totalQty }}</text>
        </view>
        <view class="buyers">
          <text
            v-for="b in r.buyers"
            :key="b.orderNo"
            class="sh-chip"
            @tap="report(b.orderNo, r.skuNo)"
          >
            {{ b.nickname }} ×{{ b.qty }}
          </text>
        </view>
      </view>
    </template>

    <!-- 按用户：装袋用 -->
    <template v-else>
      <view v-for="b in byBuyer" :key="b.orderNo" class="sh-card row">
        <view class="row__head">
          <view class="row__main">
            <text class="row__title">{{ b.nickname }}</text>
            <text class="sh-muted sh-num">{{ b.orderNo }}</text>
          </view>
          <text class="row__qty sh-num">
            ×{{ b.items.reduce((s, i) => s + i.qty, 0) }}
          </text>
        </view>
        <view class="buyers">
          <text v-for="(it, i) in b.items" :key="i" class="sh-chip">
            {{ it.title }} ×{{ it.qty }}
          </text>
        </view>
      </view>
    </template>

    <view
      v-if="preparing.length"
      class="sh-btn arrive"
      @tap="markAllArrived"
    >
      {{ $t("picking.markArrived", { n: preparing.length }) }}
    </view>
  </sh-scaffold>
</template>

<style scoped>
.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 20rpx;
}
.reporthint {
  display: block;
  margin: 0 8rpx 20rpx;
  line-height: 1.6;
}
.tabs {
  display: flex;
  gap: 12rpx;
  margin-bottom: 24rpx;
}
.tabs .sh-chip {
  font-size: 26rpx;
  padding: 14rpx 28rpx;
}
/* 列表密度对齐 C 端（平台版式约定）：卡片之间只留一条缝、正文行高 1.35。
   商家一天要扫几十次这类列表，行距每多 10rpx，一屏就少一行。 */
.row {
  margin-bottom: 14rpx;
}
.row__head {
  display: flex;
  align-items: center;
  gap: 20rpx;
}
.row__cover {
  font-size: 52rpx;
  width: 84rpx;
  height: 84rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  text-align: center;
  line-height: 84rpx;
}
.row__main {
  flex: 1;
  min-width: 0;
}
.row__title {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.row__qty {
  font-size: 36rpx;
  font-weight: 700;
  color: var(--sh-primary);
}
.buyers {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 20rpx;
}
.arrive {
  margin-top: 32rpx;
}
</style>
