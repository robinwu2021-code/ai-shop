"use client";

// 收款接入（OP09）。行操作：查看（含就绪检查）· 编辑（抽屉）· 启用 / 停用（理由弹框）。页面级：新增（抽屉）。
// 字段随接入方式联动：H5 要域名与会话时长、API/APP 要接入应用、APP 还要包名、POS 可填终端号 —— 不适用的字段禁用并清空，
// 与 near-pay 的校验口径一致（不适用的字段必须不传）。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import type { FieldDef } from "@/components/ui/form-drawer";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell, useDraftOperator } from "@/app/payment-center/_kit/pay-kit";
import type { EntrySave } from "@/app/payment-center/_api/api";
import { ACCESS_METHODS, type EntryRow } from "@/app/payment-center/_api/types";
import { csv, joinCsv, options, reasonField, useRun, useStatusMap, useTabList, usePc, yesNo, type C } from "./shared";

const NAME = "access";
const is = (...m: string[]) => (v: Record<string, unknown>) => !m.includes(String(v.accessMethod));

function fields(c: C): FieldDef[] {
  return [
    { key: "name", label: c.entryName, required: true, maxLength: 128 },
    { key: "merchantNo", label: c.merchantNo, required: true, readOnlyOnEdit: true, maxLength: 64 },
    { key: "storeNo", label: c.storeNo, required: true, readOnlyOnEdit: true, maxLength: 64 },
    { key: "accessMethod", label: c.accessMethod, type: "select", required: true, readOnlyOnEdit: true, options: [{ value: "", label: "—" }, ...options(ACCESS_METHODS, (v) => v)] },
    { key: "productCodes", label: c.productCodes, required: true, help: c.productCodesHelp },
    { key: "allowedDomain", label: c.allowedDomain, maxLength: 253, disabledWhen: is("H5"), pattern: { re: "^$|^[a-z0-9.-]+$", msg: "pay.example.com" } },
    { key: "sessionTimeoutSeconds", label: c.sessionTimeout, type: "number", min: 60, max: 3600, disabledWhen: is("H5") },
    { key: "appNo", label: c.appNo, maxLength: 64, disabledWhen: is("API", "APP") },
    { key: "clientAppIdentifier", label: c.clientAppIdentifier, maxLength: 128, disabledWhen: is("APP") },
    { key: "terminalNo", label: c.terminalNo, maxLength: 64, disabledWhen: is("POS") },
    reasonField(c),
  ];
}

function toForm(r: EntryRow | null) {
  return {
    name: r?.name ?? "", merchantNo: r?.merchantNo ?? "", storeNo: r?.storeNo ?? "", accessMethod: r?.accessMethod ?? "", productCodes: joinCsv(r?.productCodes),
    allowedDomain: r?.allowedDomain ?? "", sessionTimeoutSeconds: r?.sessionTimeoutSeconds ?? "", appNo: r?.appNo ?? "", clientAppIdentifier: r?.clientAppIdentifier ?? "", terminalNo: r?.terminalNo ?? "", reason: "",
  };
}

/** 只带当前接入方式适用的字段 */
function toSave(v: Record<string, unknown>, expectedVersion: number): EntrySave {
  const m = String(v.accessMethod);
  const s = (k: string) => (String(v[k] ?? "").trim() || null);
  return {
    expectedVersion, reason: String(v.reason), name: String(v.name).trim(), merchantNo: String(v.merchantNo).trim(), storeNo: String(v.storeNo).trim(), accessMethod: m,
    productCodes: csv(v.productCodes),
    ...(m === "H5" ? { allowedDomain: s("allowedDomain"), sessionTimeoutSeconds: v.sessionTimeoutSeconds === "" ? null : Number(v.sessionTimeoutSeconds) } : {}),
    ...(m === "API" || m === "APP" ? { appNo: s("appNo") } : {}),
    ...(m === "APP" ? { clientAppIdentifier: s("clientAppIdentifier") } : {}),
    ...(m === "POS" ? { terminalNo: s("terminalNo") } : {}),
  };
}

export function AccessTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const operator = useDraftOperator();
  const run = useRun(NAME);
  const statusMap = useStatusMap("entry", ["DRAFT", "ACTIVE", "DISABLED"], { ACTIVE: "success" });
  const methodMap = Object.fromEntries(ACCESS_METHODS.map((m) => [m, { label: m, tone: "muted" as const }]));
  const list = useTabList<EntryRow, { status: string; accessMethod: string }>(NAME, { status: "", accessMethod: "" }, (api, q) => api.entries.list(q));
  const { api } = list;
  const draft = (action: string, id?: string) => (operator ? { operator, page: "pay-access", action, id } : null);

  const actions: ActionSpec<EntryRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[560px]", title: () => c.entryDetailTitle,
      render: (ctx) => {
        const r = ctx.row!;
        return (
          <DetailBody
            sections={[{ title: c.sectionBasic, fields: [
              { label: c.entryNo, value: <PayRefCell value={r.entryNo} /> },
              { label: c.colStatus, value: <StatusBadge value={r.status} map={statusMap} /> },
              { label: c.entryName, value: r.name, full: true },
              { label: c.merchantNo, value: r.merchantNo },
              { label: c.storeNo, value: r.storeNo },
              { label: c.accessMethod, value: r.accessMethod },
              { label: c.productCodes, value: joinCsv(r.productCodes) },
              { label: c.allowedDomain, value: r.allowedDomain },
              { label: c.sessionTimeout, value: r.sessionTimeoutSeconds },
              { label: c.appNo, value: r.appNo },
              { label: c.terminalNo, value: r.terminalNo },
              { label: c.readiness, value: yesNo(r.readiness?.ready, c) },
              { label: c.notReadyReasons, value: r.readiness?.reasons.join("、") || "—", full: true },
            ] }]}
            actions={<DetailActions ctx={ctx} keys={["disable", "enable", "edit"]} />}
          />
        );
      },
    },
    {
      key: "edit", label: c.edit, kind: "drawer", perm: "pay:collection-entry:manage", width: "w-[560px]", title: () => c.editEntryTitle,
      render: (ctx) => (
        <ActionForm
          notify={false}
          fields={fields(c)}
          initial={toForm(ctx.row)}
          isEdit
          draft={draft("edit", ctx.row!.entryNo)}
          baseVersion={ctx.row!.version}
          onSubmit={(v) => run(() => api.entries.update(ctx.row!.entryNo, toSave(v, ctx.row!.version)))}
          onDone={ctx.back ?? ctx.close}
        />
      ),
    },
    {
      key: "enable", label: c.enable, kind: "dialog", perm: "pay:collection-entry:manage", visible: (r) => !!r && r.status !== "ACTIVE",
      confirm: (r) => ({ title: `${c.enable} ${r?.name}`, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.entries.enable(r!.entryNo, { expectedVersion: r!.version, reason })),
    },
    {
      key: "disable", label: c.disable, kind: "dialog", perm: "pay:collection-entry:manage", danger: true, visible: (r) => r?.status === "ACTIVE",
      confirm: (r) => ({ title: `${c.disable} ${r?.name}`, danger: true, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.entries.disable(r!.entryNo, { expectedVersion: r!.version, reason })),
    },
  ];

  const pageActions: ActionSpec<EntryRow>[] = [{
    key: "create", label: c.create, kind: "drawer", perm: "pay:collection-entry:manage", width: "w-[560px]", title: () => c.createEntryTitle,
    render: (ctx) => (
      <ActionForm
          notify={false}
        fields={fields(c)}
        initial={toForm(null)}
        draft={draft("create")}
        onSubmit={(v) => run(() => api.entries.create(toSave(v, 0)))}
        onDone={ctx.close}
      />
    ),
  }];

  const columns: Column<EntryRow>[] = [
    { header: c.entryNo, cell: (r) => <PayRefCell value={r.entryNo} /> },
    { header: c.entryName, cell: (r) => r.name },
    { header: c.storeNo, cell: (r) => r.storeNo },
    { header: c.accessMethod, cell: (r) => r.accessMethod },
    { header: c.readiness, cell: (r) => yesNo(r.readiness?.ready, c) },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
  ];

  return (
    <PayListPage<EntryRow>
      header={header}
      help={c.accessDesc}
      filters={<>
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />
        <FilterSelect value={list.filters.accessMethod} onChange={(v) => list.setFilter("accessMethod", v)} options={methodMap} allLabel={c.accessMethod} />
      </>}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => r.entryNo}
      actions={actions}
      empty={c.emptyEntries}
      pageActions={pageActions}
      resolve={(id) => api.entries.detail(id).then((d) => d.record)}
      draftPage="pay-access"
    />
  );
}
