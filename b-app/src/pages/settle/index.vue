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
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { money } from "@shared/utils/money";
import { monthDay } from "@shared/utils/datetime";
import type { RateCard, SettleBill } from "@shared/types";

const bills = ref<SettleBill[]>([]);
const rate = ref<RateCard | null>(null);

/** 万分比 → 百分数。后端 RateCardVO 存的是万分比整数（2% = 200），直接显示会变成 200% */
const pct = (bp: number) => `${(bp / 100).toFixed(bp % 100 === 0 ? 0 : 2)}%`;

async function load() {
  [bills.value, rate.value] = await Promise.all([api.mSettleList(), api.mRateCard()]);
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="settle.title">
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

    <sh-empty v-if="!bills.length" :text='$t("settle.empty")'></sh-empty>

    <view v-for="b in bills" :key="b.billNo" class="sh-card bill">
      <view class="bill__head">
        <text class="bill__period sh-num">
          {{ monthDay(b.periodStart) }} – {{ monthDay(b.periodEnd) }}
        </text>
        <text class="sh-chip" :class="b.status === 'DONE' ? 'sh-chip--primary' : 'sh-chip--warning'">
          {{ $t(`settle.status${b.status}`) }}
        </text>
      </view>

      <view class="bill__amount">
        <text class="sh-muted">{{ $t("settle.payable") }}</text>
        <text class="sh-num big">{{ money(b.payableMinor, b.currency) }}</text>
      </view>

      <view class="rows">
        <view class="row">
          <text class="sh-muted">{{ $t("settle.orderCount") }}</text>
          <text class="sh-num">{{ b.orderCount }}</text>
        </view>
        <view class="row">
          <text class="sh-muted">{{ $t("settle.commission") }}</text>
          <text class="sh-num minus">-{{ money(b.commissionMinor, b.currency) }}</text>
        </view>
        <view class="row">
          <text class="sh-muted">{{ $t("settle.fulfillFee") }}</text>
          <text class="sh-num minus">-{{ money(b.fulfillFeeMinor, b.currency) }}</text>
        </view>
        <view class="row">
          <text class="sh-muted">{{ $t("settle.settled") }}</text>
          <text class="sh-num">{{ money(b.settledMinor, b.currency) }}</text>
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
  font-size: 32rpx;
  font-weight: 700;
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
  font-weight: 700;
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
  font-size: 22rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
</style>
