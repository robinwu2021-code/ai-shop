<script setup lang="ts">
// 朋友圈单页模式的页顶提示（TDD-C端朋友圈分享修补 AC3）。
//
// 从朋友圈卡片进来时只能看，不能登录、下单或跳页；底部那条「前往小程序」是唯一出口。
// 不说的话，他点加购没反应、点商品进不去，只会以为页面坏了。其它场景下不渲染。
import { inTimelineSinglePage } from "@shared/ports/share";

const show = inTimelineSinglePage();
</script>

<template>
  <view v-if="show" class="sh-notice sh-notice--muted spt">
    <text class="txt-sub">{{ $t("share.singlePageTip") }}</text>
  </view>
</template>

<style scoped>
/* 上下间距由 sh-scaffold 的块间缝给（小程序那份名单已登记这个标签），这里只管左右 */
.spt {
  margin-inline: 24rpx;
}
</style>
