<script setup lang="ts">
// 筛选条（chip 横排）。
//
// 抽之前两端有**两套实现**：一套是 `sh-chip` 横排（B 端订单/商品/客户），
// 一套是自定义 `tabs__item` 方块（C 端券包/团购）。同一个产品里两种筛选条，
// 用户会以为是两种不同的控件。抽的同时统一成 chip 那套 —— 它更轻，
// 且天然支持横向滚动（tab 一多，方块那套会折行，把内容顶下去半屏）。
//
// 超过 4 项自动可横滑：写死 scroll-view 会让只有两三项的页面多出一层无用滚动容器。
defineProps<{
  items: readonly { key: string; label: string }[];
  active: string;
}>();
defineEmits<{ (e: "change", key: string): void }>();
</script>

<template>
  <scroll-view v-if="items.length > 4" scroll-x class="tabs tabs--scroll" :show-scrollbar="false">
    <text
      v-for="it in items"
      :key="it.key"
      class="sh-chip tabs__chip"
      :class="{ 'sh-chip--primary': active === it.key }"
      @tap="$emit('change', it.key)"
    >
      {{ it.label }}
    </text>
  </scroll-view>

  <view v-else class="tabs">
    <text
      v-for="it in items"
      :key="it.key"
      class="sh-chip tabs__chip"
      :class="{ 'sh-chip--primary': active === it.key }"
      @tap="$emit('change', it.key)"
    >
      {{ it.label }}
    </text>
  </view>
</template>

<style scoped>
.tabs {
  display: flex;
  gap: 12rpx;
  /* 分栏与下方内容的距离。走变量的理由同 .sh-card（见 base.css） */
  margin-bottom: var(--sh-gap-tabs, 20rpx);
}
.tabs--scroll {
  display: block;
  white-space: nowrap;
}
.tabs--scroll .tabs__chip {
  margin-inline-end: 12rpx;
}
.tabs__chip {
  font-size: 24rpx;
  padding: 12rpx 24rpx;
}
</style>
