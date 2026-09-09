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

/*
 * 占位块的高度。**安全区不写在这里** —— 内联样式没法像 CSS 那样「后一条覆盖前一条」，
 * 而 `env()` 在部分 Android 内核里不认：一旦它进了 `calc()`，整条声明失效、
 * 高度塌成 0，最后一行内容就被条压住了（2026-09-05 真机上撞到的正是这个）。
 *
 * 所以这里只算「条本身要占多高」，安全区那一段由下面 `.ab__pad` 的
 * `padding-bottom` 单独加 —— 那是**另一个属性**，不认 `env()` 的设备最多少掉那一段，
 * 不会把高度一起赔进去。
 */
const padStyle = computed(() => ({
  height: props.tabbar
    ? `calc(${props.pad}rpx + var(--sh-tabbar-h, 124rpx))`
    : `${props.pad}rpx`,
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
/* 安全区单独加在 padding 上，理由见 padStyle 的注释 */
.ab__pad {
  /* 兜底写 0：不认 env() 的内核上就是没有安全区，而不是整条声明失效 */
  padding-bottom: 0;
  padding-bottom: constant(safe-area-inset-bottom, 0px);
  padding-bottom: env(safe-area-inset-bottom, 0px);
  box-sizing: content-box;
}
.ab {
  position: fixed;
  inset-inline: 28rpx;
  bottom: 28rpx;
  bottom: calc(28rpx + constant(safe-area-inset-bottom, 0px));
  bottom: calc(28rpx + env(safe-area-inset-bottom, 0px));
  z-index: var(--sh-z-actionbar);
}
/* 有底部菜单的页面：压在菜单之上。**高度走变量不抄数字** ——
   菜单高度改一次，这里跟着变（cart 的注释记着它曾经被菜单盖住过）。 */
.ab--tabbar {
  /* 贴着菜单，不留缝：那 20rpx 的灰缝正是「两块板没对齐」的来源（见下面那段） */
  inset-inline: 0;
  bottom: var(--sh-tabbar-h, 124rpx);
  bottom: calc(var(--sh-tabbar-h, 124rpx) + constant(safe-area-inset-bottom, 0px));
  bottom: calc(var(--sh-tabbar-h, 124rpx) + env(safe-area-inset-bottom, 0px));
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

/*
 * **压在底部菜单上时不做药丸，做通栏。**
 *
 * 两种形状在屏幕底部打架：这条是全圆药丸、左右内缩 28rpx、浮在灰底上；
 * 而 `sh-tabbar` 是方角通栏、贴底、顶上一条发丝线。两块都是白的，
 * 中间夹着 20rpx 的灰缝 —— **那条缝太窄，读不成「分开」，
 * 形状又不同，读不成「一体」**，于是看着像两块没对齐的板。
 * 全圆端头在屏幕底部也是孤例：那一片再没有第二个圆角这么大的东西。
 *
 * 浮动药丸这个形态本身是对的，但它对的是**没有底部菜单**的页面 ——
 * 那时它浮在内容之上，下面是页面本身，圆角与阴影都说得通。
 * 有菜单时下面是另一条白栏，浮不起来。
 *
 * 所以这一档改成贴着菜单、通栏、方角、共用同一条发丝线：底部两行读成一块。
 * （淘宝 / 京东 / 拼多多的购物车结算条都是这个形态。）
 * 今天只有 `c-app/pages/cart` 一处用到 `tabbar`，其余调用点不受影响。
 */
.ab--tabbar.ab--plain,
.ab--tabbar.ab--lead {
  border-radius: 0;
  border-top: var(--sh-hairline);
}
/*
 * 通栏之后内边距走**页面留白**，让条里的内容与页面内容同一条边线。
 *
 * 药丸态下这里是 `16rpx 16rpx 16rpx 40rpx` —— 左边那 40 是为圆角留的
 *（见上面那段：文字贴着圆边会看着像被切掉一块）。方角之后这个理由没了，
 * 而照搬它会让「全选」比上面那行「共 N 件」往里缩约 20px，两条左边线对不齐。
 * 直接补成 68rpx（= 药丸原来的 28 内缩 + 40 内边距）也不对：那保住的是
 * **药丸的**光学位置，而现在参照物换成了页面。
 */
.ab--tabbar.ab--lead,
.ab--tabbar.ab--plain {
  padding-inline: var(--sh-pad-page, 28rpx);
}
</style>
