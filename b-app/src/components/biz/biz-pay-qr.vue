<!--
  收款二维码：把链接编成点阵，用 view 铺格子（三端同一条路，见 @shared/utils/qrcode 的说明）。
  两色走 --sh-qr-*（恒为纯黑纯白、不随皮肤变）；四周留白是扫得出来的前提，不是装饰。
-->
<template>
  <view v-if="matrix.length" class="payqr" :style="{ width: size + 'rpx' }">
    <view v-for="(row, r) in matrix" :key="r" class="payqr__row" :style="{ height: cell + 'rpx' }">
      <view
        v-for="(dark, c) in row"
        :key="c"
        class="payqr__cell"
        :class="{ 'payqr__cell--on': dark }"
        :style="{ width: cell + 'rpx', height: cell + 'rpx' }"
      ></view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { qrMatrix } from "@shared/utils/qrcode";

const props = withDefaults(defineProps<{ link: string; size?: number }>(), { size: 420 });
const matrix = computed(() => qrMatrix(props.link));
/** 格子边长取两位小数：整除不了时多出来的那一点留在右下，不会把码挤变形 */
const cell = computed(() => (matrix.value.length ? Math.floor(((props.size - 48) / matrix.value.length) * 100) / 100 : 0));
</script>

<style scoped>
.payqr {
  margin: 24rpx auto 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 24rpx;
  background: var(--sh-qr-paper);
  border-radius: 16rpx;
}
.payqr__row {
  display: flex;
}
.payqr__cell {
  background: var(--sh-qr-paper);
}
.payqr__cell--on {
  background: var(--sh-qr-ink);
}
</style>
