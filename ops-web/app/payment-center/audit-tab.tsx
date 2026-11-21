"use client";

// 审计事件（P10，near-pay OpsAuditController，只读）。筛选按资源类型与动作，搜索按资源编号；查看里给出前后摘要（已脱敏）。
import * as React from "react";
import { DetailBody } from "@/components/ui/detail-drawer";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell } from "@/app/payment-center/_kit/pay-kit";
import type { AuditEventRow } from "@/app/payment-center/_api/types";
import { fmtTime } from "@/lib/utils";
import { useCopy } from "@/lib/use-copy";
import { DetailLoader, joinCsv, usePc, useTabList } from "./shared";
import { P10_COPY } from "./p10-copy";
import { prettyJson } from "./p9-panels";

const NAME = "audit-events";

export function AuditTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const m = useCopy(P10_COPY);
  const list = useTabList<AuditEventRow, Record<string, never>>(NAME, {}, (api, q) => api.audits.list({ ...q, keyword: undefined, resourceNo: q.keyword }));
  const { api } = list;
  const json = (s: string | null) => <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-all txt-caption">{prettyJson(s)}</pre>;

  const actions: ActionSpec<AuditEventRow>[] = [{
    key: "view", label: c.view, kind: "drawer", width: "w-[640px]", title: () => m.auditDetailTitle,
    render: (ctx) => (
      <DetailLoader name={NAME} id={ctx.row!.auditNo} load={() => api.audits.detail(ctx.row!.auditNo)}>
        {(d) => {
          const a = d.record;
          return <DetailBody sections={[
            { title: c.sectionBasic, fields: [
              { label: m.auditNo, value: <PayRefCell value={a.auditNo} /> },
              { label: m.occurredAt, value: fmtTime(a.occurredAt) },
              { label: m.operatorNo, value: a.operatorNo },
              { label: m.roles, value: joinCsv(a.roleNos) },
              { label: m.face, value: a.face },
              { label: m.resourceType, value: a.resourceType },
              { label: m.resourceNo, value: a.resourceNo },
              { label: m.action, value: a.action },
              { label: m.result, value: a.result },
              { label: m.approvalRef, value: a.approvalRef ?? "—" },
              { label: m.requestId, value: a.requestId },
              { label: m.reason, value: a.reason ?? "—", full: true },
            ] },
            { title: m.beforeSummary, content: json(a.beforeSummary) },
            { title: m.afterSummary, content: json(a.afterSummary) },
          ]} />;
        }}
      </DetailLoader>
    ),
  }];

  const columns: Column<AuditEventRow>[] = [
    { header: m.auditNo, cell: (r) => <PayRefCell value={r.auditNo} /> },
    { header: m.occurredAt, cell: (r) => fmtTime(r.occurredAt) },
    { header: m.operatorNo, cell: (r) => r.operatorNo },
    { header: m.resourceType, cell: (r) => r.resourceType },
    { header: m.resourceNo, cell: (r) => r.resourceNo },
    { header: m.action, cell: (r) => r.action },
    { header: m.result, cell: (r) => r.result },
  ];

  return (
    <PayListPage<AuditEventRow>
      header={header}
      help={m.auditDesc}
      search={list.keyword}
      onSearch={list.setKeyword}
      searchPlaceholder={m.auditSearch}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => r.auditNo}
      actions={actions}
      empty={m.auditEmpty}
    />
  );
}
