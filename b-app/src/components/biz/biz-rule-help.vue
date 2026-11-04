<script setup lang="ts">
// 规则说明：按需打开共享 sh-sheet，避免低频口径常驻挤占页面。
import { ref } from "vue";
import { useI18n } from "vue-i18n";
defineProps<{ title: string; texts: string[]; label?: string }>();
const { t } = useI18n();
const open = ref(false);
</script>

<template>
  <view class="sh-row">
    <text class="sh-link sh-hit" @tap="open = true">{{ label || t("common.rules") }}</text>
    <sh-sheet :visible="open" :title="title" fit @close="open = false">
      <view class="rules">
        <text v-for="(text, i) in texts" :key="i" class="txt-body">{{ text }}</text>
      </view>
    </sh-sheet>
  </view>
</template>

<style scoped>
.rules {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  overflow-wrap: anywhere;
}
</style>
