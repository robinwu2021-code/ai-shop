<script setup lang="ts">
// 键值行：左边一个名目，右边它的值。
//
// **五个页面各写了一份**，而它们其实是**两种排法**：
//
//   定宽键（键占固定一列，值跟着左对齐）
//     goods-edit `.kv`      键 160rpx   ← 与 qualifications **逐字节相同**
//     qualifications `.kv`  键 160rpx
//     sku-identity `.rule`  键 **180rpx** + 一条上边框
//
//   两端对齐（名目贴左、值贴右）
//     payment `.kv`         `justify-content: space-between`
//     member-detail `.kv`   同上
//
// **两种排法不是写歪了，是两件事**：定宽键用在「一行行填/看同一类字段」
//（值要对齐成一列才扫得快）；两端对齐用在「名目 + 一个数」（值贴右更像账）。
// 所以这里是一个组件两个形态，而不是硬统一成一种。
//
// 键宽默认 **160rpx**：三处里两处是它，且那两处逐字节相同。
// `sku-identity` 的 180 是因为它的名目更长（「货号规则」「条码规则」），
// 由调用点传 `keyWidth` —— **列宽是版面，不是这个件的属性**
//（与 `sh-uploader` 的格子尺寸、`sh-option` 的内边距同一条分工）。
withDefaults(
  defineProps<{
    label: string;
    /** 两端对齐：名目贴左、值贴右。默认是定宽键 */
    between?: boolean;
    /** 定宽键的列宽（rpx） */
    keyWidth?: number;
    /** 上边框：连排时用来分隔（`sku-identity` 的规则表） */
    divided?: boolean;
  }>(),
  { between: false, keyWidth: 160, divided: false },
);
</script>

<template>
  <view class="kv" :class="{ 'is-between': between, 'is-divided': divided }">
    <text class="kv__k" :style="between ? {} : { flex: `0 0 ${keyWidth}rpx` }">{{ label }}</text>
    <view class="kv__v"><slot></slot></view>
  </view>
</template>

<style scoped>
.kv {
  display: flex;
  align-items: center;
  gap: 16rpx;
}
.kv.is-between {
  justify-content: space-between;
}
/* 连排时的分隔线。`sku-identity` 原本写的是 `1rpx`，而这套界面的 hairline
   是 2rpx（sh-tabbar / sh-savebar 都是）—— 1rpx 在多数屏上会被凑整成 0 或 1，
   同一条线在两台手机上粗细不同 */
.kv.is-divided {
  padding: 12rpx 0;
  border-top: var(--sh-hairline);
}
.kv__k {
  font-size: 26rpx;
  color: var(--sh-sub);
}
.kv__v {
  flex: 1;
  min-width: 0;
}
/* 两端对齐时值贴右，且不该抢走剩余空间 */
.kv.is-between .kv__v {
  flex: none;
  text-align: end;
}
</style>
