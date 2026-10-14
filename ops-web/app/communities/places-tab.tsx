"use client";

// 固定地址库 —— **这一屏回答「我们还要依赖地图多久」。**
//
// 库里一条 = 「某个坐标格子叫什么名字，上次核对是什么时候」。它同时是答案库、
// 是防止频繁调地图的挡板、也是逐步长大的自有资产 —— 三样本来就是同一份数据。
//
// **`mapStatus` 那一行比表格更要紧**：熔断与额度是后端进程内的状态，
// 买家那边只看得到「地名标没标陈旧」—— 这儿是**唯一**能提前发现
// 「地图快不行了」的地方。没有这一屏，端点做了等于把那个信号扔了。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ErrorState, Skeleton } from "@/components/ui/misc";
import { useCopy } from "@/lib/use-copy";
import { COMMUNITIES_COPY } from "./copy";
import type { GeoPlace } from "@/lib/types";

type Copy = (typeof COMMUNITIES_COPY)["zh"];

/** 地图状态那一条。**坏的时候要显眼，好的时候也要说一句** —— 一个空位与「还没加载完」长得一样 */
function MapStatus({ status, c }: { status: string; c: Copy }) {
  const bad = status !== "CLOSED";
  return (
    <div className={`rounded-card border p-4 ${bad ? "border-destructive" : "border-border"}`}>
      <div className="txt-body text-muted-foreground">{c.geoMapStatus}</div>
      <div className="mt-1 txt-display">
        {status === "CLOSED" ? c.geoMapOk
          : status === "QUOTA_EXHAUSTED" ? c.geoMapQuota : c.geoMapOpen}
      </div>
      <div className="mt-2 txt-caption text-muted-foreground">
        {bad ? c.geoMapBadHint : c.geoMapOkHint}
      </div>
    </div>
  );
}

export function PlacesTab({ enabled }: { enabled: boolean }) {
  const c = useCopy(COMMUNITIES_COPY);
  const qc = useQueryClient();
  const [regionCode, setRegionCode] = useState("");
  /** 试算的结果。**做完试算才允许真跑** —— 见下面那颗按钮 */
  const [preview, setPreview] = useState<string>("");

  const q = useQuery({
    queryKey: ["geo-places"],
    queryFn: () => api.listGeoPlaces({ limit: 200 }),
    enabled,
  });

  const promote = useMutation({
    mutationFn: (dryRun: boolean) =>
      api.promoteGeoPlaces({ regionCode, minHits: 10, dryRun }),
    onSuccess: (r) => {
      setPreview(r.dryRun
        ? c.geoPromotePreview.replace("{n}", String(r.received))
        : c.geoPromoteDone.replace("{n}", String(r.created)));
      if (!r.dryRun) void qc.invalidateQueries({ queryKey: ["geo-places"] });
    },
  });

  const columns: Column<GeoPlace>[] = [
    { header: c.geoColName, cell: (r) => r.name },
    {
      header: c.geoColKind,
      // POI 是唯一值得沉淀成聚落的一档 —— 标出来，别让人去数
      cell: (r) => <Badge tone={r.kind === "POI" ? "info" : "muted"}>{r.kind}</Badge>,
    },
    { header: c.geoColAddress, cell: (r) => r.address ?? "—" },
    { header: c.geoColHits, cell: (r) => <span className="tabular-nums">{r.hitCount}</span> },
    { header: c.geoColVerified, cell: (r) => r.verifiedAt?.slice(0, 10) ?? "—" },
    {
      header: c.geoColPromoted,
      // 已升级的那些从此走聚落那条更靠前的路 —— 它们不再依赖地图
      cell: (r) => (r.promotedNo ? <Badge tone="success">{c.geoPromoted}</Badge> : "—"),
    },
  ];

  if (q.isLoading) return <Skeleton />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const page = q.data;
  if (!page) return null;

  return (
    <div className="space-y-4">
      <div className="grid gap-3 md:grid-cols-2">
        <MapStatus status={page.mapStatus} c={c} />
        <div className="rounded-card border border-border bg-card p-4">
          <div className="txt-body text-muted-foreground">{c.geoTotal}</div>
          <div className="mt-1 txt-display tabular-nums">{page.total}</div>
          <div className="mt-2 txt-caption text-muted-foreground">{c.geoTotalHint}</div>
        </div>
      </div>

      {/*
        沉淀：**两步，且默认那一步是安全的那个**。
        一次动几百行，而它的错误要到买家那一端才看得见 —— 与 import-estates 同一条规矩。
      */}
      <div className="rounded-card border border-border bg-card p-4 space-y-3">
        <div className="txt-body font-medium">{c.geoPromoteTitle}</div>
        <div className="txt-caption text-muted-foreground">{c.geoPromoteHint}</div>
        <div className="flex flex-wrap items-center gap-2">
          <label className="txt-caption text-muted-foreground" htmlFor="geo-region">
            {c.geoRegionCode}
          </label>
          <input
            id="geo-region"
            className="h-[var(--ctl-h)] rounded-control border border-border bg-background px-3 txt-body focus-ring"
            value={regionCode}
            placeholder="440309"
            onChange={(e) => setRegionCode(e.target.value.trim())}
          />
          <Button
            variant="outline"
            disabled={!regionCode || promote.isPending}
            onClick={() => promote.mutate(true)}
          >
            {c.geoDryRun}
          </Button>
          {/*
            **试算过才让真跑**：没试算过的那一下是盲的，而它的错误不会当场报错 ——
            建出一批坐标可疑的聚落，症状是那些小区的人永远搜不到货。
          */}
          <Button
            disabled={!regionCode || !preview || promote.isPending}
            onClick={() => promote.mutate(false)}
          >
            {c.geoPromoteRun}
          </Button>
        </div>
        {preview && <div className="txt-caption">{preview}</div>}
      </div>

      {/*
        `empty` 要给一句说清「空着意味着什么」的话 —— 默认的「暂无数据」
        与「还没加载完」长得一样，而这一屏的读者正是来看「库里长成什么样」的。
      */}
      <DataTable
        columns={columns}
        rows={page.rows}
        rowKey={(r) => r.geoKey}
        error={q.error}
        onRetry={() => void q.refetch()}
        empty={c.geoEmpty}
      />
    </div>
  );
}
