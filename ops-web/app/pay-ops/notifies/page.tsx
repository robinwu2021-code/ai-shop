"use client";
// 业务通知（F53）：支付 → 业务方的答复（第三方 webhook）。放弃了的可以手动重推，投递记录记操作人。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PayOpsNotifyRow } from "@/lib/api/contracts/payops";
import { useCan } from "@/lib/use-can";
import { fmtTime } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";
import { TabHeader } from "@/components/ui/tab-header";
import { StatusBadge, useTT } from "../shared";

export default function PayOpsNotifiesPage() {
  const tt = useTT();
  const can = useCan();
  const qc = useQueryClient();
  const [status, setStatus] = useState("GIVEN_UP");
  const list = useQuery({ queryKey: ["pay-ops-notifies", status], queryFn: () => api.payOpsNotifies({ status: status || undefined }) });
  const resend = useMutation({ mutationFn: (id: number) => api.payOpsResendNotify(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["pay-ops-notifies"] }) });
  const columns = [
    { header: tt("事件", "Event"), cell: (n: PayOpsNotifyRow) => n.eventType },
    { header: tt("事件号", "Event id"), cell: (n: PayOpsNotifyRow) => <span dir="ltr">{n.eventKey}</span> },
    { header: tt("目标", "Target"), cell: (n: PayOpsNotifyRow) => <span dir="ltr">{n.targetUrl ?? n.targetType}</span> },
    { header: tt("已投递", "Attempts"), numeric: true, cell: (n: PayOpsNotifyRow) => n.attempts },
    { header: tt("状态", "Status"), cell: (n: PayOpsNotifyRow) => <StatusBadge status={n.status} /> },
    { header: tt("建立", "Created"), cell: (n: PayOpsNotifyRow) => fmtTime(n.createdAt) },
    { header: tt("操作", "Actions"), stickyEnd: true, cell: (n: PayOpsNotifyRow) => n.targetType === "WEBHOOK" && can("pay:notify:resend")
      ? <Button size="sm" variant="outline" disabled={resend.isPending} onClick={() => resend.mutate(n.id)}>{tt("重推", "Resend")}</Button> : null },
  ];
  return <main className="space-y-3">
    <TabHeader tabs={[{ key: "notifies", label: tt("业务通知", "Notifications") }]} value="notifies" onChange={() => {}}
      desc={tt("自动投递失败会退避重试，超过 24 小时放弃；接收方修好后在这里重推。", "Failed deliveries back off and give up after 24 hours; resend here once the receiver is fixed.")} />
    <label className="flex w-60 flex-col gap-1">{tt("状态", "Status")}
      <Select value={status} onChange={(e) => setStatus(e.target.value)}>
        <option value="GIVEN_UP">{tt("已放弃", "Given up")}</option>
        <option value="FAILED">{tt("失败待重试", "Failed, retrying")}</option>
        <option value="PENDING">{tt("待投递", "Pending")}</option>
        <option value="DELIVERED">{tt("已送达", "Delivered")}</option>
        <option value="">{tt("全部", "All")}</option>
      </Select></label>
    {resend.error && <Notice tone="warning">{resend.error.message}</Notice>}
    <DataTable columns={columns} rows={list.data} loading={list.isPending} error={list.error} onRetry={() => void list.refetch()}
      rowKey={(n) => String(n.id)} empty={tt("没有这个状态的通知。", "No notifications in this state.")} />
  </main>;
}
