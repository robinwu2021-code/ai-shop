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
const props = withDefaults(defineProps<{ src?: string; mode?: string }>(), {
  src: "",
  mode: "aspectFill",
});

/** data: 也算 —— 拍照预览阶段给的是本地临时路径或 base64，同样不能当文字排 */
const isImg = (s: string): boolean => /^(https?:)?\/\//.test(s) || s.startsWith("data:") || s.startsWith("blob:") || s.startsWith("file://") || s.startsWith("/");
</script>

<template>
  <view class="cover">
    <image v-if="isImg(props.src)" :src="props.src" :mode="props.mode" class="cover__img" />
    <text v-else class="cover__emoji"><slot>{{ props.src }}</slot></text>
  </view>
</template>

<style scoped>
/* 居中用 flex 而不是靠调用点的 line-height：调用点那套是为文字写的，
   换成图片后 line-height 不再居中任何东西。overflow 是第二道闸（见上）。 */
.cover {
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  flex-shrink: 0;
}
.cover__img {
  width: 100%;
  height: 100%;
}
/* 字号/颜色继承调用点 —— 各处封面大小不同，这里不该定死 */
.cover__emoji {
  line-height: 1;
}
</style>
