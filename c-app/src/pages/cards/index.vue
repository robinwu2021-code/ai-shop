<script setup lang="ts">
// 卡包：储值卡看余额、次卡看剩余次数。
// 一期只做「买到手、看得见」，核销扣次/扣额度在 M1（见待完成清单）。
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { ROUTES } from "@shared/utils/constants";
import { isoDate, money } from "@shared/utils/format";
import type { UserCard } from "@shared/types";

const cards = ref<UserCard[]>([]);
const loaded = ref(false);

async function load() {
  cards.value = await api.myCards();
  loaded.value = true;
}

function expired(c: UserCard) {
  return c.expireAt <= Date.now();
}

function goShopping() {
  uni.switchTab({ url: ROUTES.home });
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="cards.title">
    <view v-for="c in cards" :key="c.cardNo" class="card" :class="{ 'is-expired': expired(c) }">
      <view class="card__head">
        <sh-cover class="card__cover" :src="c.cover"></sh-cover>
        <view class="sh-fill">
          <text class="txt-strong card__title">{{ c.title }}</text>
          <text class="txt-caption card__no sh-num">{{ c.cardNo }}</text>
        </view>
      </view>

      <view class="card__value">
        <!-- 储值卡看余额，次卡看次数 —— 两种卡的「还剩多少」是不同的东西 -->
        <text v-if="c.balanceMinor != null" class="txt-hero sh-num">
          {{ money(c.balanceMinor, c.currency) }}
        </text>
        <text v-else-if="c.timesLeft != null" class="txt-hero sh-num">
          {{ $t("cards.timesLeft", { n: c.timesLeft }) }}
        </text>
        <text class="txt-caption sh-num">
          {{ expired(c) ? $t("cards.expired") : $t("cards.until", { d: isoDate(c.expireAt) }) }}
        </text>
      </view>
    </view>

    <view v-if="loaded && !cards.length" class="empty">
      <text class="txt-sub empty__text">{{ $t("cards.empty") }}</text>
      <view class="sh-btn empty__btn" @tap="goShopping">{{ $t("visited.go") }}</view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.card {
  background: var(--sh-primary-tint);
  border-radius: 32rpx;
  padding: 32rpx;
  margin-bottom: 20rpx;
}
.card.is-expired {
  background: var(--sh-faint);
  opacity: 0.6;
}
.card__head {
  display: flex;
  align-items: center;
  gap: 20rpx;
}
.card__cover {
  width: 88rpx;
  height: 88rpx;
  border-radius: 24rpx;
  background: var(--sh-surface);
  text-align: center;
  line-height: 88rpx;
  font-size: 42rpx;
  flex-shrink: 0;
}

.card__title {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card__no {
  display: block;
  margin-top: 8rpx;
}
.card__value {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 20rpx;
  margin-top: 28rpx;
}

.empty__text {
  display: block;
  margin-bottom: 40rpx;
}
.empty__btn {
  display: inline-block;
  padding-left: 60rpx;
  padding-right: 60rpx;
}
/* 带引导按钮的空态，保留页面自有结构 */
.empty {
  text-align: center;
  padding: 120rpx 40rpx;
}
</style>
