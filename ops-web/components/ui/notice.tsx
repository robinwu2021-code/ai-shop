import * as React from "react";
import { cn } from "@/lib/utils";

/**
 * 页内提示条（原语）：列表/表单上方的一行小字，说明「当前视图为什么少了点什么」。
 * 只管样式，不管文案语义 —— 权限降级请用业务件 `<ReadOnlyNotice>`（components/read-only-notice）。
 *
 * `tone` 是补上的一档：此前只有灰底一种，页面遇到"这条是警告"就自己拼一个彩色 div，
 * 于是同一种提示在不同页面长得不一样。四档语义色都走 `--*-tint` 底 + `--*-ink` 字
 * （不是直接拿实心语义色当文字色 —— 那样小字只有约 2.8:1，过不了 AA）。
 */
export function Notice({
  children, tone = "muted", className,
}: {
  children: React.ReactNode;
  /** muted=中性说明（默认）· info=补充信息 · warning=需要注意 · danger=会造成损失 */
  tone?: "muted" | "info" | "warning" | "danger";
  className?: string;
}) {
  return (
    <div
      data-surface="notice"
      className={cn(
        "mb-4 rounded-card px-3.5 py-2 txt-body",
        {
          muted: "bg-muted text-muted-foreground",
          info: "bg-info-tint text-info-ink",
          warning: "bg-warning-tint text-warning-ink",
          danger: "bg-destructive-tint text-destructive-ink",
        }[tone],
        className,
      )}
    >
      {children}
    </div>
  );
}
