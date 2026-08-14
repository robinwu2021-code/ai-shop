<script setup lang="ts">
import { computed } from "vue";
import { onLaunch } from "@dcloudio/uni-app";
import { configureShell } from "@ai-shop/ui/shell";
import { ROUTES, TABS, TAB_ROUTES } from "@shared/utils/constants";
import { initPush } from "@shared/ports/push";
import { setUnauthorizedHandler } from "@shared/net/http-client";
import { flyState, registerCartAnchor } from "@/shared/fly";
import { useThemeStore } from "@ai-shop/ui/stores/theme";
import { useAppStore } from "@ai-shop/ui/stores/app";
import { useMarketStore } from "@ai-shop/ui/stores/market";
import { useUserStore } from "@/stores/user";
import { useCommunityStore } from "@/stores/community";
import { useCartStore } from "@/stores/cart";
import { initFonts } from "@shared/ports/font";
import { USE_MOCK } from "@/api";
import { restoreDb } from "@shared/mock/db";

onLaunch(() => {
  // 外壳的 C 端特征：购物车角标、飞入小球与它的落点、切语言/市场后要重拉的服务端文案。
  // 组件库对这些一无所知（packages/ui/src/shell.ts）
  const cart = useCartStore();
  configureShell({
    tabs: TABS,
    badge: (key) => (key === "cart" ? cart.count : 0),
    // landTick 变一次 = 小球落了一次 = 购物车图标弹一下
    pulse: computed(() => (flyState.landTick ? "cart" : "")),
    onTabbarReady: (ctx) => registerCartAnchor(".tabbar__anchor--cart", ctx),
    onLangChange: async () => {
      await Promise.all([useCommunityStore().refreshLocalized(), cart.load()]);
    },
    onMarketChange: () => cart.load(),
  });

  // mock 的服务端状态（购物车/订单/卡包）从本地恢复 —— 真后端本来就是持久的，
  // mock 不持久会掩盖问题（例如「刷新后购物车空了」在真环境不会发生）
  if (USE_MOCK) restoreDb();
  // market 要最先初始化：money/datetime 的格式化依赖它设定的货币与时区
  useMarketStore().init();
  useThemeStore().init(); // 皮肤 + 明暗
  useAppStore().init(); // 语言 + RTL
  useUserStore().restore(); // 登录态

  /*
   * 登录失效时去登录页。**注册在壳上**，因为 401 可能从任何一个请求回来。
   *
   * 此前一处也没接：令牌一过期（重启后端、放一夜），页面渲染成一片空白
   * 加一个未捕获错误 —— 没有提示、没有跳转，刷新也一样，因为没人清 token。
   *
   * 用 reLaunch 而不是 navigateTo：登录态没了，栈里剩下的页面每一张都拉不到数据。
   * 再延一个宏任务：这条路径最常见的触发点是启动时的那几个请求，
   * 那一刻首页还没挂载，此时发起的跳转会被直接丢掉（B 端实测过两次）。
   */
  setUnauthorizedHandler(() => {
    useUserStore().clearSession();
    uni.showToast({ title: "登录已失效，请重新登录", icon: "none" });
    setTimeout(() => uni.reLaunch({ url: ROUTES.login }), 0);
  });

  // 社区归属里存的是**绑定当时那门语言/那个市场**的文案快照。
  // 上次用英文绑定、这次以中文启动时，界面是中文而归属条是英文 —— 所以启动时也要校正一次，
  // 不能只在「切语言」这个动作里重拉。失败不阻塞启动。
  const community = useCommunityStore();
  community.restore();
  void community.refreshLocalized();

  initFonts(); // 远程字体，失败静默降级

  // 推送点击的落点（ADR-018）。tab 页只能 switchTab —— 用 navigateTo 会静默失败，
  // 表现是「点开推送什么也没发生」
  initPush((link) => {
    const path = link.split("?")[0] ?? link;
    if (TAB_ROUTES.has(path)) {
      uni.switchTab({ url: path });
    } else {
      uni.navigateTo({ url: link });
    }
  });
});
</script>

<style>
/* 全局样式基座两端共用，见 packages/ui/src/styles/base.css */
@import "@ai-shop/ui/styles/base.css";
</style>
