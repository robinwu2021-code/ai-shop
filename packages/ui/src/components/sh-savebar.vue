<script setup lang="ts">
// 底部「还没保存」条：左边说明，右边放弃 / 保存。
//
// **收进库的理由与 sh-add 一样**：2026-08-26 扫出来，`store` 与 `store-scope`
// 的 `.savebar` / `.savebar__t` / `.savebar__save` **逐字节相同**。
//
// **为什么是 props 而不是插槽**：插槽内容吃不到组件的 scoped 样式
//（base.css 里那段表单件的注释记过同一件事），于是两个按钮的尺寸还是得各页各写 ——
// 而它们不一致正是收编前的现状：`store` 的「放弃」是一枚 tint 小 chip（本地 .mini），
// `store-scope` 的是 muted 按钮。定成 props，这个分叉就没有地方再长出来。
// 将来真出现第三种动作组合，再加插槽不迟；现在加等于把已知的分叉留一扇门。
//
// **自带占位**：这条是 fixed 的，不占流内高度 —— 收编前两页都没给内容留位，
// 一旦 dirty，页面最后一段就被它盖住了（要往上滚一点才看得见）。
// 这里在流内放一块等高的空白，跟着 v-if 一起出现，页面不必各自处理。
defineProps<{
  /** 有未保存的改动时才出现 */
  visible: boolean;
  /** 左边那句话，如「有未保存的修改」 */
  text: string;
  discardText: string;
  saveText: string;
}>();

defineEmits<{ (e: "discard"): void; (e: "save"): void }>();
</script>

<template>
  <view v-if="visible">
    <!-- 流内占位。**高度取得略宽松**：真实高度取决于按钮字号与安全区，
         逐像素对齐要么写死一个会过期的数、要么运行时量一次 ——
         而这块空白只要「不挡住内容」就够了，多出的十几 rpx 没有代价。 -->
    <view class="bar__pad"></view>
    <view class="bar">
      <text class="bar__t">{{ text }}</text>
      <text class="sh-btn sh-btn--muted bar__discard" @tap="$emit('discard')">{{ discardText }}</text>
      <view class="sh-btn bar__save" @tap="$emit('save')">{{ saveText }}</view>
    </view>
  </view>
</template>

<style scoped>
.bar__pad {
  height: calc(140rpx + constant(safe-area-inset-bottom));
  height: calc(140rpx + env(safe-area-inset-bottom));
}
.bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  /* 宽屏下跟着应用框收窄：sh-scaffold 的 transform 让 fixed 以框为包含块，
     所以这里不必自己算 left/right（见 sh-scaffold 的注释） */
  z-index: 40;
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 20rpx 24rpx;
  padding-bottom: calc(20rpx + constant(safe-area-inset-bottom));
  padding-bottom: calc(20rpx + env(safe-area-inset-bottom));
  background: var(--sh-surface);
  /* 这条 hairline 与 sh-tabbar 顶部那条同源：白条压在浅灰页面上，
     不加分界时列表像是溢出到条里去了 */
  border-top: var(--sh-hairline);
}
.bar__t {
  flex: 1;
  font-size: 26rpx;
  color: var(--sh-sub);
}
.bar__discard {
  flex-shrink: 0;
  padding: 24rpx 32rpx;
  font-size: 26rpx;
  font-weight: 400;
}
.bar__save {
  padding: 20rpx 48rpx;
}
</style>
