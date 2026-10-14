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

/** 这次没取到。**与「确定为空」是两件事** —— 网络不通时不该显示「还没有…」 */
const failed = ref(false);

async function load() {
  try {
    cards.value = await api.myCards();
    failed.value = false;
  } catch {
    failed.value = true;
  }
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
    <view v-for="c in cards" :key="c.cardNo" class="sh-card card" :class="{ 'is-expired': expired(c) }">
      <view class="card__head sh-row">
        <sh-cover class="card__cover" :src="c.cover" :w="200"></sh-cover>
        <view class="sh-fill">
          <text class="txt-strong card__title">{{ c.title }}</text>
          <text class="txt-caption card__no sh-num">{{ c.cardNo }}</text>
        </view>
      </view>

      <view class="card__value sh-row sh-row--between sh-row--baseline">
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

    <sh-empty v-if="!cards.length" :pending="!loaded" :failed="failed" @retry="load" :text="String($t('cards.empty'))">
      <template #action>
        <view class="sh-btn sh-btn--sm" @tap="goShopping">{{ $t("visited.go") }}</view>
      </template>
    </sh-empty>
  </sh-scaffold>
</template>

<style scoped>
.card {
  background: var(--sh-primary-tint);
  margin-bottom: 20rpx;
}
.card.is-expired {
  background: var(--sh-faint);
  opacity: 0.6;
}
.card__head {
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
  gap: 20rpx;
  margin-top: 28rpx;
}

</style>
