"use client";

import { useRef, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api, IS_MOCK } from "@/lib/api";
import { usePointsText } from "@/lib/points-i18n";
import { usePaging } from "@/lib/use-paging";
import { printOperationResult } from "@/lib/print-operation-result";
import { pointsCommand } from "@/lib/points-command";
import type { PrintScope, PrintReconcile } from "@/lib/api/inspection-types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import { DataTable } from "@/components/ui/data-table";

export function PrintMonitor({ canResolve }: { canResolve: boolean }) {
  const { t } = usePointsText();
  const [scope, setScope] = useState<PrintScope | null>(null);
  return <div className="space-y-4">
    <Notice>{t("按门店查看打印状态与操作记录，需要启用打印服务。", "View print status and operation records by store. Printing service must be enabled.")}</Notice>
    {IS_MOCK && <Notice tone="warning">{t("演示没有打印监控事实，对账不可执行。", "The demo has no print monitoring facts. Reconciliation is unavailable.")}</Notice>}
    <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); const d = new FormData(e.currentTarget); setScope({ tenantNo: String(d.get("tenantNo")).trim(), entityNo: String(d.get("entityNo")).trim(), storeNo: String(d.get("storeNo")).trim() }); }}>
      <label>{t("租户编号", "Tenant ID")}<Input name="tenantNo" required pattern=".*\S.*" /></label><label>{t("主体编号", "Entity ID")}<Input name="entityNo" required pattern=".*\S.*" /></label><label>{t("门店编号", "Store ID")}<Input name="storeNo" required pattern=".*\S.*" /></label><Button>{t("查询门店", "Inspect store")}</Button>
    </form>
    {scope && <ScopedMonitor key={JSON.stringify(scope)} scope={scope} canResolve={canResolve} />}
  </div>;
}
export function ScopedMonitor({ scope, canResolve }: { scope: PrintScope; canResolve: boolean }) {
  const { t } = usePointsText();
  const { page, setPage, size } = usePaging(20);
  const [status, setStatus] = useState("");
  const [operationNo, setOperationNo] = useState("");
  const [formError, setFormError] = useState("");
  const health = useQuery({ queryKey: ["print-health", scope], queryFn: () => api.printHealth(scope), retry: false });
  const incidents = useQuery({ queryKey: ["print-incidents", scope, status, page, size], queryFn: () => api.printIncidents({ ...scope, status, page, size }), retry: false });
  const operation = useQuery({ queryKey: ["print-operation", scope, operationNo], queryFn: () => api.printOperation(operationNo, scope), enabled: !!operationNo, retry: false });
  const intent = useRef<{ body: string; requestId: string } | undefined>(undefined);
  const reconcile = useMutation({ mutationFn: async (value: PrintReconcile) => {
    const next = pointsCommand(value, intent.current);
    intent.current = next.intent;
    return api.reconcilePrint(value, next.intent.requestId);
  }, onSuccess: (result) => { intent.current = undefined; setOperationNo(result.operationNo); void health.refetch(); void incidents.refetch(); } });
  const error = health.error || incidents.error || operation.error || reconcile.error;
  const result = printOperationResult(operationNo, operation.data, reconcile.data);
  return <div className="space-y-4">
    {formError && <Notice tone="warning">{formError}</Notice>}
    {error && <Notice tone="warning" className="flex flex-wrap items-center gap-3"><span className="min-w-0 break-words">{error.message}</span><Button onClick={() => { void health.refetch(); void incidents.refetch(); if (operationNo) void operation.refetch(); }}>{t("重试查询", "Retry reads")}</Button></Notice>}
    {(health.isFetching || incidents.isFetching || operation.isFetching) && <p>{t("加载中…", "Loading…")}</p>}
    <section className="space-y-3 rounded-card border border-border p-4"><h2 className="txt-heading">{t("门店打印健康", "Store print health")}</h2>
      {<><p>{t("快照时间", "Snapshot time")}: {health.data?.asOf ?? "—"}</p><DataTable error={health.error} onRetry={() => void health.refetch()} loading={health.isFetching} rows={(health.data?.stores ?? []).map((r, key) => ({ ...r, key }))} rowKey={(r) => String(r.key)} empty={t("无健康快照。", "No health snapshots.")} columns={[
        { header: t("活跃打印机", "Active printers"), cell: (r) => r.activePrinters }, { header: t("待处理任务", "Pending jobs"), cell: (r) => r.pendingJobs }, { header: t("未知任务", "Unknown jobs"), cell: (r) => r.unknownJobs }, { header: t("阻塞请求", "Blocked requests"), cell: (r) => r.blockedRequests },
      ]} /></>}
    </section>
    <section className="space-y-3 rounded-card border border-border p-4"><h2 className="txt-heading">{t("打印事件", "Print incidents")}</h2>
      <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); setStatus(String(new FormData(e.currentTarget).get("status")).trim()); setPage(1); }}><label>{t("状态（可选）", "Status (optional)")}<Input name="status" /></label><Button>{t("筛选", "Filter")}</Button></form>
      {<><DataTable error={incidents.error} onRetry={() => void incidents.refetch()} loading={incidents.isFetching} rows={incidents.data?.records ?? []} rowKey={(r) => r.incidentNo} empty={t("无匹配事件。", "No matching incidents.")} columns={[
        { header: t("事件 / 对象", "Incident / target"), cell: (r) => `${r.incidentNo} · ${r.targetType}/${r.targetNo}` }, { header: t("类型 / 级别", "Type / severity"), cell: (r) => `${r.incidentType} / ${r.severity}` }, { header: t("状态 / 次数", "Status / occurrences"), cell: (r) => `${r.status} / ${r.occurrenceCount}` }, { header: t("最近发生", "Last seen"), cell: (r) => r.lastSeenAt },
      ]} /><div className="flex flex-wrap items-center gap-3"><Button disabled={page <= 1} onClick={() => setPage(page - 1)}>{t("上一页", "Previous page")}</Button><span>{page} · {incidents.data?.total ?? "—"}</span><Button disabled={!incidents.data || page * size >= incidents.data.total || incidents.isFetching} onClick={() => setPage(page + 1)}>{t("下一页", "Next page")}</Button></div></>}
    </section>
    <section className="space-y-3 rounded-card border border-border p-4"><h2 className="txt-heading">{t("操作记录", "Operation record")}</h2>
      <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); setOperationNo(String(new FormData(e.currentTarget).get("operationNo")).trim()); }}><label>{t("原操作编号", "Original operation ID")}<Input name="operationNo" required pattern=".*\S.*" /></label><Button>{t("查询操作", "Inspect operation")}</Button></form>
      {result && <><p>{result.operationNo} · {result.type} · {result.status} · {result.targetType}/{result.targetNo}</p><p>{result.createdAt} / {result.finishedAt ?? "—"}</p>{result.error && <Notice tone="warning">{result.error.key} · {result.error.traceNo} · {result.error.retryable ? t("可重试", "Retryable") : t("不可重试", "Not retryable")}</Notice>}{result.result && <pre className="whitespace-pre-wrap break-all txt-caption">{result.result.canonicalJson}</pre>}</>}
    </section>
    {canResolve && <section className="space-y-3 rounded-card border border-border p-4"><h2 className="txt-heading">{t("显式打印对账", "Explicit print reconciliation")}</h2>
      <Notice>{t("默认预演。关闭预演会提交真实对账；请核对目标、来源与时间范围。未收到结果时，原样重试会继续同一次操作。", "Dry run is the default. Disabling it submits a real reconciliation; check the target, source and time range. If no result arrives, retrying unchanged continues the same operation.")}</Notice>
      <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); if (!canResolve || IS_MOCK || reconcile.isPending) return; const d = new FormData(e.currentTarget); const reason = String(d.get("reason")).trim(); const sourceKind = String(d.get("sourceKind")).trim(); const from = new Date(String(d.get("from"))).toISOString(); const to = new Date(String(d.get("to"))).toISOString(); if (!reason || !sourceKind || from > to) { setFormError(t("请填写原因和来源，且开始时间不能晚于结束时间。", "Enter a reason and source; from time must not follow to time.")); return; } setFormError(""); reconcile.mutate({ ...scope, sourceKind, from, to, dryRun: d.get("dryRun") === "on", reason }); }}>
        <label>{t("来源类型代码", "Source kind code")}<Input name="sourceKind" defaultValue="SUB_ORDER" required pattern=".*\S.*" /></label><label>{t("开始时间（本地）", "From (local time)")}<Input name="from" type="datetime-local" required /></label><label>{t("结束时间（本地）", "To (local time)")}<Input name="to" type="datetime-local" required /></label><label>{t("原因", "Reason")}<Input name="reason" required pattern=".*\S.*" /></label><label className="inline-flex items-center gap-2"><input className="focus-ring" name="dryRun" type="checkbox" defaultChecked />{t("预演", "Dry run")}</label><Button disabled={IS_MOCK || reconcile.isPending}>{reconcile.isPending ? t("提交中…", "Submitting…") : t("提交对账", "Submit reconciliation")}</Button>
      </form>
    </section>}
  </div>;
}
