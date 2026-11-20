"use client";

// 平台设置 · 支付配置：审批流程（SY02，只读）与经营设置（OP01，单条记录也按「表格 + 行操作」呈现，编辑走抽屉）。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import { Notice } from "@/components/ui/notice";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell, useDraftOperator } from "@/app/payment-center/_kit/pay-kit";
import type { ApprovalFlowRow, OperatingSettings } from "@/app/payment-center/_api/types";
import { fmtTime } from "@/lib/utils";
import { DetailLoader, MiniTable, csv, joinCsv, reasonField, useRun, useStatusMap, useTabList, usePc, yesNo } from "./shared";

export function ApprovalFlowsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const statusMap = useStatusMap("flow", ["DRAFT", "PUBLISHED", "RETIRED"], { PUBLISHED: "success" });
  const list = useTabList<ApprovalFlowRow, Record<string, never>>("approval-flows", {}, (api, q) => api.approvalFlows.list(q));
  const { api } = list;
  const actions: ActionSpec<ApprovalFlowRow>[] = [{
    key: "view", label: c.view, kind: "drawer", width: "w-[560px]", title: () => c.flowDetailTitle,
    render: (ctx) => (
      <DetailLoader name="approval-flows" id={ctx.row!.flowNo} load={() => api.approvalFlows.detail(ctx.row!.flowNo)}>
        {(d) => {
          const f = d.record;
          return <DetailBody sections={[
            { title: c.sectionBasic, fields: [
              { label: c.flowNo, value: <PayRefCell value={f.flowNo} /> },
              { label: c.colStatus, value: <StatusBadge value={f.status} map={statusMap} /> },
              { label: c.flowName, value: f.name },
              { label: c.bizType, value: f.bizType },
              { label: c.market, value: f.market },
              { label: c.revision, value: f.revisionNo },
              { label: c.sensitiveLabel, value: yesNo(f.sensitive, c) },
              { label: c.leaseMinutes, value: f.leaseMinutes },
              { label: c.timeoutHours, value: f.timeoutHours },
              { label: c.escalateAfterHours, value: f.escalateAfterHours },
            ] },
            { title: c.sectionNodes, content: <MiniTable rows={f.nodes} rowKey={(n) => String(n.nodeNo)} empty={c.emptyNodes} columns={[
              { header: "#", cell: (n) => n.nodeNo },
              { header: c.roleRef, cell: (n) => n.roleRef },
            ]} /> },
            { title: c.sectionRevisions, content: <MiniTable rows={d.revisions.filter((x) => x.revisionNo !== f.revisionNo)} rowKey={(x) => String(x.revisionNo)} empty={c.emptyRevisions} columns={[
              { header: c.revision, cell: (x) => x.revisionNo },
              { header: c.nodeCount, cell: (x) => x.nodes.length },
              { header: c.colStatus, cell: (x) => <StatusBadge value={x.status} map={statusMap} /> },
            ]} /> },
          ]} />;
        }}
      </DetailLoader>
    ),
  }];
  const columns: Column<ApprovalFlowRow>[] = [
    { header: c.flowNo, cell: (r) => <PayRefCell value={r.flowNo} /> },
    { header: c.flowName, cell: (r) => r.name },
    { header: c.bizType, cell: (r) => r.bizType },
    { header: c.nodeCount, numeric: true, cell: (r) => r.nodes.length },
    { header: c.revision, numeric: true, cell: (r) => r.revisionNo },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
  ];
  return (
    <PayListPage<ApprovalFlowRow> header={header} help={c.flowsDesc} search={list.keyword} onSearch={list.setKeyword} searchPlaceholder={c.flowsSearch}
      query={list.query} page={list.page} size={list.size} onPage={list.setPage} onSize={list.setSize}
      columns={columns} rowKey={(r) => r.flowNo} actions={actions} empty={c.emptyFlows} />
  );
}

type SettingsRow = OperatingSettings & { id: "settings" };

export function SettingsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const operator = useDraftOperator();
  const run = useRun("settings");
  // 单条记录：表里只有一行，操作与其它页一致（查看 / 编辑）
  const list = useTabList<SettingsRow, Record<string, never>>("settings", {}, async (api) => {
    const s = await api.settings.get();
    return s ? { records: [{ ...s, id: "settings" as const }], total: 1 } : { records: [], total: 0 };
  });
  const { api } = list;
  const actions: ActionSpec<SettingsRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", title: () => c.settingsDetailTitle,
      render: (ctx) => {
        const s = ctx.row!;
        return <DetailBody sections={[{ title: c.sectionBasic, fields: [
          { label: c.market, value: s.market },
          { label: c.currency, value: `${s.currency} (${c.currencyScale} ${s.currencyScale})` },
          { label: c.timezone, value: s.timezone },
          { label: c.defaultChannel, value: s.defaultChannelCode },
          { label: c.channelOrder, value: joinCsv(s.channelOrder), full: true },
          { label: c.colUpdated, value: `${fmtTime(s.updatedAt)} · ${s.updatedBy ?? ""}`, full: true },
        ] }]} actions={<DetailActions ctx={ctx} keys={["edit"]} />} />;
      },
    },
    {
      key: "edit", label: c.edit, kind: "drawer", perm: "pay:channel:manage", title: () => c.editSettingsTitle,
      render: (ctx) => (
        <>
          <Notice tone="info" className="mb-4">{c.settingsNote}</Notice>
          <ActionForm notify={false}
            fields={[
              { key: "defaultChannelCode", label: c.defaultChannel, required: true, maxLength: 32 },
              { key: "channelOrder", label: c.channelOrder, required: true, help: c.channelOrderHelp },
              reasonField(c),
            ]}
            initial={{ defaultChannelCode: ctx.row!.defaultChannelCode ?? "", channelOrder: joinCsv(ctx.row!.channelOrder), reason: "" }}
            isEdit draft={operator ? { operator, page: "pay-settings", action: "edit", id: "settings" } : null} baseVersion={ctx.row!.version}
            onSubmit={(v) => run(() => api.settings.save({ expectedVersion: ctx.row!.version, defaultChannelCode: String(v.defaultChannelCode).trim(), channelOrder: csv(v.channelOrder), reason: String(v.reason) }))}
            onDone={ctx.back ?? ctx.close} />
        </>
      ),
    },
  ];
  const columns: Column<SettingsRow>[] = [
    { header: c.market, cell: (s) => s.market },
    { header: c.currency, cell: (s) => s.currency },
    { header: c.timezone, cell: (s) => s.timezone },
    { header: c.defaultChannel, cell: (s) => s.defaultChannelCode ?? "—" },
    { header: c.channelOrder, cell: (s) => joinCsv(s.channelOrder) },
    { header: c.colUpdated, cell: (s) => fmtTime(s.updatedAt) },
  ];
  return (
    <PayListPage<SettingsRow> header={header} help={c.settingsDesc}
      query={list.query} page={list.page} size={list.size} onPage={list.setPage} onSize={list.setSize}
      columns={columns} rowKey={(s) => s.id} actions={actions} empty={c.emptySettings} />
  );
}
