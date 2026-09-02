"use client";

// 类目 × 支付方式 —— 接 `/ops/category-pay-modes`。
//
// 这是「能不能当面付」四层判定的**第 ① 层**（类目 → 主体资质 → 门店 → 商品）。
//
// ⚠️ **这张表是黑名单不是白名单**：没有行即放行，插一行才是禁止。
// 反过来设计的话，上线当天得先把所有类目配一遍才有人下得了单 ——
// 而一期只想用「主体资质」那一层做主力，其余三层默认放行。
//
// 所以这一页的读法与「类目 × 规格」相反：那边空着是**缺口**（标红），
// 这边空着是**正常**。把两页做成一个样子会让人以为这里也有一堆待办。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { CategoryPayMode } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import type { ProductsCopy } from "./copy";

export function CategoryPayModeTab({ c, canEdit }: { c: ProductsCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const [busy, setBusy] = useState<string | null>(null);

  const list = useQuery({
    queryKey: ["category-pay-modes"],
    queryFn: () => api.listCategoryPayModes(),
  });
  const save = useMutation({
    mutationFn: (v: { categoryNo: string; allow: boolean }) =>
      api.saveCategoryPayMode(v.categoryNo, v.allow),
    onSuccess: (rows) => qc.setQueryData(["category-pay-modes"], rows),
    onSettled: () => setBusy(null),
  });

  const rows = list.data ?? [];
  const blocked = rows.filter((r) => !r.offlineAllowed).length;

  const columns: Column<CategoryPayMode>[] = [
    {
      header: c.cpmColCategory,
      cell: (r) => (
        <div>
          <div className="font-semibold">{r.categoryName}</div>
          <div className="text-[12px] text-muted-foreground">{r.parentName} · {r.categoryNo}</div>
        </div>
      ),
      width: "16rem",
    },
    {
      header: c.cpmColOffline,
      // 被禁的那几条才是要找的东西 —— 用警示色标出来，默认允许保持中性
      cell: (r) => (r.offlineAllowed
        ? <Badge tone="muted">{c.cpmAllowed}</Badge>
        : <Badge tone="danger">{c.cpmBlocked}</Badge>),
      width: "8rem",
    },
    {
      header: "",
      cell: (r) => (canEdit ? (
        <Button
          size="sm"
          variant="secondary"
          disabled={busy === r.categoryNo}
          onClick={() => {
            setBusy(r.categoryNo);
            save.mutate({ categoryNo: r.categoryNo, allow: !r.offlineAllowed });
          }}
        >
          {r.offlineAllowed ? c.cpmBlock : c.cpmAllow}
        </Button>
      ) : null),
      width: "7rem",
    },
  ];

  return (
    <>
      <div className="mb-2 flex items-baseline justify-between">
        <h3 className="text-[15px] font-semibold">{c.cpmTitle}</h3>
        <span className="text-[12px] tabular-nums text-muted-foreground">
          {c.cpmSummary.replace("{blocked}", String(blocked)).replace("{total}", String(rows.length))}
        </span>
      </div>
      <HelpNote className="mb-3">{c.cpmNotice}</HelpNote>

      <DataTable
        columns={columns} rows={rows} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(r) => r.categoryNo}
        // 被禁的行才染色。**空着不是缺口**，与「类目 × 规格」那页正好相反
        rowClassName={(r) => (r.offlineAllowed ? undefined : "bg-destructive-tint/30")}
        empty={c.cpmEmpty}
      />
    </>
  );
}
