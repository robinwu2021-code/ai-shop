<script setup lang="ts">
// 选项卡片：整块可选，选中时描边变主色 + tint 底。
//
// 用在「这一屏要在三四个方案里挑一个」的地方 —— 比 chip 重，比按钮轻：
// 每个选项里通常还有一行说明，chip 装不下。
//
// **四个页面各画了一个**（2026-08-26），描边与圆角没有一处对得上：
//
//   activity-edit    .opt   2rpx faint        **12rpx（越档）**
//   member-settings  .opt   2rpx faint        **12rpx（越档）**
//   store-categories .opt   2rpx line         16rpx
//   stores           .pick  **3rpx** line     **12rpx（越档）**
//
// 三处圆角 12rpx 都不在五档上（8/12/16/22px = 16/24/32/44rpx）。取 **16rpx**：
// 它在档上，且是四处里唯一合规的那个（`store-categories`）。
//
// 描边取 `2rpx var(--sh-line)`：`faint` 是「弱色块」的底色，拿来当线本来就是借用；
// `line` 才是分隔线那一档。**选中时描边与底色一起换** ——
// 只换描边的话，在小屏上两个 2rpx 的差别要凑近看。
withDefaults(
  defineProps<{
    selected?: boolean;
    disabled?: boolean;
  }>(),
  { selected: false, disabled: false },
);

const emit = defineEmits<{ (e: "tap"): void }>();
</script>

<template>
  <view
    class="opt"
    :class="{ 'is-on': selected, 'is-off': disabled }"
    @tap="disabled || emit('tap')"
  >
    <slot></slot>
  </view>
</template>

<style scoped>
.opt {
  padding: 20rpx;
  border-radius: 16rpx;
  border: 2rpx solid var(--sh-line);
  background: transparent;
}
/* 描边与底色一起换：只换描边的话，两个 2rpx 的差别要凑近看 */
.opt.is-on {
  border-color: var(--sh-primary);
  background: var(--sh-primary-tint);
}
.opt.is-off {
  opacity: 0.45;
}
</style>
