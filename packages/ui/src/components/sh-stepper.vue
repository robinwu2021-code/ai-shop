<script setup lang="ts">
/**
 * 数量步进器：减 · 数 · 加，装在一枚弱色药丸里。
 *
 * **三个页面各画了一份**（2026-09-06 数的：`cart` / `goods` / `request-create`），
 * 形状几乎一样、细节全不一样：
 *
 *   cart            按钮 52  数字宽 64  ✅ 到头压暗  ✅ 点数字直接输入  图标走 sh-icon
 *   goods           按钮 56  数字宽 56  ✗            ✗                 字面量 `−` `＋`
 *   request-create  按钮 56  数字宽 56  ✗            ✗                 字面量 `−` `＋`
 *
 * 而且**夹取规则也三份**：goods 夹 `[1, maxQty]`、request-create 只夹下界、
 * cart 把上界算在页面的 `atMax()` 里。同一件事三种写法，其中两处点到头没有任何反馈 ——
 * 手指在一个没反应的加号上点，屏幕不说为什么。
 *
 * 收进来之后：**夹取只有一份**，到头一律压暗，图标一律走 `sh-icon`
 *（字面量 `−`(U+2212) 与 `＋`(全角) 是两套字形，粗细本来就对不上）。
 *
 * 尺寸取多数派的 56；数字宽取 **64**（cart 的那个）—— 它不是随手写大的：
 * 数量到两位数时 56 会让整枚药丸变宽一点点，一列里的药丸就参差不齐了。
 *
 * <p><b>为什么不做成 v-model 独占</b>：cart 的数量不在页面里，
 * 加减要发给服务端（`cart.update`）。所以值走 `modelValue`、变化同时抛
 * `update:modelValue`（本地 ref 用 v-model）与 `change`（要自己接管的用它）。
 */
const props = withDefaults(
  defineProps<{
    modelValue: number;
    /**
     * 下界。默认 **1** —— 减到 0 是「删除」，那是另一个动作，得他自己去点。
     *
     * 这个默认值有来历：`cart` 的减号此前在 qty=1 时把 0 传下去，
     * 而后端与 mock 都把 `qty<=0` 当删除 —— 商品当场消失，没有确认也没有撤销。
     * 夹取归到这里之后，三个调用点里就不会再有一个漏掉下界。
     */
    min?: number;
    /** 上界。不传就不封顶 */
    max?: number;
    /**
     * 点数字直接输入。一次买 20 件不该点 19 下加号 ——
     * 只在**接了 `@edit`** 的地方才可点，不然点上去没反应比不可点更糟。
     */
    editable?: boolean;
  }>(),
  { min: 1, max: Number.POSITIVE_INFINITY, editable: false },
);

const emit = defineEmits<{
  "update:modelValue": [n: number];
  change: [n: number];
  edit: [];
}>();

function step(d: number) {
  const n = props.modelValue + d;
  if (n < props.min || n > props.max) return;
  emit("update:modelValue", n);
  emit("change", n);
}
</script>

<template>
  <!-- `@tap.stop`：药丸常常坐在一整行可点的卡片里（cart 的每一行都能点进商品），
       不拦住的话加一件商品会顺手跳走一页 -->
  <view class="stepper sh-row" @tap.stop>
    <view
      class="stepper__btn sh-hit sh-center"
      :class="{ 'is-off': modelValue <= min }"
      @tap.stop="step(-1)"
    >
      <sh-icon name="minus" :size="26" color="var(--sh-ink)"></sh-icon>
    </view>
    <text
      class="txt-strong stepper__num sh-num"
      @tap.stop="editable && emit('edit')"
    >{{ modelValue }}</text>
    <view
      class="stepper__btn sh-hit sh-center"
      :class="{ 'is-off': modelValue >= max }"
      @tap.stop="step(1)"
    >
      <sh-icon name="plus" :size="26" color="var(--sh-ink)"></sh-icon>
    </view>
  </view>
</template>

<style scoped>
.stepper {
  /* 坐在一行里时不许被压扁 —— 数字一挤就换行，药丸变成两层 */
  flex: none;
  gap: 8rpx;
  padding: 8rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
}
.stepper__btn {
  width: 56rpx;
  height: 56rpx;
  border-radius: 9999px;
  background: var(--sh-surface);
}
/* 到头了就明说，别让他反复点一个没反应的按钮 */
.stepper__btn.is-off {
  opacity: 0.35;
}
/* 见类注释：64 是为了两位数时药丸不变宽，一列药丸才齐 */
.stepper__num {
  min-width: 64rpx;
  text-align: center;
}
</style>
