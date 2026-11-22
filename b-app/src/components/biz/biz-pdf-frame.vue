<template>
  <!-- 只有 H5 内嵌：src 来自 ports/pdf-view 的 openPdf（blob 地址）；其它端由系统查看器打开，这里不渲染 -->
  <!-- #ifdef H5 -->
  <view v-if="src" class="sh-card pdf-frame-wrap">
    <iframe :src="src" class="pdf-frame" :title="title"></iframe>
  </view>
  <!-- #endif -->
</template>

<script setup lang="ts">
defineProps<{ src: string; title: string }>();
</script>

<style scoped>
.pdf-frame-wrap { padding: 0; overflow: hidden; }
.pdf-frame { width: 100%; height: 70vh; border: 0; display: block; }
</style>
