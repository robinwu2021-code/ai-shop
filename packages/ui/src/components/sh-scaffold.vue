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
    /**
     * 标题后面缀一段**运行时才知道的字**，如「库存 · 福田店」。
     *
     * <p>为什么不是再开一个 titleKey：门店名不是词条，它是数据。
     * 而这一段存在的理由是**腾出首屏那一行** —— 门店维度的页面本来要在正文顶部
     * 挂一枚「当前门店」胶囊（`biz-store-tag`），那一行在列表页上很贵。
     * 缀进标题栏一个像素都不占，而「我在哪家店」还答得出。
     *
     * <p>⚠️ 它<b>不改变</b>那条不变量：门店维度的页面必须让人看得见自己在哪家店。
     * `biz-store-scope` 闸门认这两种写法中的任意一种，认不到仍然红。
     */
    titleSuffix?: string;
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
    /**
     * 这次没取到：整页替换成「没能加载出来 + 重试」，**不渲染 slot**。
     *
     * 与上面 `denied` 是同一类事的两个面。那条注释说的是
     * 「把『不给看』渲染成『没有』，比报错更糟 —— 它不像故障，像事实」；
     * 这一条说的是**把『没取到』渲染成『没有』**。
     *
     * 为什么收在外壳上：详情页与看板页的整页内容都挂在
     * `<template v-if="sum">` 这类守卫后面，拉不到就是**一个只有标题栏的空白页**
     * —— 不解释、不能重试，用户以为应用坏了。而这类页面存在的全部理由就是
     * 那份拉来的数据，拉不到该说什么没有第二种答案，所以不必让 13 个页面各写一遍。
     *
     * 呈现直接借 `sh-empty` 的出错态：文案（`common.loadFailed` / `loadFailedTip`）
     * 与那颗重试按钮都在那儿，再画一份只会两处漂。
     */
    failed?: boolean;
    /** 出错那一行的文案。留空用通用的「没能加载出来」 */
    failedText?: string;
    /**
     * 还不知道：**渲染外壳但不渲染 slot**。
     *
     * 详情页的正文全靠 `goods.title` 这类解引用，所以它们写成
     * `<sh-scaffold v-if="goods">` —— 于是首屏那一瞬间与失败之后，
     * **连外壳都不渲染**：没有导航栏、没有一个字，退不回去，也不知道发生了什么。
     * 把守卫挪到这里，外壳照常在，正文等数据到了再出。
     *
     * 与 `sh-empty` 的 `pending` 是同一档：不转圈（那一档留给骨架屏，眼下不做），
     * 只是不把「还不知道」画成「确定没有」。
     */
    pending?: boolean;
    /**
     * 沉浸式：**不画标题栏、也不给它留位置**，内容从屏幕最顶上开始。
     *
     * <p>给商品详情这类「主图顶到状态栏」的页面用：返回、购物车由页面自己浮在图上画
     * （小程序上那一页同时要在 pages.json 里设 `navigationStyle: custom`）。
     * 默认关 —— 其余页面的标题栏一律照旧。
     */
    immersive?: boolean;
  }>(),
  { padded: true, titleKey: "", tab: "", denied: false, deniedText: "",
    failed: false, failedText: "", pending: false, immersive: false },
);

// 重试由调用点决定重新拉什么 —— 外壳只负责那颗按钮长什么样、摆在哪
const emit = defineEmits<{ retry: [] }>();

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

const navTitle = computed(() => {
  if (!props.titleKey) return "";
  const base = String(t(props.titleKey));
  return props.titleSuffix ? `${base} · ${props.titleSuffix}` : base;
});

function goBack() {
  uni.navigateBack();
}

function applyTitle() {
  if (!props.titleKey) return;
  uni.setNavigationBarTitle({ title: navTitle.value });
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
// 门店名是异步拉回来的：不跟着它重刷，标题会一直停在没有后缀的那一版
watch(() => props.titleSuffix, applyTitle);
</script>

<template>
  <view class="sh-root sh-frame" :class="rootClass">
    <!-- #ifdef H5 || APP-PLUS -->
    <view v-if="!immersive" class="navbar" :style="{ paddingTop: statusBar + 'px' }">
      <view class="sh-center navbar__bar">
        <view v-if="canBack" class="sh-center navbar__back sh-hit" @tap="goBack">
          <sh-icon name="chevronLeft" :size="34" color="var(--sh-ink)"></sh-icon>
        </view>
        <text class="txt-title navbar__title">{{ navTitle }}</text>
      </view>
    </view>
    <!-- #endif -->
    <view
      class="sh-scaffold"
      :class="{ 'is-padded': padded, 'has-tabbar': !!tab, 'is-immersive': immersive }"
      :style="{ '--sh-navbar-h': statusBar + 44 + 'px' }"
    >
      <view v-if="denied" class="sh-denied">
        <text class="txt-title sh-denied__t">{{ deniedText || $t("common.noPermTitle") }}</text>
        <text class="txt-sub sh-denied__d">{{ $t("common.noPermHint") }}</text>
      </view>
      <!-- 顺序要紧：无权比没取到更根本 —— 没权限的人重试一万次也还是没权限 -->
      <view v-else-if="failed" class="sh-failed">
        <sh-empty bare failed :failed-text="failedText" @retry="emit('retry')"></sh-empty>
      </view>
      <!-- pending：外壳在，正文不在。**顺序在 failed 之后** —— 拉失败时
           `goods` 也还是空的，两个都为真时要看见的是出错，不是一片空白 -->
      <view v-else-if="pending"></view>
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
  z-index: var(--sh-z-nav);
  background: var(--sh-surface);
}
.navbar__bar {
  position: relative;
  height: 44px;
}
/* 返回键**绝对定位**：标题要在整条栏里居中，而不是在「返回键右边那段」里居中 ——
   后者会让有返回键和没返回键的两页标题位置差半个箭头，翻页时看得出来 */
.navbar__back {
  position: absolute;
  inset-inline-start: 8rpx;
  width: 72rpx;
  height: 72rpx;
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
  padding-bottom: 40rpx;
  padding-bottom: calc(40rpx + constant(safe-area-inset-bottom, 0px));
  padding-bottom: calc(40rpx + env(safe-area-inset-bottom, 0px));
}
/* 页面边距。走变量的理由同 .sh-card（见 base.css）：两端密度诉求不同，
   默认 28rpx 保持 C 端原样，B 端在自己的 App.vue 里调紧 */
.sh-scaffold.is-padded {
  padding-inline: var(--sh-pad-page, 28rpx);
  padding-top: var(--sh-pad-page, 28rpx);
}
/* 标题栏是 fixed 的，内容要留出等高的顶部空间（含状态栏）。
   `--sh-navbar-h` 由组件按机型算出来传进来 —— 状态栏高度各机不同，写死会在刘海屏上压住内容 */
/* #ifdef H5 || APP-PLUS */
.sh-scaffold {
  /* 变量定义在 base.css 的常量块里（与 `--sh-tabbar-h` 同处）——
     定义在这儿的话 `skin-vars` 守卫看不见它，会判成「引用了皮肤里没有的变量」。
     那道守卫拦的正是「`--sh-*` 拼错了没人知道」，不该为一处破例。 */
  padding-top: var(--sh-navbar-h);
}
.sh-scaffold.is-padded {
  padding-top: calc(var(--sh-navbar-h) + var(--sh-pad-page, 28rpx));
}
/* #endif */
/* 沉浸式：标题栏不画，它的位置也不留（写在条件编译块之后，才压得过上面那两条） */
.sh-scaffold.is-immersive {
  padding-top: 0;
}
.sh-scaffold.is-immersive.is-padded {
  padding-top: var(--sh-pad-page, 28rpx);
}
/* 自定义 tabBar 是 fixed 的，内容区要留出等高的底部空间 */
.sh-scaffold.has-tabbar {
  padding-bottom: calc(var(--sh-tabbar-h, 124rpx) + 40rpx);
  padding-bottom: calc(var(--sh-tabbar-h, 124rpx) + 40rpx + env(safe-area-inset-bottom, 0px));
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
  color: var(--sh-ink);
}
.sh-denied__d {
  margin-top: 16rpx;
  color: var(--sh-sub);
}
/* 与 `.sh-denied` 站在同一个高度：两者都是「整页替换」，位置不一致会让人
   以为是两种不同性质的东西。`sh-empty` 自带 72rpx 内边距，这里补到 160。 */
.sh-failed {
  padding-top: 88rpx;
}
</style>
