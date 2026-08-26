<script setup lang="ts">
// 「＋ 加一项」按钮。
//
// **收进库的理由不是复用，是它已经是同一份代码了**：2026-08-26 扫出来，
// `goods-edit` 与 `my-specs` 的 `.btn-add` / `.btn-add__t` **逐字节相同**，
// 而两页谁也不知道另一页存在 —— 下一次有人改其中一处，两页就分叉了。
// 同一批扫描里 `.build` / `.chips` / `.opt` 已经分叉了，它们当初也是复制过去的。
//
// **带字，不是裸图标**（判断照抄 my-specs 的注释）：一个 ＋ 摆在标题栏里
// 认不出是加什么 —— 那一页上「加规格」与「加档位」是两件事，入口还离得不远。
//
// `active` 是**展开中**态：同一个按钮管开合，不必再找关掉它的地方 ——
// 图标换 ✕、底色褪成描边、文案换成「收起」。
import { computed } from "vue";

const props = withDefaults(
  defineProps<{
    text: string;
    /** 展开态的文案（如「收起」）。不给就沿用 text */
    activeText?: string;
    /** 展开中 */
    active?: boolean;
    /** 小一号：嵌在内容行里用（如每个参数后面的「＋ 加值」） */
    small?: boolean;
  }>(),
  { activeText: "", active: false, small: false },
);

defineEmits<{ (e: "tap"): void }>();

const label = computed(() => (props.active && props.activeText ? props.activeText : props.text));
</script>

<template>
  <view class="add" :class="{ 'add--on': active, 'add--sm': small }" @tap="$emit('tap')">
    <sh-icon
      :name="active ? 'close' : 'plus'"
      :size="small ? 20 : 24"
      :color="active ? 'var(--sh-sub)' : 'var(--sh-primary)'"
    ></sh-icon>
    <text class="add__t">{{ label }}</text>
  </view>
</template>

<style scoped>
.add {
  display: flex;
  align-items: center;
  gap: 8rpx;
  padding: 10rpx 22rpx;
  border-radius: 9999px;
  background: var(--sh-primary-tint);
}
/* 展开中褪成描边。**理由从 my-specs 一起搬过来**（收编时那段注释会跟着 CSS 一起消失，
   而它记的是一次真实的返工）：上一版展开后仍填实心主色，于是一个「收起」
   比下面所有可点的规格都抢眼 —— 而它恰恰是这一刻最不需要被点的那个。
   认得出、不喊叫。
   **内边距同时减 2rpx**：描边多出上下各 2rpx，不减的话按钮会在展开那一下
   自己长高 4rpx，标题行跟着抖一下。这条与 base.css 里 .sh-btn--danger
   的处理是同一个理由（那里也是实心与描边并排时要对齐基线）。
   收编前的 .btn-add--on 没有减，所以是抖的。 */
.add--on {
  background: transparent;
  border: 2rpx solid var(--sh-line);
  padding: 8rpx 20rpx;
}
/* 小一号：跟在一排 chip 后面时不该比它们高（goods-edit 的「＋ 加值」就是这个位置） */
.add--sm {
  padding: 4rpx 16rpx;
}
.add--sm.add--on {
  padding: 2rpx 14rpx;
}
.add__t {
  font-size: 24rpx;
  font-weight: 600;
  color: var(--sh-primary-text);
}
.add--on .add__t {
  color: var(--sh-sub);
}
</style>
