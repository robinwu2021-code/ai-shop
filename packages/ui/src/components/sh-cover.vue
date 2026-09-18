<script setup lang="ts">
// 商品封面。**`cover` 是一个二义字段** —— 种子数据里是 emoji（🍚），
// 商家自己拍完上传后是一条 COS 的 https URL。
//
// 抽出来的理由不是复用，是**没有一个页面分流过这两种值**：14 处渲染点
// 一律 `<text>{{ cover }}</text>`，于是商家一上传真图，那一行就把
// 「https://hxmall-merchant-…/goods/….jpeg」按 60rpx 字号铺出去 ——
// 96rpx 的框根本装不下，文字漫出来盖住整页，下面的卡片全点不着。
// 症状不在上传那一步，而在**任何一个列出该商品的页面**，包括 C 端顾客看的那些。
//
// 所以这里做两件事，缺一不可：
//   ① 按值分流：像 URL 就走 <image>，否则当 emoji 当文字排
//   ② `overflow: hidden`：将来再有什么意外的值落进来，它也只能糊自己那一格
//
// 尺寸/圆角/底色仍由调用点的 class 决定（各处大小本就不同），这里只管
// 「渲染成什么」。class 落在根 view 上，内层图片撑满即可。
/** uni `<image>` 的裁剪模式。**收成联合类型而不是 string**：
 *  写错一个字母（`aspectfill`）时 image 会静默退回 `scaleToFill`，图被拉变形，
 *  而类型检查一声不吭 —— 这一族的值只有九个，没有理由让它是自由文本。 */
type CoverMode = "scaleToFill" | "aspectFit" | "aspectFill" | "widthFix" | "heightFix"
  | "top" | "bottom" | "center" | "left" | "right";

const props = withDefaults(defineProps<{ src?: string; mode?: CoverMode }>(), {
  src: "",
  mode: "aspectFill",
});

/** data: 也算 —— 拍照预览阶段给的是本地临时路径或 base64，同样不能当文字排 */
const isImg = (s: string): boolean => /^(https?:)?\/\//.test(s) || s.startsWith("data:") || s.startsWith("blob:") || s.startsWith("file://") || s.startsWith("/");
</script>

<template>
  <view class="sh-center cover">
    <image v-if="isImg(props.src)" :src="props.src" :mode="props.mode" class="cover__img" />
    <text v-else class="cover__emoji"><slot>{{ props.src }}</slot></text>
  </view>
</template>

<style scoped>
/* 居中用 flex 而不是靠调用点的 line-height：调用点那套是为文字写的，
   换成图片后 line-height 不再居中任何东西。overflow 是第二道闸（见上）。 */
.cover {
  overflow: hidden;
  flex-shrink: 0;
  /*
   * **圆角在这里，不在调用点。** 2026-09-08 实测：把一条真实图片 URL 放进种子，
   * 商品卡上渲染出来的是一个**直角硬方块** —— 而同屏每一个别的东西都是圆角。
   * 原因是这个框的圆角此前由调用点各给一个：`skurow__cover` 24rpx、
   * `freq__cover` 16rpx、`card__cover` **一个都没有**。
   *
   * 它对 emoji 无所谓（emoji 没有底色，圆角切不到东西），所以三种写法长期看不出区别 ——
   * **真图落地的那一刻才同时暴露**。而 `card__cover` 的注释里还写着
   * 「真实商品图上线后这里直接换成 <image>，尺寸不用再动」：尺寸确实不用动，
   * 缺的是框，而那句话让人以为已经想过了。
   *
   * 24rpx = 圆角五档的 md，与 `.field__input`、`.sh-notice` 同档。
   */
  border-radius: 24rpx;
}
.cover__img {
  width: 100%;
  height: 100%;
}
/* #ifdef MP-WEIXIN */
/*
 * **小程序上要自己把调用点给的那个框撑满。**
 *
 * 调用点是这么给尺寸的：`<sh-cover class="card__cover">`，而 `.card__cover`
 * 写在调用方的样式里。两端落点不一样：
 *
 * · H5：组件根元素**就是**那个带 class 的元素 —— 尺寸直接落在 `.cover` 上；
 * · 小程序：class 落在**宿主节点** `<sh-cover>` 上，而 `.cover` 是组件内部的
 *   另一个 view。调用方的样式进不到组件内部，于是宿主有 168rpx、
 *   `.cover` 却是 height:auto，里面 `height:100%` 的 <image> 算出来就是 **0**。
 *
 * 症状是「图不显示」：节点在、请求也发了，就是没有高度。
 * 页面上看不出是没加载还是没尺寸 —— 底色本来就是占位灰。
 * 2026-09-18 商品第一次挂真图才暴露出来，此前所有封面都是 emoji（走 <text>，
 * 由字号撑开，不依赖父级高度），所以这条路从来没被走到过。
 *
 * 只在小程序加：H5 上加了会和调用点的尺寸打架（只给了宽的地方会被塞上高）。
 */
.cover {
  width: 100%;
  height: 100%;
}
/* #endif */
/* 字号/颜色继承调用点 —— 各处封面大小不同，这里不该定死 */
.cover__emoji {
  line-height: 1;
}
</style>
