<script setup lang="ts">
// 「选一项」弹层的壳。与 sh-prompt / sh-confirm 同源，由 sh-scaffold 无条件渲染。
//
// 形态用 `sh-sheet`（贴底）而不是 sh-dialog（居中）：**选一项是「继续往下做一步」
// 不是「打断」**，和它替掉的 `uni.showActionSheet` 是同一个位置，
// 商家的手不用重新找地方。
import { closePick, pickState } from "../prompt";

const s = pickState;
</script>

<template>
  <sh-sheet
    :visible="s.visible"
    :title="s.title || ''"
    :hint="s.hint"
    @close="closePick(null)"
  >
    <view
      v-for="(it, i) in s.items"
      :key="i"
      class="pick__row"
      :class="{ 'is-on': i === s.selected }"
      @tap="closePick(i)"
    >
      <text class="pick__t">{{ it }}</text>
      <sh-icon
        v-if="i === s.selected"
        name="check"
        :size="26"
        color="var(--sh-primary-text)"
      ></sh-icon>
    </view>
  </sh-sheet>
</template>

<style scoped>
/* 一行一项，通铺到边 —— 与 sh-block 里的列表行同一个密度。
   88rpx ≈ 44pt，点按目标的下限；这是一列全是点按目标的东西，不能省 */
.pick__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 88rpx;
  padding: 8rpx 0;
}
.pick__row + .pick__row {
  border-top: var(--sh-hairline);
}
.pick__t {
  font-size: 30rpx;
  color: var(--sh-ink);
}
.is-on .pick__t {
  color: var(--sh-primary-text);
  font-weight: 600;
}
</style>
