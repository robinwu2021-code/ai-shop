"use client";
// 支付异常（W3 E04 的运营入口）：关单后到账、重复支付、金额不符。处置只有两种：原路退回 / 判定无需处置。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PayOpsExceptionRow } from "@/lib/api/contracts/payops";
import { useCan } from "@/lib/use-can";
import { money, fmtTime } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";
import { TabHeader } from "@/components/ui/tab-header";
import { StatusBadge, useTT } from "../shared";

const KINDS: Record<string, [string, string]> = {
  LATE_PAYMENT: ["关单后到账", "Paid after close"], DUPLICATE_PAYMENT: ["重复支付", "Duplicate payment"],
  AMOUNT_MISMATCH: ["金额不符", "Amount mismatch"], CURRENCY_MISMATCH: ["币种不符", "Currency mismatch"],
};
/** 能原路退回的：顾客多付的那两种。金额不符要先查清楚，不给一键退 */
const REFUNDABLE = new Set(["LATE_PAYMENT", "DUPLICATE_PAYMENT"]);

export default function PayOpsExceptionsPage() {
  const tt = useTT();
  const can = useCan();
  const qc = useQueryClient();
  const [status, setStatus] = useState("OPEN");
  const list = useQuery({ queryKey: ["pay-ops-exceptions", status], queryFn: () => api.payOpsExceptions({ status: status || undefined }) });
  const done = () => qc.invalidateQueries({ queryKey: ["pay-ops-exceptions"] });
  const refund = useMutation({ mutationFn: (id: number) => api.payOpsRefundException(id), onSuccess: done });
  const ignore = useMutation({ mutationFn: (v: { id: number; note: string }) => api.payOpsIgnoreException(v.id, v.note), onSuccess: done });
  function askRefund(e: PayOpsExceptionRow) {
    if (window.confirm(tt(`把 ${money(e.amountMinor, e.currency)} 原路退回给顾客？`, `Refund ${money(e.amountMinor, e.currency)} to the customer?`))) refund.mutate(e.id);
  }
  function askIgnore(e: PayOpsExceptionRow) {
    const note = window.prompt(tt("为什么无需处置（如：线下已退）", "Why no action is needed (e.g. refunded offline)"));
    if (note && note.trim()) ignore.mutate({ id: e.id, note: note.trim() });
  }
  const busy = refund.isPending || ignore.isPending;
  const columns = [
    { header: tt("类型", "Kind"), cell: (e: PayOpsExceptionRow) => KINDS[e.kind] ? tt(...KINDS[e.kind]) : e.kind },
    { header: tt("支付单号", "Pay order"), cell: (e: PayOpsExceptionRow) => <span dir="ltr">{e.payOrderNo ?? "—"}</span> },
    { header: tt("金额", "Amount"), numeric: true, cell: (e: PayOpsExceptionRow) => money(e.amountMinor, e.currency) },
    { header: tt("状态", "Status"), cell: (e: PayOpsExceptionRow) => <StatusBadge status={e.status} /> },
    { header: tt("处置人", "Handled by"), cell: (e: PayOpsExceptionRow) => e.handledBy ?? "—" },
    { header: tt("说明", "Note"), cell: (e: PayOpsExceptionRow) => e.note ?? "—" },
    { header: tt("发生", "Occurred"), cell: (e: PayOpsExceptionRow) => fmtTime(e.createdAt) },
    { header: tt("操作", "Actions"), stickyEnd: true, cell: (e: PayOpsExceptionRow) => e.status === "OPEN" && can("pay:order:manage") ? <div className="flex gap-2">
      {REFUNDABLE.has(e.kind) && <Button size="sm" disabled={busy} onClick={() => askRefund(e)}>{tt("原路退回", "Refund")}</Button>}
      <Button size="sm" variant="outline" disabled={busy} onClick={() => askIgnore(e)}>{tt("无需处置", "No action")}</Button>
    </div> : null },
  ];
  const err = refund.error ?? ignore.error;
  return <main className="space-y-3">
    <TabHeader tabs={[{ key: "exceptions", label: tt("支付异常", "Payment exceptions") }]} value="exceptions" onChange={() => {}}
      desc={tt("这些钱进来了但支付单没法认：多半要原路退回顾客。退回不经支付单余额，也不记流水。", "Money received that the order can't accept. Refunds go back against the attempt itself, outside the order balance.")} />
    <label className="flex w-60 flex-col gap-1">{tt("状态", "Status")}
      <Select value={status} onChange={(e) => setStatus(e.target.value)}>
        <option value="OPEN">{tt("待处置", "Open")}</option>
        <option value="AUTO_REFUNDING">{tt("原路退回中", "Refunding")}</option>
        <option value="RESOLVED">{tt("已处置", "Resolved")}</option>
        <option value="IGNORED">{tt("无需处置", "Ignored")}</option>
        <option value="">{tt("全部", "All")}</option>
      </Select></label>
    {err && <Notice tone="warning">{err.message}</Notice>}
    <DataTable columns={columns} rows={list.data} loading={list.isPending} error={list.error} onRetry={() => void list.refetch()}
      rowKey={(e) => String(e.id)} empty={tt("没有这个状态的支付异常。", "No exceptions in this state.")} />
  </main>;
}
