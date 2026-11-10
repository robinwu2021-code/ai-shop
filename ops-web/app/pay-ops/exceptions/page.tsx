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
    <OrderPaymentResolutions />
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


/** Source-order exceptions are separate from acquiring-order exceptions and use their original cash owner. */
function OrderPaymentResolutions() {
  const tt = useTT();
  const can = useCan();
  const qc = useQueryClient();
  const [status, setStatus] = useState("MANUAL_REQUIRED");
  const list = useQuery({ queryKey: ["order-payment-resolutions", status],
    queryFn: () => api.orderPaymentResolutions(status || undefined), enabled: can("pay:order:view") });
  const refund = useMutation({
    mutationFn: (row: import("@/lib/api/contracts/payops").OrderPaymentResolutionRow) =>
      api.refundOrderPaymentResolution(row.payNo, row.paymentNo),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["order-payment-resolutions"] }),
  });
  type Row = import("@/lib/api/contracts/payops").OrderPaymentResolutionRow;
  const columns = [
    { header: tt("原订单号", "Original order"), cell: (r: Row) => r.payNo },
    { header: tt("原支付号", "Original payment"), cell: (r: Row) => r.paymentNo },
    { header: tt("金额", "Amount"), cell: (r: Row) => money(r.amountMinor, r.currency) },
    { header: tt("原因", "Reason"), cell: (r: Row) => r.reason },
    { header: tt("状态", "Status"), cell: (r: Row) => r.status === "RESOLVED" ? tt("已退款", "Refunded")
      : r.status === "REFUNDING" ? tt("退款核对中", "Refund being verified") : tt("人工待核对", "Manual review") },
    { header: tt("原退款执行 / 错误", "Original refund / error"),
      cell: (r: Row) => [r.refundNo, r.refundErrorCode].filter(Boolean).join(" / ") || "—" },
    { header: tt("操作", "Actions"), cell: (r: Row) => r.canRefund && can("pay:order:manage")
      ? <Button size="sm" disabled={refund.isPending} onClick={() => {
        if (window.confirm(tt("核对原款后继续原路退款？订单保持关闭，渠道未知结果需人工核对。",
          "Continue the original refund after checking the payment? The order stays closed; unknown outcomes require review."))) {
          refund.mutate(r);
        }
      }}>{r.refundNo ? tt("查询 / 继续原退款", "Query / continue original refund") : tt("按原单退款", "Refund original payment")}</Button>
      : null },
  ];
  if (!can("pay:order:view")) return null;
  return <section className="space-y-3">
    <h2 className="font-semibold">{tt("订单迟到款人工队列", "Late order payment review")}</h2>
    <Notice>{tt("取消或关单后的到账款由运营核对后按原单退款，不自动恢复订单。缺少历史支付坐标或事实不符时需另行人工核对。",
      "Payments after cancellation or closure need manual review and a refund against the original payment. Orders stay closed. Missing or conflicting original facts require investigation.")}</Notice>
    <Select className="max-w-60" value={status} onChange={(e) => setStatus(e.target.value)}>
      <option value="MANUAL_REQUIRED">{tt("待核对", "Needs review")}</option>
      <option value="REFUNDING">{tt("退款核对中", "Refund being verified")}</option>
      <option value="RESOLVED">{tt("已退款", "Refunded")}</option>
      <option value="">{tt("全部", "All")}</option>
    </Select>
    {refund.error && <Notice tone="warning">{refund.error.message}</Notice>}
    <DataTable columns={columns} rows={list.data} loading={list.isPending} error={list.error}
      onRetry={() => void list.refetch()} rowKey={(r) => `${r.payNo}/${r.paymentNo}`}
      empty={tt("没有这个状态的订单支付异常。", "No order payment exceptions in this state.")} />
  </section>;
}
