<script setup lang="ts">
// 图片格：已传的缩略图排一行，末尾一个「＋」。
//
// **四个页面各写了一份**（2026-08-26 扫出来）：`apply` 的执照、`payment` 的收款码、
// `qualifications` 的资质照、`goods-edit` 的商品主图 —— 而 `goods-edit` 一页里
// 还有第二套（详情图，见下）。
//
// **这一件不是纯搬运**，四份之间有真差异也有真漂移，收编时要分开处置：
//
// · **真差异（留给调用点）**：格子尺寸。执照是横的（160×112）、商品主图是方的（104）、
//   收款码大一点（160）—— 拍的东西形状不同，不该被组件拉平，所以 `w`/`h` 是 props。
// · **真漂移（收掉）**：
//     ‑ 圆角 24 与 16 各两处 → 统一 16rpx（`radius.sm`）。缩略图是小件，24 显得笨
//     ‑ 「＋」三处是全角 `＋`、`payment` 一处是半角 `+` —— 半角在中文字体里又细又矮，
//       与旁边三页不是一个东西。统一全角
//     ‑ 上传中的提示三种写法（`…` / `"…"` / 一句 i18n）→ 统一 `…`
// · **不改的**：`apply` / `payment` 现在**没有删除入口**，收编后仍然没有 ——
//   `removable` 默认关。给一个页面凭空添一个删除手势不是收编，是改需求。
//
// **`goods-edit` 的详情图（`.dimgs`）不归它管**：那是竖排列表 + 每行 ↑↓✕ 的
// 排序件，与「一排缩略图 + ＋」是两个形态。名字像、东西不同 ——
// 这一课这套界面已经上过三次（addbtn/candchip、卡内标题行/分段标题、行内首行）。
import { computed } from "vue";

const props = withDefaults(
  defineProps<{
    /** 已经有的图（URL 或 emoji，交给 sh-cover 分流） */
    list: readonly string[];
    /** 上限；到了就不再显示「＋」。不给＝不限 */
    max?: number;
    /** 格子宽高（rpx）。默认方形 104 —— 商品主图那一档 */
    w?: number;
    h?: number;
    /** 正在传：「＋」变「…」并挡住重复点 */
    uploading?: boolean;
    /** 显示右上角的删除角标。**默认关**，见文件头 */
    removable?: boolean;
    /** 第一格左下角的角标（如「主图」）。留空不显示 */
    badge?: string;
  }>(),
  { max: 0, w: 104, h: 0, uploading: false, removable: false, badge: "" },
);

const emit = defineEmits<{
  (e: "add"): void;
  (e: "remove", index: number): void;
  (e: "tapItem", index: number): void;
}>();

const cell = computed(() => ({ width: `${props.w}rpx`, height: `${props.h || props.w}rpx` }));
const canAdd = computed(() => !props.max || props.list.length < props.max);

function add() {
  if (props.uploading) return;
  emit("add");
}
</script>

<template>
  <view class="up">
    <view
      v-for="(img, i) in list"
      :key="img + i"
      class="up__cell"
      :style="cell"
      @tap="emit('tapItem', i)"
    >
      <sh-cover class="up__img" :style="cell" :src="img"></sh-cover>
      <text v-if="badge && i === 0" class="up__badge">{{ badge }}</text>
      <text v-if="removable" class="up__del" @tap.stop="emit('remove', i)">×</text>
    </view>
    <view v-if="canAdd" class="up__add" :style="cell" @tap="add">
      <text class="up__plus">{{ uploading ? "…" : "＋" }}</text>
    </view>
  </view>
</template>

<style scoped>
.up {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 12rpx;
}
.up__cell {
  position: relative;
  flex: none;
}
.up__img {
  border-radius: 16rpx;
  background: var(--sh-faint);
  /* 兜底字号：sh-cover 在拿到 emoji 时按文字排，字号继承调用点 */
  font-size: 48rpx;
}
/*
 * 第一格的角标（「主图」）：压在左下角，不挡图的主体。
 *
 * **理由从 goods-edit 搬过来**（收编时那段注释会跟着 CSS 一起消失）：
 * 用角标而不是另起一行说明 —— **哪张是封面必须看图就知道**；
 * 靠位置约定（「第一张」）的话，滑动之后没人数得清自己在第几张。
 */
.up__badge {
  position: absolute;
  left: 0;
  bottom: 0;
  padding: 2rpx 8rpx;
  border-top-right-radius: 8rpx;
  background: var(--sh-scrim);
  color: #fff;
  font-size: 24rpx;
  line-height: 1.3;
}
/* 删除角标探出格子外一点：压在图上会挡住内容，而缩略图本来就小 */
.up__del {
  position: absolute;
  top: -10rpx;
  inset-inline-end: -10rpx;
  width: 40rpx;
  height: 40rpx;
  line-height: 36rpx;
  text-align: center;
  border-radius: 9999px;
  background: var(--sh-scrim);
  color: #fff;
  font-size: 26rpx;
}
.up__add {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 16rpx;
  background: var(--sh-faint);
}
.up__plus {
  font-size: 40rpx;
  color: var(--sh-sub);
}
</style>
