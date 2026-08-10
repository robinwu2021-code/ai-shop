<script setup lang="ts">
// 订单列表（B-11.4.1）。
//
// tab 按「我要做什么」分，不按订单状态字典分 —— 商家不关心状态字典的细分，
// 只关心「这单要不要我发货」。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import { datetime } from "@shared/utils/datetime";
import type { Order, OrderStatus } from "@shared/types";

const merchant = useMerchantStore();

/** 状态色：要动手的用主色、售后用警示色、终态保持中性 —— 一眼能挑出「该我做的」 */
function statusChip(status: OrderStatus): string {
  if (status === "PAID" || status === "ARRIVED") return "sh-chip--primary";
  return "";
}

const TABS: { key: string; status?: OrderStatus; labelKey: string }[] = [
  { key: "all", labelKey: "order.tabAll" },
  { key: "toShip", status: "PAID", labelKey: "order.tabToShip" },
  { key: "shipped", status: "SHIPPED", labelKey: "order.tabShipped" },
  { key: "toVerify", status: "ARRIVED", labelKey: "order.tabToVerify" },
  { key: "done", status: "COMPLETED", labelKey: "order.tabDone" },
  // 售后不按订单状态筛 —— 它是另一张单，走 /biz/after-sale（见 load()）
  { key: "afterSale", labelKey: "order.tabAfterSale" },
];

const tab = ref("all");
const list = ref<Order[]>([]);
const loading = ref(false);

/**
 * 看全部门店还是只看当前门店。
 *
 * ⚠️ 后端一直支持 `allStores`，端上从没传过 —— 于是这一页恒等于「当前门店」，
 * 而界面上既不显示是哪家店、也没有切换入口。单店时看不出区别，
 * 多店老板会以为自己看到的是全部流水。
 *
 * 只在**真的有多家店**时显示这个开关：单店商家看到一个「全部门店 / 当前门店」
 * 的切换，只会疑惑自己是不是漏配了什么。
 */
const allStores = ref(false);

const empty = computed(() => !loading.value && !list.value.length);

async function load() {
  if (!merchant.isActive) return;
  loading.value = true;
  try {
    const scope = { size: 50, allStores: allStores.value || undefined };
    if (tab.value === "afterSale") {
      const afterSales = await api.mAfterSaleList();
      // 用 subOrderNo：列表一行是一张子订单，售后单的 orderNo 是主单号
      const nos = new Set(afterSales.map((a) => a.subOrderNo));
      const res = await api.mOrderList(scope);
      list.value = res.records.filter((o) => nos.has(o.orderNo));
    } else {
      const status = TABS.find((t) => t.key === tab.value)?.status;
      const res = await api.mOrderList({ ...scope, status });
      list.value = res.records;
    }
  } finally {
    loading.value = false;
  }
}

function switchTab(key: string) {
  tab.value = key;
  void load();
}

function toggleScope() {
  allStores.value = !allStores.value;
  void load();
}

function open(o: Order) {
  uni.navigateTo({ url: `${ROUTES.order}?orderNo=${o.orderNo}` });
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="order.title" tab="orders">
    <sh-tabs
      :items="TABS.map((t) => ({ key: t.key, label: String($t(t.labelKey)) }))"
      :active="tab"
      @change="switchTab"
    ></sh-tabs>

    <!-- 门店范围。只有多店才出现 —— 单店商家看到这个切换只会疑惑 -->
    <view v-if="merchant.multiStore" class="scope" @tap="toggleScope">
      <text class="scope__cur">
        {{ allStores ? $t("order.scopeAll") : merchant.currentStore?.name || $t("order.scopeCurrent") }}
      </text>
      <text class="scope__switch">
        {{ allStores ? $t("order.scopeToCurrent") : $t("order.scopeToAll") }}
      </text>
    </view>

    <sh-empty v-if="empty" :text='$t("order.empty")'></sh-empty>

    <view v-for="o in list" :key="o.orderNo" class="sh-card row" @tap="open(o)">
      <view class="row__head">
        <text class="row__no sh-num">{{ o.orderNo }}</text>
        <!-- 行内显示的是**订单自己的状态**。原先拼的是 tab 的 key
             （`order.tab${PAID ? 'ToShip' : 'All'}`），于是除待发货外一律显示「全部」——
             一屏订单看下来全是「全部」，等于这一列没有信息 -->
        <text class="sh-chip" :class="statusChip(o.status)">
          {{ $t(`order.status${o.status}`) }}
        </text>
      </view>

      <view v-for="it in o.items" :key="it.skuNo" class="item">
        <text class="item__cover">{{ it.cover }}</text>
        <view class="item__main">
          <text class="item__title">{{ it.title }}</text>
          <text class="sh-muted">{{ it.spec }} × {{ it.qty }}</text>
        </view>
      </view>

      <view class="row__foot">
        <text class="sh-muted">{{ datetime(o.createdAt) }}</text>
        <text class="row__amount sh-num">{{ money(o.amount.payableMinor, o.amount.currency) }}</text>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.scope {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 24rpx;
  margin-bottom: 16rpx;
  background: var(--sh-faint);
  border-radius: 16rpx;
}
.scope__cur {
  font-size: 26rpx;
  color: var(--sh-ink);
}
.scope__switch {
  font-size: 24rpx;
  color: var(--sh-primary);
}
/* 列表密度对齐 C 端（平台版式约定）：卡片之间只留一条缝、正文行高 1.35。
   商家一天要扫几十次这类列表，行距每多 10rpx，一屏就少一行。 */
.row {
  margin-bottom: 14rpx;
}
.row__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20rpx;
}
.row__no {
  font-size: 24rpx;
  color: var(--sh-sub);
}
.item {
  display: flex;
  gap: 20rpx;
  align-items: center;
  margin-bottom: 16rpx;
}
.item__cover {
  font-size: 48rpx;
  width: 76rpx;
  height: 76rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  text-align: center;
  line-height: 76rpx;
}
.item__main {
  flex: 1;
  min-width: 0;
}
.item__title {
  display: block;
  font-size: 26rpx;
  color: var(--sh-ink);
}
.row__foot {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-top: 12rpx;
}
.row__amount {
  font-size: 30rpx;
  font-weight: 700;
  color: var(--sh-ink);
}
</style>
