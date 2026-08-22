<script setup lang="ts">
/**
 * 时段输入（营业时间 / 取货点开放时段）。两个系统时间控件，不让人手敲「06:30–21:00」——
 * 手敲的结果是全角横线、少个冒号、写成「6点半到9点」，C 端拿到就渲染不齐。
 *
 * v-model 是字符串 "HH:mm–HH:mm"（与后端/C 端既有口径一致，分隔符是 en dash），
 * 空串 = 未填。结束必须晚于开始：跨夜店铺很少，先不放开。
 */
import { computed } from "vue";
import { useI18n } from "vue-i18n";

const props = defineProps<{
  modelValue: string;
  /** 可清空（选填场景） */
  clearable?: boolean;
}>();
const emit = defineEmits<{ (e: "update:modelValue", v: string): void }>();
const { t } = useI18n();

const SEP = "–";
const parts = computed(() => {
  const [a = "", b = ""] = (props.modelValue || "").split(/[–\-~]/).map((x) => x.trim());
  return { start: /^\d{2}:\d{2}$/.test(a) ? a : "", end: /^\d{2}:\d{2}$/.test(b) ? b : "" };
});

function setPart(which: "start" | "end", v: string) {
  const next = { ...parts.value, [which]: v };
  if (next.start && next.end && next.end <= next.start) {
    uni.showToast({ title: t("store.timeRange.endAfterStart"), icon: "none" });
    return;
  }
  emit("update:modelValue", next.start || next.end ? `${next.start}${SEP}${next.end}` : "");
}
function clear() {
  emit("update:modelValue", "");
}
</script>

<template>
  <view class="tr">
    <picker mode="time" :value="parts.start || '08:00'" @change="setPart('start', String(($event as any).detail.value))">
      <view class="tr__box" :class="{ 'is-empty': !parts.start }">{{ parts.start || $t("store.timeRange.startPh") }}</view>
    </picker>
    <text class="tr__sep">{{ SEP }}</text>
    <picker mode="time" :value="parts.end || '21:00'" @change="setPart('end', String(($event as any).detail.value))">
      <view class="tr__box" :class="{ 'is-empty': !parts.end }">{{ parts.end || $t("store.timeRange.endPh") }}</view>
    </picker>
    <text v-if="clearable && modelValue" class="tr__clear" @tap="clear">{{ $t("store.timeRange.clear") }}</text>
  </view>
</template>

<style scoped>
.tr {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.tr picker {
  flex: 1;
  min-width: 0;
}
.tr__box {
  display: flex;
  align-items: center;
  height: 88rpx;
  padding: 0 24rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  font-size: 30rpx;
  color: var(--sh-ink);
}
.tr__box.is-empty {
  color: var(--sh-sub);
}
.tr__sep {
  flex-shrink: 0;
  font-size: 28rpx;
  color: var(--sh-sub);
}
.tr__clear {
  flex-shrink: 0;
  font-size: 24rpx;
  color: var(--sh-sub);
}
</style>
