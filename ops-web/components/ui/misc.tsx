"use client";

import * as React from "react";
import { cn } from "@/lib/utils";
import { useI18n } from "@/lib/i18n";
import { Card } from "./card";

/**
 * 统计卡（工作台 KPI）。
 *
 * 走 `Card` 原语而不是自己拼 `rounded-card bg-card shadow-…`：第四轮给 Card 加了
 * hairline 描边，自己拼的这份没跟上 —— 同一屏里 KPI 卡没边、下面的表格卡有边。
 * 这正是"绕开原语"的典型代价：改一处的时候漏掉另一处，而且没人会发现。
 */
export function StatCard({
  label, value, sub, tone, loading,
}: {
  label: string;
  value: React.ReactNode;
  sub?: string;
  tone?: "up" | "down";
  /** 数据未回来。此前调用方只能自己往 value 里塞一个 Skeleton，各页塞得不一样高 */
  loading?: boolean;
}) {
  return (
    <Card data-surface="stat" className="p-5">
      <div data-slot="label" className="txt-body text-muted-foreground">{label}</div>
      <div className="mt-2 txt-display tabular-nums">
        {loading ? <Skeleton className="w-24" style={{ height: "30px" }} /> : value}
      </div>
      {sub && !loading && (
        // 语义色的 ink 档：实心 --success 压在白卡上小字只有约 3:1，过不了 AA
        <div className={cn("mt-1 txt-caption", tone === "down" ? "text-[var(--destructive-ink)]" : "text-[var(--success-ink)]")}>
          {sub}
        </div>
      )}
    </Card>
  );
}

/**
 * KPI 卡片行。5 个页面在重复同一串栅格类名，且窄屏断点各写各的。
 * 两列起步、大屏三列：再多一列在 1280px 下每张卡就装不下一个金额了。
 */
export function StatRow({ children, className }: { children: React.ReactNode; className?: string }) {
  return <div className={cn("mb-4 grid grid-cols-2 gap-4 lg:grid-cols-3", className)}>{children}</div>;
}

/**
 * 空态。`action` 是**出路**：空列表最常见的两种原因是「筛过头了」和「还没建过」，
 * 两种都有对应动作。只写一句「暂无数据」等于让人自己猜下一步该干嘛。
 */
export function EmptyState({
  title, desc, action,
}: { title: string; desc?: string; action?: React.ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 rounded-card bg-muted/50 py-16 text-center">
      <div className="text-sm font-semibold">{title}</div>
      {desc && <div className="txt-caption text-muted-foreground">{desc}</div>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

export function Skeleton({ className, style }: { className?: string; style?: React.CSSProperties }) {
  return <div className={cn("animate-pulse rounded-field bg-muted", className)} style={style} />;
}

// 紧凑页头：标题与操作同一行；说明作为标题后的小字（面包屑已在顶栏给出位置，故从简）。
export function PageTitle({ title, desc, action }: { title: string; desc?: string; action?: React.ReactNode }) {
  return (
    <div className="mb-3 flex items-center justify-between gap-4">
      <div className="flex items-baseline gap-2 min-w-0">
        <h1 className="txt-title shrink-0">{title}</h1>
        {desc && <p className="truncate txt-caption text-muted-foreground">{desc}</p>}
      </div>
      {action && <div className="shrink-0">{action}</div>}
    </div>
  );
}

// 简易分页。
/** 可选的每页条数。50 封顶：再多一屏也扫不完，只是把接口拖慢。 */
/**
 * 业务单号单元格（商品编码、订单号、申请单号…）。
 *
 * <p><b>它是表里最不重要、却最长的一列</b>：21 位的 `G20260817214022000026`
 * 会把标题、商家、类目一起挤扁，而运营真正要读的是后面那些。
 * 这里把它压成等宽小字 + 定宽截断，完整值放 `title` —— 需要复制时仍拿得到。
 *
 * <p>等宽而不是普通字体：单号是逐位比对的东西，比例字体下 `0/O`、`1/l` 对不齐。
 */
export function IdCell({ value, width = "9rem" }: { value: string; width?: string }) {
  return (
    <span
      title={value}
      style={{ maxWidth: width }}
      className="block truncate font-mono txt-caption text-muted-foreground"
    >
      {value}
    </span>
  );
}

export const PAGE_SIZES = [10, 20, 50] as const;

/** 超过这么多页才出跳页输入框。少于它时输入框只是噪音。 */
const JUMP_THRESHOLD_PAGES = 5;

/**
 * 跳页输入：直接键入页码 + 回车。
 *
 * 越界不静默纠正成边界值 —— 那样用户以为跳到了第 99 页，其实停在第 12 页。
 * 输入非法时按回车**不动**，并把框里的值退回当前页，让人看得出"没跳成"。
 */
function PageJump({ page, pages, onPage }: { page: number; pages: number; onPage: (p: number) => void }) {
  const { t } = useI18n();
  const [draft, setDraft] = React.useState(String(page));
  React.useEffect(() => { setDraft(String(page)); }, [page]);

  const commit = () => {
    const n = Number(draft);
    if (!Number.isInteger(n) || n < 1 || n > pages) { setDraft(String(page)); return; }
    if (n !== page) onPage(n);
  };

  return (
    <span className="flex items-center gap-1 tabular-nums">
      <input
        aria-label={t("common.jumpToPage")}
        inputMode="numeric"
        value={draft}
        onChange={(e) => setDraft(e.target.value.replace(/[^0-9]/g, ""))}
        onKeyDown={(e) => { if (e.key === "Enter") commit(); }}
        onBlur={commit}
        className="w-10 rounded-field bg-secondary px-1.5 py-1 text-center tabular-nums focus-ring"
      />
      <span>/ {pages}</span>
    </span>
  );
}

export function Pagination({
  page, size, total, onPage, onSize,
}: {
  page: number;
  size: number;
  total: number;
  onPage: (p: number) => void;
  /**
   * 传了才出「每页 N 条」选择器（同时要把页码复位到 1，否则可能停在不存在的页）。
   * 对账、导出前核数这类场景要一屏看完，翻 5 页去数 100 条是纯粹的浪费。
   */
  onSize?: (n: number) => void;
}) {
  const { t } = useI18n();
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <div className="mt-4 flex items-center justify-between txt-body text-muted-foreground">
      <div className="flex items-center gap-3">
        <span>{t("common.totalItems", { n: total })}</span>
        {onSize && (
          <select
            aria-label={t("common.pageSize")}
            className="rounded-field bg-secondary px-2 py-1 transition-colors hover:bg-accent focus-ring"
            value={size}
            onChange={(e) => onSize(Number(e.target.value))}
          >
            {PAGE_SIZES.map((n) => (
              <option key={n} value={n}>{t("common.perPage", { n })}</option>
            ))}
          </select>
        )}
      </div>
      <div className="flex items-center gap-2">
        <button
          className="rounded-field bg-secondary px-2.5 py-1 transition-colors hover:bg-accent focus-ring disabled:opacity-40 disabled:hover:bg-secondary"
          disabled={page <= 1}
          onClick={() => onPage(page - 1)}
        >{t("common.prevPage")}</button>
        {/* 页码超过 5 页才出跳页输入：只有两三页时它是纯噪音。
            订单/流水这类动辄几十页的场景，"下一页点 12 次"不是可用的操作。 */}
        {pages > JUMP_THRESHOLD_PAGES ? (
          <PageJump page={page} pages={pages} onPage={onPage} />
        ) : (
          <span className="tabular-nums">{t("common.pageOf", { p: page, total: pages })}</span>
        )}
        <button
          className="rounded-field bg-secondary px-2.5 py-1 transition-colors hover:bg-accent focus-ring disabled:opacity-40 disabled:hover:bg-secondary"
          disabled={page >= pages}
          onClick={() => onPage(page + 1)}
        >{t("common.nextPage")}</button>
      </div>
    </div>
  );
}
