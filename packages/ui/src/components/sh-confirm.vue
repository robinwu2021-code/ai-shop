<script setup lang="ts">
// 确认弹层的壳。与 sh-prompt 同源，由 sh-scaffold 无条件渲染。
//
// **它能做而系统弹框做不到的那一件事**：危险操作用红实心确定键
//（`.sh-btn--danger-solid`）。设计语言里那一档写着「只留给二次确认的最终一击」，
// 而它此前两端引用数是 **0** —— 不是没人需要，是没有地方用它：
// 二次确认全在 `uni.showModal` 里，而那里没有我们的按钮。
import { closeConfirm, confirmState } from "../prompt";

const s = confirmState;
</script>

<template>
  <view v-if="s.visible" class="cf">
    <view class="cf__mask" @tap="closeConfirm(false)"></view>
    <view class="cf__panel">
      <text class="cf__title">{{ s.title }}</text>
      <text v-if="s.hint" class="cf__hint">{{ s.hint }}</text>
      <view class="cf__acts">
        <text v-if="!s.alert" class="sh-btn sh-btn--muted cf__act" @tap="closeConfirm(false)">
          {{ s.cancelText || $t("common.cancel") }}
        </text>
        <text
          class="sh-btn cf__act"
          :class="{ 'sh-btn--danger-solid': s.danger }"
          @tap="closeConfirm(true)"
        >{{ s.confirmText || $t("common.confirm") }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.cf {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
}
.cf__mask {
  position: absolute;
  inset: 0;
  background: var(--sh-scrim);
}
/* 居中而不是贴底：确认是一个「打断」，而底部弹层是「继续往下做一步」。
   两种意思用两种位置分开 —— 与 sh-prompt 贴底正好相反 */
.cf__panel {
  position: relative;
  width: 560rpx;
  max-width: 82vw;
  box-sizing: border-box;
  padding: 40rpx 36rpx 28rpx;
  border-radius: 32rpx;
  background: var(--sh-surface);
}
.cf__title {
  display: block;
  font-size: 34rpx;
  font-weight: 600;
  line-height: 1.4;
  color: var(--sh-ink);
}
.cf__hint {
  display: block;
  margin-top: 12rpx;
  font-size: 26rpx;
  line-height: 1.6;
  color: var(--sh-sub);
}
.cf__acts {
  display: flex;
  gap: 16rpx;
  margin-top: 32rpx;
}
.cf__act {
  flex: 1;
  padding: 22rpx 0;
}
</style>
