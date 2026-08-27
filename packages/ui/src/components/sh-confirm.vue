<script setup lang="ts">
// 确认弹层的壳。与 sh-prompt 同源，由 sh-scaffold 无条件渲染。
//
// **它能做而系统弹框做不到的那一件事**：危险操作用红实心确定键
//（`.sh-btn--danger-solid`）。设计语言里那一档写着「只留给二次确认的最终一击」，
// 而它此前两端引用数是 **0** —— 不是没人需要，是没有地方用它：
// 二次确认全在 `uni.showModal` 里，而那里没有我们的按钮。
import { closeConfirm, confirmState } from "../prompt";

const s = confirmState;
</script>

<template>
  <sh-dialog :visible="s.visible" :title="s.title" @close="closeConfirm(false)">
    <text v-if="s.hint" class="cf__hint">{{ s.hint }}</text>
    <template #actions>
      <text v-if="!s.alert" class="sh-btn sh-btn--muted sh-dialog__act" @tap="closeConfirm(false)">
        {{ s.cancelText || $t("common.cancel") }}
      </text>
      <text
        class="sh-btn sh-dialog__act"
        :class="{ 'sh-btn--danger-solid': s.danger }"
        @tap="closeConfirm(true)"
      >{{ s.confirmText || $t("common.confirm") }}</text>
    </template>
  </sh-dialog>
</template>

<style scoped>
/* 外壳（遮罩 / 居中 / 面板 / 标题 / 动作条）全在 `sh-dialog` 里 ——
   这里此前有一份逐条相同的副本，见该组件的注释。 */
.cf__hint {
  display: block;
  margin-top: 12rpx;
  font-size: 26rpx;
  line-height: 1.6;
  color: var(--sh-sub);
}
</style>
