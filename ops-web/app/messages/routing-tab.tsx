"use client";

// 场景×通道矩阵（P-14.1）。
//
// 「哪个事件走哪些通道」以前**硬编码在编排里** —— 后端把它做成可配置已经很久，
// 而运营端一直没有入口：配置存在、能改、有审计，却没人看得见。
// 这一屏就是把那份配置摊开。
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { Switch } from "@/components/ui/switch";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import type { SceneChannelCell } from "@/lib/types";

/** 一行 = 一个「场景 × 受众」 */
type Row = { scene: string; audience: string; cells: SceneChannelCell[] };
import type { MessageCopy } from "./copy";

/** 通道列的固定顺序 —— 按「用户看到它的打扰程度」从轻到重排，不按字母序 */
const CHANNELS = ["INAPP", "WXSUB", "SMS", "MAIL", "PUSH"] as const;

export function RoutingTab({ c, canEdit }: { c: MessageCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ["scene-channels"], queryFn: () => api.sceneChannels() });

  const toggle = useMutation({
    mutationFn: (v: { scene: string; audience: string; channel: string; enabled: boolean }) =>
      api.setSceneChannel(v),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["scene-channels"] }); notify.success(c.rtSaved); },
  });

  const rows = list.data ?? [];
  /*
   * 按「场景 × 受众」聚成一行。**受众不能省** —— 同一个场景对买家和对商家
   * 是两条独立配置（`SUB_ORDER_PAID` 对商家默认响铃推送，对买家根本没有这一格）。
   * 把它们并成一行的话，运营改的是哪一边就说不清了。
   */
  const groups = new Map<string, Row>();
  for (const r of rows) {
    const k = `${r.scene}|${r.audience}`;
    if (!groups.has(k)) groups.set(k, { scene: r.scene, audience: r.audience, cells: [] });
    groups.get(k)!.cells.push(r);
  }

  const columns: Column<Row>[] = [
    { header: c.rtColScene, cell: (g) => c[`scene_${g.scene}` as keyof MessageCopy] ?? g.scene },
    {
      header: c.rtColAudience,
      cell: (g) => (
        <span className="text-muted-foreground">
          {c[`aud_${g.audience}` as keyof MessageCopy] ?? g.audience}
        </span>
      ),
    },
    ...CHANNELS.map((ch) => ({
      header: c[`ch_${ch}` as keyof MessageCopy] as React.ReactNode,
      cell: (g: Row) => {
        const cell = g.cells.find((x) => x.channel === ch);
        /*
         * **「这个场景没有这条通道」与「有但关着」是两回事**，
         * 都画成一个关着的开关就把它们混成了一件事 —— 前者点不动、后者一点就生效。
         * 没有的格子留白。
         */
        if (!cell) return <span className="text-muted-foreground/50">—</span>;
        return (
          <div className="flex items-center gap-2">
            <Switch
              checked={cell.enabled}
              disabled={!canEdit || cell.locked || toggle.isPending}
              onChange={(v: boolean) => toggle.mutate({
                scene: g.scene, audience: g.audience, channel: ch, enabled: v,
              })}
            />
            {/* 锁定的格子要说明为什么，否则读起来像「坏了」 */}
            {cell.locked && <Badge tone="muted">{c.rtLocked}</Badge>}
            {cell.pushLevel === "RING" && <Badge tone="warning">{c.rtRing}</Badge>}
          </div>
        );
      },
    })),
  ];

  return (
    <>
      <Notice className="mb-3">{c.rtNotice}</Notice>
      {!canEdit && <ReadOnlyNotice what={c.rtTitle} perm="message:template:update" className="mb-3" />}

      {/*
        用库里的 DataTable，不自己搭 <table> —— 手搭的表格会漏掉它已经处理好的东西：
        loading / error 与空态分开（把故障渲染成「没有数据」会让运营去改筛选条件，
        而真正该做的是报障）、斑马纹、横滚容器。守卫 ops-ui-discipline 拦的就是这个，
        它在我第一版上当场红了。
      */}
      <DataTable
        columns={columns}
        rows={[...groups.values()]}
        loading={list.isPending}
        error={list.error}
        onRetry={() => list.refetch()}
        rowKey={(g) => `${g.scene}|${g.audience}`}
        empty={c.rtEmpty}
      />
    </>
  );
}
