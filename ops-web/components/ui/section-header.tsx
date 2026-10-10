"use client";

// 页内小节标题：标题 + 右侧概要（通常是「n / total 已配置」这类计数）+ 可选说明行。
//
// **为什么要收一件**：页头（`TabHeader`）与配置卡（`ConfigCard`）都有唯一实现，
// 唯独「一屏里再分一小节」这层没有，于是 13 处 `<h3>` 长出了五种写法：
//
//   · `text-[15px] font-semibold` + `text-[12px] tabular-nums …`   ×7（绕开字阶，手写像素）
//   · `txt-heading` 裸标题                                          ×2
//   · `txt-h3`                                                     ×2 ← **这个类不存在**，
//        globals.css 里只有 display/title/heading/body/strong/label/caption 七档。
//        它不报错、不告警，只是标题按正文 14px/400 渲染 —— 「看得见」与「看对了」
//        差的就是这一档，截图上不盯着比根本发现不了。
//   · `txt-label text-muted-foreground`                            ×1（12px 灰字，
//        比它下面的正文还弱，读起来不像标题像脚注）
//
// 五种写法的意图是同一个。收成一件之后，字阶由这里定，调用点只说「标题是什么」。

import * as React from "react";
import { cn } from "@/lib/utils";

export function SectionHeader({
  title,
  summary,
  desc,
  className,
}: {
  title: React.ReactNode;
  /**
   * 右侧概要，与标题同一基线（`items-baseline`，不是 `items-center`——
   * 15px 与 12px 居中对齐时视觉上会差半个字）。
   * 带 `tabular-nums`：这里放的多半是计数，数字跳动时宽度不该跟着抖。
   */
  summary?: React.ReactNode;
  /** 说明行，落在标题下方。一句话讲清这小节管什么，不要放风险提示（那是 `Notice`） */
  desc?: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn(desc ? "mb-3" : "mb-2", className)}>
      <div className="flex items-baseline justify-between gap-3">
        <h3 className="txt-heading">{title}</h3>
        {summary != null && (
          <span className="txt-caption tabular-nums text-muted-foreground">{summary}</span>
        )}
      </div>
      {desc != null && <p className="mt-1 txt-caption text-muted-foreground">{desc}</p>}
    </div>
  );
}
