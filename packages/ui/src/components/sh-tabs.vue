<script setup lang="ts">
// 筛选条（chip 横排）。
//
// 抽之前两端有**两套实现**：一套是 `sh-chip` 横排（B 端订单/商品/客户），
// 一套是自定义 `tabs__item` 方块（C 端券包/团购）。同一个产品里两种筛选条，
// 用户会以为是两种不同的控件。抽的同时统一成 chip 那套 —— 它更轻，
// 且天然支持横向滚动（tab 一多，方块那套会折行，把内容顶下去半屏）。
//
// 超过 4 项自动可横滑：写死 scroll-view 会让只有两三项的页面多出一层无用滚动容器。
//
// `line`：**页签**而不是筛选 —— 文字 + 当前项下面一道短主色线（门店门户的「商品 / 评价 / 店铺」）。
// 同一屏上页签与筛选（分类 chip）要是两种样子，否则分不出哪排是换页、哪排是筛选。
// 此前页面要么借 `sh-seg` 画成三颗大按钮（比下面的分类还抢眼），要么各自手搓下划线。
defineProps<{
  items: readonly { key: string; label: string }[];
  active: string;
  line?: boolean;
}>();
defineEmits<{ (e: "change", key: string): void }>();
</script>

<template>
  <view v-if="line" class="tabs tabs--line">
    <view
      v-for="it in items"
      :key="it.key"
      class="tabs__line sh-hit"
      :class="{ 'is-on': active === it.key }"
      @tap="$emit('change', it.key)"
    >
      <text :class="active === it.key ? 'txt-strong' : 'txt-body txt-quiet'">{{ it.label }}</text>
      <view class="tabs__bar"></view>
    </view>
  </view>

  <scroll-view v-else-if="items.length > 4" scroll-x class="tabs tabs--scroll" :show-scrollbar="false">
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
  padding: 12rpx 24rpx;
}
/* 页签态：不占底色，靠字重与一道短线。下间距由调用方给（它通常贴着一条分隔线） */
.tabs--line {
  gap: 48rpx;
  margin-bottom: 0;
}
.tabs__line {
  position: relative;
  padding: 20rpx 0 16rpx;
}
.tabs__bar {
  position: absolute;
  bottom: 0;
  inset-inline: 0;
  width: 32rpx;
  height: 4rpx;
  margin-inline: auto;
  border-radius: 9999px;
}
.tabs__line.is-on .tabs__bar {
  background: var(--sh-primary);
}
</style>
