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
import { useI18n } from "vue-i18n";
import { money } from "@shared/utils/money";
import { FULFILLMENT } from "@shared/utils/constants";
import { datetime } from "@shared/utils/datetime";
import type { Order } from "@shared/types";
import {
  DELIVERY_SHAPE,
  fulfillmentsOf,
  tabQuery,
  type OrderTabSpec,
} from "@shared/strategies/order-view";

/** 要商家核销的履约：自提点、邻居家、到店核销。**靠履约方式判，不靠状态** */
const PICKUP_LIKE = new Set<string>(
  fulfillmentsOf(DELIVERY_SHAPE.SELF_PICKUP, DELIVERY_SHAPE.SELF_SERVE),
);

const merchant = useMerchantStore();
const { t } = useI18n();

/** 状态色：要动手的用主色、售后用警示色、终态保持中性 —— 一眼能挑出「该我做的」 */
function statusChip(o: Order): string {
  // 「该我做的」= 待履约，或已履约但还要我核销的（自提/到店核销）
  // 待收款也是「该我做的」—— 而且是这一批里唯一一件不做就收不到钱的事
  if (o.status === "WAIT_OFFLINE_PAY") return "sh-chip--primary";
  if (o.status === "PAID") return "sh-chip--primary";
  if (o.status === "FULFILLING" && PICKUP_LIKE.has(o.fulfillment)) return "sh-chip--primary";
  return "";
}

/**
 * 行上那句状态，**要连履约方式一起看**。
 *
 * `PAID` 的中文一律写成「待发货」时，一张自提单也会显示「待发货」——
 * 店主会去找快递单号，而这单根本不发货：邻居自己来取。
 * 工作台上早就是分开的（待发货 / 待自送 / 待核销），列表这里却只按状态拼 key。
 *
 * 只处理「还没交付」的两个状态，其余（已完成/已退款/待付款）与履约方式无关。
 */
function statusText(o: Order): string {
  const f = o.fulfillment;
  if (o.status === "PAID") {
    if (f === FULFILLMENT.PICKUP || f === FULFILLMENT.NEIGHBOR_PICKUP) return t("home.toVerify");
    if (f === FULFILLMENT.DELIVERY) return t("home.toDeliver");
    return t("order.statusPAID");
  }
  if (o.status === "FULFILLING") {
    // 自送单在路上说「配送中」更准；自提/到店核销说「待核销」；快递才是「已发货」
    if (f === FULFILLMENT.DELIVERY) return t("order.delivering");
    if (PICKUP_LIKE.has(f)) return t("home.toVerify");
    return t("order.statusSHIPPED");
  }
  return t(`order.status${o.status}`);
}

/*
 * 页签 = **谓词**（抽象状态 + 交付形态），不是状态值。
 *
 * 商家侧与买家侧看同一批单、分法不同：买家分「去取 / 等着」，
 * 商家分「待发货 / 已发货 / 待核销」。**同一份状态支撑两种分法**，
 * 正是因为状态不含履约 —— 此前 `ARRIVED`/`SHIPPED` 把商家的分法烧进了状态里。
 */
const ALL_TABS: (OrderTabSpec & { labelKey: string; perm?: string })[] = [
  { key: "all", labelKey: "order.tabAll" },
  /*
   * 待收款单独一个页签，**排在待发货前面**。
   *
   * 不并进「待发货」：那一组的动作是「把货交出去」，这一组是「把钱收进来」，
   * 而线下单在收到钱之前根本不该发货 —— 混在一起，店员会照着列表先发后收。
   */
  { key: "toReceive", status: "WAIT_OFFLINE_PAY", labelKey: "order.tabToReceive" },
  { key: "toShip", status: "PAID", labelKey: "order.tabToShip" },
  {
    key: "shipped",
    status: "FULFILLING",
    shapes: [DELIVERY_SHAPE.SHIP_TO_BUYER, DELIVERY_SHAPE.SERVE_TO_BUYER],
    labelKey: "order.tabShipped",
  },
  {
    key: "toVerify",
    status: "FULFILLING",
    shapes: [DELIVERY_SHAPE.SELF_PICKUP, DELIVERY_SHAPE.SELF_SERVE],
    labelKey: "order.tabToVerify",
  },
  { key: "done", status: "COMPLETED", labelKey: "order.tabDone" },
  // 售后不按订单状态筛 —— 它是另一张单，走 /biz/after-sale（见 load()）
  // 而那张单要 `biz:aftersale`：这一页的门禁只有 `biz:order:view`，
  // 店员与配送员进得来但点不开这个 tab。**跟着自己的权限走**，与工作台待办格子同一手法。
  { key: "afterSale", labelKey: "order.tabAfterSale", perm: "biz:aftersale" },
];

const TABS = computed(() => ALL_TABS.filter((t) => !t.perm || merchant.can(t.perm)));

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
  if (!merchant.canOperate) return;
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
      const spec = ALL_TABS.find((t) => t.key === tab.value);
      const res = await api.mOrderList({ ...scope, ...(spec ? tabQuery(spec) : {}) });
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
  <sh-scaffold title-key="order.title" tab="orders" :denied="!merchant.can('biz:order:view')">
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
      <sh-go>{{ allStores ? $t("order.scopeToCurrent") : $t("order.scopeToAll") }}</sh-go>
    </view>

    <sh-empty v-if="empty" :text='$t("order.empty")'></sh-empty>

    <view v-for="o in list" :key="o.orderNo" class="sh-card row" @tap="open(o)">
      <view class="row__head">
        <text class="row__no sh-num">{{ o.orderNo }}</text>
        <!-- 行内显示的是**订单自己的状态**。原先拼的是 tab 的 key
             （`order.tab${PAID ? 'ToShip' : 'All'}`），于是除待发货外一律显示「全部」——
             一屏订单看下来全是「全部」，等于这一列没有信息 -->
        <text class="sh-chip" :class="statusChip(o)">
          {{ statusText(o) }}
        </text>
      </view>

      <view v-for="it in o.items" :key="it.skuNo" class="item">
        <sh-cover class="item__cover" :src="it.cover"></sh-cover>
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
  font-size: 24rpx;
  color: var(--sh-ink);
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
/*
 * 商品名是这张卡片的**标题**，要靠字重站住，不能只靠颜色。
 * 此前它 26rpx/400 墨色，而紧挨着的规格与时间是 26rpx/400 灰色 ——
 * 同号同重，只差一档灰度，于是整张卡片唯一「重」的东西是金额，
 * 一屏列表扫下来看不出每单卖的是什么。墨色已经是最深的一档，
 * 再往下压没有空间，能动的是字重与字号。
 */
.item__title {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
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
