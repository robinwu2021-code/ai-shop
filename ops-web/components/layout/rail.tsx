"use client";

// L1 图标栏（Rail）：RBAC 过滤 + 当前项高亮 + 待建灰显 + pinBottom + 可展开标签。
// **当前 21 个业务域，竖排 904px —— 在 720px 以下的窗口必然超出视口。**
// nav 一直有 overflow-y-auto（滚得动），但 macOS 的覆盖式滚动条静止时不渲染，
// 于是「下面还有 8 个」没有任何提示。`ScrollHint` 补的就是这个提示，不是滚动本身。
// 仅依赖 pathname（不读 query），无需 Suspense。
import * as React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import * as Icons from "lucide-react";
import {
  RAIL_WIDTH, RAIL_EXPANDED_WIDTH, type NavSection,
  visibleSections, findActiveSection, sectionDefaultHref, normPath,
} from "@/lib/nav";
import { useAuth } from "@/lib/auth";
import { useNavPrefs } from "@/lib/stores/nav-prefs";
import { useServerMenu, useNavTree } from "@/lib/stores/server-menu";
import { useI18n } from "@/lib/i18n";
import { Tooltip } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import { ScrollHint, useScrollHint } from "./scroll-hint";

function iconOf(name: string) {
  return (Icons[name as keyof typeof Icons] ?? Icons.Circle) as React.ComponentType<{ className?: string }>;
}

function RailItem({
  section, active, soon, href, expanded,
}: { section: NavSection; active: boolean; soon: boolean; href?: string; expanded: boolean }) {
  const Icon = iconOf(section.icon);
  const { t, tNav } = useI18n();
  const label = tNav(section.label);
  const inner = (
    <>
      {active && <span className="absolute top-1/2 h-6 w-[3px] -translate-y-1/2 rounded-e-full bg-primary" style={{ insetInlineStart: 0 }} />}
      <Icon className="size-5 shrink-0" />
      {expanded && <span className="truncate txt-body">{label}</span>}
      {expanded && soon && <span className="ms-auto rounded-control bg-muted px-1 txt-caption text-muted-foreground">{t("common.soon")}</span>}
    </>
  );
  // shrink-0：项多时容器溢出滚动，flex 默认会压扁子项（图标变形），必须禁止收缩。
  const base = cn(
    "group relative flex shrink-0 items-center gap-3 rounded-field py-2 transition-colors",
    expanded ? "px-3" : "justify-center px-0",
  );
  // 折叠态提示：自绘 Tooltip（components/ui/tooltip.tsx，portal+fixed）。
  // 注意 ⚠️ 不要改回 absolute 浮层：父级 nav 有 overflow-y-auto，CSS 规范下一轴 auto 会把
  // 另一轴的 visible 也算成 auto → 浮层被裁掉，表现为「只有图标、没有任何提示」。
  // 也不要退回原生 title（约 1s 延迟 + 系统样式，十几个纯图标下辨识成本太高）。
  // 展开态已显示名称，不出 tip（label=undefined 时 Tooltip 完全透传）。
  const tipText = !expanded ? `${label}${soon ? ` · ${t("common.soon")}` : ""}` : undefined;

  return (
    <Tooltip label={tipText} side="right">
      {({ ref, ...tp }) =>
        soon || !href ? (
          <span
            {...tp}
            ref={ref}
            aria-disabled
            tabIndex={0} // 不可点也要能 Tab 到，否则键盘用户读不到「敬请期待」
            className={cn(base, "cursor-not-allowed text-muted-foreground/40")}
          >
            {inner}
          </span>
        ) : (
          <Link
            {...tp}
            ref={ref}
            href={href}
            aria-label={label}
            className={cn("focus-ring", base, active ? "bg-accent font-medium text-[var(--primary)]" : "text-sidebar-foreground hover:bg-accent/60 hover:text-foreground")}
          >
            {inner}
          </Link>
        )
      }
    </Tooltip>
  );
}

export function Rail() {
  const pathname = normPath(usePathname());
  const perms = useAuth((s) => s.perms);
  const { railExpanded, toggleRail } = useNavPrefs();
  const scrollRef = React.useRef<HTMLElement>(null);
  const hint = useScrollHint(scrollRef);
  const { t } = useI18n();

  const serverHrefs = useServerMenu((s) => s.hrefSet);
  const nav = useNavTree();
  const sections = visibleSections(perms, serverHrefs, nav);
  const activeKey = findActiveSection(pathname, perms, serverHrefs)?.key;
  const top = sections.filter((s) => !s.pinBottom);
  const bottom = sections.filter((s) => s.pinBottom);

  const render = (s: NavSection) => (
    <RailItem
      key={s.key}
      section={s}
      active={s.key === activeKey}
      soon={!!s.soon}
      href={sectionDefaultHref(s, perms)}
      expanded={railExpanded}
    />
  );

  return (
    <aside
      data-shell="rail"
      // 底色由父级导航纸提供（规范 §2.2：rail 与 panel 同属一张纸）
      className="hidden shrink-0 flex-col text-sidebar-foreground md:flex"
      style={{ width: railExpanded ? RAIL_EXPANDED_WIDTH : RAIL_WIDTH }}
    >
      <div className={cn("flex h-14 items-center gap-2", railExpanded ? "px-4" : "justify-center")}>
        <span className="flex size-8 shrink-0 items-center justify-center rounded-field bg-primary txt-caption text-primary-foreground">邻</span>
        {railExpanded && <span className="truncate txt-strong">{t("common.appName")}</span>}
      </div>
      {/* relative：渐隐条挂在容器的定位父级上，跟着容器一起滚就没意义了 */}
      <div className="relative flex min-h-0 flex-1 flex-col">
        {/*
          gap-0.5 + py-1 而不是 gap-1 + py-2：13 个 L1 在 620px 高的窗口里
          可用高只有 524（视口 − Logo 56 − 收起按钮 40），原来的节奏要 536px，差 12px。
          为 12px 再合并一个业务域是拿像素倒推信息架构 —— 收紧自己的间距才是这一层该做的事。
          现在 13×36 + 12×2 + 8 = 500，还剩 24px 余量。
        */}
        <nav ref={scrollRef} className={cn("flex flex-1 flex-col gap-0.5 overflow-y-auto px-2 py-1")}>
          {top.map(render)}
          <div className="flex-1" />
          {bottom.map(render)}
        </nav>
        <ScrollHint side="top" show={hint.top} />
        <ScrollHint side="bottom" show={hint.bottom} />
      </div>
      <button
        type="button"
        onClick={toggleRail}
        aria-label={railExpanded ? t("nav.collapse") : t("nav.expand")}
        className="focus-ring flex h-[calc(var(--ctl-h)+4px)] items-center justify-center text-muted-foreground hover:bg-accent hover:text-foreground"
      >
        {railExpanded ? <Icons.ChevronsLeft className="size-4" /> : <Icons.ChevronsRight className="size-4" />}
      </button>
    </aside>
  );
}
