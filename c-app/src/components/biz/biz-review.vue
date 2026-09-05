<script setup lang="ts">
// 单条评价：评分、内容、晒图、商家回复、点赞。
import { monthDay } from "@shared/utils/format";
import type { Review } from "@shared/types";

defineProps<{ review: Review }>();
defineEmits<{ (e: "like"): void }>();
</script>

<template>
  <view class="rv">
    <view class="rv__head">
      <text class="rv__avatar">{{ review.avatar }}</text>
      <view class="rv__who">
        <text class="txt-sub txt-bold rv__name">{{ review.nickname }}</text>
        <!-- single-review：这是**某个人给的星数**，不是聚合评分，不需要 ratingCount 护栏 -->
        <sh-rating :value="review.rating" :size="22" :show-value="false"></sh-rating>
      </view>
      <text class="txt-caption txt-quiet rv__date sh-num">{{ monthDay(review.createdAt) }}</text>
    </view>

    <text class="txt-sub rv__content">{{ review.content }}</text>

    <view v-if="review.images.length" class="rv__imgs">
      <view v-for="(img, i) in review.images" :key="i" class="rv__img">{{ img }}</view>
    </view>

    <text class="txt-caption txt-quiet rv__spec">{{ review.spec }}</text>

    <view v-if="review.reply" class="rv__reply">
      <text class="txt-caption rv__reply-text">
        <text class="txt-bold rv__reply-tag">{{ $t("merchant.reply") }}</text>
        {{ review.reply }}
      </text>
    </view>

    <view class="rv__foot">
      <view class="like" :class="{ 'is-on': review.liked }" @tap="$emit('like')">
        <text class="like__icon">{{ review.liked ? "♥" : "♡" }}</text>
        <text class="txt-caption like__count sh-num">{{ review.likeCount }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.rv + .rv {
  margin-top: 32rpx;
}
.rv__head {
  display: flex;
  align-items: center;
  gap: 16rpx;
}
.rv__avatar {
  width: 60rpx;
  height: 60rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  text-align: center;
  line-height: 60rpx;
  font-size: 30rpx;
  flex-shrink: 0;
}
.rv__who {
  flex: 1;
  min-width: 0;
}
.rv__name {
  display: block;
  color: var(--sh-ink);
  margin-bottom: 4rpx;
}
.rv__date {
  color: var(--sh-sub);
}
.rv__content {
  display: block;
  color: var(--sh-ink);
  margin-top: 16rpx;
}
.rv__imgs {
  display: flex;
  gap: 12rpx;
  margin-top: 16rpx;
}
.rv__img {
  width: 140rpx;
  height: 140rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 60rpx;
}
.rv__spec {
  display: block;
  color: var(--sh-sub);
  margin-top: 16rpx;
}
.rv__reply {
  margin-top: 16rpx;
  background: var(--sh-faint);
  border-radius: 24rpx;
  padding: 20rpx 24rpx;
}
.rv__reply-text {
  color: var(--sh-sub);
}
.rv__reply-tag {
  color: var(--sh-primary-text);
}
.rv__foot {
  display: flex;
  justify-content: flex-end;
  margin-top: 12rpx;
}
.like {
  display: flex;
  align-items: center;
  gap: 8rpx;
  padding: 8rpx 24rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
}
.like.is-on {
  background: var(--sh-danger-tint);
}
.like__icon {
  font-size: 26rpx;
  color: var(--sh-sub);
  line-height: 1;
  transition: transform 0.22s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.like.is-on .like__icon {
  color: var(--sh-danger);
  transform: scale(1.25);
}
.like__count {
  color: var(--sh-sub);
}
.like.is-on .like__count {
  color: var(--sh-danger);
}
</style>
