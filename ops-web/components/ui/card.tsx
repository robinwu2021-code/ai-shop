import * as React from "react";
import { cn } from "@/lib/utils";

/**
 * 卡片。形态取自 C 端 pb-card：圆角 + 阴影。
 *
 * **加了一条 hairline 描边**（`--card-border`），这是与 C 端刻意的分歧：
 * C 端全局 border-width 是 0，卡片浮在彩色/图片背景上，靠阴影就分得开；
 * 运营台是白卡压浅灰画布、一屏好几张卡紧挨着，只靠 elevation-1 那点阴影，
 * 在低亮度或低对比度屏幕上边界基本消失（这次返工的直接起因）。
 * tone 卡片不描边：它本来就有颜色底，再加线就吵。
 *
 * `tone` 是 C 端有而我们此前缺的一档：语义 tint 底 + **去掉阴影**。
 * 用在需要"这块要注意"的地方（提示卡、告警摘要、待办聚合），
 * 比给白卡加一圈彩色描边更贴近 C 端语言 —— C 端全局 border-width 是 0。
 */
export function Card({
  className, tone, ...props
}: React.HTMLAttributes<HTMLDivElement> & {
  tone?: "primary" | "success" | "warning" | "danger" | "info";
}) {
  return (
    <div
      data-surface="card"
      className={cn(
        "rounded-card",
        // tone 卡片的**文字色要跟着底走**：底换成了 tint、文字还留在 --card-foreground，
        // 暗色下 tint 底会变深，读数只是"勉强够"——那是巧合不是设计。
        tone
          ? {
              primary: "bg-[color-mix(in_oklch,var(--primary)_10%,transparent)] text-card-foreground",
              success: "bg-success-tint text-success-ink",
              warning: "bg-warning-tint text-warning-ink",
              danger: "bg-destructive-tint text-destructive-ink",
              info: "bg-info-tint text-info-ink",
            }[tone]
          : "border border-[var(--card-border)] bg-card text-card-foreground shadow-[var(--card-shadow)]",
        className,
      )}
      {...props}
    />
  );
}
export function CardHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("flex flex-col gap-1 p-5", className)} {...props} />;
}
export function CardTitle({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("font-medium leading-none tracking-tight", className)} {...props} />;
}
export function CardDescription({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("txt-body text-muted-foreground", className)} {...props} />;
}
export function CardContent({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("p-5 pt-0", className)} {...props} />;
}
