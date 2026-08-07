"use client";

// 全局 toast 容器（挂在 Providers 里）。扁平色块风格，RTL 下自动靠对侧（用 inset-inline-end）。
import { CircleCheck, CircleX, Info, X } from "lucide-react";
import { useToasts } from "@/lib/notify";
import { useI18n } from "@/lib/i18n";
import { cn } from "@/lib/utils";

const ICON = { success: CircleCheck, error: CircleX, info: Info };
// 文字走 --*-ink（压在 tint 浅底上的那一档），不是实心语义色原值：
// 后者压在 16% 混色底上只有约 2.8:1，小字过不了 AA —— 与 Badge 修过的是同一个坑。
const TONE = {
  success: "bg-success-tint text-success-ink",
  error: "bg-destructive-tint text-destructive-ink",
  info: "bg-secondary text-foreground",
};

export function Toaster() {
  const { toasts, dismiss } = useToasts();
  const { t } = useI18n();
  if (!toasts.length) return null;
  return (
    // role=status + aria-live：读屏用户此前完全感知不到 toast —— 而「保存成功/失败」
    // 恰恰是最需要被播报的一类反馈。polite 而非 assertive：不打断正在念的内容。
    <div
      role="status"
      aria-live="polite"
      className="pointer-events-none fixed bottom-5 z-[var(--z-toast)] flex flex-col gap-2"
      style={{ insetInlineEnd: "1.25rem" }}
    >
      {toasts.map((item) => {
        const Icon = ICON[item.type];
        return (
          <div
            key={item.id}
            className={cn(
              "pointer-events-auto flex items-center gap-2.5 rounded-card px-4 py-2.5 txt-body shadow-[var(--card-shadow)]",
              TONE[item.type],
            )}
          >
            <Icon className="size-4 shrink-0" />
            <span className="max-w-[320px]">{item.message}</span>
            <button
              onClick={() => dismiss(item.id)}
              // ms-1：物理方向的 ml 在 RTL 下会把间距放到错误一侧
              className="ms-1 rounded-control opacity-60 hover:opacity-100 focus-ring"
              aria-label={t("common.cancel")}
            >
              <X className="size-3.5" />
            </button>
          </div>
        );
      })}
    </div>
  );
}
