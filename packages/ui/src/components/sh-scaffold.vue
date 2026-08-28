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
    /**
     * 无权访问：整页替换成一句说明，**不渲染 slot**。
     *
     * 为什么在外壳上而不是各页面自己判：受限页面的失败形状高度一致 ——
     * 接口被拒 → catch 成空数据 → 页面画出一个正常的空态。
     * 实测过的样子是店员打开结算页看到「还没有可结算的订单」，
     * 他会以为店里没生意，而不是「这页不该我看」。
     * **把「不给看」渲染成「没有」，比报错更糟**：它不像故障，像事实。
     *
     * 判断本身留在各端（外壳是 C/B 共用的，不认识 B 端的角色），
     * 这里只负责把话说清楚。
     */
    denied?: boolean;
    /** 无权时显示的话。留空用通用文案 */
    deniedText?: string;
  }>(),
  { padded: true, titleKey: "", tab: "", denied: false, deniedText: "" },
);

const { t } = useI18n();
const theme = useThemeStore();
const app = useAppStore();

const rootClass = computed(() => [...theme.rootClass, ...app.dirClass]);

/*
 * **自绘标题栏**（只在 H5 与 App 上）。
 *
 * 为什么要自绘：App 用的是**原生**标题栏，H5 用的是 uni 画的 HTML 栏 ——
 * 字体、高度、返回箭头、按压反馈全不归我们管，两端因此长得不一样，
 * 而这正是「打包后的 App 和 H5 有差距」里最后一处结构性差异。
 * 自绘之后两端画的是同一段 HTML，用的是同一套字阶与皮肤变量。
 *
 * **小程序端不动**：那边右上角有胶囊按钮，自绘要精确避让它
 *（`getMenuButtonBoundingClientRect`），而胶囊的位置各机型不同 ——
 * 那是另一件事的风险，不该顺带做。所以 `navigationStyle: custom`
 * 只写在 pages.json 的 `app-plus` 与 `h5` 两个平台段里。
 */
const statusBar = (() => {
  try {
    return uni.getSystemInfoSync().statusBarHeight ?? 0;
  } catch {
    return 0;
  }
})();

/** 能返回 = 栈里不止一页。tab 页永远是栈底，不该有返回箭头 */
const canBack = computed(() => {
  if (props.tab) return false;
  try {
    return getCurrentPages().length > 1;
  } catch {
    return false;
  }
});

const navTitle = computed(() => (props.titleKey ? String(t(props.titleKey)) : ""));

function goBack() {
  uni.navigateBack();
}

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

/*
 * **titleKey 会变**：商品编辑页传的是 `isEdit ? 编辑商品 : 新建商品`，
 * 而 `isEdit` 依赖 onLoad 拿到的 query —— 挂载那一刻它还是 false。
 * 只在 onMounted 应用一次的话，页内标题是「编辑商品」、
 * 导航栏却一直写着「新建商品」，两个标题在同一屏上互相矛盾。
 */
watch(() => props.titleKey, applyTitle);
</script>

<template>
  <view class="sh-root sh-frame" :class="rootClass">
    <!-- #ifdef H5 || APP-PLUS -->
    <view class="navbar" :style="{ paddingTop: statusBar + 'px' }">
      <view class="navbar__bar">
        <view v-if="canBack" class="navbar__back" @tap="goBack">
          <sh-icon name="chevronLeft" :size="34" color="var(--sh-ink)"></sh-icon>
        </view>
        <text class="txt-title navbar__title">{{ navTitle }}</text>
      </view>
    </view>
    <!-- #endif -->
    <view
      class="sh-scaffold"
      :class="{ 'is-padded': padded, 'has-tabbar': !!tab }"
      :style="{ '--sh-navbar-h': statusBar + 44 + 'px' }"
    >
      <view v-if="denied" class="sh-denied">
        <text class="sh-denied__t">{{ deniedText || $t("common.noPermTitle") }}</text>
        <text class="sh-denied__d">{{ $t("common.noPermHint") }}</text>
      </view>
      <slot v-else />
    </view>
    <sh-tabbar v-if="tab" :active="tab"></sh-tabbar>
    <!-- 输入弹层的壳。挂在这里而不是各页自己摆：`prompt()` 要能在任何一段
         业务代码里 await，而那段代码未必知道自己所在的页面摆没摆过弹层 -->
    <sh-prompt></sh-prompt>
    <sh-confirm></sh-confirm>
    <sh-pick></sh-pick>
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
/*
 * 自绘标题栏。**fixed 而不是随流** —— 窄屏上 `.sh-frame` 不是 fixed（原生页面滚动），
 * 随流的话标题会跟着内容滚走；宽屏上 `.sh-frame` 带 transform，
 * fixed 会以框为包含块，于是标题栏自动跟着窄栏收窄，不必另写一套。
 * 高度 44px 取自 uni 自己那条 `.uni-page-head`，两端对齐。
 */
.navbar {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 50;
  background: var(--sh-surface);
}
.navbar__bar {
  position: relative;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
}
/* 返回键**绝对定位**：标题要在整条栏里居中，而不是在「返回键右边那段」里居中 ——
   后者会让有返回键和没返回键的两页标题位置差半个箭头，翻页时看得出来 */
.navbar__back {
  position: absolute;
  inset-inline-start: 8rpx;
  width: 72rpx;
  height: 72rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}
/* 标题两端留出返回键的宽度，长标题才不会压到箭头上 */
.navbar__title {
  max-width: calc(100% - 176rpx);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.sh-scaffold {
  min-height: 100vh;
  box-sizing: border-box;
  padding-bottom: calc(40rpx + constant(safe-area-inset-bottom));
  padding-bottom: calc(40rpx + env(safe-area-inset-bottom));
}
/* 页面边距。走变量的理由同 .sh-card（见 base.css）：两端密度诉求不同，
   默认 28rpx 保持 C 端原样，B 端在自己的 App.vue 里调紧 */
.sh-scaffold.is-padded {
  padding-left: var(--sh-pad-page, 28rpx);
  padding-right: var(--sh-pad-page, 28rpx);
  padding-top: var(--sh-pad-page, 28rpx);
}
/* 标题栏是 fixed 的，内容要留出等高的顶部空间（含状态栏）。
   `--sh-navbar-h` 由组件按机型算出来传进来 —— 状态栏高度各机不同，写死会在刘海屏上压住内容 */
/* #ifdef H5 || APP-PLUS */
.sh-scaffold {
  /* **默认值写出来，不靠 `var()` 的兜底。** 兜底会把拼错的变量名盖住（守卫拦的正是这个），
     而且这个默认值本身有意义：H5 没有状态栏，44px 就是最终值；
     App 上组件按 `statusBarHeight` 把它顶掉（行内 style 权重更高）。 */
  --sh-navbar-h: 44px;
  padding-top: var(--sh-navbar-h);
}
.sh-scaffold.is-padded {
  padding-top: calc(var(--sh-navbar-h) + var(--sh-pad-page, 28rpx));
}
/* #endif */
/* 自定义 tabBar 是 fixed 的，内容区要留出等高的底部空间 */
.sh-scaffold.has-tabbar {
  padding-bottom: calc(var(--sh-tabbar-h) + 40rpx + env(safe-area-inset-bottom));
}
/* 无权态：整屏只讲一件事，与「还没开店」那一屏同一套版式 */
.sh-denied {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 160rpx 48rpx 0;
  text-align: center;
}
.sh-denied__t {
  font-size: 34rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.sh-denied__d {
  margin-top: 16rpx;
  font-size: 26rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
</style>
