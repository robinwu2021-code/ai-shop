"use client";

// 类目 × 规格总览（规格库 V195）—— 接 `GET /ops/category-specs`。
//
// 为什么要有这一屏：商家建品时能选到什么规格，全由类目绑定决定，而在此之前
// 运营端**看不到这层关系** —— 只有一张模板列表，看不出「哪个类目还没配」。
// 而没配的后果是那一类的商家只能手输，手输的选项没有规格编码，
// 三家店的「500g」「五百克」「0.5kg」永远聚不到一起（线上 378 件商品，带编码的 0 件）。
//
// 所以这张表的第一职责不是展示已配的，是**把缺口顶到眼前**：一条都没绑的类目标红并计数。
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { CategorySpec, CategorySpecDim } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";
import type { ProductsCopy } from "./copy";

/** 一个维度的胶囊：主维度、通用/专用、以及 PROP（不进 SKU）各自标出来 */
function DimChip({ dim, c, on, open }: {
  dim: CategorySpecDim; c: ProductsCopy; on: () => void; open: boolean;
}) {
  return (
    <button
      type="button"
      onClick={on}
      className="inline-flex items-center gap-1.5 rounded-chip border border-border px-2.5 py-1
                 text-[12px] leading-[1.5] hover:bg-muted"
      aria-expanded={open}
    >
      <span className="font-semibold">{dim.name}</span>
      {dim.primary && <Badge tone="default">{c.csPrimary}</Badge>}
      {/* 通用 vs 专用：判据是「值的含义是否跨类目一致」，不是用在几个类目 */}
      <Badge tone={dim.universal ? "info" : "muted"}>
        {dim.universal ? c.csUniversal : c.csDedicated}
      </Badge>
      {/* PROP 不进 SKU 笛卡尔积 —— 标出来，否则运营会以为它也会生成规格 */}
      {dim.usage === "PROP" && <Badge tone="warning">{c.csProp}</Badge>}
      <span className="tabular-nums text-muted-foreground">{dim.valueCount}</span>
    </button>
  );
}

export function CategorySpecPanel({ c }: { c: ProductsCopy }) {
  const [openKey, setOpenKey] = useState<string | null>(null);

  const list = useQuery({
    queryKey: ["category-specs"],
    queryFn: () => api.listCategorySpecs(),
  });

  const rows = list.data ?? [];
  const configured = rows.filter((r) => r.dimCount > 0).length;
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
          <div className="text-[12px] text-muted-foreground">
            {r.parentName} · {r.categoryNo}
          </div>
        </div>
      ),
      width: "14rem",
    },
    { header: c.csColType, cell: (r) => r.categoryType ?? "—", width: "7rem" },
    {
      header: c.csColDims,
      cell: (r) =>
        r.dimCount > 0
          ? <span className="tabular-nums">{r.dimCount}</span>
          // 缺口用危险色而不是灰：它是要被处理的，不是「暂无数据」
          : <Badge tone="danger">{c.csNone}</Badge>,
      numeric: true,
      width: "7rem",
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
                  <DimChip
                    key={key} dim={d} c={c} open={openKey === key}
                    on={() => setOpenKey(openKey === key ? null : key)}
                  />
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
                      {/* 归一量：500g / 半斤 / 0.5kg 都是 500 —— 排序与同规格比价靠它 */}
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
  ];

  return (
    <div className="mb-6">
      <div className="mb-2 flex items-baseline justify-between">
        <h3 className="text-[15px] font-semibold">{c.csTitle}</h3>
        <span className="text-[12px] tabular-nums text-muted-foreground">{summary}</span>
      </div>
      <Notice className="mb-3">{c.csNotice}</Notice>
      <DataTable
        columns={columns} rows={rows} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(r) => r.categoryNo}
        empty={c.csEmpty}
      />
    </div>
  );
}
