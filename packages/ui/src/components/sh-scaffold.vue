<script setup lang="ts">
// 页面外壳：主题类名 + 书写方向 + 页面底色 + 安全区 + 导航栏标题 + 自定义底部菜单 + 常驻覆盖层。
// **每个页面的根元素都必须是 sh-scaffold**，否则小程序端换肤 / RTL / 三语标题都不生效。
import { computed, onMounted, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useThemeStore } from "../stores/theme";
import { useAppStore } from "../stores/app";

const props = withDefaults(
  defineProps<{
    padded?: boolean;
    /** 导航栏标题的 i18n key。pages.json 的 navigationBarTitleText 是静态的，
     *  切语言必须运行时改写，否则标题永远停在建包时那门语言。 */
    titleKey?: string;
    /** 传入即渲染自定义底部菜单，值为当前高亮的 tab key */
    tab?: string;
  }>(),
  { padded: true, titleKey: "", tab: "" },
);

const { t } = useI18n();
const theme = useThemeStore();
const app = useAppStore();

const rootClass = computed(() => [...theme.rootClass, ...app.dirClass]);

function applyTitle() {
  if (!props.titleKey) return;
  uni.setNavigationBarTitle({ title: String(t(props.titleKey)) });
}

// 导航栏颜色/标题在 onLaunch 时可能还没就绪，每页挂载时补一次
onMounted(() => {
  theme.reapply();
  applyTitle();
});

// 面板里切语言/皮肤时页面不重建，标题与导航栏配色要跟着变
watch(() => app.lang, applyTitle);
</script>

<template>
  <view class="sh-root sh-frame" :class="rootClass">
    <view class="sh-scaffold" :class="{ 'is-padded': padded, 'has-tabbar': !!tab }">
      <slot />
    </view>
    <sh-tabbar v-if="tab" :active="tab"></sh-tabbar>
    <app-overlay></app-overlay>
  </view>
</template>

<style scoped>
/*
 * 宽屏（PC 浏览器）下把整页收进一条居中的窄栏。
 *
 * 分工必须是这样，缺一不可：
 *   .sh-frame     固定、与视口等高、带 transform，**自己不滚**
 *   .sh-scaffold  在框内部滚
 *
 * 为什么 transform 在框上：页面里有十几处 `position: fixed` 的悬浮条（结算条、
 * 提交按钮、底部菜单）。transform 让框成为它们的包含块，于是它们跟着窄栏收窄，
 * 不必逐页去改 left/right —— 漏改一处就会在宽屏上横跨整屏。
 *
 * 为什么滚动**不能**放在框上（踩过）：那样框既是包含块又是滚动盒，
 * 而相对滚动盒定位的元素是跟着内容一起滚的 —— 底部菜单会随着下滑飘到页面中间。
 * 把滚动挪到内层，框就只剩「包含块 + 等高视口」这一个身份，悬浮条才真的吸底。
 *
 * 代价只有一个：桌面端滚动条属于内层而不是 window，`uni.pageScrollTo` 打不着 ——
 * 用 `@ai-shop/ui/scroll` 的 `scrollToTop()` 代替（它两边都管）。
 *
 * 窄屏（手机、小程序、App）完全不进这个分支：原生页面滚动，一切照旧。
 */
.sh-frame {
  max-width: var(--sh-app-max);
  margin: 0 auto;
}
/* 601px 起才是「桌面」。以下都是手机/小平板，按设备自身宽度自适应，不进这个分支。
   阈值 = pages.json 的 rpxCalcMaxDeviceWidth + 1（见 base.css 里那段说明） */
@media (min-width: 601px) {
  .sh-frame {
    position: fixed;
    /* uni 的导航栏是 fixed 的，H5 端把它的高度暴露成 --window-top；
       写 0 会让内容滚到标题栏底下 */
    top: var(--window-top, 0px);
    /* 不用 --window-bottom：那是 uni 给原生 tabBar 留的位（50px），
       而原生 tabBar 已被全局隐藏，跟着它留白会在底部空出一条 */
    bottom: 0;
    left: 50%;
    width: var(--sh-app-max);
    transform: translateX(-50%);
    overflow: hidden;
    /* 关键：.sh-root 带着 `min-height: 100vh`（给普通页面撑满用的）。
       框变成 fixed 之后这条会压过 top/bottom —— 框比视口高出一个标题栏的高度，
       **底部菜单就被顶到屏幕外面**，看起来像「菜单没了」。这里必须解掉。 */
    min-height: 0;
  }
  .sh-frame > .sh-scaffold {
    height: 100%;
    min-height: 0;
    overflow-y: auto;
    overscroll-behavior: contain;
  }
}
.sh-scaffold {
  min-height: 100vh;
  box-sizing: border-box;
  padding-bottom: calc(40rpx + constant(safe-area-inset-bottom));
  padding-bottom: calc(40rpx + env(safe-area-inset-bottom));
}
.sh-scaffold.is-padded {
  padding-left: 28rpx;
  padding-right: 28rpx;
  padding-top: 28rpx;
}
/* 自定义 tabBar 是 fixed 的，内容区要留出等高的底部空间 */
.sh-scaffold.has-tabbar {
  padding-bottom: calc(var(--sh-tabbar-h) + 40rpx + env(safe-area-inset-bottom));
}
</style>
