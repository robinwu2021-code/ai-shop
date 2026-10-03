"use client";

import * as React from "react";
import { cn } from "@/lib/utils";
import { useT } from "@/lib/i18n";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";

/**
 * 帮助说明 —— **一个小触发器 + 浮层**，正文不进主界面。
 *
 * <h2>为什么不是常驻的 Notice</h2>
 *
 * 运营端有一百多处 `<Notice>`，其中大半说的是「这一页是什么、这一栏怎么算」。
 * 对第一次来的人有用，而对**每天开同一页的操作员是纯噪声**：他要的那一行数据
 * 被三行解释推到了下面，一页上摞两三条时列表要滚一屏才开始。
 *
 * <h2>为什么不是内联展开（details）</h2>
 *
 * 先做过一版原生 `<details>`：默认收起，点开就地展开。收起时确实干净，
 * 但**点开会把下面的内容整体推下去** —— 在表格里尤其糟：展开一行说明，
 * 整张表跳一下，而且那一行比别的行高，字段就不再横向对齐了。
 * 浮层不占布局流，点开点关列表纹丝不动，这也是「帮助」该有的分量。
 *
 * <h2>什么该用它、什么不该</h2>
 *
 * 判据不是「文字长短」，是**它说的是「现在有事」还是「这是什么」**：
 *
 * <ul>
 *   <li>`warning` / `danger` 的 Notice **不要换成它** —— 那些是当前状态的警示，
 *       收进浮层等于把警告藏了；</li>
 *   <li>条件渲染的 muted 也不要（「这个小区还没设区划」「先选一条再操作」）——
 *       它出现本身就是信息；</li>
 *   <li>无条件常驻的说明性文字用它。</li>
 * </ul>
 *
 * <h2>用法</h2>
 *
 * 标题默认取全站词条 `common.helpNote`（「说明」）：
 *
 * ```tsx
 * <HelpNote>这一栏按下单时间算，不是支付时间。</HelpNote>
 * ```
 *
 * 一页上有**两个以上**时给各自的 `title` —— 都叫「说明」的话，没人知道该点哪个。
 * 表格行里用 `inline`：不要外层的块级间距，触发器直接跟在那一格的文字后面。
 */
export function HelpNote({
  title, children, className, defaultOpen = false, inline = false,
}: {
  /**
   * 触发器上显示的那一行。**要能独立成句** —— 它是点开之前唯一的线索。
   * 不给则用全站的「说明」；同一屏有多个时务必各给一个。
   */
  title?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
  /** 极少用：只有「这一页第一次来的人一定要读」才给 true */
  defaultOpen?: boolean;
  /**
   * 行内模式：只渲染触发器本身，不带块级包装与外边距。
   * 表格单元格、标题旁边用它；页面顶部的整段说明用默认（块级）。
   */
  inline?: boolean;
}) {
  const t = useT();
  const label = title ?? t("common.helpNote");
  const trigger = (
    <PopoverTrigger
      className={cn(
        "inline-flex w-fit max-w-full cursor-pointer items-center gap-1 txt-body",
        "text-muted-foreground focus-ring rounded-chip",
        "hover:text-foreground data-[state=open]:text-foreground",
        inline && className,
      )}
    >
      {/* 问号自己画：一个圆圈里一个 ?，比 emoji 稳（不吃系统字体） */}
      <svg aria-hidden viewBox="0 0 16 16" className="size-3.5 shrink-0">
        <circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" strokeWidth="1.3" />
        <path d="M6.1 6.1a1.9 1.9 0 1 1 2.4 1.9c-.4.15-.5.4-.5.8v.4"
              fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
        <circle cx="8" cy="11.6" r=".85" fill="currentColor" />
      </svg>
      <span className="min-w-0 truncate">{label}</span>
    </PopoverTrigger>
  );

  return (
    <Popover defaultOpen={defaultOpen}>
      {inline ? trigger : <div className={cn("mb-3", className)}>{trigger}</div>}
      {/*
        * 宽度给到 22rem 封顶：再宽一行就超过一次注视能扫完的长度，
        * 而这些说明都是两三句话，窄一点反而好读。
        */}
      <PopoverContent className="max-w-[22rem] txt-body leading-relaxed text-muted-foreground">
        {children}
      </PopoverContent>
    </Popover>
  );
}
