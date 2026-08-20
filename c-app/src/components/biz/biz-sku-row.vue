<script setup lang="ts">
/**
 * 商品行：图 + 名 + 规格，右侧与名下各留一个扩展位。
 *
 * **为什么需要它**：购物车、订单列表、订单详情、确认页、售后、评价 六个页面
 * 各写了一份 `.row / .row__cover / .row__main / .row__title / .row__spec`。
 * 骨架完全相同，值却在漂：圆角 22 / 24 / 28rpx 三种（都不在圆角五档上）、
 * 间距 20 / 24rpx 两种。没人是故意写不一样的 —— 只是复制过去之后各自微调了一下。
 *
 * **哪些差异是保留的**：图的尺寸分两档。购物车与确认页的图更大（`lg`），
 * 因为那两页用户还在挑、还会改主意；订单与售后是既成事实，图只用来认出是哪件（`md`）。
 * 这是有理由的差异，不是漂移，所以做成参数而不是抹平。
 *
 * **两个扩展位**：
 * - 默认插槽在**名与规格之下**（购物车的赠品行与数量步进器、确认页的单价×数量）
 * - `#right` 在**行的右侧**（订单列表的单价与件数）
 * 只有这两处会变；再多的变化应该说明它不是「商品行」，而是另一种东西。
 */
withDefaults(
  defineProps<{
    cover: string;
    title: string;
    spec?: string;
    /** md：已成交（订单/售后/评价）· lg：还在挑（购物车/确认页） */
    size?: "md" | "lg";
  }>(),
  { spec: "", size: "md" },
);
</script>

<template>
  <view class="skurow" :class="`skurow--${size}`">
    <sh-cover class="skurow__cover" :src="cover"></sh-cover>
    <view class="skurow__main">
      <text class="skurow__title">{{ title }}</text>
      <text v-if="spec" class="skurow__spec">{{ spec }}</text>
      <slot />
    </view>
    <slot name="right" />
  </view>
</template>

<style scoped>
.skurow {
  display: flex;
  align-items: center;
  gap: 24rpx;
}
/* 行距归组件自己管。原先由各页面的 `.row { margin-bottom }` + `.row:last-child { 0 }`
   两条规则配合，六个页面各写一遍且值不一（24 / 28rpx）；
   写成相邻兄弟选择器后，最后一行天然没有多余外边距，不需要那条 last-child。 */
.skurow + .skurow {
  margin-top: 24rpx;
}
/* 图与内容的对齐：内容只有两行时居中最稳；有插槽内容时由页面自己的插槽撑开，
   仍居中 —— 之前六处里有的写 center 有的不写，同一种行在不同页面对齐方式不同。 */
.skurow__cover {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--sh-faint);
  /* 圆角走 token 的 md 档 —— 原先 22 / 24 / 28rpx 三种，前两者根本不在五档上 */
  border-radius: 24rpx;
}
.skurow--md .skurow__cover {
  width: 108rpx;
  height: 108rpx;
  font-size: 48rpx;
}
.skurow--lg .skurow__cover {
  width: 128rpx;
  height: 128rpx;
  font-size: 48rpx;
}
.skurow__main {
  flex: 1;
  min-width: 0;
}
.skurow__title {
  display: block;
  font-size: 30rpx;
  font-weight: 600;
  line-height: 1.4;
  color: var(--sh-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.skurow__spec {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 6rpx;
}
</style>
