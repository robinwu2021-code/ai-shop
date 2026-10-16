<script setup lang="ts">
// 单条评价：评分、内容、晒图、商家回复、点赞。
import { monthDay } from "@shared/utils/format";
import type { Review } from "@shared/types";

defineProps<{ review: Review }>();
defineEmits<{ (e: "like"): void }>();
</script>

<template>
  <view class="rv">
    <view class="sh-row rv__head">
      <text class="rv__avatar">{{ review.avatar }}</text>
      <view class="sh-fill rv__who">
        <text class="txt-strong rv__name">{{ review.nickname }}</text>
        <!-- single-review：这是**某个人给的星数**，不是聚合评分，不需要 ratingCount 护栏 -->
        <sh-rating :value="review.rating" :size="22" :show-value="false"></sh-rating>
      </view>
      <text class="txt-caption txt-quiet rv__date sh-num">{{ monthDay(review.createdAt) }}</text>
    </view>

    <text class="txt-body rv__content">{{ review.content }}</text>

    <view v-if="review.images.length" class="rv__imgs">
      <sh-cover v-for="(img, i) in review.images" :key="i" class="rv__img" :src="img" :w="200"></sh-cover>
    </view>

    <text class="txt-caption txt-quiet rv__spec">{{ review.spec }}</text>

    <view v-if="review.reply" class="sh-notice sh-notice--muted rv__reply">
      <text class="txt-caption txt-quiet rv__reply-text">
        <text class="txt-bold txt-primary rv__reply-tag">{{ $t("merchant.reply") }}</text>
        {{ review.reply }}
      </text>
    </view>

    <view class="rv__foot">
      <view class="sh-row sh-hit like" :class="{ 'is-on': review.liked }" @tap="$emit('like')">
        <text class="like__icon">{{ review.liked ? "♥" : "♡" }}</text>
        <text class="txt-caption txt-quiet like__count sh-num">{{ review.likeCount }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.rv + .rv {
  margin-top: 32rpx;
}
.rv {
  min-width: 0;
}
.rv__date {
  flex-shrink: 0;
  white-space: nowrap;
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
.rv__name {
  display: block;
  color: var(--sh-ink);
  margin-bottom: 4rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rv__content {
  display: block;
  color: var(--sh-ink);
  margin-top: 16rpx;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.rv__imgs {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 16rpx;
}
.rv__img {
  flex-shrink: 0;
  width: 140rpx;
  height: 140rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  font-size: 60rpx;
}
.rv__spec {
  display: block;
  color: var(--sh-sub);
  margin-top: 16rpx;
}
.rv__reply {
  margin-top: 16rpx;
}
.rv__reply-text {
  display: block;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.rv__foot {
  display: flex;
  justify-content: flex-end;
  margin-top: 12rpx;
}
.like {
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
  transition: transform var(--sh-t-base) var(--sh-ease-spring);
}
.like.is-on .like__icon {
  color: var(--sh-danger);
  transform: scale(1.25);
}
.like.is-on .like__count {
  color: var(--sh-danger);
}
</style>
