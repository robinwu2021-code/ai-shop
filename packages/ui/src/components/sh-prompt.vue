<script setup lang="ts">
// 输入弹层的**壳**。由 `sh-scaffold` 无条件渲染，页面不用摆它 ——
// 页面只 `await prompt({...})`（见 `@ai-shop/ui/prompt`）。
//
// 为什么壳挂在 scaffold 上而不是各页自己放：`prompt()` 要能在任何一段
// 业务代码里 await，而那段代码未必知道自己所在的页面有没有摆过弹层。
// 少一处摆放 = 少一处「点了没反应」。
//
// 壳（遮罩 / 面板 / 抓手条）走 base.css 的 `.sh-mask` `.sh-panel` `.sh-grip`
// —— 此前三个弹层各画一份，`__mask` 逐字节相同。不复用 `sh-sheet` **组件**
// 是因为重复的不是行为：那一个带关闭按钮、层级也不同，而这一个的内容是固定的
// 三件（说明 / 输入 / 两个按钮），层级要压过弹层（它会开在弹层之上）。
import { computed } from "vue";
import { closePrompt, promptState } from "../prompt";

const s = promptState;

/** 数字键盘要给到 input 的 type；密码另走 password 属性 */
const inputType = computed(() => (s.type === "text" ? "text" : s.type));
</script>

<template>
  <view v-if="s.visible" class="pr">
    <!-- 点遮罩＝取消。与右上角的关闭同义，不做「点外面不关」那种设计 -->
    <view class="sh-mask" @tap="closePrompt(null)"></view>
    <view class="sh-panel">
      <view class="sh-grip"></view>
      <text class="txt-title pr__title">{{ s.title }}</text>
      <!-- 说明**在输入框外面**。这是这个组件存在的一半理由，见 prompt.ts -->
      <text v-if="s.hint" class="sh-hint">{{ s.hint }}</text>
      <input
        v-model="s.input"
        class="field__input pr__input"
        :type="inputType"
        :password="s.password"
        :placeholder="s.placeholder"
        :maxlength="s.maxlength || -1"
        placeholder-class="sh-ph"
        placeholder-style="color: var(--sh-sub)"
        :focus="true"
        @confirm="closePrompt(s.input)"
      />
      <view class="pr__acts">
        <text class="sh-btn sh-btn--muted pr__act" @tap="closePrompt(null)">
          {{ s.cancelText || $t("common.cancel") }}
        </text>
        <text class="sh-btn pr__act" @tap="closePrompt(s.input)">
          {{ s.confirmText || $t("common.confirm") }}
        </text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.pr {
  position: fixed;
  inset: 0;
  z-index: var(--sh-z-dialog);
}
.pr__title {
  display: block;
  color: var(--sh-ink);
}
.pr__input {
  display: block;
  width: 100%;
  box-sizing: border-box;
  margin-top: 20rpx;
}
.pr__acts {
  display: flex;
  gap: 16rpx;
  margin-top: 24rpx;
}
.pr__act {
  flex: 1;
}
</style>
