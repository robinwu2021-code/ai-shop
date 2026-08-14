"use client";

import { Suspense, useEffect, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { IS_MOCK } from "@/lib/api";
import { breadcrumb } from "@/lib/nav";
import { useNavPrefs } from "@/lib/stores/nav-prefs";
import { useServerMenu, useNavTree } from "@/lib/stores/server-menu";
import { useI18n } from "@/lib/i18n";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ThemeSwitcher } from "./theme-switcher";
import { LangSwitcher } from "./lang-switcher";
import { NotifyBell } from "./notify-bell";
import { CommandPalette, useCommandPalette } from "./command-palette";
import { ChevronRight, LogOut, PanelLeft, Search } from "lucide-react";

// 面包屑：L1 › 分组 › 子功能（URL 反推，标签经 tNav 本地化）。
// **整条不可点**：它是位置指示器不是导航 —— 每一级在 Rail / SecondaryNav / TabHeader
// 里都已有可点的呈现，再给一份链接只会让人以为点了能去别处。理由见 lib/nav.ts breadcrumb()。
// 读 useSearchParams → 包 Suspense。
function Breadcrumb() {
  const pathname = usePathname();
  const sp = useSearchParams();
  const perms = useAuth((s) => s.perms);
  const { tNav } = useI18n();
  const serverHrefs = useServerMenu((s) => s.hrefSet);
  const nav = useNavTree();
  const crumbs = breadcrumb(pathname, sp.get("tab"), sp.get("view"), perms, serverHrefs, nav);
  if (!crumbs.length) return null;
  return (
    <nav aria-label="breadcrumb" className="flex items-center gap-1 txt-body text-muted-foreground">
      {crumbs.map((c, i) => {
        const last = i === crumbs.length - 1;
        return (
          <span key={i} className="flex items-center gap-1">
            {i > 0 && <ChevronRight className="size-3.5 opacity-50 rtl:-scale-x-100" />}
            <span className={last ? "text-foreground" : undefined} aria-current={last ? "page" : undefined}>
              {tNav(c)}
            </span>
          </span>
        );
      })}
    </nav>
  );
}

/** ⌘K 入口条。快捷键本身是全局的，这里只是让不知道有快捷键的人也能点开。 */
function SearchTrigger({ onOpen }: { onOpen: () => void }) {
  const { t } = useI18n();
  // Mac 显示 ⌘K，其余显示 Ctrl K。SSR 静态导出 → 首帧无 navigator，挂载后再定。
  const [mac, setMac] = useState(false);
  useEffect(() => { setMac(/Mac|iP(hone|ad|od)/.test(navigator.platform || navigator.userAgent)); }, []);
  return (
    <button
      type="button"
      onClick={onOpen}
      aria-label={t("nav.search")}
      className="flex items-center gap-2 rounded-field px-2 py-1 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
    >
      <Search className="size-3.5" />
      <span className="max-sm:hidden">{t("nav.searchHint")}</span>
      <kbd className="rounded-chip border border-border px-1.5 txt-caption max-sm:hidden">{mac ? "⌘" : "Ctrl "}K</kbd>
    </button>
  );
}

export function Header() {
  const { username, role, merchantNo, communityNo, logout } = useAuth();
  // 数据域徽标：被限定到某商家/社区的账号，顶栏要一直显示裁剪范围 ——
  // 否则运营看到"列表里只有 3 个商家"会以为是数据丢了（矩阵 §2.3 数据域）。
  const scope = merchantNo || communityNo;
  const { t } = useI18n();
  const router = useRouter();
  const { panelCollapsed, togglePanel } = useNavPrefs();
  const { open, setOpen } = useCommandPalette();
  return (
    <header className="sticky top-0 z-[var(--z-sticky)] flex h-14 items-center justify-between bg-[color-mix(in_srgb,var(--surface)_82%,transparent)] px-6 backdrop-blur-md">
      <div className="flex items-center gap-3 txt-body text-muted-foreground">
        {/* 面板开关放顶栏而不是面板自己身上：收起后面板整个不渲染，
            按钮长在它上面就跟着消失了，没法再展开。 */}
        <button
          type="button"
          onClick={togglePanel}
          aria-label={t(panelCollapsed ? "nav.expandPanel" : "nav.collapsePanel")}
          title={t(panelCollapsed ? "nav.expandPanel" : "nav.collapsePanel")}
          aria-pressed={panelCollapsed}
          className="-ms-2 hidden rounded-field p-1 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground md:block"
        >
          <PanelLeft className="size-4 rtl:-scale-x-100" />
        </button>
        <Suspense fallback={null}>
          <Breadcrumb />
        </Suspense>
        {scope && <span>{t("common.scope")} <span className="text-foreground">{scope}</span></span>}
        {IS_MOCK && <Badge tone="warning">{t("common.mockData")}</Badge>}
      </div>
      <div className="flex items-center gap-2 txt-body">
        <SearchTrigger onOpen={() => setOpen(true)} />
        <CommandPalette open={open} onOpenChange={setOpen} />
        <NotifyBell />
        <LangSwitcher />
        <ThemeSwitcher />
        {/* 角色名不再单独占位：进用户名的 title。此前"运营管理员 admin"两段文本
            说的是同一个人，占了顶栏最大的一块。 */}
        <span className="font-medium" title={t(`role.${role}`)}>{username}</span>
        <Button
          size="sm"
          variant="ghost"
          aria-label={t("common.logout")}
          title={t("common.logout")}
          onClick={() => {
            logout();
            router.push("/login");
          }}
        >
          <LogOut className="size-4 rtl:-scale-x-100" />
        </Button>
      </div>
    </header>
  );
}
