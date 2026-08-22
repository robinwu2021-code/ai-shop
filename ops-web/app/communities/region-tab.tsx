"use client";

// 区划维护（人工）。
//
// 官方数据停更（统计局 2024-10 起不再公开），之后真实发生的区划调整
// —— 新设街道/镇、撤并、更名 —— 只能在这里手工维护。
//
// 三个动作的分寸：
//   · 新增：层级由父级推导，生成码带字母段（父码+X+序号），与官方纯数字码永不冲突；
//   · 停用：只影响新选择，存量商家的经营范围不动（与行业停用同一口径）。
//     **不级联** —— 一次误操作不该波及几十个街道，而恢复时没人记得原来哪些是停的；
//   · 改名：不动码，存量引用不受影响。
//
// 这一屏同时是 enabled 那一列的**第一个消费界面**：它带着「开城开关」的注释
// 上线两年，此前全仓没有任何写入口。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import type { Region } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Toolbar } from "@/components/ui/toolbar";
import type { COMMUNITIES_COPY } from "./copy";

type Copy = (typeof COMMUNITIES_COPY)["zh"];

export function RegionTab({ c, canDecide }: { c: Copy; canDecide: boolean }) {
  const qc = useQueryClient();
  // 面包屑：从省级往下钻，一次一层 —— 66 万行的树不可能整棵渲染
  const [trail, setTrail] = useState<Region[]>([]);
  const parent = trail[trail.length - 1]?.regionCode;
  // 行内编辑：当前在改名的码 + 草稿
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  // 新增：父级就是当前层
  const [adding, setAdding] = useState(false);
  const [addName, setAddName] = useState("");

  const list = useQuery({
    queryKey: ["opsRegions", parent ?? ""],
    // enabledOnly=false：维护面必须看得见停用的，否则再也开不回来
    queryFn: () => api.listRegions(parent, false),
  });

  const refresh = () => qc.invalidateQueries({ queryKey: ["opsRegions"] });

  const rename = useMutation({
    mutationFn: () => api.renameRegion(editing!, draft.trim()),
    onSuccess: () => { setEditing(null); refresh(); notify.success(c.rgRenamed); },
  });
  const toggle = useMutation({
    mutationFn: (r: Region) => api.toggleRegion(r.regionCode, !r.enabled),
    onSuccess: () => { refresh(); notify.success(c.rgToggled); },
  });
  const create = useMutation({
    mutationFn: () => api.createRegion(parent!, addName.trim()),
    onSuccess: () => { setAdding(false); setAddName(""); refresh(); notify.success(c.rgCreated); },
  });

  const columns: Column<Region>[] = [
    {
      header: c.colRgNodeName,
      cell: (r) =>
        editing === r.regionCode ? (
          <span className="flex items-center gap-2">
            <Input value={draft} onChange={(e) => setDraft(e.target.value)} className="w-48" />
            <Button size="sm" loading={rename.isPending} disabled={!draft.trim()} onClick={() => rename.mutate()}>
              {c.rgSave}
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setEditing(null)}>{c.rgCancel}</Button>
          </span>
        ) : r.hasChild ? (
          <button className="link" onClick={() => setTrail([...trail, r])}>{r.name} ›</button>
        ) : (
          <span>{r.name}</span>
        ),
    },
    { header: c.colRgNodeCode, cell: (r) => <span className="font-mono text-muted-foreground">{r.regionCode}</span> },
    {
      header: c.colRgNodeState,
      cell: (r) => (
        <Badge tone={r.enabled ? "success" : "muted"}>{r.enabled ? c.rgEnabled : c.rgDisabled}</Badge>
      ),
    },
    {
      header: "",
      cell: (r) =>
        canDecide && editing !== r.regionCode ? (
          <span className="flex gap-2">
            <Button size="sm" variant="ghost" onClick={() => { setEditing(r.regionCode); setDraft(r.name); }}>
              {c.rgRename}
            </Button>
            <Button size="sm" variant="ghost" loading={toggle.isPending} onClick={() => toggle.mutate(r)}>
              {r.enabled ? c.rgDisable : c.rgEnable}
            </Button>
          </span>
        ) : null,
    },
  ];

  // 街道/镇不再有下级（村是聚落，归社区网格那个 tab 管）
  const atStreet = trail[trail.length - 1]?.level === "STREET";

  return (
    <>
      <Toolbar>
        <span className="flex flex-wrap items-center gap-1 text-sm">
          <button className="link" onClick={() => setTrail([])}>{c.rgRoot}</button>
          {trail.map((x, i) => (
            <span key={x.regionCode}>
              {" / "}
              <button className="link" onClick={() => setTrail(trail.slice(0, i + 1))}>{x.name}</button>
            </span>
          ))}
        </span>
        {canDecide && parent && !atStreet && (
          adding ? (
            <span className="flex items-center gap-2">
              <Input value={addName} onChange={(e) => setAddName(e.target.value)} className="w-48" placeholder={c.rgAddPh} />
              <Button size="sm" loading={create.isPending} disabled={!addName.trim()} onClick={() => create.mutate()}>
                {c.rgSave}
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setAdding(false)}>{c.rgCancel}</Button>
            </span>
          ) : (
            <Button size="sm" variant="outline" onClick={() => setAdding(true)}>{c.rgAdd}</Button>
          )
        )}
      </Toolbar>

      <DataTable
        columns={columns}
        rows={list.data}
        loading={list.isLoading}
        error={list.error}
        onRetry={() => list.refetch()}
        rowKey={(r) => r.regionCode}
        empty={atStreet ? c.rgStreetLeaf : c.rgEmpty}
      />
    </>
  );
}
