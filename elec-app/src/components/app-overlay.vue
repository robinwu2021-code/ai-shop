<script setup lang="ts">
// 端上的全局浮层插槽：sh-scaffold 每页都会渲染一个 <app-overlay>，由各端自己提供
// （c-app 在这里放「飞入购物车」的小球）。元器件没有这类浮层，所以是空的 ——
// 但**不能没有这个组件**：缺了它每渲染一页就报一条「Failed to resolve component」，一晚上积了六千条。
// 测试期并进 c-app 时，分包里的页面按 c-app 的 easycom 规则解析到 c-app 那一份。
</script>

<template>
  <view v-if="false"></view>
</template>
