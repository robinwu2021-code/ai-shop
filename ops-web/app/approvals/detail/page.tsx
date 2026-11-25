"use client";

// 审批详情（TDD-OpsKit审批对接 §1.5，契约 P5–P9）：申请内容（改前 → 改后）、流程进度（审批段与执行节点）、审批步骤、记录与流转。
// 按钮只看 OpsKit 给的结论：mine.canDecide / canWithdraw；不能审批时显示 whyNot（错误码 → 文案）。
// 执行节点失败后的「重试 / 放弃」按 pipeline[].actions 出按钮，点击走实例迁移。通过不必填意见，驳回必填。
import * as React from "react";
import { Suspense } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { DataTable } from "@/components/ui/data-table";
import { DetailBody } from "@/components/ui/detail-drawer";
import { EmptyState, ErrorState, Skeleton } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { useI18n } from "@/lib/i18n";
import { fmtTime } from "@/lib/utils";
import { explainError, explainKey } from "../_api/errors";
import type { ApprovalView, Fact, HistoryEntry, Round, Segment, Step } from "../_api/types";
import { QUERY_KEY, displayTitle, sourceHref, useAc, useApprovalStatusMap, useOkRun, useOpsKit } from "../_kit";

export default function ApprovalDetailPage() {
  return <Suspense><ApprovalDetail /></Suspense>;
}

function ApprovalDetail() {
  const c = useAc();
  const api = useOpsKit();
  const { locale } = useI18n();
  const params = useSearchParams();
  const no = params.get("no") ?? "";
  const q = useQuery({ queryKey: [QUERY_KEY, "approval", no], queryFn: () => api.approval(no), enabled: !!no });
  const back = <Link href="/approvals" className="txt-caption focus-ring text-primary-ink hover:underline">{c.back}</Link>;
  if (!no) return <div><TabHeader tabs={[{ key: "detail", label: c.detailTitle }]} value="detail" onChange={() => {}} action={back} /><EmptyState title={c.noApproval} /></div>;
  if (q.isPending) return <div className="space-y-3"><Skeleton className="h-6 w-1/3" /><Skeleton className="h-40 w-full" /></div>;
  if (q.error) return <ErrorState error={new Error(explainError(q.error, locale))} onRetry={() => void q.refetch()} />;
  const a = q.data;
  return (
    <div>
      <TabHeader tabs={[{ key: "detail", label: displayTitle(a) }]} value="detail" onChange={() => {}} desc={a.process?.name ?? a.process?.code} action={back} />
      <Detail a={a} reload={() => void q.refetch()} />
    </div>
  );
}

/** @param reload 父页重取：步骤 / 轮次表的行来自已到手的 a，出错已由父页的 ErrorState 兜住，这里只给「重试」 */
function Detail({ a, reload }: { a: ApprovalView; reload: () => void }) {
  const c = useAc();
  const api = useOpsKit();
  const run = useOkRun();
  const { locale } = useI18n();
  const { confirm, dialog } = useConfirm();
  const statusMap = useApprovalStatusMap();
  const segMap: StatusMap<string> = {
    DONE: { label: c.seg_DONE, tone: "success" }, CURRENT: { label: c.seg_CURRENT, tone: "info" }, TODO: { label: c.seg_TODO, tone: "muted" },
    FAILED: { label: c.seg_FAILED, tone: "danger" }, REJECTED: { label: c.seg_REJECTED, tone: "danger" },
    ABANDONED: { label: c.seg_ABANDONED, tone: "muted" }, CANCELLED: { label: c.seg_CANCELLED, tone: "muted" }, SKIPPED: { label: c.seg_SKIPPED, tone: "muted" },
  };
  const history = useQuery({ queryKey: [QUERY_KEY, "history", a.instanceNo], queryFn: () => api.history(a.instanceNo!), enabled: !!a.instanceNo });
  const mine = a.mine;
  const source = sourceHref(a.subject);

  const decide = (decision: "APPROVE" | "REJECT") => confirm(decision === "APPROVE"
    ? { title: c.approveTitle, action: () => run(() => api.decide(a.approvalNo, { taskNo: mine!.taskNo!, decision })) }
    : { title: c.rejectTitle, danger: true, requireReason: true, reasonHint: c.rejectHint,
        action: (reason) => run(() => api.decide(a.approvalNo, { taskNo: mine!.taskNo!, decision, reason })) });
  const withdraw = () => confirm({ title: c.withdrawTitle, danger: true, action: () => run(() => api.withdraw(a.approvalNo)) });
  const transition = (code: string, name: string, requiresComment?: boolean) => confirm({
    title: c.transitionTitle.replace("{name}", name), requireReason: !!requiresComment, reasonHint: c.transitionHint,
    action: (reason) => run(() => api.transition(a.instanceNo!, code, reason || undefined)),
  });
  const segmentActions = (a.pipeline ?? []).flatMap((s) => s.actions ?? []);

  const actions = (
    <div className="flex flex-wrap items-center gap-2">
      {mine?.canDecide && mine.taskNo && <>
        <Button onClick={() => void decide("APPROVE")}>{c.approve}</Button>
        <Button variant="outline" onClick={() => void decide("REJECT")}>{c.reject}</Button>
      </>}
      {mine?.canWithdraw && <Button variant="ghost" onClick={() => void withdraw()}>{c.withdraw}</Button>}
      {segmentActions.map((x) => <Button key={x.code} variant="outline" onClick={() => void transition(x.code, x.name ?? x.code, x.requiresComment)}>{x.name ?? x.code}</Button>)}
      {!mine?.canDecide && mine?.whyNot && a.status === "PENDING" && (
        <span className="txt-caption text-muted-foreground">{c.cannotDecide}{explainKey(mine.whyNot, locale) ?? mine.whyNot}</span>
      )}
    </div>
  );

  return (
    <>
      <DetailBody
        sections={[
          { title: c.sectionBasic, fields: [
            { label: c.colApprovalNo, value: a.approvalNo },
            { label: c.colStatus, value: <StatusBadge value={a.status} map={statusMap} /> },
            { label: c.subject, value: <span className="inline-flex gap-2">{a.subject?.display?.title ?? a.subject?.ref}{source && <Link className="focus-ring text-primary-ink hover:underline" href={source}>{c.openSource}</Link>}</span>, full: true },
            { label: c.colStep, value: a.currentStep?.name ?? "—" },
            { label: c.colRequester, value: a.requestedBy?.displayName ?? a.requestedBy?.ref ?? "—" },
            { label: c.colRequestedAt, value: fmtTime(a.requestedAtMs) },
            { label: c.colDue, value: fmtTime(a.stepDueAtMs) },
          ] },
          { title: c.sectionFacts, content: <Facts facts={a.facts} /> },
          ...(a.pipeline?.length ? [{ title: c.sectionPipeline, content: <Pipeline segments={a.pipeline} map={segMap} /> }] : []),
          { title: c.sectionSteps, content: <DataTable compact rows={a.steps ?? [] as Step[]} error={null} onRetry={reload} rowKey={(s) => s.code} empty={c.emptySteps} columns={[
            { header: c.colStep, cell: (s) => s.name ?? s.code },
            { header: c.approver, cell: (s) => s.approverText ?? "—" },
            { header: c.condition, cell: (s) => s.conditionText ?? "—" },
            { header: c.colStatus, cell: (s) => <StatusBadge value={s.state} map={segMap} /> },
            { header: c.decidedBy, cell: (s) => s.decidedBy ?? "—" },
            { header: c.decidedAt, cell: (s) => fmtTime(s.decidedAtMs) },
          ]} /> },
          { title: c.sectionRounds, content: <DataTable compact rows={a.rounds ?? []} error={null} onRetry={reload} rowKey={(r) => `${r.round}:${r.atMs}`} empty={c.emptyRounds} columns={[
            { header: "#", cell: (r) => r.round },
            { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
            { header: c.decidedBy, cell: (r) => r.by ?? "—" },
            { header: c.comment, cell: (r) => r.comment ?? "—" },
            { header: c.decidedAt, cell: (r) => fmtTime(r.atMs) },
          ]} /> },
          { title: c.sectionHistory, content: <DataTable compact rows={history.data ?? []} loading={history.isPending && !!a.instanceNo} error={history.error} onRetry={() => void history.refetch()}
            rowKey={(h) => String(h.seq ?? h.atMs)} empty={c.emptyHistory} columns={[
              { header: c.decidedAt, cell: (h) => fmtTime(h.atMs) },
              { header: c.colStep, cell: (h) => h.actionName ?? h.action ?? "—" },
              { header: c.colStatus, cell: (h) => `${h.fromName ?? h.from ?? ""} → ${h.toName ?? h.to ?? ""}` },
              { header: c.decidedBy, cell: (h) => h.actorText ?? (typeof h.actor === "string" ? h.actor : h.actor?.displayName ?? h.actor?.ref) ?? "—" },
              { header: c.comment, cell: (h) => h.reason ?? "—" },
            ]} /> },
        ]}
        actions={actions}
      />
      {dialog}
    </>
  );
}

/** 申请内容：有 before 的显示「改前 → 改后」。 */
function Facts({ facts }: { facts?: Fact[] }) {
  const c = useAc();
  if (!facts?.length) return <p className="txt-caption text-muted-foreground">{c.noFacts}</p>;
  return (
    <dl className="grid grid-cols-[minmax(6rem,auto)_1fr] gap-x-4 gap-y-2">
      {facts.map((f, i) => (
        <React.Fragment key={`${f.label}:${i}`}>
          <dt className="txt-caption text-muted-foreground">{f.label}</dt>
          <dd>
            {f.before !== undefined && f.before !== null
              ? <span><span className="text-muted-foreground line-through" title={c.before}>{f.before || "—"}</span>{" → "}<span className="font-medium" title={c.after}>{f.value || "—"}</span></span>
              : f.value ?? "—"}
            {f.hint && <span className="ml-2 txt-caption text-muted-foreground">{f.hint}</span>}
          </dd>
        </React.Fragment>
      ))}
    </dl>
  );
}

/** 流程进度：审批段与执行节点依次排开，失败的给出原因。 */
function Pipeline({ segments, map }: { segments: Segment[]; map: StatusMap<string> }) {
  const c = useAc();
  return (
    <ol className="flex flex-wrap items-center gap-2">
      {segments.map((s, i) => (
        <li key={`${s.code}:${i}`} className="flex items-center gap-2">
          {i > 0 && <span className="text-muted-foreground">→</span>}
          <span className="rounded-field border px-2 py-1">
            <span className="txt-caption text-muted-foreground">{s.kind === "ACTION" ? c.kindAction : c.kindApproval}</span>{" "}
            <span>{s.name ?? s.code}</span>{" "}
            <StatusBadge value={s.state} map={map} />
            {s.error?.code && <span className="ml-1 txt-caption text-destructive">{s.error.message ?? s.error.code}</span>}
          </span>
        </li>
      ))}
    </ol>
  );
}
