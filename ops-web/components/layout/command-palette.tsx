"use client";

// ⌘K 命令面板：全局搜索导航（L1 + L3），选中即跳转。
//
// 为什么要它：Rail(56) + 面板(176) = 232px 常驻横向开销，而 18 个业务域 × 数十个叶子
// 靠肉眼在面板里找并不快。有了它，面板才**可以收起**（nav-prefs.panelCollapsed）——
// 搜索本身不省面积，"搜索 + 面板可收起"才省。两者要一起上，单给搜索等于没动面积。
//
// 候选集与匹配都在 lib/nav.ts（纯函数、可单测）；这里只管交互：
// 键盘（↑↓ 选择 / Enter 跳转 / Esc 关闭）、输入、高亮。
import * as React from "react";
import { useRouter } from "next/navigation";
import * as Dialog from "@radix-ui/react-dialog";
import { Search, CornerDownLeft } from "lucide-react";
import { navSearchEntries, matchesQuery, type NavSearchEntry } from "@/lib/nav";
import { useAuth } from "@/lib/auth";
import { useServerMenu, useNavTree } from "@/lib/stores/server-menu";
import { useI18n } from "@/lib/i18n";
import { cn } from "@/lib/utils";

/** 结果上限：面板是"快速跳转"，不是"浏览全部"——超过一屏就该继续输入而不是翻。 */
const MAX_RESULTS = 12;

export function useCommandPalette() {
  const [open, setOpen] = React.useState(false);
  // ⌘K / Ctrl+K 全局快捷键。输入框内也生效（那是"再按一次关掉"的直觉）。
  React.useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key.toLowerCase() === "k" && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        setOpen((o) => !o);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);
  return { open, setOpen };
}

export function CommandPalette({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const router = useRouter();
  const perms = useAuth((s) => s.perms);
  const serverHrefs = useServerMenu((s) => s.hrefSet);
  const nav = useNavTree();
  const { t, tNav } = useI18n();
  const [query, setQuery] = React.useState("");
  const [cursor, setCursor] = React.useState(0);
  const listRef = React.useRef<HTMLDivElement>(null);

  const entries = React.useMemo(
    () => navSearchEntries(perms, serverHrefs, nav),
    [perms, serverHrefs, nav],
  );

  // 候选串用**译后**的文案拼，否则切到 EN 后只能用中文搜到东西。
  const results = React.useMemo(() => {
    const scored = entries.filter((e) => {
      const hay = [tNav(e.section), e.group ? tNav(e.group) : "", tNav(e.label)].join(" ");
      return matchesQuery(hay, query);
    });
    return scored.slice(0, MAX_RESULTS);
    // tNav 随 locale 变，locale 变了要重算 —— 依赖里带上它。
  }, [entries, query, tNav]);

  // 每次改查询把光标拉回首项：否则筛完之后高亮可能落在列表之外。
  React.useEffect(() => setCursor(0), [query]);
  // 每次打开清空：上一次的查询词对这一次没有意义，还挡着新输入。
  React.useEffect(() => { if (open) { setQuery(""); setCursor(0); } }, [open]);

  // 键盘选中的项要滚进视野（纯键盘操作时看不见高亮就等于没有高亮）
  React.useEffect(() => {
    listRef.current?.querySelector<HTMLElement>('[data-active="true"]')
      ?.scrollIntoView({ block: "nearest" });
  }, [cursor, results]);

  const go = React.useCallback((e: NavSearchEntry | undefined) => {
    if (!e) return;
    onOpenChange(false);
    router.push(e.href);
  }, [onOpenChange, router]);

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") { e.preventDefault(); setCursor((c) => (results.length ? (c + 1) % results.length : 0)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setCursor((c) => (results.length ? (c - 1 + results.length) % results.length : 0)); }
    else if (e.key === "Enter") { e.preventDefault(); go(results[cursor]); }
  };

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        {/* data-[state=open]: 前缀会让 animate-in 这类纯 CSS 类生成不出规则，见 confirm-dialog.tsx 同注释 */}
        <Dialog.Overlay className="fixed inset-0 z-[var(--z-dialog)] bg-black/40 animate-in fade-in" />
        <Dialog.Content
          onKeyDown={onKeyDown}
          aria-label={t("nav.search")}
          // 顶部 18vh 而非垂直居中：命令面板的惯例位置，且列表变长时不会上下跳。
          // 动画用 zoom-in-top 不是 zoom-in —— 后者的关键帧焊死了居中用的 -50% Y，
          // 配上 fill-mode:both 会把这个顶部锚定的弹层永久拽上去半个身位（见 globals.css）。
          className="fixed left-1/2 top-[18vh] z-[var(--z-dialog)] w-[min(92vw,520px)] -translate-x-1/2 overflow-hidden rounded-sheet bg-card shadow-pop outline-none animate-in zoom-in-top"
        >
          <Dialog.Title className="sr-only">{t("nav.search")}</Dialog.Title>
          <div className="flex items-center gap-2 px-4 py-3">
            <Search className="size-4 shrink-0 text-muted-foreground" />
            <input
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={t("nav.searchPlaceholder")}
              className="focus-ring min-w-0 flex-1 bg-transparent txt-body outline-none placeholder:text-muted-foreground"
            />
            <kbd className="shrink-0 rounded-chip border border-border px-1.5 txt-caption text-muted-foreground">Esc</kbd>
          </div>

          {results.length === 0 ? (
            <div className="px-4 pb-4 pt-1 txt-body text-muted-foreground">{t("nav.searchEmpty")}</div>
          ) : (
            <div ref={listRef} role="listbox" className="max-h-[52vh] overflow-y-auto px-2 pb-2">
              {results.map((e, i) => (
                <button
                  key={`${e.href}|${e.label}`}
                  type="button"
                  role="option"
                  aria-selected={i === cursor}
                  data-active={i === cursor}
                  // 鼠标移上去就等于选中：否则键盘光标与鼠标悬停会同时高亮两行
                  onMouseMove={() => setCursor(i)}
                  onClick={() => go(e)}
                  className={cn("focus-ring", 
                    "flex w-full items-center gap-2 rounded-field px-2.5 py-2 text-start txt-body transition-colors",
                    i === cursor ? "bg-accent text-foreground" : "text-muted-foreground",
                  )}
                >
                  <span className="truncate font-medium text-foreground">{tNav(e.label)}</span>
                  {/* 面包屑式的来源提示：同名叶子分布在不同模块时，只看叶子名分不清去哪 */}
                  {!e.isSection && (
                    <span className="truncate txt-caption text-muted-foreground">
                      {tNav(e.section)}{e.group ? ` · ${tNav(e.group)}` : ""}
                    </span>
                  )}
                  {i === cursor && <CornerDownLeft className="ms-auto size-3.5 shrink-0 opacity-60" />}
                </button>
              ))}
            </div>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
