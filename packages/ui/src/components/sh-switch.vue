<script setup lang="ts">
// 开关：一个滑块，开／关。
//
// **全 B 端没有一处用原生 `<switch>`**（2026-08-26 扫的），而三个页面
// 各画了一个：`apply` 的自提点、`points` 的积分开关、`store-scope` 的渠道开关。
// 三个数**没有一个对得上**：
//
//   apply        88×48  胶囊     滑块 40  位移 40
//   points       84×48  **24rpx（根本不是胶囊）**  滑块 36  位移 42
//   store-scope  88×48  胶囊     滑块 36  位移 46
//
// 它们不是「同一个东西写歪了」——是**同一个东西被画了三遍**，而三遍都不知道
// 另外两遍存在。取 88×48 胶囊（三分之二的页面本来就是）、滑块 40、位移 40：
// **`88 - 40 - 4×2 = 40`**，位移由几何算出来，不是量出来的。
//
// **为什么不用原生 `<switch>`**：它的颜色只能给一个 `color`，跟不了皮肤的明暗，
// 尺寸在三端各不相同 —— 这与自定义 tabBar 的三条理由是同一类问题（见 sh-tabbar）。
withDefaults(
  defineProps<{
    modelValue: boolean;
    /** 不可用：压暗且不响应。`points` 的「未开通不能开」就是这个态 */
    disabled?: boolean;
  }>(),
  { disabled: false },
);

const emit = defineEmits<{ (e: "update:modelValue", v: boolean): void }>();

function toggle(v: boolean) {
  emit("update:modelValue", !v);
}
</script>

<template>
  <view
    class="sw"
    :class="{ 'is-on': modelValue, 'is-off': disabled }"
    @tap="disabled || toggle(modelValue)"
  >
    <view class="sw__knob"></view>
  </view>
</template>

<style scoped>
.sw {
  position: relative;
  flex: none;
  width: 88rpx;
  height: 48rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  transition: background 0.2s ease;
}
.sw.is-on {
  background: var(--sh-primary);
}
/* 不可用：压暗而不是变灰 —— 变灰会与「关着」撞成一个样 */
.sw.is-off {
  opacity: 0.45;
}
.sw__knob {
  position: absolute;
  top: 4rpx;
  inset-inline-start: 4rpx;
  width: 40rpx;
  height: 40rpx;
  border-radius: 9999px;
  /* 滑块用 surface 不是 #fff：深色皮肤下纯白会刺眼，而 surface 跟着明暗走 */
  background: var(--sh-surface);
  transition: transform 0.2s ease;
}
/* 位移 = 88 − 40 − 4×2 = 40。算出来的，不是量出来的 */
.sw.is-on .sw__knob {
  transform: translateX(40rpx);
}
/* RTL 下滑块要往另一头走 */
.sh-root.is-rtl .sw.is-on .sw__knob {
  transform: translateX(-40rpx);
}
</style>
