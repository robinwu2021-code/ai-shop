<script setup lang="ts">
// 居中对话框的**外壳**：遮罩 + 居中面板 + 可选标题 + 内容插槽 + 可选动作条。
//
// **为什么单独抽出来**：这个外壳此前有两份 ——
//   · `sh-confirm`（`confirm()` 的宿主，25 个调用点）
//   · `order` 的线下收款确认（`.mask` + `.dlg`），因为它中间要放一个 60rpx 的
//     应收金额，纯文字的 `confirm()` 装不下
// 两份的声明几乎逐条相同（居中、32rpx 圆角、34/600 标题、两个等宽动作），
// 差别只有面板宽度 560 / 600 —— 也就是说这不是「两种对话框」，是同一种画了两遍。
//
// **居中而不是贴底**：确认是一个「打断」，底部弹层是「继续往下做一步」。
// 两种意思用两种位置分开（见 sh-sheet）。
//
// ⚠️ `order` 那一份的面板底色写的是 `var(--sh-bg)` 而不是 `--sh-surface` ——
// 亮色下两者接近看不出来，**暗色下 bg 是纯黑、surface 是 #161719**，
// 于是那张卡在暗色里是一块纯黑。收编顺带修掉。
withDefaults(
  defineProps<{
    visible: boolean;
    /** 标题。不给就只有插槽内容（自己画标题的场合） */
    title?: string;
    /** 点遮罩要不要关。危险操作可以关掉，逼用户明确选一个 */
    maskClosable?: boolean;
  }>(),
  { title: "", maskClosable: true },
);

const emit = defineEmits<{ close: [] }>();
</script>

<template>
  <view v-if="visible" class="dlg">
    <view class="dlg__mask" @tap="maskClosable && emit('close')"></view>
    <view class="dlg__panel" @tap.stop>
      <text v-if="title" class="dlg__title">{{ title }}</text>
      <slot></slot>
      <view v-if="$slots.actions" class="dlg__acts">
        <slot name="actions"></slot>
      </view>
    </view>
  </view>
</template>

<style scoped>
.dlg {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
}
.dlg__mask {
  position: absolute;
  inset: 0;
  background: var(--sh-scrim);
}
/* `max-width: 82vw` 是给窄屏兜底：560rpx 在 320pt 的机器上会顶到两边 */
.dlg__panel {
  position: relative;
  width: 560rpx;
  max-width: 82vw;
  box-sizing: border-box;
  padding: 40rpx 36rpx 28rpx;
  border-radius: 32rpx;
  background: var(--sh-surface);
}
.dlg__title {
  display: block;
  font-size: 34rpx;
  font-weight: 600;
  line-height: 1.4;
  color: var(--sh-ink);
  margin-bottom: 12rpx;
}
/* 动作条。**等宽不能写在这里** —— `.dlg__acts > * { flex: 1 }` 会被编译成
   `> *[data-v-本组件]`，而插槽内容带的是调用方的 scope id，一行都不生效。
   所以按钮要自己挂全局的 `.sh-dialog__act`（见 base.css）。
   第一次漏了这条的结果：两个按钮挤成两个小药丸，等宽没了。 */
.dlg__acts {
  display: flex;
  gap: 16rpx;
  margin-top: 32rpx;
}
</style>
