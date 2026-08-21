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
/* 页内小块的空态（如某个分组下暂无内容），不必占满一屏 */
.empty.is-compact {
  padding: 40rpx 24rpx;
}
.empty.is-bare {
  padding: 60rpx 24rpx;
}
</style>
