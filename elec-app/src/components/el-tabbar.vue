<script setup lang="ts">
// 底部菜单。放在四个落点页（找料 / 询价 / 供货 / 我的）的最后；为什么不用原生 tabBar 与 sh-tabbar 见 shared/tabs.ts。
// 样子照 sh-tabbar（同一套变量与字号），只是切换走 redirectTo。
// 红点来自 GET /elec/me：询价 = 有新报价的询价单数；供货 = 待回的求购 + 快到期的库存。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useUserStore } from "@/stores/user";
import { ELEC_TABS, rememberTab, switchTab, type ElecTab } from "@/shared/tabs";
import type { ElecMe } from "@shared/types";

const props = defineProps<{ active: ElecTab }>();
const user = useUserStore();
const me = ref<ElecMe | null>(null);

onShow(async () => {
  rememberTab(props.active);
  me.value = user.isLogin ? await api.me().catch(() => null) : null;
});

// ⚠️ 这里别写成「感叹号紧跟变量 b」的三元：`b` 恰好是 UnoCSS 的边框工具类，感叹号加 b 被当成
// important 版的它，transformer 去改写这段源码时撞车，整个文件编译失败（注释里写出来也一样会炸）
function badgeOf(t: ElecTab): number {
  const b = me.value?.badges;
  if (b == null) return 0;
  if (t === "rfq") return b.rfqNewOffers;
  if (t === "supply") return b.dispatchPending + b.stockExpiring;
  return 0;
}

const tabs = computed(() => ELEC_TABS.map((t) => ({ ...t, on: t.key === props.active, badge: badgeOf(t.key) })));

function tap(t: ElecTab) {
  if (t !== props.active) switchTab(t);
}
</script>

<template>
  <!-- 占位：菜单是 fixed 的，页面最后一屏要让出它的高度 -->
  <view class="etab-space"></view>
  <view class="etab">
    <view v-for="t in tabs" :key="t.key" class="etab__item" :class="{ 'is-on': t.on }" @tap="tap(t.key)">
      <view class="etab__icon">
        <sh-icon :name="t.on ? t.iconOn : t.icon" :size="46"></sh-icon>
        <text v-if="t.badge" class="sh-badge-count etab__badge sh-num">{{ t.badge > 99 ? "99+" : t.badge }}</text>
      </view>
      <text class="txt-body">{{ t.label }}</text>
    </view>
  </view>
</template>

<style scoped>
.etab-space {
  height: calc(var(--sh-tabbar-h, 124rpx) + 24rpx);
  height: calc(var(--sh-tabbar-h, 124rpx) + 24rpx + env(safe-area-inset-bottom, 0px));
}
.etab {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  max-width: var(--sh-app-max);
  margin: 0 auto;
  z-index: var(--sh-z-tabbar);
  display: flex;
  background: var(--sh-surface);
  border-top: var(--sh-hairline);
  box-shadow: var(--sh-shadow-up);
  padding: 16rpx 0;
  padding: 16rpx 0 calc(16rpx + env(safe-area-inset-bottom, 0px));
}
.etab__item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
  padding: 8rpx 0;
  color: var(--sh-sub);
}
.etab__item.is-on {
  color: var(--sh-primary-text);
}
.etab__icon {
  position: relative;
  line-height: 0;
}
.etab__badge {
  position: absolute;
  top: -8rpx;
  inset-inline-start: 26rpx;
}
</style>
