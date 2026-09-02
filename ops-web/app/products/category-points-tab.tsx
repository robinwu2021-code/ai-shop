"use client";

// 类目 × 积分 —— 接 `/ops/category-points`。
//
// **平台按类目统一管理，商家不参与配置。** 依据是实测：线上 199 件商品里，
// 用商品级配置配了积分的是 **0** 件 —— 一个 0% 填充率的配置项不是「灵活」，
// 是「没人用」。而运营配 30 个类目是做得到的：规格那套现在 30/30 全配齐。
//
// 与「类目 × 支付方式」相反，这一页**空着就是缺口**：没配规则的类目走平台兜底，
// 而兜底是一个所有类目共用的数字 —— 生鲜与家电按同一个比例发分，怎么算都不对。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { CategoryPoints } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import type { ProductsCopy } from "./copy";

export function CategoryPointsTab({ c, canEdit }: { c: ProductsCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const [editing, setEditing] = useState<string | null>(null);
  const [mode, setMode] = useState<"FIXED" | "RATIO">("RATIO");
  const [value, setValue] = useState("");

  const list = useQuery({ queryKey: ["category-points"], queryFn: () => api.listCategoryPoints() });
  const save = useMutation({
    mutationFn: (v: { categoryNo: string; earnMode: "FIXED" | "RATIO" | null; earnValue: number | null }) =>
      api.saveCategoryPoints(v.categoryNo, { earnMode: v.earnMode, earnValue: v.earnValue }),
    onSuccess: (rows) => {
      qc.setQueryData(["category-points"], rows);
      setEditing(null);
    },
  });

  const rows = list.data ?? [];
  const configured = rows.filter((r) => r.earnMode).length;

  /** 一行规则怎么念。**单位必须写出来** —— 「50」既可能是 50 分也可能是千分之五 */
  function ruleText(r: CategoryPoints) {
    if (!r.earnMode) return null;
    return r.earnMode === "FIXED"
      ? c.cptFixedText.replace("{v}", String((r.earnValue ?? 0) / 100))
      : c.cptRatioText.replace("{v}", String((r.earnValue ?? 0) / 100));
  }

  const columns: Column<CategoryPoints>[] = [
    {
      header: c.cptColCategory,
      cell: (r) => (
        <div>
          <div className="font-semibold">{r.categoryName}</div>
          <div className="text-[12px] text-muted-foreground">{r.parentName} · {r.categoryNo}</div>
        </div>
      ),
      width: "16rem",
    },
    {
      header: c.cptColRule,
      // 没配用危险色：它是要被处理的，不是「暂无数据」（与类目 × 规格同一读法）
      cell: (r) => (r.earnMode
        ? <span>{ruleText(r)}</span>
        : <Badge tone="danger">{c.cptNone}</Badge>),
    },
    {
      header: "",
      cell: (r) => {
        if (!canEdit) return null;
        if (editing !== r.categoryNo) {
          return (
            <Button size="sm" variant="secondary" onClick={() => {
              setEditing(r.categoryNo);
              setMode(r.earnMode ?? "RATIO");
              setValue(r.earnValue == null ? "" : String(r.earnValue));
            }}>{c.cptEdit}</Button>
          );
        }
        return (
          <div className="flex items-center gap-1.5">
            <select
              className="focus-ring h-[calc(var(--ctl-h)-4px)] rounded-input border border-border bg-background px-1.5 text-[12px]"
              value={mode}
              onChange={(e) => setMode(e.target.value as "FIXED" | "RATIO")}
            >
              <option value="RATIO">{c.cptRatio}</option>
              <option value="FIXED">{c.cptFixed}</option>
            </select>
            {/*
              输入的是**整数**：FIXED 存分、RATIO 存万分比。
              不收小数是刻意的 —— 金额与比例一旦用浮点，对账时的分位差没人说得清。
            */}
            <input
              className="focus-ring h-[calc(var(--ctl-h)-4px)] w-20 rounded-input border border-border bg-background px-1.5 text-[12px] tabular-nums"
              inputMode="numeric"
              value={value}
              onChange={(e) => setValue(e.target.value.replace(/[^\d]/g, ""))}
            />
            <Button size="sm" disabled={!value} onClick={() => save.mutate({
              categoryNo: r.categoryNo, earnMode: mode, earnValue: Number(value),
            })}>{c.cptSave}</Button>
            {/* 清除 = 回到平台兜底，不是「发 0 分」—— 两者在库里就是 NULL 与 0 的区别 */}
            <Button size="sm" variant="ghost" onClick={() => save.mutate({
              categoryNo: r.categoryNo, earnMode: null, earnValue: null,
            })}>{c.cptClear}</Button>
          </div>
        );
      },
      width: "20rem",
    },
  ];

  return (
    <>
      <div className="mb-2 flex items-baseline justify-between">
        <h3 className="text-[15px] font-semibold">{c.cptTitle}</h3>
        <span className="text-[12px] tabular-nums text-muted-foreground">
          {c.cptSummary.replace("{n}", String(configured)).replace("{total}", String(rows.length))}
        </span>
      </div>
      <HelpNote className="mb-3">{c.cptNotice}</HelpNote>

      <DataTable
        columns={columns} rows={rows} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(r) => r.categoryNo}
        rowClassName={(r) => (r.earnMode ? undefined : "bg-destructive-tint/30")}
        empty={c.cptEmpty}
      />
    </>
  );
}
