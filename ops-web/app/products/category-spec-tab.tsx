"use client";

// 类目 × 规格（规格库 V195）—— 接 `/ops/category-specs`。
//
// 这一页回答第三个问题：**谁用哪些**。类目树回答「卖什么」，规格库回答「有哪些规格」，
// 三件事此前挤在「规格模板维护」一个页面里，而那张模板表已经退化成兜底。
//
// 这张表的第一职责是**把缺口顶到眼前**：一条规格都没配的类目标红并计数。
// 没配的后果不是「少个推荐」——商家侧不再有品类兜底（去掉那条回落是这一版的决定），
// 那一类的商家只能手输，而手输的选项没有规格编码，跨店聚合就此断掉。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import type { CategorySpec, CategorySpecBinding, SpecDim } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import type { ProductsCopy } from "./copy";

/** 抽屉里的编辑态：一个类目的绑定是一组**有序**的东西，所以用数组不用 Set */
type Editing = {
  category: CategorySpec;
  bindings: CategorySpecBinding[];
};

export function CategorySpecTab({ c, canEdit }: { c: ProductsCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const [openKey, setOpenKey] = useState<string | null>(null);
  const [editing, setEditing] = useState<Editing | null>(null);

  const list = useQuery({ queryKey: ["category-specs"], queryFn: () => api.listCategorySpecs() });
  // 抽屉里要能从全量维度里挑，所以两个分区都拉
  const dims = useQuery({ queryKey: ["spec-dims", "all"], queryFn: () => api.listSpecDims({}) });

  const save = useMutation({
    mutationFn: () => api.saveCategorySpecs(editing!.category.categoryNo, editing!.bindings),
    onSuccess: () => {
      setEditing(null);
      qc.invalidateQueries({ queryKey: ["category-specs"] });
      qc.invalidateQueries({ queryKey: ["spec-dims"] });
      notify.success(c.save);
    },
  });

  const rows = list.data ?? [];
  const configured = rows.filter((r) => r.dimCount > 0).length;
  const summary = c.csSummary
    .replace("{total}", String(rows.length))
    .replace("{configured}", String(configured))
    .replace("{gap}", String(rows.length - configured));

  /** 把一行的现状翻成可编辑的绑定 —— 抽屉打开时做一次，之后只改本地态 */
  function startEdit(r: CategorySpec) {
    setEditing({
      category: r,
      bindings: r.dims.map((d) => ({
        dimNo: d.dimNo,
        usageType: d.usage,
        primary: d.primary,
        required: false,
        valueNos: d.values.map((v) => v.valueNo),
        labels: Object.fromEntries(
          d.values
            .map((v) => {
              const src = dims.data?.find((x) => x.dimNo === d.dimNo)
                ?.values.find((x) => x.valueNo === v.valueNo);
              // 只有真的换过名才回填 —— 否则保存时会把一堆等于原名的「换名」写进去
              return src && src.label !== v.label ? [v.valueNo, v.label] : null;
            })
            .filter(Boolean) as [string, string][],
        ),
      })),
    });
  }

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
                    className="inline-flex items-center gap-1.5 rounded-chip border border-border
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
        ? <Button size="sm" variant="secondary" onClick={() => startEdit(r)}>{c.csEdit}</Button>
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
      <Notice className="mb-3">{c.csNotice}</Notice>

      <DataTable
        columns={columns} rows={rows} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(r) => r.categoryNo}
        rowClassName={(r) => (r.dimCount === 0 ? "bg-destructive-tint/30" : undefined)}
        empty={c.csEmpty}
      />

      <Drawer
        open={!!editing} onOpenChange={(o) => !o && setEditing(null)}
        title={editing ? fill(c.csEditTitle, { name: editing.category.categoryName }) : ""}
        desc={editing?.category.categoryNo}
        width="w-[680px]"
        footer={editing ? (
          <Button loading={save.isPending} onClick={() => save.mutate()}>{c.csEditOk}</Button>
        ) : null}
      >
        {editing && (
          <BindingEditor
            c={c}
            all={dims.data ?? []}
            editing={editing}
            onChange={(bindings) => setEditing({ ...editing, bindings })}
          />
        )}
      </Drawer>
    </>
  );
}

/**
 * 绑定编辑器：左边是已选（有序，第一个是主维度），右边是可选。
 *
 * <p>「主维度」用<b>顺序</b>表达而不是一个单选钮：它就是「排第一的那个」——
 * 两种表示并存的话，用户会遇到「排在第二却标着主」的状态，而那时预填哪一个说不清。
 */
function BindingEditor({ c, all, editing, onChange }: {
  c: ProductsCopy; all: SpecDim[]; editing: Editing;
  onChange: (b: CategorySpecBinding[]) => void;
}) {
  const picked = editing.bindings;
  const pickedNos = new Set(picked.map((b) => b.dimNo));
  const rest = all.filter((d) => !pickedNos.has(d.dimNo) && d.status === "ACTIVE");
  const dimOf = (no: string) => all.find((d) => d.dimNo === no);

  const move = (i: number, to: number) => {
    if (to < 0 || to >= picked.length) return;
    const next = [...picked];
    const [x] = next.splice(i, 1);
    next.splice(to, 0, x!);
    // 主维度 = 排第一的那个，位置一变就跟着变
    onChange(next.map((b, k) => ({ ...b, primary: k === 0 })));
  };

  return (
    <div className="space-y-5">
      <p className="text-[13px] text-muted-foreground">{c.csEditHint}</p>

      <div>
        <div className="mb-2 txt-label text-muted-foreground">{c.csPicked}</div>
        <div className="space-y-2">
          {picked.map((b, i) => {
            const d = dimOf(b.dimNo);
            if (!d) return null;
            return (
              <div key={b.dimNo} className="rounded-card border border-border p-3">
                <div className="flex items-center gap-2">
                  <span className="font-semibold">{d.name}</span>
                  {i === 0 && <Badge tone="default">{c.csPrimary}</Badge>}
                  <Badge tone={d.universal ? "info" : "muted"}>
                    {d.universal ? c.csUniversal : c.csDedicated}
                  </Badge>
                  <span className="ml-auto flex gap-1">
                    <Button size="sm" variant="ghost" onClick={() => move(i, i - 1)}>↑</Button>
                    <Button size="sm" variant="ghost" onClick={() => move(i, i + 1)}>↓</Button>
                    <Button size="sm" variant="ghost"
                      onClick={() => onChange(picked.filter((x) => x.dimNo !== b.dimNo)
                        .map((x, k) => ({ ...x, primary: k === 0 })))}>
                      {c.csUnbind}
                    </Button>
                  </span>
                </div>
                <div className="mt-2 flex flex-wrap gap-1.5">
                  {d.values.filter((v) => v.status === "ACTIVE").map((v) => {
                    const on = b.valueNos.includes(v.valueNo);
                    const shown = b.labels[v.valueNo] ?? v.label;
                    return (
                      <button key={v.valueNo} type="button"
                        onClick={() => {
                          const valueNos = on
                            ? b.valueNos.filter((x) => x !== v.valueNo)
                            : [...b.valueNos, v.valueNo];
                          onChange(picked.map((x) => x.dimNo === b.dimNo ? { ...x, valueNos } : x));
                        }}
                        onDoubleClick={() => {
                          // 双击换名：500g 在蔬菜下叫「约1斤」，归一量不变
                          const next = window.prompt(
                            fill(c.csRenamePrompt, { cat: editing.category.categoryName, label: v.label }),
                            shown,
                          );
                          if (next == null) return;
                          const labels = { ...b.labels };
                          if (next.trim() && next.trim() !== v.label) labels[v.valueNo] = next.trim();
                          else delete labels[v.valueNo];
                          onChange(picked.map((x) => x.dimNo === b.dimNo ? { ...x, labels } : x));
                        }}
                        className={`rounded-chip px-2 py-0.5 text-[12px] ${
                          on ? "bg-[var(--primary)] text-white" : "bg-muted text-muted-foreground"}`}>
                        {shown}
                        {b.labels[v.valueNo] && <span className="opacity-70"> ← {v.label}</span>}
                      </button>
                    );
                  })}
                </div>
                <p className="mt-1.5 text-[11.5px] text-muted-foreground">{c.csRenameHint}</p>
              </div>
            );
          })}
          {!picked.length && <p className="text-[13px] text-muted-foreground">{c.csNone}</p>}
        </div>
      </div>

      <div>
        <div className="mb-2 txt-label text-muted-foreground">{c.csPickDims}</div>
        <div className="flex flex-wrap gap-1.5">
          {rest.map((d) => (
            <button key={d.dimNo} type="button"
              onClick={() => onChange([...picked, {
                dimNo: d.dimNo, usageType: d.usageType, primary: picked.length === 0,
                required: false, valueNos: [], labels: {},
              }])}
              className="inline-flex items-center gap-1.5 rounded-chip border border-border
                         px-2.5 py-1 text-[12px] hover:bg-muted">
              {d.name}
              <Badge tone={d.universal ? "info" : "muted"}>
                {d.universal ? c.csUniversal : c.csDedicated}
              </Badge>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
