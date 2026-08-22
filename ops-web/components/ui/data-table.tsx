"use client";

import * as React from "react";
import { AlertTriangle, ChevronRight, ChevronUp, ChevronDown, ChevronsUpDown } from "lucide-react";
import { Card } from "./card";
import { Checkbox } from "./checkbox";
import { Table, THead, TBody, TR, TH, TD } from "./table";
import { Skeleton, EmptyState } from "./misc";
import { Button } from "./button";
import { cn } from "@/lib/utils";
import { useI18n } from "@/lib/i18n";

export interface Column<T> {
  /**
   * 表头。多数时候是一个字符串；允许 ReactNode 是为了**全选复选框**这类
   * 「表头本身是个控件」的列 —— 否则调用方会在 cell 里手拼一列假表头。
   * ⚠️ 放控件的列不要再设 sortKey：点表头排序与点控件会抢同一个点击。
   */
  header: React.ReactNode;
  cell: (row: T) => React.ReactNode;
  className?: string;
  /** 设了才可排序：点表头切 asc/desc（受控，实际排序由调用方做） */
  sortKey?: string;
  /**
   * 数字列：右对齐 + 等宽数字（`tabular-nums`）。
   * 金额、数量、比率都该设它 —— 此前各页手写 `className="text-end tabular-nums"`，
   * 于是有的页写了、有的漏了，同一张表里金额有的对齐有的不对齐。
   * 表头跟着一起右对齐，否则列名与数字各靠一边，扫描时视线要来回跳。
   */
  numeric?: boolean;
  /** 覆盖对齐（numeric 已隐含 end，只在少数「文本列要右对齐」时用） */
  align?: "start" | "end" | "center";
  /** 列宽，直接写 CSS 值（如 "12rem"）。不设则由内容撑开 */
  width?: string;
}

export type SortDir = "asc" | "desc";

/**
 * 行选择 checkbox（含半选态）。
 *
 * 原为就地实现的原生 `<input type=checkbox>`（靠 ref 副作用设 `indeterminate`），
 * 已上移为原语 `ui/checkbox.tsx`；这里只留「三态 boolean → CheckedState」的转接
 * 与 `stopPropagation`（行整体可点，勾选不该顺带打开详情）。
 */
function RowCheckbox({
  checked, indeterminate, onChange, label,
}: {
  checked: boolean;
  indeterminate?: boolean;
  onChange: (v: boolean) => void;
  label: string;
}) {
  return (
    <span onClick={(e) => e.stopPropagation()} className="inline-flex">
      <Checkbox
        aria-label={label}
        checked={checked ? true : indeterminate ? "indeterminate" : false}
        // 半选被点击时 Radix 给 true（半选 → 全选），与全选列的直觉一致。
        onChange={(v) => onChange(v === true)}
      />
    </span>
  );
}

// 通用列表表格：列配置 + 行数据 + 加载/空态。让新列表页保持一致、精简。
// 可选能力（不传即与旧行为完全一致）：行选择 / 行展开 / 受控排序。
export function DataTable<T>({
  columns, rows, loading, error, onRetry, rowKey, empty, emptyAction,
  selectable, selectedKeys, onSelectedChange,
  expandable,
  sortKey, sortDir, onSortChange,
  rowClassName, rowProps,
}: {
  columns: Column<T>[];
  rows: T[] | undefined;
  loading?: boolean;
  /**
   * 取数失败时传进来（直接给 `query.error`）。
   * **必须与空态分开**：出错时渲染成「没有符合条件的数据」，运营会去改筛选条件，
   * 而真正该做的是报障 —— 把故障说成没数据是这个组件此前最严重的一个缺陷。
   */
  error?: unknown;
  /** 有它才出「重试」按钮（通常传 `() => query.refetch()`） */
  onRetry?: () => void;
  rowKey: (row: T) => string;
  empty?: string;
  /**
   * 空态里的出路按钮。列表空掉通常只有两种原因：**筛过头了**（给「清空筛选」）
   * 或**还没建过**（给「新增」）。没有出路的空态只会让人退出去重来一遍。
   */
  emptyAction?: React.ReactNode;
  /** 显示行选择 checkbox 列（最左） */
  selectable?: boolean;
  selectedKeys?: string[];
  onSelectedChange?: (keys: string[]) => void;
  /** 返回展开内容则该行可展开（展开箭头列紧随选择列） */
  expandable?: (row: T) => React.ReactNode;
  sortKey?: string;
  sortDir?: SortDir;
  onSortChange?: (key: string, dir: SortDir) => void;
  /**
   * 行级样式钩子：整行强调/弱化（如 预约即将超时高亮、白名单过期灰显、套餐下架灰显）。
   * `Column.className` 只能到列级，行级状态表达不了 —— B0 首版遗漏，2026-07-29 补。
   */
  rowClassName?: (row: T) => string | undefined;
  /**
   * 行级原生属性（拖拽、data-*、右键菜单）。给的是 `<tr>` 本身的 props ——
   * 拖放的**放**必须落在整行上：只把 handle 做成放置目标的话，行有 48px 高
   * 而 handle 只有 16px，八成的下落点会掉进行的空白处、什么都不发生。
   *
   * ⚠️ 与 `rowClassName` 各管各的：这里再给 className 会**覆盖**它，所以合并在下面做。
   */
  rowProps?: (row: T) => React.HTMLAttributes<HTMLTableRowElement>;
}) {
  const { t } = useI18n();
  const emptyText = empty ?? t("common.empty");
  const [expanded, setExpanded] = React.useState<string[]>([]);

  const selected = React.useMemo(() => new Set(selectedKeys ?? []), [selectedKeys]);
  const allKeys = React.useMemo(() => (rows ?? []).map(rowKey), [rows, rowKey]);
  const selectedOnPage = allKeys.filter((k) => selected.has(k)).length;
  const allChecked = allKeys.length > 0 && selectedOnPage === allKeys.length;
  const someChecked = selectedOnPage > 0 && !allChecked;

  const toggleAll = (v: boolean) => {
    if (!onSelectedChange) return;
    const rest = (selectedKeys ?? []).filter((k) => !allKeys.includes(k));
    onSelectedChange(v ? [...rest, ...allKeys] : rest);
  };
  const toggleOne = (k: string, v: boolean) => {
    if (!onSelectedChange) return;
    const cur = selectedKeys ?? [];
    onSelectedChange(v ? (cur.includes(k) ? cur : [...cur, k]) : cur.filter((x) => x !== k));
  };
  const toggleExpand = (k: string) =>
    setExpanded((p) => (p.includes(k) ? p.filter((x) => x !== k) : [...p, k]));

  const leadCols = (selectable ? 1 : 0) + (expandable ? 1 : 0);
  const totalCols = columns.length + leadCols;

  /** 列语义 → className（表头与行体共用同一套，保证两者永远同向对齐）。 */
  const colClass = (c: Column<T>) =>
    cn(
      (c.align ?? (c.numeric ? "end" : undefined)) === "end" && "text-end",
      (c.align ?? (c.numeric ? "end" : undefined)) === "center" && "text-center",
      c.numeric && "tabular-nums",
      c.className,
    );
  const colStyle = (c: Column<T>) => (c.width ? { width: c.width } : undefined);

  const headerCell = (c: Column<T>, i: number) => {
    const sortable = !!c.sortKey && !!onSortChange;
    if (!sortable) return <TH key={i} className={colClass(c)} style={colStyle(c)}>{c.header}</TH>;
    const active = sortKey === c.sortKey;
    const Icon = active ? (sortDir === "desc" ? ChevronDown : ChevronUp) : ChevronsUpDown;
    return (
      <TH key={i} className={colClass(c)} style={colStyle(c)}>
        <button
          type="button"
          className={cn(
            "inline-flex items-center gap-1 rounded-field transition-colors hover:text-foreground",
            "focus-ring",
            active && "text-foreground",
          )}
          aria-sort={active ? (sortDir === "desc" ? "descending" : "ascending") : "none"}
          title={t("table.sortBy")}
          onClick={() => onSortChange!(c.sortKey!, active && sortDir === "asc" ? "desc" : "asc")}
        >
          {c.header}
          <Icon className={cn("size-3.5", !active && "opacity-50")} />
        </button>
      </TH>
    );
  };

  return (
    <Card className="overflow-hidden">
      {error ? (
        <div className="flex flex-col items-center justify-center gap-3 py-14 text-center">
          <div className="flex size-11 items-center justify-center rounded-sheet bg-destructive-tint text-[var(--destructive-ink)]">
            <AlertTriangle className="size-5" />
          </div>
          <div>
            <div className="txt-heading">{t("table.errorTitle")}</div>
            {/* 把后端/网络的原话给出来：运营报障时能直接截图，不用我们再问一遍 */}
            <p className="mt-1 max-w-md txt-body text-muted-foreground">
              {error instanceof Error ? error.message : t("error.unknown")}
            </p>
          </div>
          {onRetry && <Button size="sm" variant="outline" onClick={onRetry}>{t("table.retry")}</Button>}
        </div>
      ) : loading && !rows ? (
        // 骨架要长成**这张表**的样子：表头照常渲染，占位格按各列宽度铺。
        // 原先是 6 条等宽灰条，加载完成时列宽一变整张表会跳一下，
        // 而且看不出这页在等什么 —— 现在表头先到，等的是哪几列一目了然。
        <Table>
          <THead>
            <TR>
              {selectable && <TH className="w-10" />}
              {expandable && <TH className="w-10" />}
              {columns.map(headerCell)}
            </TR>
          </THead>
          <TBody>
            {Array.from({ length: 6 }).map((_, r) => (
              <TR key={r}>
                {selectable && <TD><Skeleton className="size-4" /></TD>}
                {expandable && <TD><Skeleton className="size-4" /></TD>}
                {columns.map((c, i) => (
                  <TD key={i} className={colClass(c)} style={colStyle(c)}>
                    {/* 宽度错落一点，免得看着像一块死板的灰砖 */}
                    <Skeleton className="h-4" style={{ width: `${[80, 55, 70, 45, 62][(r + i) % 5]}%` }} />
                  </TD>
                ))}
              </TR>
            ))}
          </TBody>
        </Table>
      ) : !rows || rows.length === 0 ? (
        <div className="p-4"><EmptyState title={emptyText} action={emptyAction} /></div>
      ) : (
        <Table>
          <THead>
            <TR>
              {selectable && (
                <TH className="w-10">
                  <RowCheckbox
                    checked={allChecked}
                    indeterminate={someChecked}
                    onChange={toggleAll}
                    label={t("table.selectAll")}
                  />
                </TH>
              )}
              {expandable && <TH className="w-10" />}
              {columns.map(headerCell)}
            </TR>
          </THead>
          <TBody>
            {rows.map((row) => {
              const k = rowKey(row);
              const content = expandable?.(row);
              const isOpen = expanded.includes(k);
              return (
                <React.Fragment key={k}>
                  <TR
                    {...rowProps?.(row)}
                    className={cn(rowClassName?.(row), rowProps?.(row)?.className)}
                  >
                    {selectable && (
                      <TD className="h-[var(--row-h)] w-10">
                        <RowCheckbox
                          checked={selected.has(k)}
                          onChange={(v) => toggleOne(k, v)}
                          label={t("table.selectRow")}
                        />
                      </TD>
                    )}
                    {expandable && (
                      <TD className="h-[var(--row-h)] w-10">
                        {content ? (
                          <button
                            type="button"
                            className={cn(
                              "rounded-field p-1 text-muted-foreground transition-colors hover:bg-accent",
                              "focus-ring",
                            )}
                            aria-expanded={isOpen}
                            aria-label={isOpen ? t("table.collapse") : t("table.expand")}
                            onClick={() => toggleExpand(k)}
                          >
                            <ChevronRight
                              className={cn("size-4 transition-transform rtl:-scale-x-100", isOpen && "rotate-90 rtl:rotate-90")}
                            />
                          </button>
                        ) : null}
                      </TD>
                    )}
                    {columns.map((c, i) => <TD key={i} className={colClass(c)} style={colStyle(c)}>{c.cell(row)}</TD>)}
                  </TR>
                  {expandable && isOpen && content && (
                    <TR className="hover:bg-muted">
                      <TD colSpan={totalCols} className="bg-muted p-4">{content}</TD>
                    </TR>
                  )}
                </React.Fragment>
              );
            })}
          </TBody>
        </Table>
      )}
    </Card>
  );
}
