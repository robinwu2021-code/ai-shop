<script setup lang="ts">
// 我的积分。**只有用户侧** —— 商家侧的积分视图随契约迁到了 b-app（ADR-007 §3）。
//
// 页面上要分开显示「可用」与「待生效」：合成一个数的话，用户看到「我有 500 分」
// 却只能用 400，没有任何办法解释这个差额（V25 拆出 pending_balance 就是为了这个）。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { POINTS } from "@shared/utils/constants";
import { datetime, isoDate, money } from "@shared/utils/format";
import type { PointAccount, PointRecord } from "@shared/types";

const account = ref<PointAccount | null>(null);
const records = ref<PointRecord[]>([]);

/** 余额折算成钱，让用户知道这些积分到底值多少 */
const worthMinor = computed(() =>
  Math.floor((account.value?.balance ?? 0) / POINTS.perMinor),
);

async function load() {
  const [a, r] = await Promise.all([api.pointAccount(), api.pointRecords()]);
  account.value = a;
  records.value = r;
}


onShow(load);
</script>

<template>
  <sh-scaffold :title-key="'points.title'">
    <!-- 用户侧：单位是分 -->
    <view class="sh-card hero">
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

    <!-- 规则：积分能抵钱，规则必须写在明面上 -->
    <view class="sh-card block rules">
      <text class="rules__title">{{ $t("points.rules") }}</text>
      <text class="rules__t">
        {{ $t("points.ruleRate", { n: POINTS.perMinor * 100 }) }}
      </text>
      <text class="rules__t">
        {{ $t("points.ruleCap", { n: Math.round(POINTS.maxDeductRatio * 100) }) }}
      </text>
      <text class="rules__t">
        {{ $t("points.ruleGrant") }}
      </text>
      <text class="rules__t">
        {{ $t("points.ruleExpire", { n: POINTS.inactiveDays }) }}
      </text>
      <text v-if="account?.expiringSoon" class="rules__warn sh-num">
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
  color: var(--sh-primary-text);
  margin-top: 8rpx;
}
.hero__off {
  display: block;
  font-size: 24rpx;
  color: var(--sh-warning);
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
  color: var(--sh-primary-text);
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
