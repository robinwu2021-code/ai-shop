<script setup lang="ts">
// 评分展示：整星/半星用「底层灰星 + 上层主色星按百分比裁切」实现，
// 不用半星图标 —— 那样只能表达 0.5 的粒度，4.3 分会被抹成 4.5，看着准其实不准。
import { computed } from "vue";

const props = withDefaults(
  defineProps<{ value: number; size?: number; showValue?: boolean }>(),
  { size: 26, showValue: true },
);

const pct = computed(() => `${Math.max(0, Math.min(5, props.value)) * 20}%`);
const text = computed(() => props.value.toFixed(1));
</script>

<template>
  <view class="rating">
    <view class="rating__stars" :style="{ fontSize: `${size}rpx` }">
      <text class="rating__bg">★★★★★</text>
      <text class="rating__fg" :style="{ width: pct }">★★★★★</text>
    </view>
    <text v-if="showValue" class="rating__value sh-num" :style="{ fontSize: `${size}rpx` }">
      {{ text }}
    </text>
  </view>
</template>

<style scoped>
.rating {
  display: flex;
  align-items: center;
  gap: 10rpx;
}
.rating__stars {
  position: relative;
  line-height: 1;
  /* 星串本身是 LTR 序列，RTL 下也不该镜像 */
  direction: ltr;
}
.rating__bg {
  color: var(--sh-line);
  letter-spacing: 2rpx;
}
.rating__fg {
  position: absolute;
  top: 0;
  left: 0;
  overflow: hidden;
  white-space: nowrap;
  color: var(--sh-warning);
  letter-spacing: 2rpx;
}
.rating__value {
  font-weight: 700;
  color: var(--sh-ink);
}
</style>
