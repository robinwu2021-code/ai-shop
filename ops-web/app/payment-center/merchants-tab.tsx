"use client";

// 商户档案（ME01）。行操作：查看（基本、来源关联、资料版本）· 暂停（理由弹框）· 恢复（理由弹框，需已通过的恢复审批）。
// 资料版本的通过/驳回属于审批，不在这一页（拆分规则 5）；新建商户与资料代填暂在支付端工作区（near-pay 侧的结果结构未核对）。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { Button } from "@/components/ui/button";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell } from "@/components/payment/pay-kit";
import { MERCHANT_STATUSES, PROFILE_STATUSES, VERIFY_STATUSES, type MerchantRow, type ProfileRevision, type SourceLink } from "@/lib/pay-ops/types";
import { paymentWorkspaceHref, shareOpsToken } from "@/lib/auth/ops-bridge";
import { useAuth } from "@/lib/auth";
import { useI18n } from "@/lib/i18n";
import { useCan } from "@/lib/use-can";
import { fmtTime } from "@/lib/utils";
import { DetailLoader, MiniTable, joinCsv, useRun, useStatusMap, useTabList, usePc, yesNo } from "./shared";

const NAME = "merchants";

export function MerchantsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const run = useRun(NAME);
  const statusMap = useStatusMap("merchant", MERCHANT_STATUSES, { ACTIVE: "success", SUSPENDED: "warning" });
  const profileMap = useStatusMap("profile", PROFILE_STATUSES, { APPROVED: "success", SUBMITTED: "info", NEED_MORE: "warning", REJECTED: "danger" });
  const verifyMap = useStatusMap("verify", VERIFY_STATUSES, { VERIFIED: "success", MISMATCH: "danger" });
  const list = useTabList<MerchantRow, { status: string; profileStatus: string }>(NAME, { status: "", profileStatus: "" }, (api, q) => api.merchants.list(q));
  const { api } = list;

  const actions: ActionSpec<MerchantRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => c.merchantDetailTitle,
      render: (ctx) => (
        <DetailLoader name={NAME} id={ctx.row!.merchantNo} load={() => api.merchants.detail(ctx.row!.merchantNo)}>
          {(d) => {
            const m = d.record.merchant;
            return (
              <DetailBody
                sections={[
                  { title: c.sectionBasic, fields: [
                    { label: c.merchantNo, value: <PayRefCell value={m.merchantNo} /> },
                    { label: c.colStatus, value: <StatusBadge value={m.status} map={statusMap} /> },
                    { label: c.merchantName, value: m.displayName },
                    { label: c.entityName, value: m.entityName },
                    { label: c.profileStatus, value: <StatusBadge value={m.profileStatus} map={profileMap} /> },
                    { label: c.verifyStatus, value: <StatusBadge value={m.verifyStatus} map={verifyMap} /> },
                    { label: c.storeCount, value: m.storeCount },
                    { label: c.payMerchantNos, value: joinCsv(m.payMerchantNos) || "—" },
                    { label: c.duplicateSuspect, value: yesNo(m.duplicateSuspect, c) },
                    { label: c.colCreated, value: fmtTime(m.createdAt) },
                    { label: c.statusReason, value: m.statusReason, full: true },
                  ] },
                  { title: c.sectionSources, content: <MiniTable<SourceLink> rows={d.record.sourceLinks} rowKey={(r) => r.linkNo} empty={c.emptySources} columns={[
                    { header: c.externalEntityId, cell: (r) => r.externalEntityId },
                    { header: c.entityName, cell: (r) => r.entityName ?? "—" },
                    { header: c.verifyStatus, cell: (r) => <StatusBadge value={r.verifyStatus} map={verifyMap} /> },
                    { header: c.colUpdated, cell: (r) => fmtTime(r.verifiedAt) },
                  ]} /> },
                  { title: c.sectionProfiles, content: <MiniTable<ProfileRevision> rows={d.record.profiles} rowKey={(r) => r.revisionNo} empty={c.emptyProfiles} columns={[
                    { header: c.revision, cell: (r) => r.revision },
                    { header: c.purpose, cell: (r) => r.purpose },
                    { header: c.colStatus, cell: (r) => <StatusBadge value={r.reviewStatus} map={profileMap} /> },
                    { header: c.submittedBy, cell: (r) => r.submittedBy ?? "—" },
                    { header: c.submittedAt, cell: (r) => fmtTime(r.submittedAt) },
                  ]} /> },
                ]}
                actions={<DetailActions ctx={ctx} keys={["suspend", "activate"]} />}
              />
            );
          }}
        </DetailLoader>
      ),
    },
    {
      key: "suspend", label: c.suspend, kind: "dialog", perm: "pay:merchant:manage", danger: true, visible: (r) => r?.status === "ACTIVE",
      confirm: () => ({ title: c.suspendTitle, desc: c.suspendDesc, danger: true, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.merchants.suspend(r!.merchantNo, { expectedVersion: r!.version, reason })),
    },
    {
      key: "activate", label: c.activate, kind: "dialog", perm: "pay:merchant:approve", visible: (r) => r?.status === "SUSPENDED",
      confirm: () => ({ title: c.activateTitle, desc: c.activateDesc, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.merchants.activate(r!.merchantNo, { expectedVersion: r!.version, reason })),
    },
  ];

  const columns: Column<MerchantRow>[] = [
    { header: c.merchantNo, cell: (r) => <PayRefCell value={r.merchantNo} /> },
    { header: c.merchantName, cell: (r) => r.displayName },
    { header: c.entityName, cell: (r) => r.entityName ?? "—" },
    { header: c.profileStatus, cell: (r) => <StatusBadge value={r.profileStatus} map={profileMap} /> },
    { header: c.verifyStatus, cell: (r) => <StatusBadge value={r.verifyStatus} map={verifyMap} /> },
    { header: c.storeCount, numeric: true, cell: (r) => r.storeCount },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
  ];

  return (
    <PayListPage<MerchantRow>
      header={header}
      help={c.merchantsDesc}
      notice={<WorkspaceHint label={c.merchantsCreateNote} button={c.oms} perm="pay:merchant:manage" />}
      search={list.keyword}
      onSearch={list.setKeyword}
      searchPlaceholder={c.merchantsSearch}
      filters={<>
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />
        <FilterSelect value={list.filters.profileStatus} onChange={(v) => list.setFilter("profileStatus", v)} options={profileMap} allLabel={c.profileStatus} />
      </>}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => r.merchantNo}
      actions={actions}
      empty={c.emptyMerchants}
      resolve={(id) => api.merchants.detail(id).then((d) => d.record.merchant)}
    />
  );
}

/** 还没原生化的那部分（新建商户、资料代填）：一条说明 + 去支付端工作区的按钮，按权限显示 */
export function WorkspaceHint({ label, button, perm, workspace = "merchants" }: { label: string; button: string; perm: string; workspace?: "merchants" | "onboarding" }) {
  const allow = useCan();
  const { locale } = useI18n();
  const token = useAuth((s) => s.token);
  if (!allow(perm)) return null;
  return (
    <div className="mb-4 flex flex-wrap items-center gap-2 rounded-card bg-muted/50 px-3.5 py-2 txt-caption text-muted-foreground">
      <span>{label}</span>
      <Button size="sm" variant="outline" className="ms-auto" onClick={() => {
        if (token) shareOpsToken(token);
        window.location.assign(paymentWorkspaceHref(window.location.origin, locale, workspace));
      }}>{button}</Button>
    </div>
  );
}
