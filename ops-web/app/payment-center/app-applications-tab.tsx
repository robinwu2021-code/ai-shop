"use client";

// 接入申请（SY11，P12）。运营代商户提交开放 API 接入申请，另一人审核；批准即建草稿应用，转「接入应用」启用与发钥。
// 行操作：查看 · 批准 / 驳回（审批码；提交人不得审核）· 撤回（仅提交人）。页面级：提交申请（抽屉）。
import * as React from "react";
import { useRouter } from "next/navigation";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import { Notice } from "@/components/ui/notice";
import { Button } from "@/components/ui/button";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell, useDraftOperator } from "@/app/payment-center/_kit/pay-kit";
import { payCenterHref } from "@/app/payment-center/module";
import { APPLICATION_STATUSES, OPEN_SCOPES, SIGN_TYPES, type AppApplicationRow, type SignType } from "@/app/payment-center/_api/types";
import { fmtTime } from "@/lib/utils";
import { useCopy, fill } from "@/lib/use-copy";
import { DetailLoader, amt, csv, joinCsv, options, reasonField, toMinor, useInstanceCurrency, usePayOperatorNo, usePc, useRun, useTabList } from "./shared";
import { P12_COPY, type P12Copy } from "./p12-copy";
import { p8Map } from "./members-tab";

const NAME = "app-applications";
const label = (m: P12Copy, key: string) => (m as unknown as Record<string, string>)[key] ?? key;

export function AppApplicationsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const m = useCopy(P12_COPY);
  const run = useRun(NAME);
  const me = useDraftOperator();
  const operatorNo = usePayOperatorNo();
  const cur = useInstanceCurrency();
  const router = useRouter();
  const statusMap = React.useMemo(() => p8Map(m, "st_aap", APPLICATION_STATUSES, { SUBMITTED: "warning", APPROVED: "success", REJECTED: "danger" }), [m]);
  const list = useTabList<AppApplicationRow, { status: string }>(NAME, { status: "" }, (api, q) => api.appApplications.list(q));
  const { api } = list;
  const pending = (r: AppApplicationRow | null) => !!r && r.status === "SUBMITTED";
  const mine = (r: AppApplicationRow | null) => !!r && !!operatorNo && r.submittedBy === operatorNo;
  const decide = (action: "approve" | "reject" | "withdraw") => (r: AppApplicationRow | null, reason: string) =>
    run(() => api.appApplications.decide(r!.applicationNo, action, { expectedVersion: r!.version, reason }));
  const scopeText = (s: string[]) => s.map((x) => label(m, `scope_${x}`)).join(" / ");

  const actions: ActionSpec<AppApplicationRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => m.applicationTitle,
      render: (ctx) => (
        <DetailLoader name={NAME} id={ctx.row!.applicationNo} load={() => api.appApplications.detail(ctx.row!.applicationNo)}>
          {(d) => {
            const a = d.record;
            return (
              <>
                {a.status === "SUBMITTED" && mine(a) && <Notice tone="info" className="mb-4">{m.selfReviewHint}</Notice>}
                {a.status === "APPROVED" && a.appNo && (
                  <Notice tone="info" className="mb-4">
                    <p>{fill(m.approvedNext, { app: a.appNo })}</p>
                    <Button size="sm" variant="outline" className="mt-2" onClick={() => router.push(payCenterHref("apps", { action: "view", id: a.appNo! }))}>{m.goApps}</Button>
                  </Notice>
                )}
                <DetailBody
                  sections={[
                    { title: c.sectionBasic, fields: [
                      { label: m.applicationNo, value: <PayRefCell value={a.applicationNo} /> },
                      { label: c.colStatus, value: <StatusBadge value={a.status} map={statusMap} /> },
                      { label: c.merchantNo, value: a.merchantNo },
                      { label: c.appName, value: a.appName },
                      { label: c.appPurpose, value: a.purpose ?? "—" },
                      { label: c.environment, value: a.environment },
                      { label: m.signType, value: label(m, `signType_${a.signType}`) },
                      { label: m.openScopes, value: scopeText(a.scopes) },
                      { label: c.notifyUrl, value: <span className="break-all">{a.notifyUrl ?? "—"}</span>, full: true },
                      { label: c.callbackDomains, value: joinCsv(a.callbackDomains) || "—" },
                      { label: c.ipWhitelist, value: a.ipWhitelist ?? "—" },
                      { label: c.rateLimit, value: a.rateLimitQps },
                      { label: c.dailyRefundLimit, value: amt(a.dailyRefundLimitMinor, cur) },
                      { label: m.contactName, value: [a.contactName, a.contactEmail].filter(Boolean).join(" · ") || "—" },
                      { label: m.submittedBy, value: `${a.submittedBy} · ${a.createdAt ? fmtTime(a.createdAt) : "—"}` },
                      { label: m.reviewedBy, value: a.reviewedBy ? `${a.reviewedBy} · ${a.reviewedAt ? fmtTime(a.reviewedAt) : ""}` : "—" },
                      { label: m.reviewReason, value: a.reviewReason ?? "—" },
                      { label: m.createdApp, value: a.appNo ? <PayRefCell value={a.appNo} /> : "—" },
                    ] },
                  ]}
                  actions={<DetailActions ctx={ctx} keys={["reject", "withdraw", "approve"]} />}
                />
              </>
            );
          }}
        </DetailLoader>
      ),
    },
    { key: "approve", label: m.approve, kind: "dialog", perm: "pay:app:approve", visible: (r) => pending(r) && !mine(r),
      confirm: () => ({ title: m.approveTitle, desc: m.approveDesc, reasonHint: c.reasonHint }), run: decide("approve") },
    { key: "reject", label: m.reject, kind: "dialog", perm: "pay:app:approve", danger: true, visible: (r) => pending(r) && !mine(r),
      confirm: () => ({ title: m.rejectTitle, desc: m.rejectDesc, danger: true, reasonHint: c.reasonHint }), run: decide("reject") },
    { key: "withdraw", label: m.withdraw, kind: "dialog", perm: "pay:app:manage", visible: (r) => pending(r) && mine(r),
      confirm: () => ({ title: m.withdrawTitle, desc: m.withdrawDesc, reasonHint: c.reasonHint }), run: decide("withdraw") },
  ];

  const pageActions: ActionSpec<AppApplicationRow>[] = [{
    key: "create", label: m.submitApplication, kind: "drawer", perm: "pay:app:manage", width: "w-[600px]", title: () => m.submitApplicationTitle,
    render: (ctx) => (
      <>
        <Notice tone="info" className="mb-4">{m.submitApplicationNote}</Notice>
        <ActionForm notify={false}
          fields={[
            { key: "merchantNo", label: c.merchantNo, required: true, maxLength: 64 },
            { key: "appName", label: c.appName, required: true, maxLength: 128 },
            { key: "purpose", label: c.appPurpose, maxLength: 255 },
            { key: "signType", label: m.signType, type: "select", required: true, help: m.signTypeHelp, options: options(SIGN_TYPES, (v) => label(m, `signType_${v}`)) },
            { key: "scopes", label: m.openScopes, type: "multiselect", csv: true, required: true, help: m.openScopesHelp, options: options(OPEN_SCOPES, (v) => label(m, `scope_${v}`)) },
            { key: "notifyUrl", label: c.notifyUrl, maxLength: 255, pattern: { re: "^(https://.+)?$", msg: "https://" } },
            { key: "callbackDomains", label: c.callbackDomains, help: c.domainsHelp },
            { key: "ipWhitelist", label: c.ipWhitelist, maxLength: 512 },
            { key: "rateLimitQps", label: m.qps, type: "number", required: true, min: 1, max: 1000 },
            { key: "dailyRefundLimit", label: c.dailyRefundLimit, type: "number", required: true, min: 0 },
            { key: "contactName", label: m.contactName, maxLength: 64 },
            { key: "contactEmail", label: m.contactEmail, maxLength: 128, pattern: { re: "^([^@\\s]+@[^@\\s]+\\.[^@\\s]+)?$", msg: "name@example.com" } },
          ]}
          initial={{ merchantNo: "", appName: "", purpose: "", signType: "HMAC", scopes: "READ", notifyUrl: "", callbackDomains: "", ipWhitelist: "", rateLimitQps: 20, dailyRefundLimit: 0, contactName: "", contactEmail: "" }}
          draft={me ? { operator: me, page: "pay-app-applications", action: "create" } : null}
          onSubmit={(v) => {
            const blank = (x: unknown) => String(x ?? "").trim() || null;
            return run(() => api.appApplications.submit({
              merchantNo: String(v.merchantNo).trim(), appName: String(v.appName).trim(), purpose: blank(v.purpose), signType: String(v.signType) as SignType,
              scopes: csv(v.scopes), notifyUrl: blank(v.notifyUrl), callbackDomains: csv(v.callbackDomains), ipWhitelist: blank(v.ipWhitelist),
              rateLimitQps: Number(v.rateLimitQps), dailyRefundLimitMinor: toMinor(v.dailyRefundLimit), contactName: blank(v.contactName), contactEmail: blank(v.contactEmail),
            }));
          }}
          onDone={ctx.close} />
      </>
    ),
  }];

  const columns: Column<AppApplicationRow>[] = [
    { header: m.applicationNo, cell: (r) => <PayRefCell value={r.applicationNo} /> },
    { header: c.appName, cell: (r) => r.appName },
    { header: c.merchantNo, cell: (r) => r.merchantNo },
    { header: m.openScopes, cell: (r) => r.scopes.join(" / ") },
    { header: m.signType, cell: (r) => r.signType },
    { header: m.submittedBy, cell: (r) => r.submittedBy },
    { header: m.submittedAt, cell: (r) => (r.createdAt ? fmtTime(r.createdAt) : "—") },
    { header: m.createdApp, cell: (r) => r.appNo ?? "—" },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
  ];

  return (
    <PayListPage<AppApplicationRow>
      header={header}
      help={m.applicationsDesc}
      search={list.keyword}
      onSearch={list.setKeyword}
      searchPlaceholder={m.applicationsSearch}
      filters={<FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => r.applicationNo}
      actions={actions}
      pageActions={pageActions}
      resolve={(id) => api.appApplications.detail(id).then((d) => d.record)}
      draftPage="pay-app-applications"
      empty={m.applicationsEmpty}
    />
  );
}
