"use client";

// 商户成员（P8，near-pay BM03 OpsMemberController）。行操作：查看 · 暂停 / 恢复 · 撤销（强确认）；页面级：补建管理员（只在商户没有生效管理员时）。
// 列表行不带版本号：状态命令先取详情拿版本再提交（near-pay 的版本只在 Detail 里）。
import * as React from "react";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import { Notice } from "@/components/ui/notice";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell, useDraftOperator } from "@/app/payment-center/_kit/pay-kit";
import { MEMBER_STATUSES, MEMBER_SUBJECT_TYPES, type MerchantMemberRow } from "@/app/payment-center/_api/types";
import { fmtTime } from "@/lib/utils";
import { useCopy, fill } from "@/lib/use-copy";
import { DetailLoader, joinCsv, options, reasonField, usePc, useRun, useTabList } from "./shared";
import { P8_COPY, type P8Copy } from "./p8-copy";

const NAME = "merchant-members";

/** P8 文案里的枚举名：`<prefix>_<value>`；没登记的值原样显示 */
export function p8Map(m: P8Copy, prefix: string, values: readonly string[], tones: Record<string, "success" | "warning" | "danger" | "info" | "muted"> = {}): StatusMap<string> {
  const t = m as unknown as Record<string, string>;
  return Object.fromEntries(values.map((v) => [v, { label: t[`${prefix}_${v}`] ?? v, tone: tones[v] ?? "muted" }]));
}
export const p8Label = (m: P8Copy, prefix: string, v: string | null | undefined) => (v ? (m as unknown as Record<string, string>)[`${prefix}_${v}`] ?? v : "—");

export function MembersTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const m = useCopy(P8_COPY);
  const operator = useDraftOperator();
  const run = useRun(NAME);
  const statusMap = React.useMemo(() => p8Map(m, "st_member", MEMBER_STATUSES, { ACTIVE: "success", SUSPENDED: "warning", REVOKED: "danger" }), [m]);
  const list = useTabList<MerchantMemberRow, { status: string; merchantNo: string }>(NAME, { status: "", merchantNo: "" }, (api, q) => api.merchantMembers.list(q));
  const { api } = list;
  const cmd = (action: "suspend" | "resume" | "revoke") => (r: MerchantMemberRow | null, reason: string) =>
    run(async () => { const d = await api.merchantMembers.detail(r!.membershipNo); return api.merchantMembers.command(r!.membershipNo, action, { expectedVersion: d.version, reason }); });
  const st = (...s: string[]) => (r: MerchantMemberRow | null) => !!r && s.includes(r.status);

  const actions: ActionSpec<MerchantMemberRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[640px]", title: () => m.memberDetailTitle,
      render: (ctx) => (
        <DetailLoader name={NAME} id={ctx.row!.membershipNo} load={() => api.merchantMembers.detail(ctx.row!.membershipNo)}>
          {(d) => {
            const x = d.record;
            return <DetailBody sections={[{ title: c.sectionBasic, fields: [
              { label: m.membershipNo, value: <PayRefCell value={x.membershipNo} /> },
              { label: c.colStatus, value: <StatusBadge value={x.status} map={statusMap} /> },
              { label: m.merchantNo, value: x.merchantNo },
              { label: m.subjectType, value: p8Label(m, "subject", x.subjectType) },
              { label: m.subjectRef, value: x.subjectRef },
              { label: m.role, value: p8Label(m, "role", x.role) },
              { label: m.storeScope, value: p8Label(m, "scope", x.storeScope) },
              { label: m.storeNos, value: x.storeScope === "ALL" ? p8Label(m, "scope", "ALL") : joinCsv(x.storeNos) || "—" },
              { label: m.memberActions, value: joinCsv(x.actions) || "—", full: true },
              { label: m.validUntil, value: x.validUntil ? fmtTime(x.validUntil) : "—" },
              { label: m.grantedBy, value: x.grantedBy },
              { label: m.revokeReason, value: x.revokeReason ?? "—" },
            ] }]} actions={<DetailActions ctx={ctx} keys={d.allowedActions} />} />;
          }}
        </DetailLoader>
      ),
    },
    { key: "suspend", label: m.memberSuspend, kind: "dialog", perm: "pay:member:manage", danger: true, visible: st("ACTIVE"),
      confirm: (r) => ({ title: fill(m.memberSuspendTitle, { no: r?.membershipNo ?? "" }), danger: true, reasonHint: c.reasonHint }), run: cmd("suspend") },
    { key: "resume", label: m.memberResume, kind: "dialog", perm: "pay:member:manage", visible: st("SUSPENDED"),
      confirm: (r) => ({ title: fill(m.memberResumeTitle, { no: r?.membershipNo ?? "" }), reasonHint: c.reasonHint }), run: cmd("resume") },
    { key: "revoke", label: m.memberRevoke, kind: "dialog", perm: "pay:member:manage", danger: true, visible: st("ACTIVE", "SUSPENDED"),
      confirm: (r) => ({ title: fill(m.memberRevokeTitle, { no: r?.membershipNo ?? "" }), desc: m.memberRevokeDesc, danger: true, requireText: r?.membershipNo, reasonHint: c.reasonHint }), run: cmd("revoke") },
  ];

  const pageActions: ActionSpec<MerchantMemberRow>[] = [{
    key: "create", label: m.memberCreate, kind: "drawer", perm: "pay:member:manage", width: "w-[560px]", title: () => m.memberCreateTitle,
    render: (ctx) => (
      <>
        <Notice tone="info" className="mb-4">{m.memberCreateNote}</Notice>
        <ActionForm notify={false}
          fields={[
            { key: "merchantNo", label: m.merchantNo, required: true, maxLength: 64 },
            { key: "subjectType", label: m.subjectType, type: "select", required: true, options: options(MEMBER_SUBJECT_TYPES, (v) => p8Label(m, "subject", v)) },
            { key: "subjectRef", label: m.subjectRef, required: true, maxLength: 128 },
            reasonField(c),
          ]}
          initial={{ merchantNo: list.filters.merchantNo, subjectType: "MCH_ACCOUNT", subjectRef: "", reason: "" }}
          draft={operator ? { operator, page: "pay-members", action: "create" } : null}
          onSubmit={(v) => run(() => api.merchantMembers.create({ merchantNo: String(v.merchantNo).trim(), subjectType: String(v.subjectType), subjectRef: String(v.subjectRef).trim(), reason: String(v.reason) }))}
          onDone={ctx.close} />
      </>
    ),
  }];

  const columns: Column<MerchantMemberRow>[] = [
    { header: m.membershipNo, cell: (r) => <PayRefCell value={r.membershipNo} /> },
    { header: m.merchantNo, cell: (r) => r.merchantNo },
    { header: m.subject, cell: (r) => `${p8Label(m, "subject", r.subjectType)} · ${r.subjectRef}` },
    { header: m.role, cell: (r) => p8Label(m, "role", r.role) },
    { header: m.storeScope, cell: (r) => (r.storeScope === "ALL" ? p8Label(m, "scope", "ALL") : joinCsv(r.storeNos)) },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: m.grantedBy, cell: (r) => r.grantedBy },
  ];

  return (
    <PayListPage<MerchantMemberRow>
      header={header}
      help={m.membersDesc}
      filters={<FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => r.membershipNo}
      actions={actions}
      pageActions={pageActions}
      resolve={(id) => api.merchantMembers.detail(id).then((d) => d.record)}
      draftPage="pay-members"
      empty={m.membersEmpty}
    />
  );
}
