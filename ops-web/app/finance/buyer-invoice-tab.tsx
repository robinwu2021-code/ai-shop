"use client";

// 买家的开票申请 —— 接 `/ops/invoice-requests`。
//
// ⚠️ **这个域里有三张名字很近的「票」，别混：**
//   · 进项票（`/ops/purchase-invoices`）  供应商 → 平台，决定平台能不能付款
//   · 商家开票申请（`/ops/finance/invoices`）平台 → 商家，服务费发票
//   · **本页**（`/ops/invoice-requests`）  平台 → 买家，决定买家能不能报销
//
// 本页按**订单**走，另两个按主体与账期走 —— 这是最快分辨它们的判据。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { money } from "@/lib/utils";
import type { BuyerInvoiceRequest } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import type { FinanceCopy } from "./copy";

export function BuyerInvoiceTab({ c, canIssue }: { c: FinanceCopy; canIssue: boolean }) {
  const qc = useQueryClient();
  const [acting, setActing] = useState<{ no: string; kind: "issue" | "reject" } | null>(null);
  const [text, setText] = useState("");

  const list = useQuery({
    queryKey: ["buyer-invoice-requests"],
    queryFn: () => api.listBuyerInvoiceRequests(),
  });
  const refresh = () => qc.invalidateQueries({ queryKey: ["buyer-invoice-requests"] });
  const done = () => { setActing(null); setText(""); refresh(); };

  const issued = useMutation({
    mutationFn: (v: { no: string; invoiceNo: string }) => api.markBuyerInvoiceIssued(v.no, v.invoiceNo),
    onSuccess: done,
  });
  const reject = useMutation({
    mutationFn: (v: { no: string; reason: string }) => api.rejectBuyerInvoiceRequest(v.no, v.reason),
    onSuccess: done,
  });

  const rows = list.data ?? [];
  const waiting = rows.filter((r) => r.status === "PENDING").length;

  const columns: Column<BuyerInvoiceRequest>[] = [
    {
      header: c.brColOrder,
      cell: (r) => (
        <div>
          <div className="font-mono text-[13px]">{r.orderNo}</div>
          <div className="text-[12px] text-muted-foreground">{r.requestNo}</div>
        </div>
      ),
      width: "13rem",
    },
    {
      header: c.brColTitle,
      cell: (r) => (
        <div>
          <div>{r.title}</div>
          <div className="text-[12px] text-muted-foreground">
            {c[`titleType_${r.titleType}` as keyof FinanceCopy] ?? r.titleType}
            {/* 公司抬头没税号是开不出来的，摆在这儿而不是等提交时报错 */}
            {r.titleType === "COMPANY" && !r.taxNo && (
              <Badge tone="danger">{c.brNoTaxNo}</Badge>
            )}
            {r.taxNo && <span className="ml-1 font-mono text-[11px]">{r.taxNo}</span>}
          </div>
        </div>
      ),
    },
    { header: c.brColAmount, cell: (r) => money(r.amountMinor), numeric: true, width: "7rem" },
    {
      header: c.brColStatus,
      cell: (r) => (
        <div>
          <Badge tone={r.status === "ISSUED" ? "default" : r.status === "REJECTED" ? "danger" : "warning"}>
            {c[`buyerInvoiceStatus_${r.status}` as keyof FinanceCopy] ?? r.status}
          </Badge>
          {r.invoiceNo && <div className="font-mono text-[11px] text-muted-foreground">{r.invoiceNo}</div>}
          {r.rejectReason && <div className="text-[11px] text-muted-foreground">{r.rejectReason}</div>}
        </div>
      ),
      width: "10rem",
    },
    {
      header: "",
      cell: (r) => {
        if (!canIssue || r.status !== "PENDING") return null;
        if (acting?.no === r.requestNo) {
          const isIssue = acting.kind === "issue";
          return (
            <div className="flex items-center gap-1.5">
              <input
                className="focus-ring h-[calc(var(--ctl-h)-4px)] w-44 rounded-input border border-border bg-background px-1.5 text-[12px]"
                placeholder={isIssue ? c.brInvoiceNoPlaceholder : c.brRejectPlaceholder}
                value={text}
                onChange={(e) => setText(e.target.value)}
              />
              <Button size="sm" disabled={!text.trim()}
                onClick={() => (isIssue
                  ? issued.mutate({ no: r.requestNo, invoiceNo: text.trim() })
                  : reject.mutate({ no: r.requestNo, reason: text.trim() }))}>
                {c.confirm}
              </Button>
              <Button size="sm" variant="ghost" onClick={() => { setActing(null); setText(""); }}>
                {c.cancel}
              </Button>
            </div>
          );
        }
        return (
          <div className="flex items-center gap-1.5">
            <Button size="sm" onClick={() => setActing({ no: r.requestNo, kind: "issue" })}>
              {c.brMarkIssued}
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setActing({ no: r.requestNo, kind: "reject" })}>
              {c.brReject}
            </Button>
          </div>
        );
      },
      width: "20rem",
    },
  ];

  return (
    <>
      <div className="mb-2 flex items-baseline justify-between">
        <h3 className="text-[15px] font-semibold">{c.brTitle}</h3>
        <span className="text-[12px] tabular-nums text-muted-foreground">
          {c.brSummary.replace("{n}", String(waiting))}
        </span>
      </div>
      <HelpNote className="mb-3">{c.brNotice}</HelpNote>

      <DataTable
        columns={columns} rows={rows} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(r) => r.requestNo}
        empty={c.brEmpty}
      />
    </>
  );
}
