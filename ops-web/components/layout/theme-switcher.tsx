"use client";

// 色块主题切换器：点开一格调色板，每个主题一个圆润色块，白底不变。
import { useEffect, useRef, useState } from "react";
import { Check, Moon, Palette, Sun } from "lucide-react";
import { THEMES, useTheme } from "@/lib/stores/theme";
import { segmentedItemClass, segmentedTrackClass } from "@/components/ui/segmented";
import { useI18n } from "@/lib/i18n";
import { cn } from "@/lib/utils";

export function ThemeSwitcher() {
  const { themeKey, dark, setDark, setTheme } = useTheme();
  const { t } = useI18n();
  const themeLabel = (k: string) => t(`theme.${k}`);
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
  }, [open]);

  const current = THEMES.find((t) => t.key === themeKey) ?? THEMES[0];

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label={t("theme.switch")}
        // 顶栏里的控件，与 LangSwitcher / Button 同高 —— 走密度 token 而不是字面值
        className="focus-ring flex h-[calc(var(--ctl-h)-4px)] items-center gap-1.5 rounded-field bg-secondary px-2.5 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
      >
        <Palette className="size-4" />
        <span className="size-3.5 rounded-chip ring-1 ring-border" style={{ background: current.color }} />
      </button>

      {open && (
        <div className="absolute right-0 top-10 z-[var(--z-popover)] w-60 rounded-card border border-[var(--card-border)] bg-card p-3 shadow-pop">
          <div className="mb-2 px-1 txt-label text-muted-foreground">{t("theme.label")}</div>
          <div className="grid grid-cols-5 gap-2">
            {THEMES.map((t) => {
              const active = t.key === themeKey;
              return (
                <button
                  key={t.key}
                  type="button"
                  onClick={() => setTheme(t.key)}
                  title={themeLabel(t.key)}
                  aria-label={themeLabel(t.key)}
                  aria-pressed={active}
                  className={cn("focus-ring", 
                    // ring 必须用 --border 而不是写死 black/5：黑白灰皮肤的色卡本身
                    // 就是近黑，在暗色弹层上会整块隐形（实测只看得到 2 个色卡）。
                    "flex aspect-square items-center justify-center rounded-card ring-1 ring-border transition-transform hover:scale-105",
                    active && "ring-2 ring-offset-2 ring-offset-card",
                  )}
                  style={{ background: t.color, boxShadow: active ? `0 0 0 2px ${t.color}` : undefined }}
                >
                  {active && <Check className="size-4 text-white" strokeWidth={3} />}
                </button>
              );
            })}
          </div>
          {/* 明暗与皮肤是两个维度，放同一个面板里：都属于"这个界面长什么样" */}
          <div className="mt-3 border-t border-[var(--border)] pt-3">
            <div className="mb-2 px-1 txt-label text-muted-foreground">{t("theme.mode")}</div>
            <div className={segmentedTrackClass("w-full")}>
              {[
                { key: false, label: t("theme.light"), Icon: Sun },
                { key: true, label: t("theme.dark"), Icon: Moon },
              ].map(({ key, label, Icon }) => (
                <button
                  key={String(key)}
                  type="button"
                  aria-pressed={dark === key}
                  onClick={() => setDark(key)}
                  className={"focus-ring " + (segmentedItemClass(dark === key, "flex flex-1 items-center justify-center gap-1.5 py-1 text-xs"))}
                >
                  <Icon className="size-3.5" /> {label}
                </button>
              ))}
            </div>
          </div>
          {/* 商务蓝不只是换主色：画布、中性阶一起换。不说明的话，用户会以为"怎么整个界面都变了" */}
          <p className="mt-2 px-1 txt-caption text-muted-foreground">
            {t("theme.fullSchemeHint")}
          </p>
        </div>
      )}
    </div>
  );
}
