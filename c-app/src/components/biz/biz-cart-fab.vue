<script setup lang="ts">
/**
 * 悬浮购物车入口。
 *
 * **为什么需要它**：能加购的页面有六个（`home` `category` `goods`
 * `merchant` `search` `store`），而其中**三个没有任何通往购物车的路**——
 * `merchant` / `search` / `store` 都不是 tab 页（没有底部菜单）、也没有操作条，
 * 加完购之后除了飞入动效那一下，屏幕上再没有任何东西提到购物车：
 * 既看不到件数，也点不进去，只能靠返回退回到 tab 页。
 *
 * `home` / `category` 有底部菜单那一格，`goods` 在自己的操作条里放了一个，
 * 所以这个件只补剩下的三页 —— **不是每一页都挂**：订单、支付、地址那些页面
 * 不卖东西，悬一个购物车在上面是噪音。
 *
 * ⚠️ **它同时是飞入动效的落点**。`flyToCart` 没有落点时会回落到「屏幕右下角」，
 * 而那个兜底与真实按钮差着一段 —— 小球飞到一个空处停下，看着像动效坏了。
 * 所以挂载后要把自己量出来报上去，离开时撤销，交还给 tab 页的底部菜单。
 *
 * ⚠️ **不做「空车就隐藏」**：它得先存在，第一次加购的小球才有地方落。
 * 空车时点进去看到的是空态与「去逛逛」，那也是一条正常的路。
 */
import { getCurrentInstance, nextTick, onMounted, onUnmounted } from "vue";
import { useCartStore } from "@/stores/cart";
import { clearCartAnchor, registerCartAnchor } from "@/shared/fly";
import { ROUTES } from "@shared/utils/constants";

const cart = useCartStore();
const instance = getCurrentInstance();

/** 角标上限。三位数会把圆形角标撑成一条，99+ 已经足够表达「很多」 */
const BADGE_MAX = 99;

function go() {
  uni.switchTab({ url: ROUTES.cart });
}

/*
 * 量自己的位置报给飞入动效。
 *
 * `nextTick` 不能省：`onMounted` 时这一层可能还没进渲染树（宿主页面往往把它
 * 挂在 `v-if="loaded"` 之下），量出来是空，而**量不到不会报错**——
 * 它只是静默回落到右下角那个兜底落点（`goods` 页的同一处注释记着这个坑）。
 */
onMounted(() => {
  nextTick(() => registerCartAnchor(".cartfab", instance?.proxy));
});
onUnmounted(() => clearCartAnchor());
</script>

<template>
  <!--
    流内占位块。**这是这个件必须自带的那一半** —— 它是 `fixed` 的，
    压在列表之上：不留这一段的话，列表**最后一行的「＋」会被永久盖住**，
    而那不是「滚一下就好」的临时遮挡，是一个按不到的按钮。
    （同一条道理写在 `sh-actionbar` 的注释里：条与占位块必须成对出现。）
  -->
  <view class="cartfab__pad"></view>
  <view class="cartfab sh-center" @tap="go">
    <sh-icon name="cartFilled" :size="44" color="var(--sh-on-primary)"></sh-icon>
    <text v-if="cart.count" class="sh-badge-count cartfab__badge sh-num">
      {{ cart.count > BADGE_MAX ? `${BADGE_MAX}+` : cart.count }}
    </text>
  </view>
</template>

<style scoped>
/* 96rpx 的按钮 + 上下各留一点。与下面 `bottom` 那个 96rpx 同源，
   改一个必然想到另一个 —— 它们就在相邻两条规则里 */
.cartfab__pad {
  height: calc(192rpx + env(safe-area-inset-bottom));
}

/*
 * 贴右下角。**不避让底部菜单**（与 `sh-fab` 的区别就在这一行）：
 * 这个件只挂在没有菜单的页面上，照着 `--sh-tabbar-h` 抬高会让它浮在半空。
 */
.cartfab {
  position: fixed;
  inset-inline-end: 32rpx;
  bottom: calc(96rpx + constant(safe-area-inset-bottom));
  bottom: calc(96rpx + env(safe-area-inset-bottom));
  z-index: 10;
  width: 96rpx;
  height: 96rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  /* 阴影走 scrim（皮肤里那层半透明黑）：写死 rgba 在深色皮肤下会糊成一团 */
  box-shadow: 0 8rpx 24rpx var(--sh-scrim);
}
/* 角标压在右上角外沿。定位归调用点，`.sh-badge-count` 只管长相（见它的注释） */
.cartfab__badge {
  position: absolute;
  top: -6rpx;
  inset-inline-end: -6rpx;
}
</style>
