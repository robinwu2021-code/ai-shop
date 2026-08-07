// 审计时间线（组合层）：金额/分值类操作的留痕列表。
//
// 形态取自实测的两处调用方（订单干预历史 / 信用分调分历史），四段结构是它们的并集：
//   [徽标] 单号 · 时间 · 操作人      ← badge + meta
//   前值 → 后值（可带金额）           ← change（可选）
//   原因/结论                        ← text（可选）
// 调用方只给数据，不给 DOM —— 时间线的缩进、竖线、行距在这里定死一份。
import * as React from "react";
import { Badge, type BadgeTone } from "./badge";
import { Skeleton, EmptyState } from "./misc";
import { useI18n } from "@/lib/i18n";

export interface TimelineItem {
  /** 列表 key，通常是留痕单号 */
  key: string;
  /** 左侧徽标：动作名（干预历史）或分值变化（调分历史） */
  badge?: { label: string; tone: BadgeTone };
  /** 单号 · 时间 · 操作人，小字弱化 */
  meta: React.ReactNode;
  /** 变化描述：`前 → 后`。数字类请自行包一层 tabular-nums */
  change?: React.ReactNode;
  /** 原因 / 结论正文 */
  text?: React.ReactNode;
}

export function Timeline({
  items, loading, empty, loadingText,
}: {
  items: TimelineItem[];
  loading?: boolean;
  /** 空列表文案（与 DataTable 的 empty 同义：要写清「为什么空」）*/
  empty: string;
  /** 不传走 i18n 的「加载中…」。此前默认值是中文硬编码，切 EN 时会漏出中文 */
  loadingText?: string;
}) {
  const { t } = useI18n();
  // 与 DataTable / Tree 对齐用骨架：同一个页面里「加载中」曾经有三种长相
  if (loading) {
    return (
      <div className="space-y-2.5" aria-label={loadingText ?? t("common.loading")} aria-busy>
        {Array.from({ length: 3 }).map((_, i) => (
          <Skeleton key={i} className="h-[var(--row-h)]" style={{ width: `${[86, 72, 80][i]}%` }} />
        ))}
      </div>
    );
  }
  if (!items.length) return <EmptyState title={empty} />;
  return (
    <ol className="space-y-2.5">
      {/* 逻辑属性：RTL 下竖线要挂到行首侧。写 border-l/pl 的话，阿语界面里线在左、内容从右起排 */}
      {items.map((it) => (
        <li key={it.key} className="border-s-2 border-[var(--border)] ps-3">
          <div className="flex flex-wrap items-center gap-1.5">
            {it.badge && <Badge tone={it.badge.tone}>{it.badge.label}</Badge>}
            <span className="txt-caption text-muted-foreground tabular-nums">{it.meta}</span>
          </div>
          {it.change != null && <div className="txt-caption text-muted-foreground">{it.change}</div>}
          {it.text != null && <div className="txt-body">{it.text}</div>}
        </li>
      ))}
    </ol>
  );
}
