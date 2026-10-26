"use client";
// 退款复核（F51）：运营只复核、不发起。两名不同的人先后通过才执行；申请人不能复核自己。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PayOpsRefundRow } from "@/lib/api/contracts/payops";
import { useCan } from "@/lib/use-can";
import { money, fmtTime } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";
import { TabHeader } from "@/components/ui/tab-header";
import { StatusBadge, useTT } from "../shared";

export default function PayOpsRefundsPage() {
  const tt = useTT();
  const can = useCan();
  const qc = useQueryClient();
  const [status, setStatus] = useState("PENDING_REVIEW");
  const list = useQuery({ queryKey: ["pay-ops-refunds", status], queryFn: () => api.payOpsRefunds({ status: status || undefined }) });
  const review = useMutation({
    mutationFn: (v: { no: string; approve: boolean; reason?: string }) => api.payOpsReviewRefund(v.no, { approve: v.approve, reason: v.reason }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["pay-ops-refunds"] }),
  });
  const reviewable = (r: PayOpsRefundRow) => r.status === "PENDING_REVIEW" || r.status === "PENDING_SECOND_REVIEW";
  function reject(r: PayOpsRefundRow) {
    const reason = window.prompt(tt("驳回原因（会给商家看到）", "Reason for rejecting (shown to the merchant)"));
    if (reason && reason.trim()) review.mutate({ no: r.payRefundNo, approve: false, reason: reason.trim() });
  }
  const columns = [
    { header: tt("退款单号", "Refund no."), cell: (r: PayOpsRefundRow) => <span dir="ltr">{r.payRefundNo}</span> },
    { header: tt("支付单号", "Pay order"), cell: (r: PayOpsRefundRow) => <span dir="ltr">{r.payOrderNo ?? "—"}</span> },
    { header: tt("金额", "Amount"), numeric: true, cell: (r: PayOpsRefundRow) => money(r.amountMinor, r.currency) },
    { header: tt("原因", "Reason"), cell: (r: PayOpsRefundRow) => r.reason ?? "—" },
    { header: tt("发起", "Requested by"), cell: (r: PayOpsRefundRow) => `${r.sourceType ?? ""} ${r.requestedBy ?? ""}` },
    { header: tt("命中规则", "Rule hit"), cell: (r: PayOpsRefundRow) => r.hitReasons ?? "—" },
    { header: tt("状态", "Status"), cell: (r: PayOpsRefundRow) => <StatusBadge status={r.status} /> },
    { header: tt("时间", "Time"), cell: (r: PayOpsRefundRow) => fmtTime(r.createdAt) },
    { header: tt("操作", "Actions"), stickyEnd: true, cell: (r: PayOpsRefundRow) => reviewable(r) && can("pay:refund:review") ? <div className="flex gap-2">
      <Button size="sm" disabled={review.isPending} onClick={() => review.mutate({ no: r.payRefundNo, approve: true })}>{tt("通过", "Approve")}</Button>
      <Button size="sm" variant="outline" disabled={review.isPending} onClick={() => reject(r)}>{tt("驳回", "Reject")}</Button>
    </div> : null },
  ];
  return <main className="space-y-3">
    <TabHeader tabs={[{ key: "refunds", label: tt("退款复核", "Refund review") }]} value="refunds" onChange={() => {}}
      desc={tt("命中复核规则的退款在这里等两名不同的运营先后通过；复核期间不会发往渠道。", "Refunds hitting a review rule wait here for two different reviewers; nothing is sent to the channel meanwhile.")} />
    <label className="flex w-60 flex-col gap-1">{tt("状态", "Status")}
      <Select value={status} onChange={(e) => setStatus(e.target.value)}>
        <option value="PENDING_REVIEW">{tt("待复核", "Awaiting review")}</option>
        <option value="PENDING_SECOND_REVIEW">{tt("待第二人复核", "Awaiting 2nd review")}</option>
        <option value="">{tt("全部", "All")}</option>
      </Select></label>
    {review.error && <Notice tone="warning">{review.error.message}</Notice>}
    <DataTable columns={columns} rows={list.data} loading={list.isPending} error={list.error} onRetry={() => void list.refetch()}
      rowKey={(r) => r.payRefundNo} empty={tt("没有待复核的退款。", "No refunds awaiting review.")} />
  </main>;
}
