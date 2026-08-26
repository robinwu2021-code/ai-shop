"use client";

// 库存流水 —— 接 `/ops/inventory/ledger`（进销存独立库）。
//
// 这是客服回答「我的货怎么少了」时唯一的依据。台账**只增不改**（`InvLedger`
// 连 `setUpdatedAt` 都没有，改一笔历史在编译期就过不去），所以这一页看到的
// 就是当时发生的事，不是事后被人对齐过的样子。
//
// **游标翻页，不是页码翻页**：台账是一直在长的流水，按 offset 翻到第 3 页时
// 前面又插进来几笔，第 3 页就会把没看过的行挤走 —— 而看的人不会察觉。
import { useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { InvLedgerRow } from "@/lib/types/product";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Input } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import { Toolbar } from "@/components/ui/toolbar";
import type { ProductsCopy } from "./copy";

const SIZE = 20;

export function InvLedgerTab({ c }: { c: ProductsCopy }) {
  // 输入框与生效值分开：每敲一个字就打一次接口，台账这种大表扛不住
  const [ownerInput, setOwnerInput] = useState("");
  const [itemInput, setItemInput] = useState("");
  const [q, setQ] = useState<{ ownerId?: string; itemId?: string }>({});

  const list = useInfiniteQuery({
    queryKey: ["inv-ledger", q],
    initialPageParam: undefined as number | undefined,
    queryFn: ({ pageParam }) => api.listInvLedger({ ...q, cursor: pageParam, size: SIZE }),
    // 游标 = 上一页最后一行的 id。**不足一页就没有下一页**，
    // 用「最后一页返回 0 行」当终点的话，到底时会多打一次空请求
    getNextPageParam: (last: InvLedgerRow[]) =>
      last.length < SIZE ? undefined : last[last.length - 1]?.id,
  });

  const rows = list.data?.pages.flat() ?? [];

  const columns: Column<InvLedgerRow>[] = [
    { header: c.invColTime, cell: (r) => <span className="tabular-nums">{r.occurredAt}</span> },
    {
      header: c.invColDoc,
      cell: (r) => (
        <div>
          <Badge tone={r.docKind === "IN" ? "info" : "muted"}>{r.docKind}</Badge>{" "}
          <span className="tabular-nums">{r.docNo}</span>
        </div>
      ),
    },
    { header: c.invColReason, cell: (r) => r.reasonCode },
    {
      header: c.invColDelta,
      numeric: true,
      // 正负是这一页最该一眼看见的东西，所以带符号显示：
      // 「12」与「-12」在窄列里差一个字符，加了号才不用回头看单据类型
      cell: (r) => (
        <span className={r.qtyDelta < 0 ? "text-destructive" : "text-success"}>
          {r.qtyDelta > 0 ? `+${r.qtyDelta}` : r.qtyDelta}
        </span>
      ),
    },
    { header: c.invColAfter, numeric: true, cell: (r) => r.balanceAfter },
    { header: c.invColOperator, cell: (r) => r.operator ?? "—" },
  ];

  return (
    <div className="space-y-4">
      <Notice tone="info">{c.invLedgerNotice}</Notice>

      <Toolbar>
        <Input
          value={ownerInput}
          onChange={(e) => setOwnerInput(e.target.value)}
          placeholder={c.invLedgerOwnerPh}
          className="w-48"
        />
        <Input
          value={itemInput}
          onChange={(e) => setItemInput(e.target.value)}
          placeholder={c.invLedgerItemPh}
          className="w-48"
        />
        <Button
          onClick={() => setQ({ ownerId: ownerInput || undefined, itemId: itemInput || undefined })}
        >
          {c.invLedgerSearch}
        </Button>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        loading={list.isLoading}
        error={list.error}
        onRetry={() => list.refetch()}
        empty={c.invLedgerEmpty}
        rowKey={(r) => String(r.id)}
      />

      {rows.length > 0 && (
        <div className="flex justify-center">
          {list.hasNextPage ? (
            <Button
              variant="secondary"
              onClick={() => list.fetchNextPage()}
              disabled={list.isFetchingNextPage}
            >
              {c.invLedgerMore}
            </Button>
          ) : (
            <span className="text-sm text-muted-foreground">{c.invLedgerEnd}</span>
          )}
        </div>
      )}
    </div>
  );
}
