<script setup lang="ts">
// 只有图标的按钮：行尾的删除、弹层右上角的关闭、列表行上的编辑。
//
// **收编的是「文字当图标」这件事。** 2026-08-26 扫出来，两端模板里还留着
// **11 处文字 ✕/×** —— 而 `sh-icon` 存在的第一条理由就是「各系统字形不一致」：
// 同一个 ✕ 在 iOS / Android / 微信里长得都不一样，还跟不了皮肤色。
// 更直接的证据：`store-scope` 用的是 `×`（U+00D7，乘号），其余用 `✕`（U+2715）——
// **同一个动作，连字符都不是同一个**。
//
// **11 处分属五种角色，只有两种归它管**（分辨在先，动手在后 —— 这套界面
// 按名字归类已经错过四次）：
//
//   ✅ 行尾删除    store-notice · store-scope · goods-edit 的规格组
//   ✅ 弹层关闭    sh-sheet · goods-edit 的类目弹层
//   ✅ 行上图标钮  my-specs 的编辑/删除（**它本来就是这个形状**，收编时一并接上）
//   ❌ 清空输入框  goods-edit ×2 · goods-list —— 贴在输入框内，尺寸受框约束
//   ❌ chip 内嵌   my-specs 的档位 ✕ —— 在药丸内部，跟着 chip 的字号走
//   ❌ 图片角标    sh-uploader 的删除 —— 探出格子外的小圆点，另一套几何
//
// **点按区偏小是继承来的，没有在这里偷偷改**：`my-specs` 的 `.ic` 是 52rpx（26px），
// 而 `b-app/App.vue` 里写着「88rpx ≈ 44pt，是点按目标的下限」。
// 现在默认沿用 56rpx —— 改大会顶高所有列表行，而这一轮没有办法在真机上验。
// **这件事记在覆盖清单的待决里**，不在收编时夹带。
import { computed } from "vue";
import type { IconName } from "@shared/design/icons";

const props = withDefaults(
  defineProps<{
    name: IconName;
    /** 图标本身的尺寸（rpx） */
    size?: number;
    /** 点按区边长（rpx）。见文件头：暂时沿用现状，不在收编时改大 */
    box?: number;
    color?: string;
  }>(),
  { size: 30, box: 56, color: "var(--sh-sub)" },
);

defineEmits<{ (e: "tap"): void }>();

const boxStyle = computed(() => ({ width: `${props.box}rpx`, height: `${props.box}rpx` }));
</script>

<template>
  <view class="ib" :style="boxStyle" @tap.stop="$emit('tap')">
    <sh-icon :name="name" :size="size" :color="color"></sh-icon>
  </view>
</template>

<style scoped>
.ib {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
}
</style>
