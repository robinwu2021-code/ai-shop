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
import type { InvBalanceRow, InvHealthRow } from "@/lib/types";
import { Badge, type BadgeTone } from "@/components/ui/badge";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { Tabs } from "@/components/ui/tabs";
import type { InventoryCopy } from "./copy";

type Kind = InvHealthRow["kind"] | "ALL";

export function HealthTab({ c }: { c: InventoryCopy }) {
  const [kind, setKind] = useState<Kind>("ALL");

  /**
   * 点进某一行看这个商家的**全部**库存待办。
   *
   * 健康度那一屏是「不知道该看谁」时的扫描，一个商家只出它最要紧的那几行；
   * 而运营点进来是因为**已经知道要看谁**了 —— 这时要的是这家店的整张待办表，
   * 那正是 `/ops/inventory/balances`（它一度也叫 health，改名让路给平台级那个）。
   */
  const [drill, setDrill] = useState<InvHealthRow | null>(null);

  const balances = useQuery({
    queryKey: ["inv-balances", drill?.entityNo],
    queryFn: () => api.listInvBalances({ entityNo: drill!.entityNo, type: "todo" }),
    enabled: !!drill,
  });

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

  /** 下钻表。**带 flags 那一列** —— 点进来是为了看「这家店哪几件在出事」 */
  const balanceColumns: Column<InvBalanceRow>[] = [
    {
      header: c.invColItem,
      cell: (b) => (
        <div>
          <div>{b.name}</div>
          <div className="text-xs text-muted-foreground">{b.specText ?? b.itemId}</div>
        </div>
      ),
    },
    {
      header: "",
      cell: (b) => (
        <>
          {b.flags.map((f) => (
            <Badge key={f} tone={f === "SHORTAGE" ? "danger" : "muted"}>
              {f === "SHORTAGE" ? c.invFlagShortage : c.invFlagStale}
            </Badge>
          ))}
        </>
      ),
    },
    { header: c.invColOnHand, numeric: true, cell: (b) => b.onHand },
    { header: c.invColReserved, numeric: true, cell: (b) => b.reserved },
    {
      header: c.invColAvailable,
      numeric: true,
      cell: (b) => (
        <span className={b.available < 0 ? "font-semibold text-destructive" : undefined}>
          {b.available}
        </span>
      ),
    },
  ];

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
        rowProps={(r) => ({
          onClick: () => setDrill(r),
          className: "cursor-pointer",
        })}
      />

      {/*
        下钻。**只读，与外面同一条口径** —— 运营改了商家的数，
        「这个数是谁改的」就多一个答案，而商家不会知道。
      */}
      <Drawer
        open={!!drill}
        onOpenChange={(o) => !o && setDrill(null)}
        title={drill ? (drill.merchantName ?? drill.entityNo) : ""}
        desc={drill?.storeNo ?? drill?.entityNo}
        width="w-[720px]"
      >
        <DataTable
          columns={balanceColumns}
          rows={balances.data}
          loading={balances.isLoading}
          error={balances.error}
          onRetry={() => balances.refetch()}
          empty={c.invBalancesEmpty}
          rowKey={(b) => b.itemId}
        />
      </Drawer>
    </div>
  );
}
