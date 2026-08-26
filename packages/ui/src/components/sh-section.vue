<script setup lang="ts">
// 卡内标题行：左边一句标题，右边可选的动作。
//
// **收编的是哪一个**：`goods-edit` 与 `sku-identity` 的 `.sec`
//（`display:flex; align-items:center; justify-content:space-between`）**逐字节相同**，
// `my-specs` 的 `.cat__head` 是同一个形状多一层块内留白。合计 8 处调用点。
//
// **不收编的是哪一个**：`groups` 与 `plan` 里也有个 `.sec`，但那是
// `<text class="txt-title sec">` —— **卡外的分组小标题**，只有 margin，没有右侧动作。
// 名字一样、东西不同，归成一类会把「两种形态」误读成「一种被画了两遍」。
//
// **与 `.sh-block__head` 的关系，说清楚**：那是同一个想法在另一个容器里的样子
//（块内，故自带横向留白；`align-items: baseline`；没有 `space-between`）。
// 两者迟早该合成一件，但 `.sh-block__head` 在 C 端有 12 处引用，
// 合并是另一件事 —— 这里先如实记下重叠，不假装它不存在。
//
// **右侧动作直接放进默认插槽**，组件不给它套壳：
// `.sec` 是 `space-between`，多套一层就把「三个孩子摊开」变成「两组左右分」，
// 而 goods-edit 的 SKU 矩阵那一行正好是三个孩子（标题 / 字段切换 / 语言）。
// 不套壳＝各调用点的排布与收编前逐像素一致。
withDefaults(
  defineProps<{
    title: string;
    /** 用在 sh-block 这类**自身没有横向留白**的容器里时打开 */
    pad?: boolean;
  }>(),
  { pad: false },
);
</script>

<template>
  <view class="sec" :class="{ 'sec--pad': pad }">
    <text class="txt-title">{{ title }}</text>
    <slot></slot>
  </view>
</template>

<style scoped>
.sec {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
/* 块内用：横向留白与 .sh-block__head 取同一个 26rpx —— 两处对不齐的话，
   同一屏上「块的标题」会有两种缩进 */
.sec--pad {
  padding: 24rpx 26rpx 16rpx;
}
</style>
