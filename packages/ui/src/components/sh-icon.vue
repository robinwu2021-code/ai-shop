<script setup lang="ts">
// 单色图标。
//
// 为什么不用 emoji：各系统字形不一致（同一个 🛒 在 iOS / Android / 微信里长得都不一样），
// 而且是彩色的 —— 没法跟着皮肤主色走，深色模式下也不协调。
//
// 为什么不用 <image>：图标颜色要跟随 4 套皮肤 × 明暗，位图得准备一堆变体。
//
// 做法：内联 SVG 转 data-URI 作为 CSS mask，颜色由 background-color 提供 —— 于是
// 图标颜色可以直接吃 var(--sh-*)，换肤零成本。mask 在 iOS/Android 的 WebView 与
// 微信小程序里都可用（带 -webkit- 前缀）。
import { computed } from "vue";
import { ICONS, type IconName } from "@shared/design/icons";

const props = withDefaults(
  defineProps<{ name: IconName; size?: number; color?: string }>(),
  { size: 44, color: "currentColor" },
);

/**
 * 有方向的图标：**阿语要镜像**。
 *
 * 这条是把 `›` 换成图标时才发现的：`›`（U+203A）在 Unicode 里是 bidi-mirrored 字符，
 * 浏览器在 RTL 语境下自己就把它翻成 `‹`（实测两张位图的水平镜像吻合 98.6%）。
 * **换成图标就不翻了** —— mask-image 是一张位图，不认方向。
 *
 * 也就是说：库里**已有的** `chevronRight` 调用点（me / verify / customers /
 * biz-region-picker）在阿语下一直是反的 —— 箭头指着来路而不是去路。
 * 没人报，因为要同时会阿语、又正好翻到那几页。
 *
 * 修在这里而不是各调用点：`direction: rtl` 会把 flex 行整体镜像（图标从右边挪到左边），
 * 唯独 glyph 本身不翻 —— 那是每一个方向性图标都要处理一次的事，只该处理一次。
 */
const DIRECTIONAL = new Set<IconName>(["chevronRight"]);
const directional = computed(() => DIRECTIONAL.has(props.name));

const style = computed(() => {
  const svg = ICONS[props.name];
  const url = `url("data:image/svg+xml;utf8,${encodeURIComponent(svg)}")`;
  return {
    width: `${props.size}rpx`,
    height: `${props.size}rpx`,
    backgroundColor: props.color,
    "-webkit-mask-image": url,
    "mask-image": url,
  };
});
</script>

<template>
  <view class="icon" :class="{ 'icon--dir': directional }" :style="style" />
</template>

<style scoped>
.icon {
  display: inline-block;
  flex-shrink: 0;
  -webkit-mask-repeat: no-repeat;
  mask-repeat: no-repeat;
  -webkit-mask-position: center;
  mask-position: center;
  -webkit-mask-size: contain;
  mask-size: contain;
}
</style>
