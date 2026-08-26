<script setup lang="ts">
// 悬浮新建按钮：贴右下角，压在列表之上。
//
// **只有一个调用点（`goods-list` 的「＋ 新建商品」），仍然收进来** ——
// 收的不是重复，是**那一行 `bottom` 里的知识**：
//
// > 抬到 tabBar 上方一指宽。128rpx 时它几乎贴着菜单，拇指落点与「商品」那个
// > tab 只差几毫米 —— 想点新建却切了页。
//
// 这句话是踩出来的，而它此前写死成 `calc(190rpx + env(...))`：**190 是
// 「tabBar 约 130rpx ＋ 空当 60rpx」凑出来的数**，而 tabBar 的真高就在
// `--sh-tabbar-h`（124rpx）里放着。改菜单高度时那个 190 不会跟着动 ——
// 按钮就又贴回菜单上了，而且没有任何症状能提示这件事。
// 这里改成 `calc(var(--sh-tabbar-h) + 60rpx + 安全区)`：**跟着变量走**。
//
// 下一个想放悬浮按钮的页面不必再推一遍这个数，这就是它值得占一个文件的理由。
withDefaults(defineProps<{ text: string }>(), {});
defineEmits<{ (e: "tap"): void }>();
</script>

<template>
  <view class="fab" @tap="$emit('tap')">{{ text }}</view>
</template>

<style scoped>
.fab {
  position: fixed;
  inset-inline-end: 32rpx;
  /* 见文件头：60rpx 是「一指宽」的空当，tabBar 的高走变量而不是抄一个数 */
  bottom: calc(var(--sh-tabbar-h) + 60rpx + constant(safe-area-inset-bottom));
  bottom: calc(var(--sh-tabbar-h) + 60rpx + env(safe-area-inset-bottom));
  z-index: 10;
  padding: 20rpx 36rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 28rpx;
  font-weight: 600;
  white-space: nowrap;
  /* 阴影用 scrim（皮肤里那层半透明黑）：写死 rgba 在深色皮肤下会糊成一团 */
  box-shadow: 0 8rpx 24rpx var(--sh-scrim);
}
</style>
