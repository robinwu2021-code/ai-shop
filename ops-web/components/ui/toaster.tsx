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
      /*
       * 页面**上方居中**。此前在右下角 —— 那是操作发生的反侧：
       * 运营点的按钮多在表格行内与页面上半部，反馈出现在视线之外，
       * 于是「保存成功」和「保存失败」都一样容易被错过，
       * 而后者被错过的代价是他以为已经改好了。
       *
       * top-16：顶栏是 h-14（56px）的 sticky，压在它上面会遮住导航与搜索；
       * 落在它下方 8px 处，既在视线里又不挡任何东西。
       * 水平居中不用 insetInlineEnd —— 居中在 RTL 下天然对称，不必分方向。
       */
      className="pointer-events-none fixed top-16 left-1/2 -translate-x-1/2 z-[var(--z-toast)] flex flex-col items-center gap-2"
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
