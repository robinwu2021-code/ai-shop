"use client";

// 审批流程列表（TDD-OpsKit审批对接 §1.5，契约 P10、P14）：每个业务一条流程，显示步骤摘要、版本、在途数；可启停、进入编辑。
// 需要 OpsKit 产品权限 process:manage（由 ai-shop 角色映射授予，契约 §3.3）。
import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { EmptyState, ErrorState } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { StatusBadge } from "@/components/ui/status-badge";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { useI18n } from "@/lib/i18n";
import { useCopy } from "@/lib/use-copy";
import { explainError } from "@/app/approvals/_api/errors";
import type { TemplateSummary } from "@/app/approvals/_api/types";
import { QUERY_KEY, useOkRun, useOpsKit } from "@/app/approvals/_kit";
import { FLOWS_COPY } from "./copy";

export default function ApprovalFlowsPage() {
  const c = useCopy(FLOWS_COPY);
  const api = useOpsKit();
  const run = useOkRun();
  const { locale } = useI18n();
  const { confirm, dialog } = useConfirm();
  const me = useQuery({ queryKey: [QUERY_KEY, "me"], queryFn: () => api.me(), staleTime: 60_000 });
  const manage = (me.data?.permissions ?? []).some((p) => p === "process:manage" || p === "*");
  const q = useQuery({ queryKey: [QUERY_KEY, "templates"], queryFn: () => api.templates(), enabled: manage });
  const enabledMap = { true: { label: c.enabled, tone: "success" as const }, false: { label: c.disabled, tone: "muted" as const } };
  const toggle = (t: TemplateSummary) => t.enabled
    ? confirm({ title: c.disableTitle.replace("{name}", t.name ?? t.code), danger: true, action: () => run(() => api.setEnabled(t.code, false)) })
    : run(() => api.setEnabled(t.code, true));
  const columns: Column<TemplateSummary>[] = [
    { header: c.colName, cell: (t) => <Link className="text-primary-ink hover:underline" href={`/approval-flows/detail?code=${encodeURIComponent(t.code)}`}>{t.name ?? t.code}</Link> },
    { header: c.colCode, cell: (t) => <code className="txt-caption">{t.code}</code> },
    { header: c.colSteps, cell: (t) => t.stepsSummary ?? "—" },
    { header: c.colVersion, cell: (t) => t.version ?? "—" },
    { header: c.colRunning, numeric: true, cell: (t) => t.running ?? 0 },
    { header: c.colEnabled, cell: (t) => <StatusBadge value={String(t.enabled) as "true" | "false"} map={enabledMap} /> },
    { header: "", cell: (t) => <Button size="sm" variant="ghost" onClick={() => void toggle(t)}>{t.enabled ? c.disable : c.enable}</Button> },
  ];
  return (
    <div>
      <TabHeader tabs={[{ key: "flows", label: c.title }]} value="flows" onChange={() => {}} desc={c.desc} />
      {me.error ? <ErrorState error={new Error(explainError(me.error, locale))} onRetry={() => void me.refetch()} />
        : me.data && !manage ? <EmptyState title={c.noPermission} />
        : q.error ? <ErrorState error={new Error(explainError(q.error, locale))} onRetry={() => void q.refetch()} />
        : <DataTable rows={q.data} loading={me.isPending || q.isPending} error={q.error} onRetry={() => void q.refetch()} columns={columns} rowKey={(t) => t.code} empty={c.empty} />}
      {dialog}
    </div>
  );
}
