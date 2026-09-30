// 「这一步要身份」的统一入口。页面在按下需要身份的按钮时调它，而不是进页面就拦 ——
// 查料号、看行情游客都能做；到了询价、成为供应商这一刻再要登录，理由是讲得通的。
import { useUserStore } from "@/stores/user";
import { ROUTES, currentRoute, withQuery } from "./routes";

/** @return 已登录（或静默登录成功）返回 true；否则带上当前页跳去登录，返回 false */
export async function ensureLogin(): Promise<boolean> {
  const user = useUserStore();
  if (user.isLogin) return true;
  if (await user.silentLogin().catch(() => false)) return true;
  uni.navigateTo({ url: withQuery(ROUTES.login, { redirect: currentRoute() }) });
  return false;
}

/**
 * 只试、不跳：已登录或静默登录成功返回 true，否则 false，**不去登录页**。
 * 给底部菜单的几格用 —— 它们在 onShow 里认人，要是也跳登录页，
 * 他在登录页按返回回来又是一次 onShow，又被推回去，出不来。页面自己摆一个「去登录」。
 */
export async function tryLogin(): Promise<boolean> {
  const user = useUserStore();
  if (user.isLogin) return true;
  return user.silentLogin().catch(() => false);
}

/** 去登录页，登完回到当前页 */
export function goLogin(): void {
  uni.navigateTo({ url: withQuery(ROUTES.login, { redirect: currentRoute() }) });
}

/**
 * 「这一步要手机号」：询价、成为供应商。平台要打得通这个电话。
 * 资料里没有就先拉一次（静默登录回来的资料可能是旧的），还没有就去绑。
 */
export async function ensurePhone(): Promise<boolean> {
  if (!(await ensureLogin())) return false;
  const user = useUserStore();
  if (user.hasPhone) return true;
  await user.loadProfile().catch(() => null);
  if (user.hasPhone) return true;
  uni.navigateTo({ url: withQuery(ROUTES.login, { need: "phone" }) });
  return false;
}
