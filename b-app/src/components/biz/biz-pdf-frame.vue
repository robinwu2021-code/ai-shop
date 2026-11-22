<script setup lang="ts">
/*
 * PDF 内嵌框：在页内显示一份 PDF（发票打印模拟等），src 是 ports/pdf-view 的 openPdf 给的 blob 地址。
 * 只有 H5 内嵌；App / 小程序由系统查看器打开，这里不渲染。
 */
defineProps<{ src: string; title: string }>();
</script>

<template>
  <!-- #ifdef H5 -->
  <view v-if="src" class="sh-card pdf-frame-wrap">
    <iframe :src="src" class="pdf-frame" :title="title"></iframe>
  </view>
  <!-- #endif -->
</template>

<style scoped>
.pdf-frame-wrap { padding: 0; overflow: hidden; }
.pdf-frame { width: 100%; height: 70vh; border: 0; display: block; }
</style>
