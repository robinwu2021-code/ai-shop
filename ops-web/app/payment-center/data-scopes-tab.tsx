"use client";

// 数据范围（SY04，菜单上挂在「平台设置 · 权限管理」）。行操作：查看 · 编辑（抽屉，改完回草稿）· 启用（理由弹框，审批码，
// 录入人不能自己启用 —— near-pay 域内强制）· 提交启用审批（near-pay 开了审批闸门时，启用须先有已批准的 DATA_SCOPE_ENABLE 审批，
// 批准后在「审核待办」执行或回到这里点启用）· 撤销（强确认 + 理由）。页面级：新增委托（抽屉）。
// 数据范围 DTO 没有 version 字段，版本取自详情 —— 所以命令前先取详情。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import { Notice } from "@/components/ui/notice";
import type { FieldDef } from "@/components/ui/form-drawer";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell, useDraftOperator } from "@/app/payment-center/_kit/pay-kit";
import type { ScopeSave } from "@/app/payment-center/_api/api";
import type { DataScopeRow } from "@/app/payment-center/_api/types";
import { fmtTime } from "@/lib/utils";
import { DetailLoader, csv, joinCsv, options, parseLocalDateTime, reasonField, useRun, useStatusMap, useTabList, usePc, usePayCenterApi, type C } from "./shared";

const NAME = "data-scopes";

function fields(c: C, typeMap: Record<string, { label: string }>, fundsMap: Record<string, { label: string }>): FieldDef[] {
  return [
    { key: "scopeType", label: c.scopeType, type: "select", required: true, readOnlyOnEdit: true, options: options(["USER", "ROLE"], (v) => typeMap[v].label) },
    { key: "subjectNo", label: c.subjectNo, required: true, readOnlyOnEdit: true, maxLength: 128 },
    { key: "marketSet", label: c.marketSet, required: true },
    { key: "entityNos", label: c.entityNos, required: true, help: c.entityNosHelp },
    { key: "storeNos", label: c.storeNos, help: c.storeNosHelp, disabledWhen: (v) => String(v.entityNos ?? "").trim() === "*" },
    { key: "fundsAccess", label: c.fundsAccess, type: "select", required: true, options: options(["NONE", "MASKED_READ"], (v) => fundsMap[v].label) },
    { key: "expiresAt", label: c.expiresAtField, type: "date", required: true },
    reasonField(c),
  ];
}

const isoDate = (ms: number) => { const d = new Date(ms); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`; };

function toForm(r: DataScopeRow | null) {
  return {
    scopeType: r?.scopeType ?? "USER", subjectNo: r?.subjectNo ?? "", marketSet: joinCsv(r?.marketSet ?? ["TW"]), entityNos: joinCsv(r?.entityNos),
    storeNos: joinCsv(r?.storeNos), fundsAccess: r?.fundsAccess ?? "NONE", expiresAt: r ? isoDate(r.expiresAt) : "", reason: "",
  };
}

/** 到期日按当天结束（本地 23:59）计，避免「选了今天、提交时已过期」 */
function toSave(v: Record<string, unknown>, expectedVersion: number): ScopeSave {
  const day = parseLocalDateTime(v.expiresAt);
  return {
    expectedVersion, scopeType: String(v.scopeType), subjectNo: String(v.subjectNo).trim(), marketSet: csv(v.marketSet), entityNos: csv(v.entityNos),
    storeNos: String(v.entityNos).trim() === "*" ? [] : csv(v.storeNos), fundsAccess: String(v.fundsAccess), expiresAt: day + 86_399_000, reason: String(v.reason),
  };
}

export function DataScopesTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const operator = useDraftOperator();
  const run = useRun(NAME);
  const statusMap = useStatusMap("scope", ["DRAFT", "ACTIVE", "REVOKED", "EXPIRED"], { ACTIVE: "success", REVOKED: "danger" });
  const typeMap = useStatusMap("scopeType", ["USER", "ROLE"]);
  const fundsMap = useStatusMap("funds", ["NONE", "MASKED_READ"], { MASKED_READ: "warning" });
  const list = useTabList<DataScopeRow, { status: string }>(NAME, { status: "" }, (api, q) => api.dataScopes.list(q));
  const { api } = list;
  const draft = (action: string, id?: string) => (operator ? { operator, page: "pay-data-scopes", action, id } : null);
  const versionOf = async (no: string) => (await api.dataScopes.detail(no)).version;

  const actions: ActionSpec<DataScopeRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[560px]", title: () => c.scopeDetailTitle,
      render: (ctx) => {
        const r = ctx.row!;
        return (
          <DetailBody
            sections={[{ title: c.sectionBasic, fields: [
              { label: c.scopeNo, value: <PayRefCell value={r.scopeNo} /> },
              { label: c.colStatus, value: <StatusBadge value={r.status} map={statusMap} /> },
              { label: c.scopeType, value: typeMap[r.scopeType]?.label },
              { label: c.subjectNo, value: r.subjectNo },
              { label: c.marketSet, value: joinCsv(r.marketSet) },
              { label: c.fundsAccess, value: <StatusBadge value={r.fundsAccess} map={fundsMap} /> },
              { label: c.entityNos, value: joinCsv(r.entityNos), full: true },
              { label: c.storeNos, value: joinCsv(r.storeNos) || "—", full: true },
              { label: c.expiresAtField, value: fmtTime(r.expiresAt) },
              { label: c.enteredBy, value: r.enteredBy },
              { label: c.enabledBy, value: r.enabledBy },
              { label: c.reasonField, value: r.reason, full: true },
            ] }]}
            actions={<DetailActions ctx={ctx} keys={["revoke", "submitEnable", "enable", "edit"]} />}
          />
        );
      },
    },
    {
      key: "edit", label: c.edit, kind: "drawer", perm: "pay:data-scope:manage", width: "w-[560px]", title: () => c.editScopeTitle,
      visible: (r) => !!r && ["DRAFT", "ACTIVE", "EXPIRED"].includes(r.status),
      render: (ctx) => <ScopeEditForm row={ctx.row!} draft={draft("edit", ctx.row!.scopeNo)} onDone={ctx.back ?? ctx.close} typeMap={typeMap} fundsMap={fundsMap} />,
    },
    {
      // near-pay DataScopeSubject：subjectType DATA_SCOPE_ENABLE、action ENABLE、冻结版本 = 当前版本 + 1；提交码 pay:data-scope:manage
      key: "submitEnable", label: c.submitScopeApproval, kind: "dialog", perm: "pay:data-scope:manage", visible: (r) => r?.status === "DRAFT",
      confirm: () => ({ title: c.submitScopeApprovalTitle, desc: c.submitScopeApprovalDesc, reasonHint: c.reasonHint }),
      run: (r, reason) => run(async () => api.approvals.submit({ subjectType: "DATA_SCOPE_ENABLE", subjectRef: r!.scopeNo, subjectRevision: (await versionOf(r!.scopeNo)) + 1, action: "ENABLE", reason }), c.submitScopeApprovalDone),
    },
    {
      key: "enable", label: c.enable, kind: "dialog", perm: "pay:data-scope:approve", visible: (r) => r?.status === "DRAFT",
      confirm: () => ({ title: c.enableScopeTitle, desc: c.enableScopeDesc, reasonHint: c.reasonHint }),
      run: (r, reason) => run(async () => api.dataScopes.enable(r!.scopeNo, { expectedVersion: await versionOf(r!.scopeNo), reason })),
    },
    {
      key: "revoke", label: c.revoke, kind: "dialog", perm: "pay:data-scope:manage", danger: true, visible: (r) => !!r && ["DRAFT", "ACTIVE", "EXPIRED"].includes(r.status),
      confirm: (r) => ({ title: c.revokeTitle, danger: true, requireText: r?.scopeNo, reasonHint: c.reasonHint }),
      run: (r, reason) => run(async () => api.dataScopes.revoke(r!.scopeNo, { expectedVersion: await versionOf(r!.scopeNo), reason })),
    },
  ];

  const pageActions: ActionSpec<DataScopeRow>[] = [{
    key: "create", label: c.create, kind: "drawer", perm: "pay:data-scope:manage", width: "w-[560px]", title: () => c.createScopeTitle,
    render: (ctx) => (
      <ActionForm
          notify={false}
        fields={fields(c, typeMap, fundsMap)}
        initial={toForm(null)}
        draft={draft("create")}
        onSubmit={(v) => run(() => api.dataScopes.create(toSave(v, 0)))}
        onDone={ctx.close}
      />
    ),
  }];

  const columns: Column<DataScopeRow>[] = [
    { header: c.scopeNo, cell: (r) => <PayRefCell value={r.scopeNo} /> },
    { header: c.scopeType, cell: (r) => typeMap[r.scopeType]?.label ?? r.scopeType },
    { header: c.subjectNo, cell: (r) => r.subjectNo },
    { header: c.entityNos, cell: (r) => joinCsv(r.entityNos) },
    { header: c.fundsAccess, cell: (r) => <StatusBadge value={r.fundsAccess} map={fundsMap} /> },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: c.expiresAtField, cell: (r) => fmtTime(r.expiresAt) },
  ];

  return (
    <PayListPage<DataScopeRow>
      header={header}
      help={c.dataScopesDesc}
      filters={<FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => r.scopeNo}
      actions={actions}
      empty={c.emptyScopes}
      pageActions={pageActions}
      resolve={(id) => api.dataScopes.detail(id).then((d) => d.record)}
      draftPage="pay-data-scopes"
    />
  );
}

/** 编辑要用详情里的版本（列表行没有） */
function ScopeEditForm({ row, draft, onDone, typeMap, fundsMap }: {
  row: DataScopeRow; draft: { operator: string; page: string; action: string; id?: string } | null; onDone: () => void;
  typeMap: Record<string, { label: string }>; fundsMap: Record<string, { label: string }>;
}) {
  const c = usePc();
  const api = usePayCenterApi();
  const run = useRun(NAME);
  return (
    <DetailLoader name={NAME} id={row.scopeNo} load={() => api.dataScopes.detail(row.scopeNo)}>
      {(d) => (
        <>
          <Notice tone="warning" className="mb-4">{c.editScopeNote}</Notice>
          <ActionForm
          notify={false}
            fields={fields(c, typeMap, fundsMap)}
            initial={toForm(d.record)}
            isEdit
            draft={draft}
            baseVersion={d.version}
            onSubmit={(v) => run(() => api.dataScopes.update(row.scopeNo, toSave(v, d.version)))}
            onDone={onDone}
          />
        </>
      )}
    </DetailLoader>
  );
}
