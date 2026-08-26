<script setup lang="ts">
// 勾选框 / 单选点。
//
// **同样零处原生 `<checkbox>` / `<radio>`**，四个页面各画一个，**四种尺寸**：
//
//   apply       .cb           28×28  描边 **`1px`**  —— 而且**没有勾**，只靠底色
//   login       .agree__box   36×36  无描边（faint 底）  文字 ✓
//   store-pick  .item__radio  44×44  2rpx  文字 ✓
//   store-scope .row__check   44×44  2rpx  文字 ✓
//
// `apply` 那个 `1px` 是真问题：这套界面按 rpx 排版，**`1px` 不跟着机型缩放** ——
// 在 375 的 SE 上是 1px，在 430 的 Pro Max 上还是 1px，于是同一个框在两台手机上
// 粗细不同。取 2rpx。
//
// 尺寸取 **44rpx**（四个里有两个是），勾用 `sh-icon name="check"` ——
// **那个图标是这次一起补进 icons.ts 的**：此前库里没有 `check`，
// 于是十处「已选中」用的都是文字 ✓，而文字符号跟着系统字形变
//（这正是 sh-icon 存在的第一条理由）。
withDefaults(
  defineProps<{
    modelValue: boolean;
    /** 单选点：圆的。默认是方角勾选框 */
    round?: boolean;
    disabled?: boolean;
  }>(),
  { round: false, disabled: false },
);

const emit = defineEmits<{ (e: "update:modelValue", v: boolean): void }>();
</script>

<template>
  <view
    class="ck"
    :class="{ 'is-on': modelValue, 'is-round': round, 'is-off': disabled }"
    @tap="disabled || emit('update:modelValue', !modelValue)"
  >
    <sh-icon v-if="modelValue" name="check" :size="26" color="var(--sh-on-primary)"></sh-icon>
  </view>
</template>

<style scoped>
.ck {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44rpx;
  height: 44rpx;
  border-radius: 16rpx;
  /* 2rpx 而不是 1px：这套界面按 rpx 排版，px 不跟着机型缩放 */
  border: 2rpx solid var(--sh-line);
  background: transparent;
}
.ck.is-round {
  border-radius: 9999px;
}
.ck.is-on {
  border-color: var(--sh-primary);
  background: var(--sh-primary);
}
.ck.is-off {
  opacity: 0.45;
}
</style>
