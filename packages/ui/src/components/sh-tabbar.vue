<script setup lang="ts">
// 自定义底部菜单。
// 换掉原生 tabBar 的三个理由：
//   1) 原生 tabBar 字号锁死，改不大
//   2) 原生 tabBar 不吃 CSS 变量，换肤要靠 uni.setTabBarStyle 运行时改写
//   3) 原生 tabBar 文案不吃 i18n，切语言要靠 uni.setTabBarItem 逐个改写
// 自定义之后这三件事都由 CSS 变量与 $t 自然解决。
//
// 角标、落点登记、弹跳都来自 `configureShell()` —— 组件不知道有没有购物车这回事。
import { computed, getCurrentInstance, nextTick, onMounted, ref, watch } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useShell } from "../shell";
import { hideNativeTabBar } from "@shared/ports/tabbar";
import type { IconName } from "@shared/design/icons";

const props = defineProps<{ active: string }>();
const shell = useShell();
const instance = getCurrentInstance();

/** 落点反馈的弹跳。B 端没配 pulse，这里永远是 false */
const bouncing = ref(false);

const tabs = computed(() =>
  shell.tabs.map((t) => ({
    ...t,
    iconName: (props.active === t.key ? t.iconOn : t.icon) as IconName,
    badge: shell.badge?.(t.key) || 0,
    /** 每个图标都带一个稳定选择器，app 侧要量哪个位置自己挑（C 端量的是购物车） */
    anchorClass: `tabbar__anchor--${t.key}`,
  })),
);

function go(key: string, route: string) {
  if (key === props.active) return;
  uni.switchTab({ url: route });
}

function ready() {
  // App 端：藏掉没被 custom:true 藏住的原生 tabBar，否则与本组件叠成两排
  hideNativeTabBar();
  nextTick(() => shell.onTabbarReady?.(instance?.proxy));
}

onMounted(ready);
// 切页返回时布局可能变化（如键盘收起、安全区变化），重新量一次
onShow(ready);

watch(
  () => shell.pulse?.value,
  (key) => {
    if (!key) return;
    bouncing.value = false;
    nextTick(() => {
      bouncing.value = true;
      setTimeout(() => (bouncing.value = false), 420);
    });
  },
);
</script>

<template>
  <view class="tabbar">
    <view
      v-for="tab in tabs"
      :key="tab.key"
      class="tabbar__item"
      :class="{ 'is-on': active === tab.key }"
      @tap="go(tab.key, tab.route)"
    >
      <view
        class="tabbar__icon-wrap"
        :class="[tab.anchorClass, { 'is-bouncing': bouncing && shell.pulse?.value === tab.key }]"
      >
        <sh-icon :name="tab.iconName" :size="46"></sh-icon>
        <text v-if="tab.badge" class="sh-badge-count tabbar__badge sh-num">
          {{ tab.badge > 99 ? "99+" : tab.badge }}
        </text>
      </view>
      <text class="tabbar__label">{{ $t(tab.labelKey) }}</text>
    </view>
  </view>
</template>

<style scoped>
.tabbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  /* 宽屏下跟着应用框走，不贴到 1920px 两端 —— 变量由 App.vue 定义，窄屏为 100% */
  max-width: var(--sh-app-max);
  margin: 0 auto;
  z-index: 90;
  display: flex;
  background: var(--sh-surface);
  /*
   * **与主区域的分界要看得见。** 白色的 tabbar 压在浅灰页面上时，
   * 两者只差一点点明度 —— 滚动到底部时列表卡片像是溢出到了菜单里。
   * 一条 hairline 加一层很浅的上投影：静止时是分界，滚动时是「页面在它下面走」。
   */
  border-top: var(--sh-hairline);
  box-shadow: 0 -6rpx 20rpx var(--sh-scrim);
  padding: 14rpx 0 calc(14rpx + env(safe-area-inset-bottom));
}
.tabbar__item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6rpx;
  padding: 6rpx 0;
  color: var(--sh-sub);
  transition: color 0.18s ease;
}
.tabbar__item.is-on {
  color: var(--sh-primary-text);
}
.tabbar__icon-wrap {
  position: relative;
  line-height: 0;
}
.tabbar__icon-wrap.is-bouncing {
  animation: shCartBounce 0.42s cubic-bezier(0.34, 1.56, 0.64, 1);
}
@keyframes shCartBounce {
  0% { transform: scale(1); }
  40% { transform: scale(1.32); }
  100% { transform: scale(1); }
}
/* 字号 28rpx —— 原生 tabBar 固定在 ~20rpx，这是本次要解决的问题 */
.tabbar__label {
  font-size: 28rpx;
  font-weight: 400;
}
.tabbar__item.is-on .tabbar__label {
  font-weight: 400;
}
.tabbar__badge {
  position: absolute;
  top: -8rpx;
  inset-inline-start: 26rpx;
}
</style>
