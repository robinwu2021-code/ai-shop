<script setup lang="ts">
/**
 * 通用底部弹层。
 *
 * <p><b>为什么不用 `uni.showModal`</b>：那是系统弹框，标题与输入框不是同一套字，
 * 字号、行高、对齐都不归我们管 —— 看起来就是「粗糙、字不齐」，而这一点改不了。
 * 而且它 `editable=true` 时把 `content` 当**预填值**（不是说明文字），
 * 于是能放的说明只剩一个 placeholder。
 *
 * <p><b>为什么不做页内展开</b>：展开会把下面的内容整段顶走，商家一边填一边
 * 失去上下文；而弹层把注意力收在一件事上，做完就回到原地。
 *
 * <p><b>必须有 `max-height`</b>（照抄 sh-theme-sheet 踩过的坑）：
 * bottom:0 的弹层内容一多就把上半截顶到视口外，够不着 ——
 * 而候选规格是会随运营配置增长的，今天 5 条不代表明天不是 25 条。
 */
defineProps<{
  visible: boolean;
  title: string;
  /** 副标题：一句话说清这个弹层里的东西是什么、代价在哪 */
  hint?: string;
}>();

const emit = defineEmits<{ close: [] }>();
</script>

<template>
  <view v-if="visible" class="sheet">
    <view class="sheet__mask" @tap="emit('close')" />
    <view class="sheet__panel">
      <view class="sheet__grip" />
      <view class="sheet__head">
        <text class="sheet__title">{{ title }}</text>
        <sh-icon-btn name="close" :size="28" :box="48" @tap="emit('close')"></sh-icon-btn>
      </view>
      <text v-if="hint" class="sheet__hint">{{ hint }}</text>
      <slot />
    </view>
  </view>
</template>

<style scoped>
.sheet {
  position: fixed;
  inset: 0;
  z-index: 100;
}

.sheet__mask {
  position: absolute;
  inset: 0;
  background: var(--sh-scrim);
}

.sheet__panel {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  background: var(--sh-surface);
  border-radius: 44rpx 44rpx 0 0;
  padding: 24rpx 36rpx calc(48rpx + env(safe-area-inset-bottom));
  /* 见类注释：没有 max-height 的 bottom:0 弹层，内容一多就把上半截顶出视口 */
  max-height: 78vh;
  box-sizing: border-box;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

.sheet__grip {
  width: 72rpx;
  height: 8rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  margin: 0 auto 28rpx;
}

.sheet__head {
  display: flex;
  align-items: center;
  gap: 16rpx;
}

/* 34rpx/600 = 字阶的 .txt-title。此前是 32rpx/700 —— 32 不在七档上，
   而 700 按字阶只给价格。**组件库带头破的那一档，页面照抄不奇怪**：
   goods-list 的 .sheet__t 与 order 的 .dlg__title 也都是 32rpx。 */
.sheet__title {
  flex: 1;
  font-size: 34rpx;
  font-weight: 600;
  color: var(--sh-ink);
}


.sheet__hint {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
</style>
