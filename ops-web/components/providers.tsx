"use client";

import { QueryClient, QueryClientProvider, MutationCache, focusManager } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useTheme, applyTheme } from "@/lib/stores/theme";
import { useLocaleStore, applyLocale } from "@/lib/stores/locale";
import { notify } from "@/lib/notify";
import { Toaster } from "@/components/ui/toaster";
import { refreshPerms, useAuth } from "@/lib/auth";

/**
 * 权限与菜单的刷新时机：**挂载时一次 + 切回窗口时一次。没有轮询。**
 *
 * 原先每 60 秒一轮。删掉的理由见 `useRefreshPerms` 里那段注释 ——
 * 一句话：**每一条改权限的写路径都已经把人踢下线了**，快照没有机会过期。
 */

/**
 * 让 perms 与菜单在**进入与回到页面时**保持最新。
 *
 * **为什么还需要它**：perms 是登录那一刻算好塞进 localStorage 的。
 * 权限真的变了时后端会踢会话（下一个请求 401 → 重新登录 → 新 perms），
 * 所以过期的窗口极窄；这里管的是另外两件事 ——
 * <b>菜单顺序</b>（刻意不踢会话的那一类）与<b>多标签页</b>
 * （在另一个标签页里重登过，这个标签页的 store 还是旧的）。
 *
 * <p>后端在 2026-08-12 把判权改成了现算（`LivePermResolver`），
 * 接口那一侧已经是改完即生效。这里补上界面这一侧 —— 两边都实时，
 * 才不会出现「菜单还在但点进去 403」或者反过来「有权限却看不见入口」。
 *
 * <p>两个触发点：**挂载**、**窗口重新可见**。<b>没有定时器</b> ——
 * 后台标签页每分钟打两个请求，一天下来是白烧的服务端资源，而它并没有在兜任何东西。
 *
 * <p>失败**不清登录态**：网络抖一下就把人踢到登录页，比权限晚几分钟生效坏得多。
 * 真的失效了（401）由 http 层统一处理。
 */
function useRefreshPerms() {
  const token = useAuth((s) => s.token);
  useEffect(() => {
    if (!token) return;
    let alive = true;

    // 只有一份实现（lib/auth 里）—— 403 被拒时走的也是它
    const refresh = () => void refreshPerms();

    refresh();
    /*
     * **切回标签页时再拉一次。没有定时器。**
     *
     * 这里原先每 60 秒轮询一次（每人每天上千次请求，其中 999‰ 拿回一模一样的东西）。
     * 拆开看，它其实什么都没在兜：
     *
     *   · **权限变更**：每一条改权限的写路径都已经 `revokeUser` 把人踢下线了 ——
     *     改角色的功能点（`PermConfigServiceImpl.setRolePoints`）、改某人的角色 /
     *     停用 / 改数据域（`OpsServiceImpl` 五处）。会话没了，下一个请求就是 401，
     *     人重新登录、拿到新的 perms。**快照根本没有机会过期**，
     *     轮询在这条路径上是纯浪费。删角色则被「还有人在用就不让删」挡在前面。
     *   · **菜单顺序**：那是唯一刻意不踢会话的一类（顺序变了，权限一点没变），
     *     靠这里传播。但它是纯展示的低频运营动作 —— 切回标签页、或下次打开页面
     *     再看到新顺序，完全够用，不值得为它每分钟打一次。
     *
     * 换句话说：**「可以重新登录一次」这个前提一旦成立，定时器就没有存在理由了。**
     * 保留 visibilitychange 是因为它零常态成本，且正好覆盖「挂着标签页去开会」那一档。
     */
    const onVisible = () => {
      if (document.visibilityState === "visible") refresh();
    };
    document.addEventListener("visibilitychange", onVisible);

    return () => {
      alive = false;
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [token]);
}

/*
 * **接管 React Query 的焦点判定，钉成「始终有焦点」。**
 *
 * 不这么做的后果是一个不会自愈的死局：请求失败时 React Query 会安排重试，
 * 而**窗口没有焦点时重试被暂停**（`fetchStatus: "paused"`）——
 * 于是 `status` 永远停在 `pending`，页面里那句
 * `if (!data && isPending) return <Skeleton/>` 就一直渲染骨架屏：
 * 没有错误、没有重试入口，而且**焦点回来也不恢复**。
 *
 * 场景一点都不刁钻：运营在后台标签页打开运营端，某个接口抖一下，
 * 他切回来看到的是一个永远转不完的加载条 —— 他会以为「系统卡住了」，
 * 而真相是「请求失败过一次，然后没人再试了」。
 * 2026-09-09 在 /communities?tab=health 上实地撞到（后端那次是本地 jar 陈了），
 * 两处独立复现：真实浏览器 + `lib/query-focus.test.ts` 里的单测。
 *
 * 代价是失去「后台标签页里不重试」这个省电优化。对一个内部管理台来说，
 * 「失败要看得见」远比省那一次重试重要 —— 何况 retry 只有 1 次。
 *
 * ⚠️ 只在浏览器里做。服务端渲染时没有 window，也没有焦点这回事。
 */
if (typeof window !== "undefined") {
  focusManager.setEventListener(() => () => {});  // 断开 visibilitychange 这个事件源
  focusManager.setFocused(true);
}

export function Providers({ children }: { children: React.ReactNode }) {
  const [qc] = useState(
    () =>
      new QueryClient({
        // 所有 mutation 失败统一 toast（消息已本地化：后端 message / mock i18n / ApiError）。
        mutationCache: new MutationCache({
          onError: (e) => notify.error(e instanceof Error ? e.message : String(e)),
        }),
        defaultOptions: { queries: { staleTime: 15_000, retry: 1, refetchOnWindowFocus: false } },
      }),
  );
  // hydration 后对齐主题与语言（首帧脚本已抢先应用，避免闪烁）。
  const themeKey = useTheme((s) => s.themeKey);
  const locale = useLocaleStore((s) => s.locale);
  useEffect(() => applyTheme(themeKey), [themeKey]);
  useEffect(() => applyLocale(locale), [locale]);
  useRefreshPerms();

  return (
    <QueryClientProvider client={qc}>
      {children}
      <Toaster />
    </QueryClientProvider>
  );
}
