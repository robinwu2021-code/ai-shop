"use client";

// 进项票（供应商开给平台）—— 接 `/ops/purchase-invoices`。
//
// 它决定**平台能不能付款**：应付账款那一页的「票到付款」判的就是这里的状态。
// 所以这两页要一起看 —— 应付卡在「票还没到」时，人要能直接跳到这儿处理。
//
// ⚠️ **抬头对不上不给核验**，而界面必须说清是这个原因。
// 财务看到「不能核验」却不知道为什么，只会去问开票的人，而对方也不知道。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { money } from "@/lib/utils";
import type { PurchaseInvoice } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";
import type { FinanceCopy } from "./copy";

export function PurchaseInvoiceTab({ c, canVerify }: { c: FinanceCopy; canVerify: boolean }) {
  const qc = useQueryClient();
  const [rejecting, setRejecting] = useState<string | null>(null);
  const [reason, setReason] = useState("");

  const list = useQuery({
    queryKey: ["purchase-invoices"],
    queryFn: () => api.listPurchaseInvoices(),
  });
  const refresh = () => qc.invalidateQueries({ queryKey: ["purchase-invoices"] });

  const verify = useMutation({ mutationFn: api.verifyPurchaseInvoice, onSuccess: refresh });
  const reject = useMutation({
    mutationFn: (v: { no: string; reason: string }) => api.rejectPurchaseInvoice(v.no, v.reason),
    onSuccess: () => { setRejecting(null); setReason(""); refresh(); },
  });

  const rows = list.data ?? [];
  const waiting = rows.filter((r) => r.status === "SUBMITTED").length;

  const columns: Column<PurchaseInvoice>[] = [
    {
      header: c.piColInvoice,
      cell: (r) => (
        <div>
          <div className="font-mono text-[13px]">{r.invoiceNumber}</div>
          <div className="text-[12px] text-muted-foreground">{r.invoiceCode} · {r.period}</div>
        </div>
      ),
      width: "12rem",
    },
    {
      header: c.piColTitle,
      cell: (r) => (
        <div>
          <div>{r.titleName}</div>
          {/* 抬头不符是**不能核验的原因**，要摆在抬头旁边而不是藏在按钮的报错里 */}
          {!r.titleMatched && <Badge tone="danger">{c.piTitleMismatch}</Badge>}
          <div className="font-mono text-[11px] text-muted-foreground">{r.titleTaxNo}</div>
        </div>
      ),
    },
    { header: c.piColAmount, cell: (r) => money(r.amountMinor), numeric: true, width: "7rem" },
    {
      header: c.piColSettle,
      // 这张票覆盖哪几张结算单 —— 核验通过之后那几张才付得了
      cell: (r) => (r.settleNos.length
        ? <span className="font-mono text-[12px]">{r.settleNos.join(" ")}</span>
        : <span className="text-muted-foreground">—</span>),
      width: "10rem",
    },
    {
      header: c.piColStatus,
      cell: (r) => (
        <div>
          <Badge tone={r.status === "VERIFIED" ? "default" : r.status === "REJECTED" ? "danger" : "warning"}>
            {c[`purchaseInvoiceStatus_${r.status}` as keyof FinanceCopy] ?? r.status}
          </Badge>
          {r.rejectReason && <div className="text-[11px] text-muted-foreground">{r.rejectReason}</div>}
        </div>
      ),
      width: "9rem",
    },
    {
      header: "",
      cell: (r) => {
        if (!canVerify || r.status !== "SUBMITTED") return null;
        if (rejecting === r.invoiceNo) {
          return (
            <div className="flex items-center gap-1.5">
              {/* 驳回原因必填 —— 原样回给商家，不写等于让人猜 */}
              <input
                className="h-8 w-44 rounded-input border border-border bg-background px-1.5 text-[12px]"
                placeholder={c.piRejectPlaceholder}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
              <Button size="sm" disabled={!reason.trim()}
                onClick={() => reject.mutate({ no: r.invoiceNo, reason: reason.trim() })}>
                {c.confirm}
              </Button>
              <Button size="sm" variant="ghost" onClick={() => { setRejecting(null); setReason(""); }}>
                {c.cancel}
              </Button>
            </div>
          );
        }
        return (
          <div className="flex items-center gap-1.5">
            {r.titleMatched
              ? <Button size="sm" disabled={verify.isPending}
                  onClick={() => verify.mutate(r.invoiceNo)}>{c.piVerify}</Button>
              : <span className="text-[12px] text-muted-foreground">{c.piCannotVerify}</span>}
            <Button size="sm" variant="ghost" onClick={() => setRejecting(r.invoiceNo)}>
              {c.piReject}
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
        <h3 className="text-[15px] font-semibold">{c.piTitle}</h3>
        <span className="text-[12px] tabular-nums text-muted-foreground">
          {c.piSummary.replace("{n}", String(waiting))}
        </span>
      </div>
      <Notice className="mb-3">{c.piNotice}</Notice>

      <DataTable
        columns={columns} rows={rows} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(r) => r.invoiceNo}
        rowClassName={(r) => (r.titleMatched ? undefined : "bg-destructive-tint/20")}
        empty={c.piEmpty}
      />
    </>
  );
}
