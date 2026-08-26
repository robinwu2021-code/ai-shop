"use client";

// 库存健康度 —— 接 `/ops/inventory/health`（进销存独立库）。
//
// **这一页空着是好事**：它列的三类商品正在给买家制造失败的下单 ——
// 点进去、加购、然后发现买不了。而那次点击是花钱买来的。
//
// 只读。运营改了商家的数，「这个数是谁改的」就多了一个答案，而商家不会知道。
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { InvHealthRow } from "@/lib/types";
import { Badge, type BadgeTone } from "@/components/ui/badge";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";
import { Tabs } from "@/components/ui/tabs";
import type { InventoryCopy } from "./copy";

type Kind = InvHealthRow["kind"] | "ALL";

export function HealthTab({ c }: { c: InventoryCopy }) {
  const [kind, setKind] = useState<Kind>("ALL");

  const list = useQuery({
    queryKey: ["inv-health", kind],
    queryFn: () => api.listInvHealth(kind === "ALL" ? {} : { kind }),
  });


  /**
   * 三类的轻重不一样，颜色要跟着分：
   * 负库存是 **danger** —— 它不是「少了几件」，是「还能卖多少」这个数已经没有意义了；
   * 零库存在架是 **warning** —— 买家点得进来但买不了；
   * 滞销是 **muted** —— 钱压着，但没有人正在受影响。
   */
  const kindTone: Record<InvHealthRow["kind"], BadgeTone> = {
    NEGATIVE: "danger", ZERO_ON_SALE: "warning", STALE: "muted",
  };
  const kindLabel: Record<InvHealthRow["kind"], string> = {
    NEGATIVE: c.invHealthNegative, ZERO_ON_SALE: c.invHealthZeroOnSale, STALE: c.invHealthStale,
  };

  const columns: Column<InvHealthRow>[] = [
    { header: "", cell: (r) => <Badge tone={kindTone[r.kind]}>{kindLabel[r.kind]}</Badge> },
    {
      header: c.invColMerchant,
      cell: (r) => (
        <div>
          <div>{r.merchantName ?? r.entityNo}</div>
          <div className="text-xs text-muted-foreground">{r.storeNo ?? r.entityNo}</div>
        </div>
      ),
    },
    {
      header: c.invColItem,
      cell: (r) => (
        <div>
          <div>{r.itemName}</div>
          <div className="text-xs text-muted-foreground">{r.specText ?? r.itemId}</div>
        </div>
      ),
    },
    { header: c.invColOnHand, numeric: true, cell: (r) => r.onHand },
    { header: c.invColReserved, numeric: true, cell: (r) => r.reserved },
    {
      header: c.invColAvailable,
      numeric: true,
      // 可用为负要一眼看见：它是这一页里唯一「已经在出事」的数
      cell: (r) => (
        <span className={r.available < 0 ? "font-semibold text-destructive" : undefined}>
          {r.available}
        </span>
      ),
    },
    {
      header: c.invColIdle,
      numeric: true,
      cell: (r) => (r.idleDays == null ? "—" : c.invIdleDays.replace("{n}", String(r.idleDays))),
    },
  ];

  return (
    <div className="space-y-4">
      <Notice tone="info">{c.invHealthNotice}</Notice>
      <Notice tone="muted">{c.invReadOnly}</Notice>

      <Tabs
        value={kind}
        onChange={(k) => setKind(k as Kind)}
        tabs={[
          { key: "ALL", label: c.invHealthAll },
          { key: "NEGATIVE", label: c.invHealthNegative },
          { key: "ZERO_ON_SALE", label: c.invHealthZeroOnSale },
          { key: "STALE", label: c.invHealthStale },
        ]}
      />

      <DataTable
        columns={columns}
        rows={list.data}
        loading={list.isLoading}
        error={list.error}
        onRetry={() => list.refetch()}
        empty={c.invHealthEmpty}
        rowKey={(r) => `${r.entityNo}-${r.itemId}-${r.kind}`}
      />
    </div>
  );
}
