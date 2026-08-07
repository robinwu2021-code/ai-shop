<script setup lang="ts">
// 应用常驻层：见 C 端同名文件。
// B 端没有购物车，也就没有飞入动效，这一层目前是空的 ——
// 但**不能删**：sh-scaffold 无条件渲染 <app-overlay>，缺了这个文件两端的外壳就不通用了。
</script>

<template>
  <view v-if="false"></view>
</template>
