"use client";

import { Suspense, useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { parseLocale, useLocaleStore } from "@/lib/stores/locale";
import { receiveSharedSession } from "@/lib/auth/shared-session";
import { useServerMenu } from "@/lib/stores/server-menu";
import { loginReturnHref } from "@/lib/auth/ops-bridge";
import { useAuth } from "@/lib/auth";
import { Rail } from "./rail";
import { SecondaryNav } from "./secondary-nav";
import { Header } from "./header";
import { useI18n } from "@/lib/i18n";
import { Button } from "@/components/ui/button";
import { PhaseGuard } from "./phase-guard";

// 登录守卫 + 三级导航布局：Rail(L1) + SecondaryNav(L2/L3，单模块域自隐) + Header/main。
// 未登录跳 /login；/login 页自身不套 shell。ready 门保证导航在 hydration 后渲染（persist 安全）。
export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const queryClient = useQueryClient();
  const loggedIn = useAuth((s) => s.loggedIn());
  const [ready, setReady] = useState(false);
  const [sessionError, setSessionError] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const { t } = useI18n();

  // trailingSlash:true → pathname 可能带尾斜杠，归一化后再比较。
  const norm = pathname.replace(/\/+$/, "") || "/";
  const isLogin = norm === "/login";

  useEffect(() => {
    let active = true;
    const locale = parseLocale(new URLSearchParams(window.location.search).get("locale"));
    if (locale) useLocaleStore.getState().setLocale(locale);
    setReady(false); setSessionError(false);
    void receiveSharedSession(() => queryClient.clear()).then(() => {
      if (active) setReady(true);
    }).catch(() => {
      if (active) setSessionError(true);
    });
    const clear = () => { queryClient.clear(); useServerMenu.getState().clear(); };
    window.addEventListener("shop-ops-session-cleared", clear);
    return () => { active = false; window.removeEventListener("shop-ops-session-cleared", clear); };
  }, [queryClient, attempt]);

  useEffect(() => {
    if (ready && !loggedIn && !isLogin) router.replace(loginReturnHref(window.location));
  }, [ready, loggedIn, isLogin, router]);

  if (sessionError) return <div className="flex min-h-screen items-center justify-center bg-muted/30">
    <div role="alert" className="max-w-md space-y-4 p-6">
      <p>{t("login.sessionRetry")}</p>
      <div className="flex gap-2">
        <Button onClick={() => setAttempt((value) => value + 1)}>{t("login.retrySession")}</Button>
        <Button variant="ghost" onClick={() => {
          useAuth.getState().logout(); setSessionError(false); setReady(true);
          router.replace(isLogin ? "/login/" : loginReturnHref(window.location));
        }}>{t("login.otherAccount")}</Button>
      </div>
    </div>
  </div>;
  if (isLogin) return ready ? <>{children}</> : null;
  if (!ready || !loggedIn) return null;

  return (
    // 规范：设计规范-布局与外壳.md §2 —— 只用两级色调，全幅铺满不留边距。
    <div data-shell="page" className="flex h-screen overflow-hidden">
      {/* 一整块白底，中间嵌一条灰带：灰色的二级面板自己就是 rail 与 content 的分隔 */}
      <div data-shell="nav" className="flex min-h-0 shrink-0 overflow-hidden">
        <Suspense fallback={null}><Rail /></Suspense>
        {/* SecondaryNav 读 useSearchParams（静态导出要求包 Suspense） */}
        <Suspense fallback={null}>
          <SecondaryNav />
        </Suspense>
      </div>

      {/* 内容纸：header 吸顶不滚，页体独立滚动 */}
      <div data-shell="sheet" className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
        {/* Header 放进滚动容器内 sticky：静止时与内容同底无感知，滚动时内容从
            毛玻璃下穿过、自然出现分界 —— 零线条（Linear/GitHub 的做法）。 */}
        <main className="flex-1 overflow-y-auto">
          <Header />
          {/* data-shell="body" 是**页体的稳定锚点**：dev 工具 /dev/pages 隔着 iframe
              扫真实页面时要能只扫页体 —— 顶栏与导航在每一页都一样，跟着扫 124 遍
              只会把清单淹掉。别改这个属性名，那边按它取根节点。 */}
          <div data-shell="body" className="p-6 pt-2">
          {/* PhaseGuard 读 useSearchParams（静态导出要求包 Suspense） */}
          <Suspense fallback={null}>
            <PhaseGuard>{children}</PhaseGuard>
          </Suspense>
          </div>
        </main>
      </div>
    </div>
  );
}
