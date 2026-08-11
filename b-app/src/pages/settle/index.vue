<script setup lang="ts">
// 结算单（B-11.9）。
//
// 展示口径的三条硬规则：
//   1. **退款要扣回**。已分账的订单退款要先回退分账再退款（ADR-002 §3），
//      账面上不能出现「退过款还照结」的钱。
//   2. **佣金按客流来源分档**。自带客流建议零佣金 —— 他带来的客户在别家的消费才是
//      平台的收益（ADR-004 §6）。商家在这里看到自己带客的实际好处。
//   3. **履约服务费单列**。它是供货方付、自提点承接方收；本店两个角色都担时账面抵消，
//      但必须分别列出来，否则店主看不懂钱去哪了。
//
// ⚠️ 费率与服务费口径未定（B9/B10），页面上明确标注，不装作已经定了。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import { monthDay } from "@shared/utils/datetime";
import type { RateCard, SettleBill } from "@shared/types";

const merchant = useMerchantStore();
const bills = ref<SettleBill[]>([]);
const rate = ref<RateCard | null>(null);
const allStores = ref(false);

const SCOPES = [
  { all: false, labelKey: "settle.scopeCurrent" },
  { all: true, labelKey: "settle.scopeAll" },
];

const multiStore = computed(() => merchant.multiStore);

/** 万分比 → 百分数。后端存的是万分比整数（2% = 200），直接显示会变成 200% */
const pct = (bp: number) => `${(bp / 100).toFixed(bp % 100 === 0 ? 0 : 2)}%`;

/** 流水上是门店号，商家认的是门店名。查不到就原样显示号 —— 空白比一个号更难查 */
function storeName(storeNo?: string) {
  if (!storeNo) return "—";
  return merchant.stores.find((s) => s.storeNo === storeNo)?.name ?? storeNo;
}

function switchScope(all: boolean) {
  allStores.value = all;
  void load();
}

async function load() {
  [bills.value, rate.value] = await Promise.all([
    api.mSettleList(allStores.value),
    api.mRateCard(),
  ]);
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="settle.title" :denied="!merchant.can('biz:finance')">
    <text class="sh-h1">{{ $t("settle.title") }}</text>

    <!-- 费率卡放在账单**之前**：先说清楚怎么算，再看算出来多少。
         把费率讲明白是「自带客流零佣金」这个策略能起作用的前提 —— 商家算不清自己能拿多少，
         就不会有动力把老客带进来 -->
    <view v-if="rate" class="sh-card ratecard">
      <text class="sh-h2">{{ $t("settle.rateTitle") }}</text>
      <view class="ratecard__row">
        <text class="sh-chip sh-chip--primary">{{ $t("order.trafficMERCHANT_OWNED") }}</text>
        <text class="ratecard__v sh-num">{{ pct(rate.merchantOwnedRate) }}</text>
      </view>
      <view class="ratecard__row">
        <text class="sh-chip">{{ $t("order.trafficPLATFORM") }}</text>
        <text class="ratecard__v sh-num">{{ pct(rate.platformRate) }}</text>
      </view>
      <text class="sh-muted ratecard__note">{{ rate.note }}</text>
    </view>

    <!--
      门店范围。**多店才显示** —— 单店商家看到「全部门店」只会疑惑还有别的店。
      钱的作用域与订单页共用同一套惯例（allStores + 后端 allowedStoresOrAll），
      不另写一套：两套实现迟早有一套忘了跟上授权模型的变化。
    -->
    <view v-if="multiStore" class="scope">
      <text
        v-for="opt in SCOPES"
        :key="String(opt.all)"
        class="sh-chip"
        :class="{ 'sh-chip--primary': allStores === opt.all }"
        @tap="switchScope(opt.all)"
      >{{ $t(opt.labelKey) }}</text>
    </view>

    <sh-empty v-if="!bills.length" :text='$t("settle.empty")'></sh-empty>

    <!--
      **一笔子订单一行**，不是周期账单 —— 后端 stl_bill 就是这个粒度。
      此前这里按「周账单」渲染（billNo / periodStart / orderCount），
      而那些字段后端从来没有过：mock 下好看，连真后端整片空白。
    -->
    <view v-for="b in bills" :key="b.settleNo" class="sh-card bill">
      <view class="bill__head">
        <text class="bill__period sh-num">{{ monthDay(b.createdAt) }}</text>
        <text
          class="sh-chip"
          :class="b.status === 'SPLIT' ? 'sh-chip--primary' : 'sh-chip--warning'"
        >{{ $t(`settle.status${b.status}`) }}</text>
      </view>

      <view class="bill__amount">
        <text class="sh-muted">{{ $t("settle.net") }}</text>
        <text class="sh-num big">{{ money(b.netMinor) }}</text>
      </view>

      <view class="rows">
        <view class="row">
          <text class="sh-muted">{{ $t("settle.gross") }}</text>
          <text class="sh-num">{{ money(b.grossMinor) }}</text>
        </view>
        <view class="row">
          <text class="sh-muted">{{ $t("settle.commission") }}（{{ pct(b.commissionRate) }}）</text>
          <text class="sh-num minus">-{{ money(b.commissionMinor) }}</text>
        </view>
        <view class="row">
          <text class="sh-muted">{{ $t("settle.fulfillFee") }}</text>
          <text class="sh-num minus">-{{ money(b.serviceFeeMinor) }}</text>
        </view>
        <!-- 多店商家必须看得见「哪家店挣的」和「打给哪个号」：
             只给其中一个，他就无法回答「河坊街店这个月的钱进了哪张卡」 -->
        <view v-if="multiStore" class="row">
          <text class="sh-muted">{{ $t("settle.store") }}</text>
          <text>{{ storeName(b.storeNo) }}</text>
        </view>
        <view v-if="multiStore && b.payMerchantNo" class="row">
          <text class="sh-muted">{{ $t("settle.payTo") }}</text>
          <text class="sh-num">{{ b.payMerchantNo }}</text>
        </view>
      </view>
    </view>

    <text class="tip">{{ $t("settle.rateHint") }}</text>
    <text class="tip">{{ $t("settle.pendingHint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.ratecard {
  margin-bottom: 14rpx;
}
.ratecard__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14rpx;
}
.ratecard__v {
  font-size: 30rpx;
  font-weight: 400;
  color: var(--sh-ink);
}
.ratecard__note {
  display: block;
  margin-top: 14rpx;
  line-height: 1.6;
}

.bill {
  margin-top: 14rpx;
}
.bill__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.bill__period {
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.bill__amount {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 24rpx 0;
}
.big {
  font-size: 48rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.rows {
  border-radius: 24rpx;
  background: var(--sh-faint);
  padding: 8rpx 24rpx;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 0;
}
.minus {
  color: var(--sh-danger);
}
.tip {
  display: block;
  margin: 24rpx 8rpx 0;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
</style>
