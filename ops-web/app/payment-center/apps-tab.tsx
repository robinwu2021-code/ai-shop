"use client";

// 接入应用（SY05）。行操作：查看（基本 + 门店授权）· 编辑 / 授权范围 / 回调地址（三个抽屉，各管一件事）· 核验回调（抽屉，审批码）
// · 启用 / 恢复（理由弹框，审批码）· 暂停（理由弹框）· 吊销（强确认）· 签发密钥（抽屉，明文只显示一次）。页面级：新增（抽屉）。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody, type DetailSection } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import { Notice } from "@/components/ui/notice";
import { Select } from "@/components/ui/input";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell, useDraftOperator } from "@/components/payment/pay-kit";
import { APP_STATUSES, type AppGrant, type AppRow } from "@/lib/pay-ops/types";
import { fmtTime, money } from "@/lib/utils";
import { DetailLoader, MiniTable, csv, fromMinor, joinCsv, options, reasonField, toMinor, usePayCenterApi, useRefresh, useRun, useStatusMap, useTabList, usePc, amt, useInstanceCurrency } from "./shared";
import { SecretOnce } from "./secret-once";

const NAME = "apps";

export function AppsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const operator = useDraftOperator();
  const run = useRun(NAME);
  const statusMap = useStatusMap("app", APP_STATUSES, { ACTIVE: "success", SUSPENDED: "warning", REVOKED: "danger" });
  const ownerMap = useStatusMap("owner", ["MERCHANT", "PLATFORM"]);
  const envMap = useStatusMap("env", ["LIVE", "SANDBOX"], { SANDBOX: "info" });
  const cbMap = useStatusMap("cb", ["UNVERIFIED", "VERIFIED"], { VERIFIED: "success", UNVERIFIED: "warning" });
  const credMap = useStatusMap("cred", ["NONE", "PENDING", "ACTIVE", "REVOKED"], { ACTIVE: "success" });
  const cur = useInstanceCurrency();
  const list = useTabList<AppRow, { status: string; environment: string }>(NAME, { status: "", environment: "" }, (api, q) => api.apps.list(q));
  const { api } = list;
  const draft = (action: string, id?: string) => (operator ? { operator, page: "pay-apps", action, id } : null);
  const st = (...s: string[]) => (r: AppRow | null) => !!r && s.includes(r.status);
  const cmd = (action: "enable" | "resume" | "suspend" | "revoke") => (r: AppRow | null, reason: string) => run(() => api.apps.command(r!.appNo, action, { expectedVersion: r!.version, reason }));

  const actions: ActionSpec<AppRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => c.appDetailTitle,
      render: (ctx) => (
        <DetailLoader name={NAME} id={ctx.row!.appNo} load={async () => ({ d: await api.apps.detail(ctx.row!.appNo), g: await api.apps.grants(ctx.row!.appNo) })}>
          {({ d, g }) => {
            const a = d.record.app;
            const sections: DetailSection[] = [
              { title: c.sectionBasic, fields: [
                { label: c.appNo, value: <PayRefCell value={a.appNo} /> },
                { label: c.colStatus, value: <StatusBadge value={a.status} map={statusMap} /> },
                { label: c.appName, value: a.name },
                { label: c.appPurpose, value: a.purpose },
                { label: c.ownerKind, value: ownerMap[a.ownerKind]?.label },
                { label: c.merchantNo, value: a.merchantNo },
                { label: c.environment, value: <StatusBadge value={a.environment} map={envMap} /> },
                { label: c.credentialStatusLabel, value: <StatusBadge value={a.credentialStatus} map={credMap} /> },
                { label: c.scopes, value: joinCsv(a.scopes), full: true },
                { label: c.notifyUrl, value: <span className="break-all">{a.notifyUrl}</span>, full: true },
                { label: c.callbackDomains, value: joinCsv(a.callbackDomains) },
                { label: c.callbackStatus, value: <StatusBadge value={a.callbackStatus} map={cbMap} /> },
                { label: c.ipWhitelist, value: a.ipWhitelist },
                { label: c.rateLimit, value: a.rateLimitQps },
                { label: c.dailyRefundLimit, value: amt(a.dailyRefundLimitMinor, cur) },
                { label: c.lastAccess, value: a.lastAccessAt ? `${fmtTime(a.lastAccessAt)} · ${a.lastAccessOutcome ?? ""}` : "—" },
              ] },
              { title: c.sectionGrants, content: <MiniTable<AppGrant> rows={g.records} rowKey={(x) => x.storeNo} empty={c.emptyGrants} columns={[
                { header: c.grantStore, cell: (x) => x.storeNo },
                { header: c.merchantNo, cell: (x) => x.merchantNo },
                { header: c.grantActions, cell: (x) => joinCsv(x.actions) },
                { header: c.colStatus, cell: (x) => x.status },
              ]} /> },
            ];
            return <DetailBody sections={sections} actions={<DetailActions ctx={ctx} keys={["revoke", "suspend", "issueKey", "callback", "scopes", "verifyCallback", "enable", "resume", "edit"]} />} />;
          }}
        </DetailLoader>
      ),
    },
    {
      key: "edit", label: c.edit, kind: "drawer", perm: "pay:app:manage", width: "w-[560px]", title: () => c.editAppTitle, visible: (r) => !!r && r.status !== "REVOKED",
      render: (ctx) => (
        <ActionForm notify={false}
          fields={[
            { key: "name", label: c.appName, required: true, maxLength: 64 },
            { key: "purpose", label: c.appPurpose, required: true, maxLength: 128 },
            { key: "ipWhitelist", label: c.ipWhitelist, maxLength: 512 },
            { key: "rateLimitQps", label: c.rateLimit, type: "number", required: true, min: 1 },
            { key: "dailyRefundLimit", label: c.dailyRefundLimit, type: "number", required: true, min: 0 },
            reasonField(c),
          ]}
          initial={{ name: ctx.row!.name, purpose: ctx.row!.purpose ?? "", ipWhitelist: ctx.row!.ipWhitelist ?? "", rateLimitQps: ctx.row!.rateLimitQps, dailyRefundLimit: fromMinor(ctx.row!.dailyRefundLimitMinor), reason: "" }}
          isEdit draft={draft("edit", ctx.row!.appNo)} baseVersion={ctx.row!.version}
          onSubmit={(v) => run(() => api.apps.update(ctx.row!.appNo, { expectedVersion: ctx.row!.version, name: String(v.name), purpose: String(v.purpose), ipWhitelist: String(v.ipWhitelist ?? ""), rateLimitQps: Number(v.rateLimitQps), dailyRefundLimitMinor: toMinor(v.dailyRefundLimit), reason: String(v.reason) }))}
          onDone={ctx.back ?? ctx.close} />
      ),
    },
    {
      key: "scopes", label: c.editScopes, kind: "drawer", perm: "pay:app:manage", title: () => c.editScopesTitle, visible: (r) => !!r && r.status !== "REVOKED",
      render: (ctx) => (
        <ActionForm notify={false} fields={[{ key: "scopes", label: c.scopes, required: true, help: c.scopesHelp }, reasonField(c)]} initial={{ scopes: joinCsv(ctx.row!.scopes), reason: "" }}
          isEdit draft={draft("scopes", ctx.row!.appNo)} baseVersion={ctx.row!.version}
          onSubmit={(v) => run(() => api.apps.scopes(ctx.row!.appNo, { expectedVersion: ctx.row!.version, scopes: csv(v.scopes), reason: String(v.reason) }))} onDone={ctx.back ?? ctx.close} />
      ),
    },
    {
      key: "callback", label: c.editCallback, kind: "drawer", perm: "pay:app:manage", title: () => c.editCallbackTitle, visible: st("DRAFT", "SUSPENDED"),
      render: (ctx) => (
        <>
          <Notice tone="info" className="mb-4">{c.editCallbackNote}</Notice>
          <ActionForm notify={false}
            fields={[{ key: "notifyUrl", label: c.notifyUrl, required: true, maxLength: 255, pattern: { re: "^https://.+", msg: "https://" } }, { key: "callbackDomains", label: c.callbackDomains, required: true, help: c.domainsHelp }, reasonField(c)]}
            initial={{ notifyUrl: ctx.row!.notifyUrl ?? "", callbackDomains: joinCsv(ctx.row!.callbackDomains), reason: "" }}
            isEdit draft={draft("callback", ctx.row!.appNo)} baseVersion={ctx.row!.version}
            onSubmit={(v) => run(() => api.apps.callback(ctx.row!.appNo, { expectedVersion: ctx.row!.version, notifyUrl: String(v.notifyUrl).trim(), callbackDomains: csv(v.callbackDomains), reason: String(v.reason) }))} onDone={ctx.back ?? ctx.close} />
        </>
      ),
    },
    {
      key: "verifyCallback", label: c.verifyCallback, kind: "drawer", perm: "pay:app:approve", title: () => c.verifyCallbackTitle,
      visible: (r) => st("DRAFT", "SUSPENDED")(r) && r!.callbackStatus === "UNVERIFIED",
      render: (ctx) => (
        <ActionForm notify={false} fields={[{ key: "evidenceRef", label: c.evidenceRef, required: true, maxLength: 128 }, reasonField(c)]} initial={{ evidenceRef: "", reason: "" }}
          draft={draft("verifyCallback", ctx.row!.appNo)} baseVersion={ctx.row!.version}
          onSubmit={(v) => run(() => api.apps.verifyCallback(ctx.row!.appNo, { expectedVersion: ctx.row!.version, evidenceRef: String(v.evidenceRef).trim(), reason: String(v.reason) }))} onDone={ctx.back ?? ctx.close} />
      ),
    },
    { key: "enable", label: c.appEnable, kind: "dialog", perm: "pay:app:approve", visible: st("DRAFT"), confirm: () => ({ title: c.appEnableTitle, reasonHint: c.reasonHint }), run: cmd("enable") },
    { key: "resume", label: c.appResume, kind: "dialog", perm: "pay:app:approve", visible: st("SUSPENDED"), confirm: () => ({ title: c.appResumeTitle, reasonHint: c.reasonHint }), run: cmd("resume") },
    { key: "suspend", label: c.appSuspend, kind: "dialog", perm: "pay:app:manage", danger: true, visible: st("ACTIVE"), confirm: () => ({ title: c.appSuspendTitle, danger: true, reasonHint: c.reasonHint }), run: cmd("suspend") },
    { key: "revoke", label: c.appRevoke, kind: "dialog", perm: "pay:app:approve", danger: true, visible: st("DRAFT", "ACTIVE", "SUSPENDED"),
      confirm: (r) => ({ title: c.appRevokeTitle, desc: c.appRevokeDesc, danger: true, requireText: r?.appNo, reasonHint: c.reasonHint }), run: cmd("revoke") },
    {
      key: "issueKey", label: c.issueKey, kind: "drawer", perm: "pay:credential:manage", title: () => c.issueKeyTitle, visible: (r) => !!r && r.status !== "REVOKED",
      render: (ctx) => <IssueKey appNo={ctx.row!.appNo} onDone={ctx.back ?? ctx.close} />,
    },
  ];

  const pageActions: ActionSpec<AppRow>[] = [{
    key: "create", label: c.create, kind: "drawer", perm: "pay:app:manage", width: "w-[560px]", title: () => c.createAppTitle,
    render: (ctx) => (
      <ActionForm notify={false}
        fields={[
          { key: "name", label: c.appName, required: true, maxLength: 64 },
          { key: "purpose", label: c.appPurpose, required: true, maxLength: 128 },
          { key: "ownerKind", label: c.ownerKind, type: "select", required: true, options: options(["MERCHANT", "PLATFORM"], (v) => ownerMap[v].label) },
          { key: "merchantNo", label: c.merchantNo, maxLength: 64, disabledWhen: (v) => v.ownerKind !== "MERCHANT" },
          { key: "environment", label: c.environment, type: "select", required: true, options: options(["SANDBOX", "LIVE"], (v) => envMap[v].label) },
          { key: "scopes", label: c.scopes, required: true, help: c.scopesHelp },
          { key: "notifyUrl", label: c.notifyUrl, required: true, maxLength: 255, pattern: { re: "^https://.+", msg: "https://" } },
          { key: "callbackDomains", label: c.callbackDomains, required: true, help: c.domainsHelp },
          { key: "rateLimitQps", label: c.rateLimit, type: "number", required: true, min: 1 },
          { key: "dailyRefundLimit", label: c.dailyRefundLimit, type: "number", required: true, min: 0 },
          reasonField(c),
        ]}
        initial={{ name: "", purpose: "", ownerKind: "MERCHANT", merchantNo: "", environment: "SANDBOX", scopes: "", notifyUrl: "", callbackDomains: "", rateLimitQps: 10, dailyRefundLimit: 0, reason: "" }}
        draft={draft("create")}
        onSubmit={(v) => run(() => api.apps.create({
          name: String(v.name), purpose: String(v.purpose), ownerKind: String(v.ownerKind), merchantNo: v.ownerKind === "MERCHANT" ? String(v.merchantNo).trim() : null,
          environment: String(v.environment), scopes: csv(v.scopes), notifyUrl: String(v.notifyUrl).trim(), callbackDomains: csv(v.callbackDomains), ipWhitelist: "",
          rateLimitQps: Number(v.rateLimitQps), dailyRefundLimitMinor: toMinor(v.dailyRefundLimit), reason: String(v.reason),
        }))}
        onDone={ctx.close} />
    ),
  }];

  const columns: Column<AppRow>[] = [
    { header: c.appNo, cell: (r) => <PayRefCell value={r.appNo} /> },
    { header: c.appName, cell: (r) => r.name },
    { header: c.ownerKind, cell: (r) => `${ownerMap[r.ownerKind]?.label ?? r.ownerKind}${r.merchantNo ? ` · ${r.merchantNo}` : ""}` },
    { header: c.environment, cell: (r) => <StatusBadge value={r.environment} map={envMap} /> },
    { header: c.callbackStatus, cell: (r) => <StatusBadge value={r.callbackStatus} map={cbMap} /> },
    { header: c.credentialStatusLabel, cell: (r) => <StatusBadge value={r.credentialStatus} map={credMap} /> },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
  ];

  return (
    <PayListPage<AppRow>
      header={header}
      help={c.appsDesc}
      filters={<>
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />
        <FilterSelect value={list.filters.environment} onChange={(v) => list.setFilter("environment", v)} options={envMap} allLabel={c.environment} />
      </>}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => r.appNo}
      actions={actions}
      pageActions={pageActions}
      resolve={(id) => api.apps.detail(id).then((d) => d.record.app)}
      draftPage="pay-apps"
      empty={c.emptyApps}
    />
  );
}

/** 签发：先选密钥类型（简单选择，不是表单 —— 不需要草稿），生成后明文只显示一次 */
function IssueKey({ appNo, onDone }: { appNo: string; onDone: () => void }) {
  const c = usePc();
  const api = usePayCenterApi();
  const refresh = useRefresh(NAME);
  const [type, setType] = React.useState<"APP_SECRET" | "APP_DATA_KEY">("APP_SECRET");
  return (
    <SecretOnce
      note={c.issueKeyNote}
      button={c.issueKey}
      shownHint={c.secretShownOnce}
      extra={<label className="mb-4 block"><span className="mb-1 block txt-label text-muted-foreground">{c.credentialType}</span>
        <Select className="w-full" value={type} onChange={(e) => setType(e.target.value as typeof type)}>
          <option value="APP_SECRET">APP_SECRET</option>
          <option value="APP_DATA_KEY">APP_DATA_KEY</option>
        </Select></label>}
      generate={async () => {
        // 出错由 SecretOnce 提示；成功不 toast —— 明文就在眼前，提示反而遮住它
        const r = await api.apps.issueCredential(appNo, { credentialType: type, reason: c.issueKey });
        void refresh();
        return { secret: r.secret, meta: <p className="txt-caption text-muted-foreground">{c.fingerprint}: <span className="font-mono">{r.fingerprint}</span></p> };
      }}
      onDone={onDone}
    />
  );
}
