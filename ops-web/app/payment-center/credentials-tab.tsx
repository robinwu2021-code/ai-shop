"use client";

// 凭证管理（P10，near-pay OpsCredentialController）。页面级：登记凭证 · 轮换策略 · 使用情况；
// 行操作：查看（版本、策略、操作记录、访问记录；版本行内：核验 / 启用 / 撤销）· 录入新版本（材料只写不读、不存草稿）· 生成新版本（明文只显示一次）。
import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import { Notice } from "@/components/ui/notice";
import { Button } from "@/components/ui/button";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import type { FieldDef } from "@/components/ui/form-drawer";
import { PayListPage, PayRefCell } from "@/app/payment-center/_kit/pay-kit";
import type { PayCenterApi } from "@/app/payment-center/_api/api";
import {
  CREDENTIAL_EXPIRY_STATES, CREDENTIAL_OWNER_TYPES, CREDENTIAL_VERSION_STATUSES,
  type CredentialBindingRow, type CredentialEvent, type CredentialPolicy, type CredentialRow, type CredentialVersion,
} from "@/app/payment-center/_api/types";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useCopy, fill } from "@/lib/use-copy";
import { DetailLoader, MiniTable, parseLocalDateTime, reasonField, usePc, useRun, useTabList, yesNo } from "./shared";
import { SecretOnce } from "./secret-once";
import { P10_COPY, type P10Copy } from "./p10-copy";
import { p8Map } from "./members-tab";

const NAME = "credentials";
type Run = <R>(fn: () => Promise<R>, ok?: string) => Promise<R>;
const ownerLabel = (m: P10Copy, v: string) => (m as unknown as Record<string, string>)[`owner_${v}`] ?? v;
/** 各材料类型要录入的字段（near-pay CredentialVersionEntry） */
type MaterialKey = "secret" | "hashKey" | "hashIv" | "publicMaterial";
const MATERIAL_FIELDS: Record<string, MaterialKey[]> = {
  COMPOSITE: ["hashKey", "hashIv"], SYMMETRIC_SECRET: ["secret"], PRIVATE_KEY: ["secret", "publicMaterial"], PUBLIC_KEY: ["publicMaterial"],
};

export function CredentialsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const m = useCopy(P10_COPY);
  const run = useRun(NAME);
  const allow = useCan();
  const expiryMap = React.useMemo(() => p8Map(m, "st_exp", CREDENTIAL_EXPIRY_STATES, { OK: "success", EXPIRING: "warning", EXPIRED: "danger" }), [m]);
  const ownerMap = React.useMemo(() => Object.fromEntries(CREDENTIAL_OWNER_TYPES.map((v) => [v, { label: ownerLabel(m, v), tone: "muted" as const }])), [m]);
  const list = useTabList<CredentialRow, { ownerType: string }>(NAME, { ownerType: "" }, (api, q) => api.credentials.list(q));
  const { api } = list;
  const types = useQuery({ queryKey: ["pay-center", "credential-types", api.source], queryFn: () => api.credentials.types(), staleTime: 3600_000 });
  const typeOf = (t: string) => types.data?.find((x) => x.credentialType === t);
  const generated = (r: CredentialRow | null) => !!r && !!typeOf(r.credentialType)?.generated && r.ownerType !== "TERMINAL";

  const actions: ActionSpec<CredentialRow>[] = [
    { key: "view", label: c.view, kind: "drawer", width: "w-[860px]", title: () => m.credDetailTitle, render: (ctx) => <CredentialDetailView api={api} keyId={ctx.row!.keyId} run={run} /> },
    {
      key: "addVersion", label: m.addVersion, kind: "drawer", perm: "pay:credential:manage", width: "w-[560px]", title: () => m.addVersionTitle,
      visible: (r) => !!r && r.status !== "REVOKED" && !generated(r) && r.ownerType !== "TERMINAL",
      render: (ctx) => <AddVersionForm api={api} row={ctx.row!} run={run} onDone={ctx.back ?? ctx.close} />,
    },
    {
      key: "generate", label: m.generate, kind: "drawer", perm: "pay:credential:manage", title: () => m.generate,
      visible: (r) => !!r && r.status !== "REVOKED" && generated(r),
      render: (ctx) => (
        <SecretOnce note={m.generateNote} button={m.generate} shownHint={c.secretShownOnce}
          generate={async () => {
            const r = await api.credentials.generate(ctx.row!.keyId, m.generate);
            void list.query.refetch();
            return { secret: r.secret, meta: <p className="txt-caption text-muted-foreground">{m.fingerprint}: <span className="font-mono">{r.fingerprint}</span> · v{r.versionIndex}</p> };
          }}
          onDone={ctx.back ?? ctx.close} />
      ),
    },
  ];

  const pageActions: ActionSpec<CredentialRow>[] = [
    {
      key: "register", label: m.credRegister, kind: "drawer", perm: "pay:credential:manage", width: "w-[560px]", title: () => m.credRegisterTitle,
      render: (ctx) => (
        <>
          <Notice tone="info" className="mb-4">{m.credRegisterNote}</Notice>
          <ActionForm notify={false} draft={null}
            fields={[
              { key: "credentialType", label: m.credentialType, type: "select", required: true, options: [{ value: "", label: "—" }, ...(types.data ?? []).filter((t) => t.ownerType !== "TERMINAL").map((t) => ({ value: t.credentialType, label: `${t.credentialType} · ${ownerLabel(m, t.ownerType)}` }))] },
              { key: "ownerRef", label: m.ownerRef, required: true, maxLength: 64 },
              reasonField(c),
            ]}
            initial={{ credentialType: "", ownerRef: "", reason: "" }}
            onSubmit={(v) => run(() => api.credentials.register({ credentialType: String(v.credentialType), ownerRef: String(v.ownerRef).trim(), reason: String(v.reason) }))}
            onDone={ctx.close} />
        </>
      ),
    },
    { key: "policies", label: m.policies, kind: "drawer", width: "w-[760px]", title: () => m.policiesTitle, render: () => <PoliciesView api={api} run={run} canEdit={allow("pay:credential:approve")} /> },
    { key: "bindings", label: m.bindings, kind: "drawer", width: "w-[860px]", title: () => m.bindingsTitle, render: () => <BindingsView api={api} /> },
  ];

  const columns: Column<CredentialRow>[] = [
    { header: m.keyId, cell: (r) => <PayRefCell value={String(r.keyId)} /> },
    { header: m.credName, cell: (r) => r.name },
    { header: m.credentialType, cell: (r) => r.credentialType },
    { header: m.ownerType, cell: (r) => `${ownerLabel(m, r.ownerType)} · ${r.ownerRef}` },
    { header: m.env, cell: (r) => r.env },
    { header: m.currentVersion, cell: (r) => (r.currentVersion ? `v${r.currentVersion}${r.currentFingerprint ? ` · ${r.currentFingerprint}` : ""}` : "—") },
    { header: m.expiryState, cell: (r) => <StatusBadge value={r.expiryState} map={expiryMap} /> },
    { header: m.pendingVersions, cell: (r) => r.pendingVersions },
  ];

  return (
    <PayListPage<CredentialRow>
      header={header}
      help={m.credsDesc}
      filters={<FilterSelect value={list.filters.ownerType} onChange={(v) => list.setFilter("ownerType", v)} options={ownerMap} allLabel={m.ownerType} />}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => String(r.keyId)}
      actions={actions}
      pageActions={pageActions}
      resolve={(id) => api.credentials.detail(Number(id)).then((d) => d.record)}
      draftPage="pay-credentials"
      empty={m.credsEmpty}
    />
  );
}

/** 也给「开放平台密钥」页签用（PLATFORM_OPEN 的核验 / 两人启用）：name 决定刷新哪个页签的查询 */
export function CredentialDetailView({ api, keyId, run, name = NAME }: { api: PayCenterApi; keyId: number; run: Run; name?: string }) {
  const c = usePc();
  const m = useCopy(P10_COPY);
  const vMap = React.useMemo(() => p8Map(m, "st_cv", CREDENTIAL_VERSION_STATUSES, { ACTIVE: "success", VERIFIED: "info", DRAFT: "warning", ROTATING: "info", REVOKED: "danger" }), [m]);
  return (
    <DetailLoader name={name} id={String(keyId)} load={() => api.credentials.detail(keyId)}>
      {(d) => {
        const r = d.record; const p = d.policy;
        return <DetailBody sections={[
          { title: c.sectionBasic, fields: [
            { label: m.keyId, value: <PayRefCell value={String(r.keyId)} /> },
            { label: m.credName, value: r.name },
            { label: m.credentialType, value: r.credentialType },
            { label: m.ownerType, value: `${ownerLabel(m, r.ownerType)} · ${r.ownerRef}` },
            { label: m.counterparty, value: r.counterparty ?? "—" },
            { label: m.purpose, value: r.purpose },
            { label: m.algorithm, value: r.algorithm },
            { label: m.materialType, value: r.materialType },
            { label: m.env, value: r.env },
            { label: m.expiresAt, value: r.expiresAt ? fmtTime(r.expiresAt) : "—" },
            { label: m.lastUsedAt, value: r.lastUsedAt ? fmtTime(r.lastUsedAt) : "—" },
          ] },
          { title: m.sectionVersions, content: <MiniTable<CredentialVersion> rows={d.versions} rowKey={(v) => String(v.versionIndex)} empty={m.noLogs} columns={[
            { header: m.versionIndex, cell: (v) => `v${v.versionIndex}` },
            { header: c.colStatus, cell: (v) => <StatusBadge value={v.status} map={vMap} /> },
            { header: m.fingerprint, cell: (v) => <span className="font-mono">{v.fingerprint} · {v.displayPrefix}</span> },
            { header: m.validTo, cell: (v) => (v.validTo ? fmtTime(v.validTo) : "—") },
            { header: m.enteredBy, cell: (v) => v.enteredBy },
            { header: m.enabledBy, cell: (v) => (v.enabledBy ? `${v.enabledBy} · ${fmtTime(v.enabledAt)}` : "—") },
            { header: "", cell: (v) => <VersionActions api={api} keyId={r.keyId} v={v} allowed={d.allowedActions} run={run} /> },
          ]} /> },
          { title: m.sectionPolicy, fields: [
            { label: m.maxAgeDays, value: p.maxAgeDays }, { label: m.warnBeforeDays, value: p.warnBeforeDays }, { label: m.overlapDays, value: p.overlapDays },
            { label: m.dualControl, value: yesNo(p.dualControl, c) }, { label: m.verifyBeforeEnable, value: yesNo(p.verifyBeforeEnable, c) }, { label: m.revealOnIssueOnly, value: yesNo(p.revealOnIssueOnly, c) },
          ] },
          { title: m.sectionEvents, content: <MiniTable<CredentialEvent> rows={d.events} rowKey={(e) => `${e.eventType}:${e.occurredAt}:${e.versionIndex ?? ""}`} empty={m.noLogs} columns={[
            { header: m.eventType, cell: (e) => `${e.eventType}${e.versionIndex != null ? ` · v${e.versionIndex}` : ""}` },
            { header: m.operatorNo, cell: (e) => e.operatorNo },
            { header: m.approvalRef, cell: (e) => e.approvalRef ?? "—" },
            { header: m.accessedAt, cell: (e) => fmtTime(e.occurredAt) },
          ]} /> },
          { title: m.sectionAccessLogs, content: <DetailLoader name={name} id={`${keyId}:access`} load={() => api.credentials.accessLogs(keyId)}>{(logs) => <MiniTable rows={logs} rowKey={(l) => `${l.accessType}:${l.accessedAt}:${l.accessorNo}`} empty={m.noLogs} columns={[
            { header: m.accessType, cell: (l) => l.accessType },
            { header: m.accessor, cell: (l) => `${l.accessorNo} · ${l.accessorRole}` },
            { header: m.approvalRef, cell: (l) => l.approvalRef ?? "—" },
            { header: m.accessedAt, cell: (l) => fmtTime(l.accessedAt) },
          ]} />}</DetailLoader> },
        ]} />;
      }}
    </DetailLoader>
  );
}

/** 版本行内操作：核验（草稿）· 提交启用审批（已核验，录入权限）· 启用（已核验，审批权限）· 撤销（审批权限）；表单就地展开 */
function VersionActions({ api, keyId, v, allowed, run }: { api: PayCenterApi; keyId: number; v: CredentialVersion; allowed: string[]; run: Run }) {
  const c = usePc();
  const m = useCopy(P10_COPY);
  const allow = useCan();
  const [open, setOpen] = React.useState<"verify" | "submit" | "enable" | "revoke" | null>(null);
  const can = {
    verify: v.status === "DRAFT" && allowed.includes("VERIFY") && allow("pay:credential:manage"),
    // near-pay CredentialEnableSubject：CREDENTIAL_ENABLE / ENABLE，对象号 keyId:版本，冻结版本 = 版本号；提交码 pay:credential:manage
    submit: v.status === "VERIFIED" && allowed.includes("ENABLE") && allow("pay:credential:manage"),
    enable: v.status === "VERIFIED" && allowed.includes("ENABLE") && allow("pay:credential:approve"),
    revoke: v.status !== "REVOKED" && allowed.includes("REVOKE") && allow("pay:credential:approve"),
  };
  if (!open) return (
    <div className="flex gap-1">
      {can.verify && <Button size="sm" variant="outline" onClick={() => setOpen("verify")}>{m.verify}</Button>}
      {can.submit && <Button size="sm" variant="outline" onClick={() => setOpen("submit")}>{m.submitEnable}</Button>}
      {can.enable && <Button size="sm" onClick={() => setOpen("enable")}>{m.enable}</Button>}
      {can.revoke && <Button size="sm" variant="ghost" onClick={() => setOpen("revoke")}>{m.revoke}</Button>}
    </div>
  );
  const fields: FieldDef[] = open === "verify" ? [{ key: "evidenceRef", label: m.evidenceRef, maxLength: 120, help: m.evidenceHelp }, reasonField(c)]
    : open === "enable" ? [{ key: "approvalRef", label: m.approvalRef, maxLength: 128 }, reasonField(c)] : [reasonField(c)];
  const title = fill(open === "verify" ? m.verifyTitle : open === "submit" ? m.submitEnableTitle : open === "enable" ? m.enableTitle : m.revokeTitle, { v: v.versionIndex });
  return (
    <div className="w-72 space-y-2">
      <p className="txt-label">{title}</p>
      {open !== "verify" && <Notice tone={open === "revoke" ? "warning" : "info"}>{open === "submit" ? m.submitEnableDesc : open === "enable" ? m.enableDesc : m.revokeDesc}</Notice>}
      <ActionForm notify={false} draft={null} fields={fields} initial={{ evidenceRef: "", approvalRef: "", reason: "" }}
        onSubmit={(x) => open === "submit"
          ? run(() => api.approvals.submit({ subjectType: "CREDENTIAL_ENABLE", subjectRef: `${keyId}:${v.versionIndex}`, subjectRevision: v.versionIndex, action: "ENABLE", reason: String(x.reason) }), m.submitEnableDone)
          : run(() => (open === "verify" ? api.credentials.verify(keyId, v.versionIndex, { evidenceRef: String(x.evidenceRef ?? "").trim() || null, reason: String(x.reason) })
          : open === "enable" ? api.credentials.enable(keyId, v.versionIndex, { approvalRef: String(x.approvalRef ?? "").trim() || null, reason: String(x.reason) })
            : api.credentials.revoke(keyId, v.versionIndex, String(x.reason))))}
        onDone={() => setOpen(null)} />
    </div>
  );
}

/** 录入新版本：按材料类型要字段；材料只写不读，不存本地草稿 */
function AddVersionForm({ api, row, run, onDone }: { api: PayCenterApi; row: CredentialRow; run: Run; onDone: () => void }) {
  const c = usePc();
  const m = useCopy(P10_COPY);
  const material: MaterialKey[] = MATERIAL_FIELDS[row.materialType] ?? ["secret"];
  return (
    <>
      <Notice tone="info" className="mb-4">{m.addVersionNote}</Notice>
      <ActionForm notify={false} draft={null}
        fields={[
          ...material.map((k): FieldDef => ({ key: k, label: m[k], required: true, type: k === "publicMaterial" ? "textarea" : "password", maxLength: k === "publicMaterial" ? 8000 : 512 })),
          { key: "externalKeyId", label: m.externalKeyId, maxLength: 64 },
          { key: "validTo", label: m.validTo, help: m.validToHelp, pattern: { re: "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})?$", msg: "YYYY-MM-DD HH:mm" } },
          reasonField(c),
        ]}
        initial={{ secret: "", hashKey: "", hashIv: "", publicMaterial: "", externalKeyId: "", validTo: "", reason: "" }}
        onSubmit={(v) => run(() => api.credentials.addVersion(row.keyId, {
          ...Object.fromEntries(material.map((k) => [k, String(v[k] ?? "")])),
          ...(String(v.externalKeyId ?? "").trim() ? { externalKeyId: String(v.externalKeyId).trim() } : {}),
          ...(String(v.validTo ?? "").trim() ? { validTo: parseLocalDateTime(v.validTo) } : {}),
          reason: String(v.reason),
        }))}
        onDone={onDone} />
    </>
  );
}

function PoliciesView({ api, run, canEdit }: { api: PayCenterApi; run: Run; canEdit: boolean }) {
  const c = usePc();
  const m = useCopy(P10_COPY);
  const [edit, setEdit] = React.useState<CredentialPolicy | null>(null);
  return (
    <DetailLoader name={NAME} id="policies" load={() => api.credentials.policies()}>
      {(rows) => (
        <div className="space-y-4">
          <Notice tone="info">{m.policyNote}</Notice>
          <MiniTable<CredentialPolicy> rows={rows} rowKey={(p) => p.ownerType} empty={m.noLogs} columns={[
            { header: m.ownerType, cell: (p) => ownerLabel(m, p.ownerType) },
            { header: m.maxAgeDays, cell: (p) => p.maxAgeDays },
            { header: m.warnBeforeDays, cell: (p) => p.warnBeforeDays },
            { header: m.overlapDays, cell: (p) => p.overlapDays },
            { header: m.verifyBeforeEnable, cell: (p) => yesNo(p.verifyBeforeEnable, c) },
            { header: "", cell: (p) => (canEdit ? <Button size="sm" variant="ghost" onClick={() => setEdit(p)}>{c.edit}</Button> : null) },
          ]} />
          {edit && (
            <section className="space-y-2 rounded-card border border-border p-3">
              <h4 className="txt-label">{fill(m.policyEditTitle, { owner: ownerLabel(m, edit.ownerType) })}</h4>
              <ActionForm notify={false} draft={null}
                fields={[
                  { key: "maxAgeDays", label: m.maxAgeDays, type: "number", required: true, min: 0, max: 3650 },
                  { key: "warnBeforeDays", label: m.warnBeforeDays, type: "number", required: true, min: 0, max: 365 },
                  { key: "overlapDays", label: m.overlapDays, type: "number", required: true, min: 0, max: 90 },
                  { key: "verifyBeforeEnable", label: m.verifyBeforeEnable, type: "select", required: true, options: [{ value: "true", label: c.yes }, { value: "false", label: c.no }] },
                  reasonField(c),
                ]}
                initial={{ maxAgeDays: edit.maxAgeDays, warnBeforeDays: edit.warnBeforeDays, overlapDays: edit.overlapDays, verifyBeforeEnable: String(edit.verifyBeforeEnable), reason: "" }}
                onSubmit={(v) => run(() => api.credentials.savePolicy(edit.ownerType, { expectedVersion: edit.version, maxAgeDays: Number(v.maxAgeDays), warnBeforeDays: Number(v.warnBeforeDays), overlapDays: Number(v.overlapDays), verifyBeforeEnable: v.verifyBeforeEnable === "true", reason: String(v.reason) }), m.policySave)}
                onDone={() => setEdit(null)} />
            </section>
          )}
        </div>
      )}
    </DetailLoader>
  );
}

function BindingsView({ api }: { api: PayCenterApi }) {
  const m = useCopy(P10_COPY);
  return (
    <DetailLoader name={NAME} id="bindings" load={() => api.credentials.bindings(30)}>
      {(rows) => <MiniTable<CredentialBindingRow> rows={rows} rowKey={(b) => `${b.source}:${b.keyId ?? ""}:${b.consumerType}:${b.consumerRef}:${b.field}`} empty={m.noLogs} columns={[
        { header: m.keyId, cell: (b) => (b.keyId != null ? String(b.keyId) : b.source) },
        { header: m.consumer, cell: (b) => `${b.consumerType} · ${b.consumerName || b.consumerRef}` },
        { header: m.field, cell: (b) => <span className="font-mono txt-caption">{b.field}</span> },
        { header: m.env, cell: (b) => `${b.channel} · ${b.environment}` },
        { header: m.transactions, cell: (b) => b.transactions },
        { header: m.lastFrozenAt, cell: (b) => (b.lastFrozenAt ? fmtTime(b.lastFrozenAt) : "—") },
      ]} />}
    </DetailLoader>
  );
}

