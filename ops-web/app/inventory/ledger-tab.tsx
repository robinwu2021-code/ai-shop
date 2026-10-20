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
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fmtTime } from "@/lib/utils";
import { fill } from "@/lib/use-copy";
import type { InvLedgerPage, InvLedgerRow } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Input } from "@/components/ui/input";
import { HelpNote } from "@/components/ui/help-note";
import { Toolbar } from "@/components/ui/toolbar";
import type { InventoryCopy } from "./copy";

const SIZE = 20;

export function LedgerTab({ c }: { c: InventoryCopy }) {
  // 输入框与生效值分开：每敲一个字就打一次接口，台账这种大表扛不住
  const [entityInput, setEntityInput] = useState("");
  const [itemInput, setItemInput] = useState("");
  const [orderInput, setOrderInput] = useState("");
  const [traceOrder, setTraceOrder] = useState("");
  const [q, setQ] = useState<{ entityNo: string; itemId?: string } | null>(null);

  const list = useInfiniteQuery({
    queryKey: ["inv-ledger", q],
    initialPageParam: undefined as number | undefined,
    queryFn: ({ pageParam }) => api.listInvLedger({ ...q!, cursor: pageParam, size: SIZE }),
    // **没选商家就不发请求**：后端的 entityNo 是必填，空着发出去只会拿回 400，
    // 而界面上会显示成「加载失败」—— 让人以为接口坏了，其实是还没输入
    enabled: !!q,
    // 游标由服务端给。**不要自己拿最后一行的 id 去推** —— 同一毫秒有多笔时会漏行
    getNextPageParam: (last: InvLedgerPage) => last.nextCursor ?? undefined,
  });

  /*
   * 概况（M5）。**在翻明细之前先答「这家到底在不在用」** ——
   * 线上 6 家商家里只有 2 家真在记账，而那 4 家在这张流水表上的样子
   * 与「今天恰好没动」一模一样：两者都是空列表。
   */
  const digest = useQuery({
    queryKey: ["inv-digest", q?.entityNo],
    queryFn: () => api.invMerchantDigest(q!.entityNo),
    enabled: !!q,
  });
  const trace = useQuery({
    queryKey: ["inv-order-trace", traceOrder],
    queryFn: () => api.getInvOrderTrace(traceOrder),
    enabled: !!traceOrder,
  });

  const rows: InvLedgerRow[] = list.data?.pages.flatMap((p) => p.entries) ?? [];
  const syncStatusLabels = {
    COMPLETED: c.invTraceSyncCompleted, PENDING: c.invTraceSyncPending, FAILED: c.invTraceSyncFailed,
    WARNING: c.invTraceSyncWarning, SKIPPED: c.invTraceSyncSkipped, ARCHIVED: c.invTraceSyncArchived,
  };
  const syncModeLabels = { UNIFIED: c.invTraceModeUnified, LEGACY: c.invTraceModeLegacy };

  const columns: Column<InvLedgerRow>[] = [
    { header: c.invColTime, cell: (r) => <span className="tabular-nums">{fmtTime(r.occurredAt)}</span> },
    {
      header: c.invColDoc,
      cell: (r) => (
        <div>
          <Badge tone={r.docKind === "IN" ? "info" : "muted"}>{r.docKind}</Badge>{" "}
          <span className="tabular-nums">{r.docNo}</span>
        </div>
      ),
    },
    {
      header: c.invColReason,
      // 认不出的码原样显示。空白会让人以为「这笔没有原因」，而它其实只是没翻译
      cell: (r) => (c as Record<string, string>)[`invReason${r.reasonCode}`] ?? r.reasonCode,
    },
    {
      header: c.invColDelta,
      numeric: true,
      // 正负是这一页最该一眼看见的东西，所以带符号显示：
      // 「12」与「-12」在窄列里差一个字符，加了号才不用回头看单据类型
      cell: (r) => (
        <span className={r.qtyDelta < 0 ? "text-destructive" : "text-success-ink"}>
          {r.qtyDelta > 0 ? `+${r.qtyDelta}` : r.qtyDelta}
        </span>
      ),
    },
    { header: c.invColAfter, numeric: true, cell: (r) => r.balanceAfter },
    { header: c.invColOperator, cell: (r) => r.operator ?? "—" },
  ];

  return (
    <div className="space-y-4">
      <HelpNote>{c.invLedgerNotice}</HelpNote>

      <Toolbar>
        <Input
          value={entityInput}
          onChange={(e) => setEntityInput(e.target.value)}
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
          disabled={!entityInput.trim()}
          onClick={() => setQ({ entityNo: entityInput.trim(), itemId: itemInput || undefined })}
        >
          {c.invLedgerSearch}
        </Button>
        <Input value={orderInput} onChange={(e) => setOrderInput(e.target.value)} placeholder={c.invTraceOrderPh} className="w-56" />
        <Button disabled={!orderInput.trim()} variant="secondary" onClick={() => setTraceOrder(orderInput.trim())}>
          {c.invTraceTitle}
        </Button>
      </Toolbar>

      {traceOrder && (
        <section className="space-y-3 rounded-card border p-4">
          <h2 className="txt-title">{c.invTraceTitle} · <span className="tabular-nums">{traceOrder}</span></h2>
          {trace.isLoading && <Notice tone="muted">{c.invLedgerSearch}…</Notice>}
          {trace.error && <Notice tone="danger">{c.invLedgerNotice}</Notice>}
          {trace.data && trace.data.reservations.length === 0 && <Notice tone="muted">{c.invTraceEmpty}</Notice>}
          {trace.data && (
            <div className="space-y-1 rounded-field border p-3 txt-caption">
              <div className="txt-title">{c.invTraceSync}</div>
              {trace.data.syncEvents.length === 0 ? <div className="text-muted-foreground">{c.invTraceSyncEmpty}</div>
                : trace.data.syncEvents.map((event) => (
                  <div key={`${event.eventType}:${event.aggregateId}`} className="flex flex-wrap gap-x-4 gap-y-1 border-t pt-1">
                    <span>{event.eventType}</span>
                    <span>{event.aggregateId}</span>
                    <span>{c.invTraceSyncStatus}: {syncStatusLabels[event.status]}</span>
                    <span>{c.invTraceSyncSource}: {event.source} · {syncModeLabels[event.outboxMode]}</span>
                  </div>
                ))}
            </div>
          )}
          {trace.data?.reservations.map((reservation) => (
            <div key={`${reservation.ownerId}:${reservation.reservationId}`} className="space-y-2 rounded-field bg-muted/30 p-3">
              <div className="flex flex-wrap gap-x-5 gap-y-1 txt-caption">
                <span>{c.invTraceReservation}: {reservation.reservationId} · {reservation.status}</span>
                <span>{c.invColMerchant}: {reservation.ownerId}</span>
                <span>V{reservation.lifecycleVersion ?? 1}</span>
                <span>{c.invColDoc}: {reservation.outboundNo ?? "—"}</span>
              </div>
              {reservation.lines.map((line) => (
                <div key={`${reservation.reservationId}:${line.lineNo}`} className="rounded-field border bg-background p-3 txt-caption">
                  <div className="flex flex-wrap gap-x-5 gap-y-1">
                    <span>{c.invColItem}: {line.itemId}</span>
                    <span>{c.invTraceLocation}: {line.locationId}</span>
                    <span>{c.invTraceReserved}: {line.reservedQty}</span>
                    <span>{c.invTraceIssued}: {line.issuedQty}</span>
                    <span>{c.invTraceIssue}: {line.outboundNo ?? "—"}</span>
                    <span>{c.invTraceOperator}: {line.updatedBy ?? "—"}</span>
                  </div>
                  {line.issueLedger.map((entry) => (
                    <div key={entry.id} className="mt-1 flex flex-wrap gap-x-5 border-t pt-1">
                      <span>{c.invColTime}: {fmtTime(entry.occurredAt)}</span>
                      <span>{c.invTraceBalance}: {entry.balanceBefore} → {entry.balanceAfter}</span>
                      <span>{c.invTraceOperator}: {entry.operator ?? entry.createdBy ?? "—"}</span>
                    </div>
                  ))}
                  {line.returns.map((receipt) => (
                    <div key={`${receipt.receiptNo}:${receipt.inboundNo}`} className="mt-1 flex flex-wrap gap-x-5 border-t pt-1">
                      <span>{c.invTraceReturn}: {receipt.receiptNo} · {receipt.afterSaleNo}</span>
                      <span>{receipt.qty} · {receipt.quality}</span>
                      <span>{c.invTraceLocation}: {receipt.locationId}</span>
                      <span>{c.invColDoc}: {receipt.inboundNo}</span>
                      <span>{c.invTraceOperator}: {receipt.createdBy ?? "—"}</span>
                    </div>
                  ))}
                </div>
              ))}
            </div>
          ))}
        </section>
      )}

      {/*
        * 概况条（M5）。**「没搬进来」与「搬了但一笔没记」要分开说** ——
        * 前者去看投影链路，后者去催商家，而在下面那张表上两者都是一片空白。
        */}
      {q && digest.data === null && !digest.isPending && (
        <Notice tone="danger">{fill(c.invDigestNoOwner, { no: q.entityNo })}</Notice>
      )}
      {q && digest.data && (
        <div className="mb-3 flex flex-wrap items-center gap-x-6 gap-y-1 rounded-card border px-3 py-2">
          <Stat label={c.invDigestItems} value={digest.data.itemCount} />
          <Stat label={c.invDigestLedger} value={digest.data.ledgerCount} />
          <Stat label={c.invDigestLast}
                value={digest.data.lastLedgerAt ? fmtTime(digest.data.lastLedgerAt) : c.invDigestNever} />
          <Stat label={c.invDigestShortage} value={digest.data.shortageCount} />
          <Stat label={c.invDigestStale} value={digest.data.staleCount} />
          {/* 建了账却一笔没记 —— 这正是线上 6 家里那 4 家的样子，值得一句话点破 */}
          {digest.data.itemCount > 0 && digest.data.ledgerCount === 0 && (
            <Badge tone="warning">{c.invDigestNeverUsed}</Badge>
          )}
        </div>
      )}

      <DataTable
        columns={columns}
        rows={q ? rows : []}
        loading={list.isLoading}
        error={list.error}
        onRetry={() => list.refetch()}
        empty={q ? c.invLedgerEmpty : c.invLedgerPickOwner}
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
            <span className="txt-body text-muted-foreground">{c.invLedgerEnd}</span>
          )}
        </div>
      )}
    </div>
  );
}

/** 概况条里的一格。标签在上、数在下，扫一眼就能比 */
function Stat({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <span className="flex items-baseline gap-1.5">
      <span className="txt-caption text-muted-foreground">{label}</span>
      <span className="txt-body tabular-nums">{value}</span>
    </span>
  );
}
