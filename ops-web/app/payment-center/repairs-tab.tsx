"use client";

// 来源关联修复（P8，near-pay OpsSourceAssociationRepairController）。页面级：申请修复（建单自动提交审批）；
// 行操作：查看 · 通过 / 驳回（审批权限；expectedVersion = 审批版本；申请人不能自审）· 执行（审批通过后；expectedVersion = 修复单版本）。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import { Notice } from "@/components/ui/notice";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell, useDraftOperator } from "@/app/payment-center/_kit/pay-kit";
import { REPAIR_STATUSES, type RepairRow } from "@/app/payment-center/_api/types";
import { fmtTime } from "@/lib/utils";
import { useCopy, fill } from "@/lib/use-copy";
import { DetailLoader, csv, joinCsv, reasonField, usePc, useRun, useTabList } from "./shared";
import { P8_COPY } from "./p8-copy";
import { p8Map } from "./members-tab";

const NAME = "association-repairs";
const snapshot = (v: Record<string, unknown> | null) => (v ? <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-all txt-caption">{JSON.stringify(v, null, 2)}</pre> : "—");

export function RepairsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const m = useCopy(P8_COPY);
  const operator = useDraftOperator();
  const run = useRun(NAME);
  const statusMap = React.useMemo(() => p8Map(m, "st_repair", REPAIR_STATUSES, { PENDING_APPROVAL: "warning", APPROVED: "info", EXECUTED: "success", REJECTED: "danger" }), [m]);
  const list = useTabList<RepairRow, { status: string }>(NAME, { status: "" }, (api, q) => api.repairs.list(q));
  const { api } = list;
  const st = (s: string) => (r: RepairRow | null) => !!r && r.status === s;
  const decide = (action: "approve" | "reject" | "execute") => (r: RepairRow | null, reason: string) =>
    run(() => api.repairs.decide(r!.repairNo, action, { expectedVersion: action === "execute" ? r!.version : r!.approvalVersion ?? 0, reason }));

  const actions: ActionSpec<RepairRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => m.repairDetailTitle,
      render: (ctx) => (
        <DetailLoader name={NAME} id={ctx.row!.repairNo} load={() => api.repairs.detail(ctx.row!.repairNo)}>
          {(d) => {
            const x = d.record;
            return <DetailBody sections={[
              { title: c.sectionBasic, fields: [
                { label: m.repairNo, value: <PayRefCell value={x.repairNo} /> },
                { label: c.colStatus, value: <StatusBadge value={x.status} map={statusMap} /> },
                { label: m.merchantNo, value: x.merchantNo },
                { label: m.linkNo, value: x.linkNo },
                { label: m.source, value: `${x.sourceSystem} · ${x.sourceScope}` },
                { label: m.linkVersion, value: x.expectedVersion },
                { label: m.fromEntity, value: x.fromOperatingEntityId },
                { label: m.toEntity, value: x.toOperatingEntityId },
                { label: m.storeNos, value: joinCsv(x.storeNos) || "—", full: true },
                { label: m.evidence, value: x.evidence, full: true },
                { label: m.approval, value: x.approvalNo ? `${x.approvalNo} · ${x.approvalStatus ?? ""}` : "—" },
                { label: m.requestedBy, value: x.requestedBy },
                { label: m.createdAt, value: fmtTime(x.createdAt) },
                { label: m.executedBy, value: x.executedBy ? `${x.executedBy} · ${fmtTime(x.executedAt)}` : "—" },
              ] },
              { title: m.beforeSnapshot, content: snapshot(x.beforeSnapshot) },
              { title: m.afterSnapshot, content: snapshot(x.afterSnapshot) },
            ]} actions={<DetailActions ctx={ctx} keys={d.allowedActions} />} />;
          }}
        </DetailLoader>
      ),
    },
    { key: "approve", label: m.repairApprove, kind: "dialog", perm: "pay:merchant:repair:approve", visible: st("PENDING_APPROVAL"),
      confirm: (r) => ({ title: fill(m.repairApproveTitle, { no: r?.repairNo ?? "" }), desc: m.repairApproveDesc, reasonHint: c.reasonHint }), run: decide("approve") },
    { key: "reject", label: m.repairReject, kind: "dialog", perm: "pay:merchant:repair:approve", danger: true, visible: st("PENDING_APPROVAL"),
      confirm: (r) => ({ title: fill(m.repairRejectTitle, { no: r?.repairNo ?? "" }), danger: true, reasonHint: c.reasonHint }), run: decide("reject") },
    { key: "execute", label: m.repairExecute, kind: "dialog", perm: "pay:merchant:repair:approve", danger: true, visible: st("APPROVED"),
      confirm: (r) => ({ title: fill(m.repairExecuteTitle, { no: r?.repairNo ?? "" }), desc: m.repairExecuteDesc, danger: true, requireText: r?.repairNo, reasonHint: c.reasonHint }), run: decide("execute") },
  ];

  const pageActions: ActionSpec<RepairRow>[] = [{
    key: "create", label: m.repairCreate, kind: "drawer", perm: "pay:merchant:repair", width: "w-[600px]", title: () => m.repairCreateTitle,
    render: (ctx) => (
      <>
        <Notice tone="info" className="mb-4">{m.repairsDesc}</Notice>
        <ActionForm notify={false}
          fields={[
            { key: "merchantNo", label: m.merchantNo, required: true, maxLength: 64, pattern: { re: "^[A-Za-z0-9_-]{1,64}$", msg: "A-Z a-z 0-9 _ -" } },
            { key: "fromOperatingEntityId", label: m.fromEntity, required: true, maxLength: 64, pattern: { re: "^[A-Za-z0-9_-]{1,64}$", msg: "A-Z a-z 0-9 _ -" } },
            { key: "toOperatingEntityId", label: m.toEntity, required: true, maxLength: 64, pattern: { re: "^[A-Za-z0-9_-]{1,64}$", msg: "A-Z a-z 0-9 _ -" } },
            { key: "expectedVersion", label: m.linkVersion, type: "number", required: true, min: 0, help: m.linkVersionHelp },
            { key: "storeNos", label: m.storeNos, help: m.storeNosHelp },
            { key: "evidence", label: m.evidence, type: "textarea", required: true, maxLength: 500, help: m.evidenceHelp },
          ]}
          initial={{ merchantNo: "", fromOperatingEntityId: "", toOperatingEntityId: "", expectedVersion: 0, storeNos: "", evidence: "" }}
          draft={operator ? { operator, page: "pay-repairs", action: "create" } : null}
          onSubmit={(v) => run(() => api.repairs.create({
            merchantNo: String(v.merchantNo).trim(), fromOperatingEntityId: String(v.fromOperatingEntityId).trim(), toOperatingEntityId: String(v.toOperatingEntityId).trim(),
            storeNos: csv(v.storeNos), evidence: String(v.evidence).trim(), expectedVersion: Number(v.expectedVersion),
          }))}
          onDone={ctx.close} />
      </>
    ),
  }];

  const columns: Column<RepairRow>[] = [
    { header: m.repairNo, cell: (r) => <PayRefCell value={r.repairNo} /> },
    { header: m.merchantNo, cell: (r) => r.merchantNo },
    { header: `${m.fromEntity} → ${m.toEntity}`, cell: (r) => `${r.fromOperatingEntityId} → ${r.toOperatingEntityId}` },
    { header: m.storeNos, cell: (r) => r.storeNos.length },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: m.requestedBy, cell: (r) => r.requestedBy },
    { header: m.createdAt, cell: (r) => fmtTime(r.createdAt) },
  ];

  return (
    <PayListPage<RepairRow>
      header={header}
      help={m.repairsDesc}
      filters={<FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => r.repairNo}
      actions={actions}
      pageActions={pageActions}
      resolve={(id) => api.repairs.detail(id).then((d) => d.record)}
      draftPage="pay-repairs"
      empty={m.repairsEmpty}
    />
  );
}
