// 朋友圈单页模式的页面跳转拦截（TDD-C端朋友圈分享修补 AC3）。
//
// 单页模式下微信禁止页面跳转，而页面上几乎每个动作最后都是一次跳转（去登录、去结算、进商品、
// 进购物车）。被禁的跳转**不报错也不动** —— 所以在壳上一处拦下来，明说「点底部『前往小程序』」。
import { inTimelineSinglePage } from "@shared/ports/share";

const NAV_APIS = ["navigateTo", "redirectTo", "switchTab", "reLaunch"] as const;

/** 被拦时说的那句话。壳在启动时交进来 —— store 里拿不到 i18n（部分页面测试把 vue-i18n 替身成只有 useI18n） */
let blockedMessage: () => string = () => "";

/** 被拦时的提示文案（购物车等非组件代码用） */
export function singlePageBlockedMessage(): string {
  return blockedMessage();
}

/**
 * 单页模式下给四个跳转接口挂拦截器：弹一句提示并取消这次调用（invoke 返回 false = 取消）。
 * 其它场景什么都不做。
 *
 * @return 是否挂上了
 */
export function guardSinglePageNavigation(message: () => string): boolean {
  blockedMessage = message;
  if (!inTimelineSinglePage()) return false;
  const blocked = {
    invoke() {
      uni.showToast({ title: message(), icon: "none" });
      return false;
    },
  };
  for (const m of NAV_APIS) uni.addInterceptor(m, blocked);
  return true;
}
