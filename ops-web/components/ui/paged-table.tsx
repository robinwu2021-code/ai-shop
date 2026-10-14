"use client";

// 分页列表：`DataTable` + `Pagination` 绑在同一份取数结果上。
//
// **为什么要有这一件**（不是为了少打字）：这两件此前是各页手拼的，35 处紧邻成对，
// 而「拼」这个动作本身就是缺口的来源。一次盘点（122 个 `DataTable` 调用点）里：
//
//   · 33 处漏了 `loading` / `error` / `onRetry` / `empty` 中的至少一项，且**全部**由 useQuery 支撑；
//     其中 15 处四项全缺 —— 接口 500 时表格渲染成「没有符合条件的数据」，
//     运营会去改筛选条件而不是报障。这正是 TDD-ops-组件库优化 §1.A 修过的缺陷：
//     **组件加了 `error` 分支，调用点没接**，于是修复只落在库里，没落到界面上。
//   · `Pagination` 漏 `onSize` 要靠 `lib/design-tokens.test.ts` 的一条正则去追。
//
// 所以这里把接线收进组件：`query` 一交，rows / loading / error / onRetry / total
// 五项没有地方可以漏；`onSize` 从「可选」变成**必填**，正则守卫要追的那件事
// 交给类型检查 —— 编译期拦得住的东西不该靠正则去扫。
//
// 判据：**列表是分页的就用它**。不分页的配置表继续直接用 `DataTable`。

import * as React from "react";
import { DataTable, type DataTableProps } from "./data-table";
import { Pagination } from "./misc";

/**
 * 取数结果的**结构化**契约 —— 刻意不 `import type { UseQueryResult }`：
 * `ui/` 不认任何取数库，换掉 TanStack Query 时这一层不该跟着改。
 * TanStack 的 `UseQueryResult<Page<T>>` 结构上满足它，直接传即可。
 */
export interface PagedQuery<T> {
  data?: { records: T[]; total: number };
  /** v5 语义：还没拿到过数据。`enabled:false` 的查询会一直为 true，那种情形自己传 `loading` */
  isPending?: boolean;
  isLoading?: boolean;
  error?: unknown;
  refetch?: () => unknown;
}

export type PagedTableProps<T> = Omit<
  DataTableProps<T>,
  "rows" | "loading" | "error" | "onRetry"
> & {
  query: PagedQuery<T>;
  page: number;
  size: number;
  onPage: (p: number) => void;
  /**
   * **必填**，与 `Pagination` 的可选形成对比。
   * 这一件存在的理由之一就是把「每页条数」从「记得写就有」变成「不写编译不过」——
   * 对账、导出前核数要一屏看完，翻五页去数一百条不是可用的操作。
   */
  onSize: (n: number) => void;
  /**
   * 覆盖加载态。只在两种情况下需要：查询是 `enabled:false` 起步的（`isPending`
   * 会一直为 true，界面永远停在骨架屏），或一屏由多个查询拼成。
   */
  loading?: boolean;
};

export function PagedTable<T>({
  query, page, size, onPage, onSize, loading, ...rest
}: PagedTableProps<T>) {
  return (
    <>
      <DataTable<T>
        {...rest}
        rows={query.data?.records}
        loading={loading ?? query.isPending ?? query.isLoading}
        // `error: TError | null` → null 转 undefined：DataTable 的 `if (error)`
        // 判得对，但让 null 流进错误块的入参只会给下一个人添一次疑惑。
        error={query.error ?? undefined}
        onRetry={query.refetch ? () => query.refetch?.() : undefined}
      />
      <Pagination
        page={page}
        size={size}
        onSize={onSize}
        total={query.data?.total ?? 0}
        onPage={onPage}
      />
    </>
  );
}
