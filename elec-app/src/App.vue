<script setup lang="ts">
import { onLaunch } from "@dcloudio/uni-app";
import { setUnauthorizedHandler } from "@shared/net/http-client";
import { useThemeStore } from "@ai-shop/ui/stores/theme";
import { useAppStore } from "@ai-shop/ui/stores/app";
import { useMarketStore } from "@ai-shop/ui/stores/market";
import { useUserStore } from "@/stores/user";
import { ROUTES, currentRoute } from "@/shared/routes";

onLaunch(() => {
  // market 最先：money/datetime 的格式化依赖它（元器件只做国内，但库件照读它）
  useMarketStore().init();
  useThemeStore().init();
  useAppStore().init();
  const user = useUserStore();
  user.restore();
  // 打开即认人（小程序）：不 await，首页游客可看；失败静默，到要身份那一步再走登录页
  void user.silentLogin();

  // 与 c-app 同一个顺序（那边的注释写了为什么）：先扔死令牌、再试静默登录，都不成才去登录页，并带上回来的路
  setUnauthorizedHandler(async () => {
    user.clearSession();
    if (await user.silentLogin(true).catch(() => false)) return;
    uni.showToast({ title: "登录已失效，请重新登录", icon: "none" });
    const back = currentRoute();
    setTimeout(() => uni.navigateTo({
      url: back ? `${ROUTES.login}?redirect=${encodeURIComponent(back)}` : ROUTES.login,
    }), 0);
  });
});
</script>

<style>
/* 全局样式基座三端共用，见 packages/ui/src/styles/base.css */
@import "@ai-shop/ui/styles/base.css";
</style>
