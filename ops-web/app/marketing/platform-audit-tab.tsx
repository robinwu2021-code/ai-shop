"use client";

// 平台活动 · 报名审核（原型 s30）。
//
// 右上写「预算已占 ¥18,400 / ¥50,000」：**每一行的「平台出资」加起来就是它** ——
// 审核时看得见还剩多少，才判断得了这一份能不能过。
// 通过 = 后端一条带条件的 UPDATE 占预算，超了拒（40032）；两个运营同时点，后到的看到「已经审过了」。
// 驳回理由必填，商家原样看到。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill } from "@/lib/use-copy";
import { notify } from "@/lib/notify";
import { money } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import type { OpsEnrollment } from "@/lib/types";
import type { MarketingCopy } from "./copy";

const STATUSES = ["SUBMITTED", "APPROVED", "REJECTED"] as const;

export function PlatformAuditTab({ c, canReview }: { c: MarketingCopy; canReview: boolean }) {
  const qc = useQueryClient();
  const acts = useQuery({ queryKey: ["platform-activities"], queryFn: () => api.listPlatformActivities() });
  const published = (acts.data ?? []).filter((a) => a.status !== "DRAFT");
  const [picked, setPicked] = useState("");
  const [status, setStatus] = useState<(typeof STATUSES)[number]>("SUBMITTED");

  const activityNo = picked || published[0]?.activityNo || "";
  const act = published.find((a) => a.activityNo === activityNo) ?? null;

  const rows = useQuery({
    queryKey: ["platform-enrollments", activityNo],
    queryFn: () => api.listEnrollments(activityNo),
    enabled: !!activityNo,
  });

  const review = useMutation({
    mutationFn: ({ no, pass, reason }: { no: string; pass: boolean; reason?: string }) =>
      api.reviewEnrollment(no, pass, reason),
    onSuccess: () => {
      notify.success(c.auditDone);
      void qc.invalidateQueries({ queryKey: ["platform-enrollments", activityNo] });
      void qc.invalidateQueries({ queryKey: ["platform-activities"] });
    },
    onError: (e: Error) => notify.error(e.message),
  });

  const all = rows.data ?? [];
  const shown = all.filter((e) => e.status === status);
  const count = (s: string) => all.filter((e) => e.status === s).length;

  const cols: Column<OpsEnrollment>[] = [
    { header: c.auditColMerchant, cell: (e) => e.merchantName || e.entityNo },
    { header: c.auditColGoods, cell: (e) => fill(c.auditGoodsN, { n: String(e.goodsNos.length) }) },
    { header: c.auditColQuota, numeric: true, cell: (e) => String(e.quota) },
    { header: c.auditColPlatform, numeric: true, cell: (e) => money(e.platformMaxMinor) },
    { header: c.auditColRating, numeric: true, cell: (e) => e.rating.toFixed(1) },
    { header: c.auditColStatus, cell: (e) => (
      <Badge tone={e.status === "APPROVED" ? "success" : e.status === "SUBMITTED" ? "warning" : "muted"}>
        {(c as unknown as Record<string, string>)["enrollStatus_" + e.status] ?? e.status}
      </Badge>
    ) },
    { header: "", cell: (e) => (canReview && e.status === "SUBMITTED" ? (
      <div className="flex gap-2">
        <Button size="sm" loading={review.isPending} onClick={() => review.mutate({ no: e.enrollmentNo, pass: true })}>
          {c.auditPass}
        </Button>
        <Button size="sm" variant="outline" onClick={() => {
          // 理由商家原样看到：空理由等于让他猜自己错在哪
          const reason = window.prompt(c.auditRejectReason) ?? "";
          if (reason.trim()) review.mutate({ no: e.enrollmentNo, pass: false, reason: reason.trim() });
        }}>{c.auditReject}</Button>
      </div>
    ) : e.status === "REJECTED" ? <span className="text-muted-foreground">{e.rejectReason}</span> : null) },
  ];

  if (!acts.isLoading && !published.length) {
    return <Notice>{c.auditNoActivity}</Notice>;
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="space-y-1">
          <Label htmlFor="audit-act">{c.auditPick}</Label>
          <Select id="audit-act" value={activityNo} onChange={(ev) => setPicked(ev.target.value)}>
            {published.map((a) => <option key={a.activityNo} value={a.activityNo}>{a.name}</option>)}
          </Select>
        </div>
        {act && (
          <span className="tabular-nums text-muted-foreground">
            {act.budgetMinor == null
              ? c.auditBudgetNone
              : fill(c.auditBudget, { used: money(act.reservedMinor), total: money(act.budgetMinor) })}
          </span>
        )}
      </div>

      <div className="flex gap-2">
        {STATUSES.map((s) => (
          <Button key={s} size="sm" variant={status === s ? "default" : "outline"} onClick={() => setStatus(s)}>
            {fill((c as unknown as Record<string, string>)["auditTab" + s], { n: String(count(s)) })}
          </Button>
        ))}
      </div>

      <DataTable columns={cols} rows={shown} rowKey={(e) => e.enrollmentNo}
        loading={rows.isLoading} error={rows.error} onRetry={() => rows.refetch()} empty={c.auditEmpty} />
    </div>
  );
}
