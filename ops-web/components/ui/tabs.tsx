"use client";

import * as React from "react";
import { segmentedItemClass, segmentedTrackClass } from "@/components/ui/segmented";

// 分段控件（segmented）：浅色块容器内，激活 tab = 实心白 pill + 极轻阴影。取代下划线分割。
// 形状规格（灰槽/全圆/字重）与 `ui/tab-header.tsx` 里的同类分段控件共用 `ui/segmented.ts`。
export function Tabs({
  tabs, value, onChange,
}: {
  /** `disabled` 项：**灰显但仍可见**。直接不渲染的话，用户不知道还有这个维度存在， */
  /*  而看得见点不动至少能问一句"为什么点不了"（配 `title` 说明原因）。 */
  tabs: { key: string; label: string; disabled?: boolean; title?: string }[];
  value: string;
  onChange: (k: string) => void;
}) {
  return (
    <div className={segmentedTrackClass("mb-5 max-w-full flex-wrap")}>
      {tabs.map((t) => (
        <button
          key={t.key}
          type="button"
          disabled={t.disabled}
          title={t.title}
          onClick={() => onChange(t.key)}
          className={segmentedItemClass(
            value === t.key,
            "px-3.5 py-1.5 text-sm disabled:cursor-not-allowed disabled:opacity-45",
          )}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}
