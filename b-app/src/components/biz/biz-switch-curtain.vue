<script setup lang="ts">
/**
 * 切店幕布：整屏主色、大字店名。过程见 shared/store-switch。
 *
 * 为什么不用 toast：uni 的成功 toast 宽度定死、约 7 个字，长店名被裁掉，
 * 而店主要确认的正是「切到了哪家」。店名在这里自动换行，不会被裁。
 */
import { useI18n } from "vue-i18n";
import type { CurtainPhase } from "@/shared/store-switch";

defineProps<{ phase: CurtainPhase; name: string }>();
const { t } = useI18n();
</script>

<template>
  <view v-if="phase" class="curtain" :class="`is-${phase}`" @touchmove.stop.prevent @tap.stop>
    <view class="curtain__mark">
      <view v-if="phase === 'going'" class="curtain__spin"></view>
      <view v-else class="curtain__tick">
        <sh-icon name="check" :size="80" color="var(--sh-primary)"></sh-icon>
      </view>
    </view>
    <!-- 「已切换至」沿用切店 toast 的那条词条、去掉店名：店名单独放大在下面 -->
    <text class="txt-body curtain__label">
      {{ phase === "going" ? t("storePick.switching") : String(t("stores.switched", { name: "" })).trim() }}
    </text>
    <text class="txt-hero curtain__name">{{ name }}</text>
  </view>
</template>

<style scoped>
.curtain {
  position: fixed;
  inset: 0;
  z-index: var(--sh-z-dialog);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 24rpx;
  padding: 0 64rpx;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  animation: curtain-in 220ms ease-out;
  transition: opacity 250ms ease-in;
}
.curtain.is-leaving {
  opacity: 0;
}
.curtain__mark {
  width: 160rpx;
  height: 160rpx;
  margin-bottom: 16rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}
.curtain__spin {
  width: 112rpx;
  height: 112rpx;
  border-radius: 50%;
  border: 8rpx solid var(--sh-primary-tint);
  border-top-color: var(--sh-on-primary);
  animation: curtain-spin 800ms linear infinite;
}
.curtain__tick {
  width: 160rpx;
  height: 160rpx;
  border-radius: 50%;
  background: var(--sh-on-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  animation: curtain-pop 360ms cubic-bezier(0.2, 1.4, 0.4, 1);
}
.curtain__label {
  color: var(--sh-on-primary);
  opacity: 0.85;
}
.curtain__name {
  color: var(--sh-on-primary);
  line-height: 1.3;
  text-align: center;
  word-break: break-all;
}
@keyframes curtain-in {
  from {
    opacity: 0;
    transform: scale(1.04);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}
@keyframes curtain-spin {
  to {
    transform: rotate(360deg);
  }
}
@keyframes curtain-pop {
  from {
    transform: scale(0.3);
    opacity: 0;
  }
  to {
    transform: scale(1);
    opacity: 1;
  }
}
</style>
