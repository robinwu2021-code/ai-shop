"use client";

// 类目 × 规格（规格库 V195）—— 接 `/ops/category-specs`。
//
// 这一页回答第三个问题：**谁用哪些**。类目树回答「卖什么」，规格库回答「有哪些规格」，
// 三件事此前挤在「规格模板维护」一个页面里，而那张模板表已经退化成兜底。
//
// 这张表的第一职责是**把缺口顶到眼前**：一条规格都没配的类目标红并计数。
// 没配的后果不是「少个推荐」——商家侧不再有品类兜底（去掉那条回落是这一版的决定），
// 那一类的商家只能手输，而手输的选项没有规格编码，跨店聚合就此断掉。
import { useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill } from "@/lib/use-copy";
import type { CategorySpec } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { CategorySpecDrawer } from "./category-spec-drawer";
import { HelpNote } from "@/components/ui/help-note";
import type { ProductsCopy } from "./copy";

export function CategorySpecTab({ c, canEdit }: { c: ProductsCopy; canEdit: boolean }) {
  /*
   * **`?cat=` 深链直接打开那一行的抽屉。**从类目树点「未配」跳过来的人，
   * 目标是「配这一类」而不是「看这张表」—— 落在表上还要自己在 31 行里
   * 重新找一遍，那一跳就白跳了。
   *
   * 只自动开一次（openedFor 记住已经开过的那个类目号）：否则关掉抽屉后
   * 任何一次重渲染都会把它弹回来，而 URL 上的参数还在。
   */
  const catParam = useSearchParams().get("cat");
  const [openNo, setOpenNo] = useState<string | null>(null);
  /** 表格里「点一个维度看它的取值」—— 与抽屉无关，`类目号:维度号` 做键 */
  const [openKey, setOpenKey] = useState<string | null>(null);

  const list = useQuery({ queryKey: ["category-specs"], queryFn: () => api.listCategorySpecs() });

  const rows = list.data ?? [];
  const configured = rows.filter((r) => r.dimCount > 0).length;

  /*
   * `?cat=` 深链：**旧地址还得能用**（有人存了书签、有人从别处链过来）。
   * 类目树现在是就地开抽屉、不跳过来了，所以这条只剩兼容作用。
   * 只跟 catParam 变化 —— 否则关掉抽屉后任何一次重渲染都会把它弹回来。
   */
  useEffect(() => {
    if (catParam) setOpenNo(catParam);
  }, [catParam]);
  const summary = c.csSummary
    .replace("{total}", String(rows.length))
    .replace("{configured}", String(configured))
    .replace("{gap}", String(rows.length - configured));

  const columns: Column<CategorySpec>[] = [
    {
      header: c.csColCategory,
      cell: (r) => (
        <div>
          <div className="font-semibold">{r.categoryName}</div>
          <div className="text-[12px] text-muted-foreground">{r.parentName} · {r.categoryNo}</div>
        </div>
      ),
      width: "13rem",
    },
    { header: c.csColType, cell: (r) => r.categoryType ?? "—", width: "6rem" },
    {
      header: c.csColDims,
      // 缺口用危险色而不是灰：它是要被处理的，不是「暂无数据」
      cell: (r) => (r.dimCount > 0
        ? <span className="tabular-nums">{r.dimCount}</span>
        : <Badge tone="danger">{c.csNone}</Badge>),
      numeric: true,
      width: "6rem",
    },
    {
      header: c.csColDetail,
      cell: (r) => {
        if (!r.dims.length) return <span className="text-muted-foreground">—</span>;
        return (
          <div className="space-y-2">
            <div className="flex flex-wrap gap-1.5">
              {r.dims.map((d) => {
                const key = `${r.categoryNo}:${d.dimNo}`;
                return (
                  <button key={key} type="button"
                    onClick={() => setOpenKey(openKey === key ? null : key)}
                    className="focus-ring inline-flex items-center gap-1.5 rounded-chip border border-border
                               px-2.5 py-1 text-[12px] leading-[1.5] hover:bg-muted">
                    <span className="font-semibold">{d.name}</span>
                    {d.primary && <Badge tone="default">{c.csPrimary}</Badge>}
                    <Badge tone={d.universal ? "info" : "muted"}>
                      {d.universal ? c.csUniversal : c.csDedicated}
                    </Badge>
                    {d.usage === "PROP" && <Badge tone="warning">{c.csProp}</Badge>}
                    <span className="tabular-nums text-muted-foreground">{d.valueCount}</span>
                  </button>
                );
              })}
            </div>
            {r.dims.map((d) => {
              const key = `${r.categoryNo}:${d.dimNo}`;
              if (openKey !== key) return null;
              return (
                <div key={key} className="flex flex-wrap gap-1.5 rounded-card bg-muted/60 p-2">
                  {d.values.map((v) => (
                    <span key={v.valueNo}
                      className="inline-flex items-center gap-1 rounded-chip bg-background px-2 py-0.5 text-[12px]">
                      {v.label}
                      {v.numericValue != null && (
                        <span className="tabular-nums text-muted-foreground">
                          {v.numericValue}{v.numericUnit}
                        </span>
                      )}
                      <span className="font-mono text-[11px] text-muted-foreground">{v.code}</span>
                    </span>
                  ))}
                </div>
              );
            })}
          </div>
        );
      },
    },
    {
      header: "",
      cell: (r) => canEdit
        ? <Button size="sm" variant="secondary" onClick={() => setOpenNo(r.categoryNo)}>{c.csEdit}</Button>
        : null,
      width: "6rem",
    },
  ];

  return (
    <>
      <div className="mb-2 flex items-baseline justify-between">
        <h3 className="text-[15px] font-semibold">{c.csTitle}</h3>
        <span className="text-[12px] tabular-nums text-muted-foreground">{summary}</span>
      </div>
      <HelpNote className="mb-3">{c.csNotice}</HelpNote>

      <DataTable
        columns={columns} rows={rows} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(r) => r.categoryNo}
        rowClassName={(r) => (r.dimCount === 0 ? "bg-destructive-tint/30" : undefined)}
        empty={c.csEmpty}
      />

      <CategorySpecDrawer c={c} canEdit={canEdit} categoryNo={openNo} onClose={() => setOpenNo(null)} />
    </>
  );
}
