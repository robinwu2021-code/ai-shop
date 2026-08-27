<script setup lang="ts">
// 悬浮内缩的贴底操作条 —— **第三种贴底形态**，此前库里没有。
//
// 三种形态各管各的场合，别混：
//   · `sh-savebar`  通栏贴底（left/right/bottom 全 0）。它是「你有未保存的改动」，
//                   压住整条底边是有意的：那是一个未完成的状态，要显眼到躲不开。
//   · `sh-fab`      右下角角标，避让 tabBar。它是「再来一个」，不挡内容。
//   · `sh-actionbar` 悬浮内缩通栏（两侧缩 28rpx、离底 28rpx）。它是这一页的**主动作**
//                   （加入购物车 / 去支付 / 提交），浮在内容之上但露出四边，
//                   让人看得见它下面还有东西。
//
// **收编的是 C 端 12 处**：9 个 `.actionbar` + 2 个 `.fab`（名字是 fab，
// 但 `inset-inline` 两侧都缩，其实是这一档）+ 1 个 `.checkoutbar`（多一层 tabBar 避让）。
// 九处的定位声明**逐字节相同**。
//
// ⚠️ **它同时拥有条和占位块，这是这个组件存在的主要理由。**
//
// 收编前 11 页**各写一份占位块**，而且名字都不一样
//（`spacer` / `fab__spacer` / `checkoutbar__spacer` / `actionbar__spacer`），
// 高度给出**六种**：140 / 160×2 / 180×4 / 200 / 220×2 —— 全是手估的，
// 因为条是 `fixed`，CSS 量不到它的真实高度，只能照着屏幕试出一个数。
//
// 条高与占位高是必须相等的两个数，散在 11 个地方靠人对。
// **不对齐不会报错，只是最后一行看不见** —— 而看不见的东西没有人会去报。
// 收进组件之后它们至少在同一个文件里，改一个必然想到另一个。
//
// （订正：我最初以为其中 4 页压根没有占位块，那是我 grep 只匹配了三个固定类名、
//   漏掉了 `fab__spacer` 这种命名。**判据太窄会让现状看起来比实际更糟** ——
//   和判据太宽一样是错的，只是错的方向反过来。）
import { computed } from "vue";

const props = withDefaults(
  defineProps<{
    /**
     * 药丸壳。不给的话只做定位，里面放什么就长什么样（多数页面放一个 `.sh-btn`）。
     *
     * 两档是逐处量出来的，不是先设计好的：
     *   · `plain` 一排东西等距（`goods` 的分享 / 购物车 / 加入按钮）
     *   · `lead`  左边一段文字、右边一个按钮 —— 左内边距要大出一截，
     *             文字才不会贴着药丸的圆边（`cart` 的合计 + 去结算、
     *             `order-confirm` 的应付 + 提交，两处**逐字节相同**）
     */
    pill?: "plain" | "lead";
    /** 这一页有底部菜单：条要压在菜单之上，不是盖住它 */
    tabbar?: boolean;
    /**
     * 占位块高度（rpx）。默认 180 够一行按钮；
     * 条里塞了两行（价格 + 按钮）的页面给 220。
     * **它没法自动算** —— 条是 fixed，CSS 量不到 slot 的高。
     * 所以这里明写一个数，而不是假装它会自己对上。
     */
    pad?: number;
  }>(),
  { pill: undefined, tabbar: false, pad: 180 },
);

const padStyle = computed(() => ({
  height: props.tabbar
    ? `calc(${props.pad}rpx + var(--sh-tabbar-h) + env(safe-area-inset-bottom))`
    : `calc(${props.pad}rpx + env(safe-area-inset-bottom))`,
}));
</script>

<template>
  <!-- 流内占位。它跟着条走，所以两者不会再对不上 -->
  <view class="ab__pad" :style="padStyle"></view>
  <view
    class="ab"
    :class="[pill ? `ab--${pill}` : '', { 'ab--tabbar': tabbar }]"
  >
    <slot></slot>
  </view>
</template>

<style scoped>
/* 两侧各缩 28rpx：露出四边是这一档与 `sh-savebar` 的全部区别 ——
   它说的是「这一页的主动作」，不是「你有一个未完成的状态」。
   宽屏下跟着应用框收窄：sh-scaffold 的 transform 让 fixed 以框为包含块（见其注释）。 */
.ab {
  position: fixed;
  inset-inline: 28rpx;
  bottom: calc(28rpx + constant(safe-area-inset-bottom));
  bottom: calc(28rpx + env(safe-area-inset-bottom));
  z-index: 40;
}
/* 有底部菜单的页面：压在菜单之上。**高度走变量不抄数字** ——
   菜单高度改一次，这里跟着变（cart 的注释记着它曾经被菜单盖住过）。 */
.ab--tabbar {
  bottom: calc(var(--sh-tabbar-h) + 20rpx + constant(safe-area-inset-bottom));
  bottom: calc(var(--sh-tabbar-h) + 20rpx + env(safe-area-inset-bottom));
}
.ab--plain,
.ab--lead {
  display: flex;
  align-items: center;
  border-radius: 9999px;
  background: var(--sh-surface);
}
.ab--plain {
  gap: 16rpx;
  padding: 12rpx;
}
/* 左内边距 40rpx：药丸是全圆角，文字贴着圆边会看着像被切掉一块。
   右边是按钮，它自己有内边距，所以三边都收到 16rpx */
.ab--lead {
  gap: 24rpx;
  padding: 16rpx 16rpx 16rpx 40rpx;
}
</style>
