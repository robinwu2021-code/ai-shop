"use client";

// 线下核款：待审申报队列（SUBMITTED / REVIEWING），按 (orderNo, claimNo) 翻页。
//
// 申报只是买家说「我付了」—— **不是到账**。点进去在订单抽屉里对照指引、录入实际到账或拒绝申报；
// 拒绝的只是这份申报，订单占用与付款窗口都不动，买家可以重报。
import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useCan } from "@/lib/use-can";
import type { MallOfflinePendingClaim } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import { Notice } from "@/components/ui/notice";
import { StatusBadge } from "@/components/ui/status-badge";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Money, claimMap, fmtAt, showErr, type MallCopy } from "./shared";
import { OrderDrawer } from "./order-drawer";

const PAGE = 20;

export function ClaimsTab({ c }: { c: MallCopy }) {
  const allow = useCan();
  const [cursors, setCursors] = React.useState<{ afterOrderNo: string; afterClaimNo: string }[]>([]);
  const [open, setOpen] = React.useState<{ storeNo: string; orderNo: string } | null>(null);
  const cur = cursors[cursors.length - 1];
  const q = useQuery({ queryKey: ["mall-claims-queue", cur?.afterOrderNo ?? "", cur?.afterClaimNo ?? ""],
    queryFn: () => api.offlinePendingClaims({ ...cur, limit: PAGE }) });

  const columns: Column<MallOfflinePendingClaim>[] = [
    { header: c.colStore, cell: (r) => <code className="txt-caption">{r.store.storeNo}</code> },
    { header: c.colOrderNo, cell: (r) => <code className="txt-caption">{r.orderNo}</code> },
    { header: c.colClaimNo, cell: (r) => <code className="txt-caption">{r.claim.claimNo}</code> },
    { header: c.colClaimStatus, cell: (r) => <StatusBadge map={claimMap(c)} value={r.claim.status} /> },
    { header: c.colDeclared, numeric: true, cell: (r) => <Money m={r.claim.declaredAmount} /> },
    { header: c.colDeclaredAt, cell: (r) => fmtAt(r.claim.declaredPaidAt) },
    { header: c.colSubmittedAt, cell: (r) => fmtAt(r.claim.submittedAt) },
    { header: c.colActions, stickyEnd: "always", cell: (r) => <Button size="sm" onClick={() => setOpen({ storeNo: r.store.storeNo!, orderNo: r.orderNo })}>{c.btnHandle}</Button> },
  ];

  return (
    <div className="space-y-3">
      <HelpNote>{c.claimsHelp}</HelpNote>
      <Notice tone="warning">{c.claimNotFunds}</Notice>
      {!allow("commerce:mall:offline:confirm") && <ReadOnlyNotice what={c.confirmWhat} perm="commerce:mall:offline:confirm" note={c.confirmNote} />}
      <DataTable columns={columns} rows={q.data} loading={q.isLoading} error={showErr(q.error)} onRetry={() => q.refetch()}
        rowKey={(r) => `${r.orderNo}/${r.claim.claimNo}`} empty={c.queueEmpty} />
      <div className="flex justify-end gap-2">
        <Button size="sm" variant="outline" disabled={!cursors.length} onClick={() => setCursors((s) => s.slice(0, -1))}>{c.btnPrev}</Button>
        <Button size="sm" variant="outline" disabled={(q.data?.length ?? 0) < PAGE} onClick={() => {
          const last = q.data![q.data!.length - 1]!;
          setCursors((s) => [...s, { afterOrderNo: last.orderNo, afterClaimNo: last.claim.claimNo }]);
        }}>{c.btnNext}</Button>
      </div>
      {open && <OrderDrawer c={c} storeNo={open.storeNo} orderNo={open.orderNo} onClose={() => setOpen(null)} />}
    </div>
  );
}
