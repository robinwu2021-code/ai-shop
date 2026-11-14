"use client";

// 商城订单：按门店查（后端要求 storeNo），orderNo 游标翻页。
// 列表只显示概览 + 三维状态；所有动作在详情抽屉里做。
import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "next/navigation";
import { api } from "@/lib/api";
import type { MallOrderSummary } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import { Money, OrderAxes, StoreBar, fmtAt, showErr, type MallCopy } from "./shared";
import { OrderDrawer } from "./order-drawer";

const PAGE = 20;

export function OrdersTab({ c, scopedStoreNo, initialOrderNo, onDetailOpenChange }: {
  c: MallCopy; scopedStoreNo?: string; initialOrderNo?: string | null; onDetailOpenChange?: (open: boolean) => void;
}) {
  const sp = useSearchParams();
  const [storeNo, setStoreNo] = React.useState(scopedStoreNo ?? sp.get("store") ?? "");
  const [cursors, setCursors] = React.useState<string[]>([]); // 每一页的 afterOrderNo
  const [open, setOpen] = React.useState<string | null>(initialOrderNo === undefined ? sp.get("order") : initialOrderNo);
  React.useEffect(() => {
    onDetailOpenChange?.(!!open);
    return () => onDetailOpenChange?.(false);
  }, [open, onDetailOpenChange]);
  const after = cursors[cursors.length - 1];
  const q = useQuery({ queryKey: ["mall-orders", storeNo, after ?? ""], enabled: !!storeNo,
    queryFn: () => api.mallOrders(storeNo, { afterOrderNo: after, limit: PAGE }) });

  const columns: Column<MallOrderSummary>[] = [
    { header: c.colOrderNo, cell: (r) => <code className="txt-caption">{r.order?.orderNo}</code> },
    { header: c.colCreatedAt, cell: (r) => fmtAt(r.order?.createdAt) },
    { header: c.colPayable, numeric: true, cell: (r) => <Money m={r.order?.payable} /> },
    { header: c.colOrderStatus, cell: (r) => <Badge tone="outline">{r.order?.status}</Badge> },
    { header: c.axesLabel, cell: (r) => <OrderAxes c={c} s={r} /> },
    { header: c.colActions, stickyEnd: "always", cell: (r) => <Button size="sm" variant="outline" onClick={() => setOpen(r.order!.orderNo!)}>{c.btnDetail}</Button> },
  ];

  return (
    <div className="space-y-3">
      <HelpNote>{c.ordersHelp}</HelpNote>
      {!scopedStoreNo && <StoreBar c={c} id="orders-store" value={storeNo} onSubmit={(v) => { setStoreNo(v); setCursors([]); }} />}
      {!storeNo ? <p className="txt-caption text-muted-foreground">{c.storeFirst}</p> : (
        <>
          <DataTable columns={columns} rows={q.data} loading={q.isLoading} error={showErr(q.error)} onRetry={() => q.refetch()}
            rowKey={(r) => r.order?.orderNo ?? ""} empty={c.ordersEmpty} />
          <div className="flex justify-end gap-2">
            <Button size="sm" variant="outline" disabled={!cursors.length} onClick={() => setCursors((s) => s.slice(0, -1))}>{c.btnPrev}</Button>
            <Button size="sm" variant="outline" disabled={(q.data?.length ?? 0) < PAGE}
              onClick={() => setCursors((s) => [...s, q.data![q.data!.length - 1]!.order!.orderNo!])}>{c.btnNext}</Button>
          </div>
        </>
      )}
      {storeNo && <OrderDrawer c={c} storeNo={storeNo} orderNo={open} onClose={() => setOpen(null)} />}
    </div>
  );
}
