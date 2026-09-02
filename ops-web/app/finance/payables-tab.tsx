"use client";

// 自营应付账款 —— 接 `/ops/payables`。
//
// **这是今天唯一真能把钱付出去的路。** 第三方走分账，而分账网关是桩实现
// （见 PRD-商家资金到账与对账）。后端十个端点早已实现，而运营端此前一个入口都没有 ——
// 也就是说：平台今天付得出去的那条路，只能靠人直接调接口。
//
// 一整条：待对账 → 确认对账 → 收票（或标无票）→ 登记付款。
// ⚠️ **票到付款**是硬规则：没有核验过的进项票、也没标过无票供应商的，付不了。
// 这条闸在后端，界面要做的是**把原因说在前面**，而不是让人点下去吃一个报错。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { money } from "@/lib/utils";
import type { Settlement } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import type { FinanceCopy } from "./copy";

/** 能不能登记付款。**与后端同一套判据** —— 两处不同就会出现「按钮亮着、点了报错」 */
function payBlockedReason(s: Settlement, c: FinanceCopy): string | null {
  if (s.status === "PENDING_RECON") return c.payNeedConfirm;
  if (s.status === "PAID") return c.payAlreadyPaid;
  if (s.invoiceStatus !== "VERIFIED" && s.invoiceStatus !== "NO_INVOICE") return c.payNeedInvoice;
  return null;
}

export function PayablesTab({ c, canEdit, canPay }: {
  c: FinanceCopy; canEdit: boolean; canPay: boolean;
}) {
  const qc = useQueryClient();
  const [status, setStatus] = useState<string>("");
  const [paying, setPaying] = useState<string | null>(null);
  const [ref, setRef] = useState("");

  const list = useQuery({
    queryKey: ["payables", status],
    queryFn: () => api.listPayables(status ? { status } : {}),
  });
  const refresh = () => qc.invalidateQueries({ queryKey: ["payables"] });

  const confirm = useMutation({ mutationFn: api.confirmPayable, onSuccess: refresh });
  const noInvoice = useMutation({
    mutationFn: (no: string) => api.markNoInvoice(no, c.pyNoInvoiceReason),
    onSuccess: refresh,
  });
  const pay = useMutation({
    mutationFn: (v: { no: string; ref: string }) => api.payPayable(v.no, v.ref),
    onSuccess: () => { setPaying(null); setRef(""); refresh(); },
  });

  const rows = list.data ?? [];
  const pending = rows.filter((r) => r.status !== "PAID");
  const pendingAmount = pending.reduce((n, r) => n + r.netMinor, 0);

  const columns: Column<Settlement>[] = [
    {
      header: c.pyColBill,
      cell: (r) => (
        <div>
          <div className="font-mono text-[13px]">{r.settleNo}</div>
          <div className="text-[12px] text-muted-foreground">{r.merchantNo} · {r.subOrderNo}</div>
        </div>
      ),
      width: "13rem",
    },
    { header: c.pyColNet, cell: (r) => money(r.netMinor), numeric: true, width: "7rem" },
    {
      header: c.pyColStatus,
      cell: (r) => (
        <Badge tone={r.status === "PAID" ? "default" : r.status === "CONFIRMED" ? "info" : "warning"}>
          {c[`payableStatus_${r.status}` as keyof FinanceCopy] ?? r.status}
        </Badge>
      ),
      width: "7rem",
    },
    {
      header: c.pyColInvoice,
      // 无票供应商用中性色不用危险色：它是**合法状态**，只是要被看见
      cell: (r) => (
        <Badge tone={r.invoiceStatus === "VERIFIED" ? "default"
          : r.invoiceStatus === "NO_INVOICE" ? "muted" : "warning"}>
          {c[`invoiceStatus_${r.invoiceStatus}` as keyof FinanceCopy] ?? r.invoiceStatus ?? "—"}
        </Badge>
      ),
      width: "8rem",
    },
    {
      header: c.pyColRef,
      cell: (r) => (r.paymentRef
        ? <span className="font-mono text-[12px]">{r.paymentRef}</span>
        : <span className="text-muted-foreground">—</span>),
      width: "9rem",
    },
    {
      header: "",
      cell: (r) => {
        const blocked = payBlockedReason(r, c);
        if (paying === r.settleNo) {
          return (
            <div className="flex items-center gap-1.5">
              {/*
                网银流水号是必填。缺了它，之后对账差额永远说不清是
                「银行慢了」还是「有人点早了」—— 与提现表不给人工 PAID 入口同一条规矩。
              */}
              <input
                className="focus-ring h-[calc(var(--ctl-h)-4px)] w-40 rounded-input border border-border bg-background px-1.5 text-[12px] font-mono"
                placeholder={c.pyRefPlaceholder}
                value={ref}
                onChange={(e) => setRef(e.target.value)}
              />
              <Button size="sm" disabled={!ref.trim() || pay.isPending}
                onClick={() => pay.mutate({ no: r.settleNo, ref: ref.trim() })}>
                {c.pyConfirmPay}
              </Button>
              <Button size="sm" variant="ghost" onClick={() => { setPaying(null); setRef(""); }}>
                {c.cancel}
              </Button>
            </div>
          );
        }
        return (
          <div className="flex items-center gap-1.5">
            {canEdit && r.status === "PENDING_RECON" && (
              <Button size="sm" variant="secondary" disabled={confirm.isPending}
                onClick={() => confirm.mutate(r.settleNo)}>{c.pyConfirm}</Button>
            )}
            {canEdit && r.invoiceStatus === "PENDING_INVOICE" && (
              <Button size="sm" variant="ghost" disabled={noInvoice.isPending}
                onClick={() => noInvoice.mutate(r.settleNo)}>{c.pyNoInvoice}</Button>
            )}
            {canPay && r.status !== "PAID" && (
              blocked
                // **把原因说在前面**，而不是给一个点了会报错的按钮
                ? <span className="text-[12px] text-muted-foreground">{blocked}</span>
                : <Button size="sm" onClick={() => setPaying(r.settleNo)}>{c.pyPay}</Button>
            )}
          </div>
        );
      },
      width: "22rem",
    },
  ];

  return (
    <>
      <div className="mb-2 flex items-baseline justify-between">
        <h3 className="text-[15px] font-semibold">{c.pyTitle}</h3>
        <span className="text-[12px] tabular-nums text-muted-foreground">
          {c.pySummary.replace("{n}", String(pending.length)).replace("{amount}", money(pendingAmount))}
        </span>
      </div>
      <HelpNote className="mb-3">{c.pyNotice}</HelpNote>

      <div className="mb-3 flex gap-1.5">
        {["", "PENDING_RECON", "CONFIRMED", "PAID"].map((s) => (
          <button key={s || "all"} type="button" onClick={() => setStatus(s)}
            className={`focus-ring rounded-chip border px-2.5 py-1 text-[12px] ${
              status === s ? "border-foreground bg-foreground text-background" : "border-border hover:bg-muted"
            }`}>
            {s ? (c[`payableStatus_${s}` as keyof FinanceCopy] ?? s) : c.all}
          </button>
        ))}
      </div>

      <DataTable
        columns={columns} rows={rows} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(r) => r.settleNo}
        empty={c.pyEmpty}
      />
    </>
  );
}
