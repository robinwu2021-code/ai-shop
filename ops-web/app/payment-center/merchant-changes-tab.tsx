"use client";

// 商户变更（ME04）。near-pay 没有跨商户的变更列表：列表 = 资料待审核（profileStatus=SUBMITTED）的商户，审的是它最新的一版。
// 行操作：查看（资料字段 + 材料）· 通过 / 驳回 / 要求补充（理由弹框，pay:merchant:approve）。命令前现取这版资料的版本号。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell } from "@/app/payment-center/_kit/pay-kit";
import { PROFILE_STATUSES, type MerchantRow } from "@/app/payment-center/_api/types";
import { fmtTime } from "@/lib/utils";
import { DetailLoader, MiniTable, useRun, useStatusMap, useTabList, usePc } from "./shared";

const NAME = "merchant-changes";
const PURPOSES = ["GENERAL", "FIAT", "STORED_VALUE", "POINTS", "CRYPTO"] as const;

export function MerchantChangesTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const run = useRun(NAME);
  const purposeMap = useStatusMap("purpose", PURPOSES);
  const profileMap = useStatusMap("profile", PROFILE_STATUSES, { SUBMITTED: "info", APPROVED: "success", REJECTED: "danger", NEED_MORE: "warning" });
  const list = useTabList<MerchantRow, Record<string, never>>(NAME, {}, (api, q) => api.merchantChanges.list(q));
  const { api } = list;
  const rev = (r: MerchantRow | null) => r?.latestProfile?.revisionNo ?? "";
  const decide = (d: "approve" | "reject" | "request-more") => (r: MerchantRow | null, reason: string) => run(async () => {
    const detail = await api.merchantChanges.revision(r!.merchantNo, rev(r));
    return api.merchantChanges.decide(r!.merchantNo, rev(r), d, { expectedVersion: detail.version, reason });
  });
  const pending = (r: MerchantRow | null) => r?.latestProfile?.reviewStatus === "SUBMITTED";

  const actions: ActionSpec<MerchantRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => c.mcDetailTitle,
      render: (ctx) => (
        <DetailLoader name={NAME} id={`${ctx.row!.merchantNo}:${rev(ctx.row)}`} load={() => api.merchantChanges.revision(ctx.row!.merchantNo, rev(ctx.row))}>
          {(d) => {
            const p = d.record;
            const fields = Object.entries(p.profile ?? {});
            return (
              <DetailBody
                sections={[
                  { title: c.sectionBasic, fields: [
                    { label: c.merchantNo, value: <PayRefCell value={ctx.row!.merchantNo} /> },
                    { label: c.merchantName, value: ctx.row!.displayName },
                    { label: c.latestRevision, value: p.revision },
                    { label: c.colStatus, value: <StatusBadge value={p.reviewStatus} map={profileMap} /> },
                    { label: c.purpose, value: purposeMap[p.purpose]?.label ?? p.purpose },
                    { label: c.market, value: p.market },
                    { label: c.submittedBy, value: p.submittedBy },
                    { label: c.submittedAt, value: fmtTime(p.submittedAt) },
                  ] },
                  { title: c.sectionProfileFields, content: <MiniTable rows={fields} rowKey={([k]) => k} empty={c.emptyProfileFields} columns={[
                    { header: c.fieldName, cell: ([k]) => <span className="font-mono txt-caption">{k}</span> },
                    { header: c.fieldValue, cell: ([, v]) => <span className="break-all">{v}</span> },
                  ]} /> },
                  { title: c.sectionMaterials, content: <MiniTable rows={p.materials ?? []} rowKey={(m) => m.ref} empty={c.emptyMaterials} columns={[
                    { header: c.materialKind, cell: (m) => m.kind },
                    { header: c.purpose, cell: (m) => m.purpose },
                    { header: c.materialRef, cell: (m) => <span className="font-mono txt-caption break-all">{m.ref}</span> },
                  ]} /> },
                ]}
                actions={<DetailActions ctx={ctx} keys={["reject", "requestMore", "approve"]} />}
              />
            );
          }}
        </DetailLoader>
      ),
    },
    { key: "approve", label: c.mcApprove, kind: "dialog", perm: "pay:merchant:approve", visible: pending,
      confirm: () => ({ title: c.mcApproveTitle, reasonHint: c.reasonHint }), run: decide("approve") },
    { key: "requestMore", label: c.mcMore, kind: "dialog", perm: "pay:merchant:approve", visible: pending,
      confirm: () => ({ title: c.mcMoreTitle, reasonHint: c.reasonHint }), run: decide("request-more") },
    { key: "reject", label: c.mcReject, kind: "dialog", perm: "pay:merchant:approve", danger: true, visible: pending,
      confirm: () => ({ title: c.mcRejectTitle, danger: true, reasonHint: c.reasonHint }), run: decide("reject") },
  ];

  const columns: Column<MerchantRow>[] = [
    { header: c.merchantNo, cell: (r) => <PayRefCell value={r.merchantNo} /> },
    { header: c.merchantName, cell: (r) => r.displayName },
    { header: c.entityName, cell: (r) => r.entityName ?? "—" },
    { header: c.latestRevision, numeric: true, cell: (r) => r.latestProfile?.revision ?? "—" },
    { header: c.purpose, cell: (r) => (r.latestProfile ? purposeMap[r.latestProfile.purpose]?.label ?? r.latestProfile.purpose : "—") },
    { header: c.colStatus, cell: (r) => (r.latestProfile ? <StatusBadge value={r.latestProfile.reviewStatus} map={profileMap} /> : "—") },
  ];

  return (
    <PayListPage<MerchantRow>
      header={header}
      help={c.mcDesc}
      search={list.keyword}
      onSearch={list.setKeyword}
      searchPlaceholder={c.mcSearch}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => r.merchantNo}
      actions={actions}
      empty={c.emptyMerchantChanges}
    />
  );
}
