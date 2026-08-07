<script setup lang="ts">
// 应用常驻层：压在所有页面之上、不随页面切换消失的东西。
// 由组件库的 sh-scaffold 无条件渲染，**两端各有一份、名字必须一样** ——
// 动态组件（`<component :is>`）小程序端不支持，这是唯一跨四端都成立的写法。
//
// C 端这一层就是「飞入购物车」的小球（原 sh-fly-cart，合并到此处：
// 它只有这一个使用者，单独留一个组件只是多一层间接）。
// 加入购物车动效：一个小球从点击处飞向底部购物车图标，落点处角标弹一下。
//
// 实现取舍：用 fixed 定位 + CSS transition，不用 uni.createAnimation（后者在小程序与 H5
// 的行为差异较大），也不用 Web Animations API（小程序不支持）。
// 飞行轨迹用两段 transition 拼出抛物线感：横向匀速 + 纵向先慢后快。
import { computed } from "vue";
import { flyState } from "@/shared/fly";

const style = computed(() => {
  const s = flyState;
  return {
    left: `${s.x}px`,
    top: `${s.y}px`,
    transform: s.flying ? `translate(${s.dx}px, ${s.dy}px) scale(0.4)` : "translate(0,0) scale(1)",
    opacity: s.flying ? 0.2 : 1,
  };
});
</script>

<template>
  <view v-if="flyState.visible" class="fly" :style="style">
    <text class="fly__text">{{ flyState.emoji }}</text>
  </view>
</template>

<style scoped>
.fly {
  position: fixed;
  z-index: 200;
  width: 72rpx;
  height: 72rpx;
  margin-left: -36rpx;
  margin-top: -36rpx;
  border-radius: 9999px;
  background: var(--sh-primary-tint);
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
  /* 横向匀速、纵向后段加速 —— 拼出抛物线的观感 */
  transition:
    transform 0.62s cubic-bezier(0.42, 0.02, 0.72, 0.35),
    opacity 0.62s ease-in;
}
.fly__text {
  font-size: 36rpx;
  line-height: 1;
}
</style>
