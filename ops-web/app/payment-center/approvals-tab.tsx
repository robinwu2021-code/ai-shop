"use client";

// 审核待办（SY01）：支付端唯一的审批出口（拆分规则 5）。
// 行操作：查看并审批（详情：变更内容、审批节点、审批意见）· 认领 / 释放（确认）· 同意 / 驳回（理由弹框）
// · 指派（抽屉：审批人 + 原因）· 撤回（申请人，理由弹框）· 执行（已通过，理由弹框）。
// 任务类命令要用当前任务号与任务版本 —— 列表行里没有，执行前现取详情（多一次读，换来不会拿旧版本去撞 409）。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell, useDraftOperator } from "@/app/payment-center/_kit/pay-kit";
import { APPROVAL_STATUSES, APPROVAL_VIEWS, type ApprovalRow, type ApprovalTask } from "@/app/payment-center/_api/types";
import { fmtTime } from "@/lib/utils";
import { DetailLoader, MiniTable, reasonField, usePayOperatorNo, useRun, useStatusMap, useTabList, usePc, yesNo } from "./shared";

const NAME = "approvals";
/** 详情的 allowedActions → 底栏按钮（review 拆成同意 / 驳回两个） */
const DETAIL_KEYS: Record<string, string[]> = { claim: ["claim"], review: ["reject", "approve"], release: ["release"], assign: ["assign"], withdraw: ["withdraw"], execute: ["execute"] };

export function ApprovalsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const me = usePayOperatorNo();
  const operator = useDraftOperator();
  const run = useRun(NAME);
  const statusMap = useStatusMap("ap", APPROVAL_STATUSES, { APPROVED: "success", REJECTED: "danger", PENDING_FIRST: "info", PENDING_SECOND: "info" });
  const execMap = useStatusMap("exec", ["NOT_STARTED", "SUCCEEDED", "FAILED", "NOT_APPLICABLE"], { SUCCEEDED: "success", FAILED: "danger" });
  const taskMap = useStatusMap("task", ["READY", "CLAIMED", "DONE"]);
  const viewMap = Object.fromEntries(APPROVAL_VIEWS.filter((v) => v !== "CANDIDATE").map((v) => [v, { label: (c as Record<string, string>)[`view_${v}`], tone: "muted" as const }]));
  const list = useTabList<ApprovalRow, { view: string; status: string }>(NAME, { view: "", status: "" }, (api, q) => api.approvals.list({ ...q, view: (q.view as string) || "CANDIDATE" } as never));
  const { api } = list;
  const pending = (r: ApprovalRow | null) => !!r && r.status.startsWith("PENDING");
  /** 现取当前任务（号与版本） */
  const taskOf = async (r: ApprovalRow): Promise<ApprovalTask> => {
    const d = await api.approvals.detail(r.approvalNo);
    if (!d.record.activeTask) throw new Error(c.versionChanged);
    return d.record.activeTask;
  };
  const review = (decision: "APPROVE" | "REJECT") => async (r: ApprovalRow | null, reason: string) => run(async () => {
    const d = await api.approvals.detail(r!.approvalNo);
    return api.approvals.review(r!.approvalNo, { expectedVersion: d.record.version, decision, reason, materialVersion: d.record.materialVersion });
  });

  const actions: ActionSpec<ApprovalRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => c.approvalDetailTitle,
      render: (ctx) => (
        <DetailLoader name={NAME} id={ctx.row!.approvalNo} load={() => api.approvals.detail(ctx.row!.approvalNo)}>
          {(d) => {
            const a = d.record;
            return (
              <DetailBody
                sections={[
                  { title: c.sectionBasic, fields: [
                    { label: c.approvalNo, value: <PayRefCell value={a.approvalNo} /> },
                    { label: c.colStatus, value: <StatusBadge value={a.status} map={statusMap} /> },
                    { label: c.subject, value: d.subjectTitle ?? a.subjectRef, full: true },
                    { label: c.bizType, value: `${a.subjectType} · ${a.action}` },
                    { label: c.applicant, value: a.applicantNo },
                    { label: c.assignee, value: a.activeTask?.assigneeNo },
                    { label: c.expiresAt, value: fmtTime(a.expiresAt) },
                    { label: c.executionStatus, value: a.execution ? <StatusBadge value={a.execution.status} map={execMap} /> : "—" },
                    { label: c.colCreated, value: fmtTime(a.createdAt) },
                  ] },
                  ...(d.snapshotJson ? [{ title: c.sectionSnapshot, content: <pre className="max-h-64 overflow-auto rounded-field bg-muted/50 p-3 txt-caption whitespace-pre-wrap">{prettyJson(d.snapshotJson)}</pre> }] : []),
                  { title: c.sectionTasks, content: <MiniTable<ApprovalTask> rows={d.tasks} rowKey={(r) => r.taskNo} empty={c.emptyTasks} columns={[
                    { header: "#", cell: (r) => r.nodeNo },
                    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={taskMap} /> },
                    { header: c.assignee, cell: (r) => r.assigneeNo ?? "—" },
                    { header: c.decision, cell: (r) => r.decision ?? "—" },
                  ]} /> },
                  { title: c.sectionDecisions, content: <MiniTable rows={d.decisions} rowKey={(r) => `${r.nodeNo}:${r.reviewerNo}`} empty={c.emptyDecisions} columns={[
                    { header: c.assignee, cell: (r) => r.reviewerNo },
                    { header: c.decision, cell: (r) => r.decision },
                    { header: c.reasonField, cell: (r) => r.reason ?? "—" },
                    { header: c.submittedAt, cell: (r) => fmtTime(r.decidedAt) },
                  ]} /> },
                ]}
                actions={<DetailActions ctx={ctx} keys={d.allowedActions.flatMap((k) => DETAIL_KEYS[k] ?? [])} />}
              />
            );
          }}
        </DetailLoader>
      ),
    },
    {
      key: "claim", label: c.claim, kind: "confirm", perm: "pay:approval:review", visible: (r) => pending(r) && !r!.assigneeNo,
      confirm: () => ({ title: c.claimTitle }),
      run: (r) => run(async () => { const t = await taskOf(r!); return api.approvals.claim(t.taskNo, { expectedVersion: t.version, reason: c.claimReason }); }),
    },
    {
      key: "approve", label: c.approve, kind: "dialog", perm: "pay:approval:review", visible: (r) => pending(r) && r!.assigneeNo === me,
      confirm: () => ({ title: c.approveTitle, reasonHint: c.reasonHint }),
      run: review("APPROVE"),
    },
    {
      key: "reject", label: c.reject, kind: "dialog", perm: "pay:approval:review", danger: true, visible: (r) => pending(r) && r!.assigneeNo === me,
      confirm: () => ({ title: c.rejectTitle, danger: true, reasonHint: c.reasonHint }),
      run: review("REJECT"),
    },
    {
      key: "release", label: c.release, kind: "confirm", visible: (r) => pending(r) && r!.assigneeNo === me,
      confirm: () => ({ title: c.releaseTitle }),
      run: (r) => run(async () => { const t = await taskOf(r!); return api.approvals.release(t.taskNo, { expectedVersion: t.version, reason: c.releaseReason }); }),
    },
    {
      key: "assign", label: c.assign, kind: "drawer", perm: "pay:approval:dispatch", visible: pending, title: () => c.assignTitle,
      render: (ctx) => (
        <ActionForm
          notify={false}
          fields={[{ key: "assigneeNo", label: c.assigneeNo, required: true, maxLength: 64 }, reasonField(c)]}
          initial={{ assigneeNo: "", reason: "" }}
          draft={operator ? { operator, page: "pay-approvals", action: "assign", id: ctx.row!.approvalNo } : null}
          onSubmit={(v) => run(async () => { const t = await taskOf(ctx.row!); return api.approvals.assign(t.taskNo, { expectedVersion: t.version, assigneeNo: String(v.assigneeNo).trim(), reason: String(v.reason) }); })}
          onDone={ctx.back ?? ctx.close}
        />
      ),
    },
    {
      key: "withdraw", label: c.withdraw, kind: "dialog", danger: true, visible: (r) => pending(r) && r!.applicantNo === me,
      confirm: () => ({ title: c.withdrawTitle, danger: true, reasonHint: c.reasonHint }),
      run: (r, reason) => run(async () => { const d = await api.approvals.detail(r!.approvalNo); return api.approvals.withdraw(r!.approvalNo, { expectedVersion: d.record.version, reason }); }),
    },
    {
      key: "execute", label: c.execute, kind: "dialog", visible: (r) => r?.status === "APPROVED" && r.executionStatus === "NOT_STARTED",
      confirm: () => ({ title: c.executeTitle, reasonHint: c.reasonHint }),
      run: (r, reason) => run(async () => { const d = await api.approvals.detail(r!.approvalNo); return api.approvals.execute(r!.approvalNo, { expectedVersion: d.record.version, reason }); }),
    },
  ];

  const columns: Column<ApprovalRow>[] = [
    { header: c.approvalNo, cell: (r) => <PayRefCell value={r.approvalNo} /> },
    { header: c.subject, cell: (r) => r.subjectTitle ?? r.bizType },
    { header: c.applicant, cell: (r) => r.applicantNo },
    { header: c.assignee, cell: (r) => r.assigneeNo ?? "—" },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: c.escalated, cell: (r) => yesNo(r.escalated, c) },
    { header: c.expiresAt, cell: (r) => fmtTime(r.expiresAt) },
  ];

  return (
    <PayListPage<ApprovalRow>
      header={header}
      help={c.approvalsDesc}
      filters={<>
        <FilterSelect value={list.filters.view} onChange={(v) => list.setFilter("view", v)} options={viewMap} allLabel={c.view_CANDIDATE} />
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />
      </>}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => r.approvalNo}
      actions={actions}
      empty={c.emptyApprovals}
      resolve={(id) => api.approvals.detail(id).then((d): ApprovalRow => ({
        approvalNo: d.record.approvalNo, bizType: d.record.subjectType, action: d.record.action, resourceNo: d.record.subjectRef, subjectTitle: d.subjectTitle,
        applicantNo: d.record.applicantNo, nodeNo: d.record.activeTask?.nodeNo ?? null, assigneeNo: d.record.activeTask?.assigneeNo ?? null,
        materialVersion: d.record.materialVersion, status: d.record.status, executionStatus: d.record.execution?.status ?? "NOT_STARTED",
        expiresAt: d.record.expiresAt, escalated: false, createdAt: d.record.createdAt, version: d.record.version,
      }))}
      draftPage="pay-approvals"
    />
  );
}

function prettyJson(s: string): string {
  try { return JSON.stringify(JSON.parse(s), null, 2); } catch { return s; }
}
