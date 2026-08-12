"use client";

import { QueryClient, QueryClientProvider, MutationCache } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useTheme, applyTheme } from "@/lib/stores/theme";
import { useLocaleStore, applyLocale } from "@/lib/stores/locale";
import { notify } from "@/lib/notify";
import { Toaster } from "@/components/ui/toaster";
import { useAuth } from "@/lib/auth";
import { useServerMenu } from "@/lib/stores/server-menu";
import { api } from "@/lib/api";

/**
 * 启动时把 perms 拉一次最新的。
 *
 * **为什么必须有**：perms 此前只在登录那一刻拿，之后冻在 localStorage 里。
 * 管理员改了某人的角色，那个人下次打开看到的还是旧权限 —— 而他不会想到
 * 「要重新登录」，他看到的是按钮该在的地方没有，或者点下去报 403。
 *
 * 失败**不清登录态**：网络抖一下就把人踢到登录页，比权限晚几分钟生效坏得多。
 * 真的失效了（401）由 http 层统一处理。
 */
function useRefreshPerms() {
  const token = useAuth((s) => s.token);
  useEffect(() => {
    if (!token) return;
    let alive = true;
    api
      .me()
      .then((r) => {
        // 组件已卸载 / 期间退出登录：这时写回去等于把已登出的人的权限塞回来
        if (!alive || !useAuth.getState().token) return;
        useAuth.setState({ role: r.role, perms: r.perms ?? [] });
        // perms 到手之后再拉菜单 —— 菜单是后端按人算的，早拉一次拿到的是旧身份的
        void useServerMenu.getState().load();
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [token]);
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
