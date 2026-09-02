"use client";

import * as React from "react";
import { cn } from "@/lib/utils";
import { useT } from "@/lib/i18n";

/**
 * 可收缩的说明块 —— **默认收起**，不明白的时候再点开。
 *
 * <h2>为什么要有它</h2>
 *
 * 运营端有 156 处 `<Notice>`，其中 87 处不写 tone（默认 muted）——
 * 那一批说的是「这一页是什么、为什么这么算」，对第一次来的人有用，
 * 而对**每天看同一页的操作员是纯噪声**：他要的那一行数据被三行解释推到了下面。
 * 一页上摞两三条时更明显，列表要滚一屏才开始。
 *
 * 判据不是「文字长短」，是**它说的是「现在有事」还是「这是什么」**：
 *
 * <ul>
 *   <li>`warning` / `danger` 的 Notice **不要换成它** —— 那些是当前状态的警示，
 *       收起来等于把警告藏了；</li>
 *   <li>`info` 看内容：说「刚刚发生了什么」的留着，说「这一栏怎么算」的可以收；</li>
 *   <li>默认 muted 的说明性文字换成它 —— 一行标题常驻，正文点开才有。</li>
 * </ul>
 *
 * <h2>为什么用原生 details 而不是自己写状态</h2>
 *
 * 键盘可达、屏幕阅读器认得、无需 JS、SSR 首屏就是收起的 —— 这四样自己写都要补，
 * 而补漏一样就是一个只有键盘用户会撞到的洞。代价是 marker 要自己关掉
 *（`[&::-webkit-details-marker]:hidden` + `list-none`），换来的是不引入
 * 任何新依赖，也不多一份「展开状态」要维护。
 *
 * <h2>用法</h2>
 *
 * 多数情况直接换掉 `<Notice>` 即可，标题默认取全站词条 `common.helpNote`（「说明」）：
 *
 * ```tsx
 * <HelpNote>这一栏按下单时间算，不是支付时间。</HelpNote>
 * ```
 *
 * 一页上有**两条以上**时给各自的 `title` —— 都叫「说明」的话，
 * 收起状态下没人知道该点哪一条。
 */
export function HelpNote({
  title, children, className, defaultOpen = false,
}: {
  /**
   * 收起时显示的那一行。**要能独立成句** —— 收起状态下它是唯一的线索。
   * 不给则用全站的「说明」；同一页有多条时务必各给一个。
   */
  title?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
  /** 极少用：只有「这一页第一次来的人一定要读」才给 true */
  defaultOpen?: boolean;
}) {
  const t = useT();
  return (
    <details
      data-surface="help"
      open={defaultOpen}
      className={cn("group mb-4 rounded-card bg-muted px-3.5 py-2", className)}
    >
      <summary
        className={cn(
          "flex w-fit cursor-pointer list-none items-center gap-1.5 txt-body",
          "text-muted-foreground focus-ring rounded-chip",
          "[&::-webkit-details-marker]:hidden",
        )}
      >
        {/* 三角自己画：原生 marker 在三家浏览器里三个样子，且没法只转不换色 */}
        <svg aria-hidden viewBox="0 0 12 12" className="size-3 shrink-0 transition-transform group-open:rotate-90">
          <path d="M4 2.5 8 6l-4 3.5z" fill="currentColor" />
        </svg>
        <span className="min-w-0 truncate">{title ?? t("common.helpNote")}</span>
      </summary>
      {/* pl 与上面的三角对齐；正文允许换行，它本来就是给人读的 */}
      <div className="mt-1.5 pl-[1.125rem] txt-body text-muted-foreground">
        {children}
      </div>
    </details>
  );
}
