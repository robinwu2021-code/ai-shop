<script setup lang="ts">
// 积分账户。C 端（我的积分）与 B 端（商家的积分资金账）共用这一页，靠 `side` 参数切换。
//
// **两侧的账本结构不一样**，只有流水部分共用：
//   C 端 账户：积分个数（可用 / 待生效 / 累计），流水 EARN / USE / REFUND / EXPIRE
//   B 端 账户：**一个数** —— 本期发分服务费，外加开关状态
//
// 商家只感知「开了积分要付发分服务费」这一件事。用户抵了多少分、平台补了多少、
// 资金池，对他全部不可见（V34）—— 他收到的是订单全额减各项费用。
import { computed, ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { POINTS } from "@shared/utils/constants";
import { datetime, isoDate, money } from "@shared/utils/format";
import type { MerchantPointAccount, PointAccount, PointRecord } from "@shared/types";

const side = ref<"user" | "merchant">("user");
const account = ref<PointAccount | null>(null);
const merchant = ref<MerchantPointAccount | null>(null);
const records = ref<PointRecord[]>([]);

/** 余额折算成钱，让用户知道这些积分到底值多少 */
const worthMinor = computed(() =>
  Math.floor((account.value?.balance ?? 0) / POINTS.perMinor),
);

async function load() {
  if (side.value === "merchant") {
    const [a, r] = await Promise.all([
      api.merchantPointAccount(),
      api.merchantPointRecords(),
    ]);
    merchant.value = a;
    records.value = r;
  } else {
    const [a, r] = await Promise.all([api.pointAccount(), api.pointRecords()]);
    account.value = a;
    records.value = r;
  }
}

onLoad((q) => {
  side.value = q?.side === "merchant" ? "merchant" : "user";
});

onShow(load);
</script>

<template>
  <sh-scaffold :title-key="side === 'merchant' ? 'points.merchantTitle' : 'points.title'">
    <!-- 用户侧：单位是分 -->
    <view v-if="side === 'user'" class="sh-card hero">
      <text class="hero__label">{{ $t("points.balance") }}</text>
      <text class="hero__v sh-num">{{ account?.balance ?? 0 }}</text>
      <text class="hero__worth sh-num">{{ $t("points.worth", { p: money(worthMinor) }) }}</text>

      <!-- 待生效必须单独显示：合进余额的话，用户会看到「我有 500 分」却只能用 400 -->
      <text v-if="account?.pendingBalance" class="hero__pending sh-num">
        {{ $t("points.pending", {
          n: account.pendingBalance,
          d: account.pendingActivateAt ? isoDate(account.pendingActivateAt) : "",
        }) }}
      </text>

      <view class="sums">
        <view class="sum">
          <text class="sum__v sh-num">{{ account?.totalEarned ?? 0 }}</text>
          <text class="sum__k">{{ $t("points.totalEarned") }}</text>
        </view>
        <view class="sum">
          <text class="sum__v sh-num">{{ account?.totalUsed ?? 0 }}</text>
          <text class="sum__k">{{ $t("points.totalUsed") }}</text>
        </view>
      </view>
    </view>

    <!-- 商家侧：只有一个数 —— 本期发分服务费。抵扣与兑付对他不可见（V34） -->
    <view v-else class="sh-card hero">
      <text class="hero__label">
        {{ $t("points.periodExpense", { p: merchant?.period ?? "" }) }}
      </text>
      <text class="hero__v sh-num">{{ money(merchant?.periodExpenseMinor ?? 0) }}</text>
      <text class="hero__worth">{{ $t("points.expenseHint") }}</text>

      <!-- 关闭时要说清「为什么」：小微是不可开，不是关着 -->
      <text v-if="merchant && !merchant.enabled" class="hero__off">
        {{ merchant.disabledReason || $t("points.disabled") }}
      </text>
    </view>

    <!-- 规则：积分能抵钱，规则必须写在明面上 -->
    <view class="sh-card block rules">
      <text class="rules__title">{{ $t("points.rules") }}</text>
      <text v-if="side === 'user'" class="rules__t">
        {{ $t("points.ruleRate", { n: POINTS.perMinor * 100 }) }}
      </text>
      <text v-if="side === 'user'" class="rules__t">
        {{ $t("points.ruleCap", { n: Math.round(POINTS.maxDeductRatio * 100) }) }}
      </text>
      <text v-if="side === 'user'" class="rules__t">
        {{ $t("points.ruleGrant") }}
      </text>
      <text v-else class="rules__t">{{ $t("points.ruleMerchant") }}</text>
      <text class="rules__t">
        {{ $t("points.ruleExpire", { n: POINTS.inactiveDays }) }}
      </text>
      <text v-if="side === 'user' && account?.expiringSoon" class="rules__warn sh-num">
        {{ $t("points.expiring", {
          n: account.expiringSoon,
          d: account.expiringAt ? isoDate(account.expiringAt) : "",
        }) }}
      </text>
    </view>

    <!-- 流水：带变动后余额，方便对账 -->
    <view class="sh-card block">
      <text class="sh-h2">{{ $t("points.records") }}</text>
      <view v-for="r in records" :key="r.recordNo" class="rec">
        <view class="rec__main">
          <text class="rec__title">{{ r.title }}</text>
          <text class="rec__at sh-num">{{ datetime(r.at) }}</text>
        </view>
        <view class="rec__right">
          <text class="rec__v sh-num" :class="r.points > 0 ? 'is-in' : 'is-out'">
            {{ r.points > 0 ? "+" : "" }}{{ r.points }}
          </text>
          <text class="rec__bal sh-num">{{ $t("points.after", { n: r.balanceAfter }) }}</text>
        </view>
      </view>
      <sh-empty bare v-if="!records.length" :text='$t("points.empty")'></sh-empty>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.hero {
  text-align: center;
  padding-top: 44rpx;
  padding-bottom: 36rpx;
}
.hero__label {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.hero__v {
  display: block;
  font-size: 48rpx;
  font-weight: 600;
  color: var(--sh-ink);
  line-height: 1.1;
  margin-top: 8rpx;
}
.hero__pending {
  display: block;
  font-size: 24rpx;
  color: var(--sh-brand);
  margin-top: 8rpx;
}
.hero__off {
  display: block;
  font-size: 24rpx;
  color: var(--sh-warn);
  margin-top: 14rpx;
}
.hero__worth {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 10rpx;
}
.sums {
  display: flex;
  margin-top: 36rpx;
}
.sum {
  flex: 1;
}
.sum__v {
  display: block;
  font-size: 30rpx;
  font-weight: 400;
  color: var(--sh-ink);
}
.sum__k {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 6rpx;
}
.block {
  margin-top: 20rpx;
}
.rules__title {
  display: block;
  font-size: 24rpx;
  font-weight: 600;
  color: var(--sh-ink);
  margin-bottom: 14rpx;
}
.rules__t {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.75;
}
.rules__warn {
  display: block;
  font-size: 24rpx;
  color: var(--sh-warning);
  line-height: 1.75;
  margin-top: 10rpx;
}
.rec {
  display: flex;
  align-items: center;
  gap: 24rpx;
  margin-top: 26rpx;
}
.rec__main {
  flex: 1;
  min-width: 0;
}
.rec__title {
  display: block;
  font-size: 26rpx;
  color: var(--sh-ink);
}
.rec__at {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 4rpx;
}
.rec__right {
  text-align: end;
  flex-shrink: 0;
}
.rec__v {
  display: block;
  font-size: 30rpx;
  font-weight: 400;
}
.rec__v.is-in {
  color: var(--sh-primary);
}
.rec__v.is-out {
  color: var(--sh-danger);
}
.rec__bal {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 4rpx;
}
</style>
