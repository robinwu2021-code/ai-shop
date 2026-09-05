<script setup lang="ts">
// 空态。
//
// 抽出来的理由不是「代码重复」，是**观感会漂移**：这套壳原本在 27 个页面里各写一份
//（B 端 13、C 端 14），结构完全相同，只有 padding 在 60/80/100rpx 之间随手取值，
// 于是同一个 App 里空态高矮不一。写法一样却各存一份的东西，迟早各走各的。
//
// 只收一个文案：图标与插画留到有真实素材时再说 —— 现在放 emoji 会跟着系统字形变，
// 且与「扁平色块」的设计语言冲突。
// 两种形态（都来自现有页面的真实用法，不是我加的花样）：
//   默认  —— 独占一屏的空列表，带卡片底色
//   bare  —— 嵌在某个分组/卡片**内部**的空态，只有一行灰字，再套一层底色会出现「卡中卡」
withDefaults(defineProps<{ text?: string; compact?: boolean; bare?: boolean }>(), {
  text: "",
  compact: false,
  bare: false,
});
</script>

<template>
  <view class="empty" :class="{ 'sh-card': !bare, 'is-compact': compact, 'is-bare': bare }">
    <text class="sh-muted"><slot>{{ text }}</slot></text>
  </view>
</template>

<style scoped>
/* 空态的上下留白。**这个数原本是随手取的**（见下方 is-compact/is-bare 的来历），
   而它出现在一个「本来就没东西可看」的状态里，留白越大越像页面坏了。
   走变量：默认保持 C 端原样，B 端调紧。 */
.empty {
  text-align: center;
  padding: var(--sh-pad-empty, 72rpx) 24rpx;
}
/*
 * 页内小块的空态（某个分组下暂无内容、或嵌在卡片里），不必占满一屏。
 *
 * **两种形态取同一个内边距**：它们的区别在「有没有卡片底」，不在松紧 ——
 * 此前一个 40rpx 一个 60rpx，是两次各自随手取的。
 *
 * 走 `--sh-pad-empty` 的一半而不是再写死一个数：那个变量是**两端的密度旋钮**
 *（C 端 72、B 端 48），而此前只有默认形态跟着它走 —— 旋钮拧一下，
 * 三种形态里两种纹丝不动。现在 C 端 36 / B 端 24，都还在 4rpx 网格上。
 */
.empty.is-compact,
.empty.is-bare {
  padding: calc(var(--sh-pad-empty, 72rpx) / 2) 24rpx;
}
</style>
