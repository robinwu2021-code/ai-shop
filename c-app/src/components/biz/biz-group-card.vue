<script setup lang="ts">
// 拼团卡片：进度、当前档位、还差几人到下一档、参团邻居头像。
// 展示重点是「还差 N 人到 X 折」而不是「已 N 人」—— 前者才驱动分享。
import { computed } from "vue";
import { countdown, money } from "@shared/utils/format";
import type { GroupBuy } from "@shared/types";

const props = defineProps<{ group: GroupBuy; now: number }>();
defineEmits<{ (e: "tap"): void }>();

const progress = computed(() =>
  Math.min(
    100,
    Math.round((props.group.joinedCount / props.group.minCount) * 100),
  ),
);

const off = computed(() =>
  Math.round((1 - props.group.groupPrice / props.group.basePrice) * 100),
);
</script>

<template>
  <view class="gcard" @tap="$emit('tap')">
    <view class="gcard__top">
      <sh-cover class="gcard__cover" :src="group.cover"></sh-cover>
      <view class="gcard__main">
        <text class="gcard__title">{{ group.title }}</text>
        <text class="gcard__pickup">{{ group.pickupName }}</text>
        <view class="gcard__price">
          <text class="gcard__now sh-num">{{ money(group.groupPrice) }}</text>
          <text v-if="off > 0" class="gcard__base sh-num">{{
            money(group.basePrice)
          }}</text>
          <text v-if="off > 0" class="sh-chip sh-chip--danger sh-num"
            >-{{ off }}%</text
          >
        </view>
      </view>
    </view>

    <!-- 还差几人到下一档 —— 这句话就是分享文案 -->
    <view class="gcard__goal">
      <text v-if="!group.reached" class="gcard__goal-text">
        {{ $t("group.needMore", { n: group.need }) }}
      </text>
      <text v-else class="gcard__goal-text gcard__goal-text--max">
        {{ $t("group.done") }}
      </text>
      <text class="gcard__cd sh-num">{{
        countdown(group.expireAt - now)
      }}</text>
    </view>

    <view class="bar">
      <view class="bar__fill" :style="{ width: `${progress}%` }" />
    </view>

    <view class="gcard__foot">
      <view class="avatars">
        <text
          v-for="(m, i) in group.members.slice(0, 5)"
          :key="i"
          class="avatars__a"
        >
          {{ m.avatar }}
        </text>
        <text class="avatars__n sh-num">
          {{ $t("group.joined", { n: group.joinedCount }) }}
        </text>
      </view>
      <view class="gcard__btn">
        {{ group.joined ? $t("group.joinedBtn") : $t("group.join") }}
      </view>
    </view>
  </view>
</template>

<style scoped>
/*
 * 团购卡是首页少数几个「真色块」之一 —— 它是**限时的、要立刻决定的**，
 * 与下面可以慢慢逛的商品流不是一类，所以要自己站得住。
 *
 * 用 faint 而不是 surface：首页已翻成白底，surface 在那儿等于透明，
 * 卡就化在背景里了；而在灰底页（团购页、店铺页）faint 同样比页底浅一档，两处都成立。
 */
/* 团购卡在「今日团」白块内，边界由外层白块给 —— 自己再描一圈是重复 */
.gcard {
  border-radius: 32rpx;
  /* 卡在「今日团」白块内，块自己给上下留白 —— 卡再加外边距会在块底叠出一道空白 */
  padding: 4rpx 26rpx;
}
.gcard__top {
  display: flex;
  gap: 24rpx;
}
.gcard__cover {
  width: 150rpx;
  height: 150rpx;
  border-radius: 32rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 96rpx;
  flex-shrink: 0;
}
.gcard__main {
  flex: 1;
  min-width: 0;
}
.gcard__title {
  display: block;
  font-size: 30rpx;
  font-weight: 600;
  line-height: 1.4;
  color: var(--sh-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.gcard__pickup {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 8rpx;
}
.gcard__price {
  display: flex;
  align-items: baseline;
  gap: 12rpx;
  margin-top: 18rpx;
}
.gcard__now {
  font-size: 40rpx;
  font-weight: 700;
  line-height: 1.2;
  color: var(--sh-ink);
}
.gcard__base {
  font-size: 24rpx;
  color: var(--sh-sub);
  text-decoration: line-through;
}
.gcard__goal {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin-top: 24rpx;
}
.gcard__goal-text {
  /* 强调靠**主色**，不靠字重 —— 这行已经是全卡唯一的彩色文字，
     再加一道粗体是同一件事说两遍。 */
  font-size: 26rpx;
  font-weight: 400;
  line-height: 1.5;
  color: var(--sh-primary-text);
}
.gcard__goal-text--max {
  color: var(--sh-success);
}
.gcard__cd {
  font-size: 24rpx;
  color: var(--sh-warning);
  flex-shrink: 0;
}
.bar {
  height: 12rpx;
  border-radius: 9999px;
  /* 槽用主色浅调：与进度条同色系，一眼读作「还没走完的部分」 */
  background: var(--sh-primary-tint);
  margin-top: 14rpx;
  overflow: hidden;
}
.bar__fill {
  height: 100%;
  border-radius: 9999px;
  background: var(--sh-primary);
  transition: width 0.3s ease;
}
.gcard__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin-top: 22rpx;
}
.avatars {
  display: flex;
  align-items: center;
  gap: 8rpx;
  min-width: 0;
}
.avatars__a {
  width: 46rpx;
  height: 46rpx;
  border-radius: 9999px;
  background: var(--sh-primary-tint);
  text-align: center;
  line-height: 46rpx;
  font-size: 24rpx;
}
.avatars__n {
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-inline-start: 8rpx;
}
.gcard__btn {
  flex-shrink: 0;
  padding: 16rpx 36rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 26rpx;
  font-weight: 600;
}
</style>
