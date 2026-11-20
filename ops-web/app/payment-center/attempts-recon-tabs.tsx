"use client";

// P3a：交易流水（TR02）与对账差异（FI11）。契约：near-pay docs/api/handbook/08-运营端P3a接口落地.md。
import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import { Notice } from "@/components/ui/notice";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell, useDraftOperator } from "@/components/payment/pay-kit";
import { payCenterHref } from "@/lib/pay-ops/center-tabs";
import { ATTEMPT_DIRECTIONS, ATTEMPT_STATUSES, RECON_DIFF_STATUSES, RECON_DIFF_TYPES, type AttemptListRow, type ReconDiffRow } from "@/lib/pay-ops/types";
import { notify } from "@/lib/notify";
import { fmtTime, money } from "@/lib/utils";
import { DetailLoader, useRun, useStatusMap, useTabList, usePc, usePayCenterApi, amt, useInstanceCurrency } from "./shared";

const orderHref = (no: string) => `${payCenterHref("orders")}&action=view&id=${encodeURIComponent(no)}`;

export function AttemptsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const run = useRun("attempts");
  const statusMap = useStatusMap("attempt", ATTEMPT_STATUSES, { SUCCESS: "success", PENDING: "info", FAILED: "danger" });
  const dirMap = useStatusMap("dir", ATTEMPT_DIRECTIONS);
  // 搜索框 = 外部单号精确查（near-pay outTradeNo）
  const list = useTabList<AttemptListRow, { status: string; direction: string }>("attempts", { status: "", direction: "" }, (api, q) => api.attempts.list({ ...q, keyword: undefined, outTradeNo: q.keyword }));
  const { api } = list;
  const actions: ActionSpec<AttemptListRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[560px]", title: () => c.attemptDetailTitle,
      render: (ctx) => (
        <DetailLoader name="attempts" id={ctx.row!.outTradeNo} load={() => api.attempts.detail(ctx.row!.outTradeNo)}>
          {(d) => {
            const a = d.record;
            return (
              <DetailBody
                sections={[{ title: c.sectionBasic, fields: [
                  { label: c.paymentNo, value: <PayRefCell value={a.paymentNo} /> },
                  { label: c.colStatus, value: <StatusBadge value={a.status} map={statusMap} /> },
                  { label: c.outTradeNo, value: <PayRefCell value={a.outTradeNo} /> },
                  { label: c.direction, value: dirMap[a.direction]?.label ?? a.direction },
                  { label: c.orderNo, value: a.payOrderNo ? <PayRefCell value={a.payOrderNo} href={orderHref(a.payOrderNo)} /> : "—" },
                  { label: c.tradeNo, value: a.tradeNo },
                  { label: c.colMerchant, value: a.merchantNo },
                  { label: c.colStore, value: a.storeName ?? a.storeNo },
                  { label: c.colChannel, value: `${a.payChannel ?? "—"} · ${a.payMethod ?? ""}` },
                  { label: c.payMerchantNo, value: a.payMerchantNo },
                  { label: c.colAmount, value: money(a.amountMinor, a.currency) },
                  { label: c.channelAmount, value: a.channelAmountMinor != null ? money(a.channelAmountMinor, a.channelCurrency ?? a.currency) : "—" },
                  { label: c.channelFee, value: a.channelFeeMinor != null ? money(a.channelFeeMinor, a.channelCurrency ?? a.currency) : "—" },
                  { label: c.colCreated, value: fmtTime(a.createdAt) },
                  { label: c.succeededAt, value: fmtTime(a.succeededAt) },
                  { label: c.errLabel, value: a.errCode ? `${a.errCode} ${a.errMsg ?? ""}` : "—", full: true },
                ] }]}
                actions={<DetailActions ctx={ctx} keys={d.allowedActions.includes("refresh") ? ["refresh"] : []} />}
              />
            );
          }}
        </DetailLoader>
      ),
    },
    {
      key: "refresh", label: c.refreshAttempt, kind: "confirm", perm: "pay:order:manage", visible: (r) => !!r?.payOrderNo && ["INIT", "PENDING"].includes(r.status),
      confirm: () => ({ title: c.refreshAttemptTitle, desc: c.refreshAttemptDesc }),
      run: (r) => run(async () => {
        const res = await api.attempts.refresh(r!.outTradeNo, { expectedVersion: r!.version, reason: c.refreshAttempt });
        if (!res.accepted) notify.info(c.refreshNotAccepted);
        return res;
      }),
    },
  ];
  const columns: Column<AttemptListRow>[] = [
    { header: c.outTradeNo, cell: (r) => <PayRefCell value={r.outTradeNo} /> },
    { header: c.direction, cell: (r) => dirMap[r.direction]?.label ?? r.direction },
    { header: c.orderNo, cell: (r) => (r.payOrderNo ? <PayRefCell value={r.payOrderNo} href={orderHref(r.payOrderNo)} /> : "—") },
    { header: c.colChannel, cell: (r) => r.payChannel ?? "—" },
    { header: c.colAmount, numeric: true, cell: (r) => money(r.amountMinor, r.currency) },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: c.colCreated, cell: (r) => fmtTime(r.createdAt) },
  ];
  return (
    <PayListPage<AttemptListRow> header={header} help={c.attemptsDesc} search={list.keyword} onSearch={list.setKeyword} searchPlaceholder={c.attemptsSearch}
      filters={<>
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />
        <FilterSelect value={list.filters.direction} onChange={(v) => list.setFilter("direction", v)} options={dirMap} allLabel={c.direction} />
      </>}
      query={list.query} page={list.page} size={list.size} onPage={list.setPage} onSize={list.setSize}
      columns={columns} rowKey={(r) => r.outTradeNo} actions={actions} resolve={(id) => api.attempts.detail(id).then((d) => d.record)} empty={c.emptyAttemptsList} />
  );
}

export function ReconDiffsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const operator = useDraftOperator();
  const run = useRun("recon-diffs");
  const api = usePayCenterApi();
  const statusMap = useStatusMap("rdiff", RECON_DIFF_STATUSES, { PENDING: "warning", RESOLVED: "success" });
  const typeMap = useStatusMap("dtype", RECON_DIFF_TYPES);
  const srcMap = useStatusMap("src", ["SELF_CHECK", "CHANNEL_BILL"]);
  const cur = useInstanceCurrency();
  // 覆盖范围必须显示：渠道账单未接入时「零差异」不代表账平（契约要求）
  const coverage = useQuery({ queryKey: ["pay-center", "recon-diffs", "coverage", api.source], queryFn: () => api.reconDiffs.coverage() });
  const list = useTabList<ReconDiffRow, { status: string; diffType: string }>("recon-diffs", { status: "PENDING", diffType: "" }, (a, q) => a.reconDiffs.list({ ...q, keyword: undefined, differenceNo: q.keyword }));
  const versionOf = async (no: string) => (await api.reconDiffs.detail(no)).version;
  const actions: ActionSpec<ReconDiffRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[560px]", title: () => c.reconDetailTitle,
      render: (ctx) => (
        <DetailLoader name="recon-diffs" id={ctx.row!.differenceNo} load={() => api.reconDiffs.detail(ctx.row!.differenceNo)}>
          {(d) => {
            const r = d.record.difference;
            return (
              <DetailBody
                sections={[{ title: c.sectionBasic, fields: [
                  { label: c.differenceNo, value: <PayRefCell value={r.differenceNo} /> },
                  { label: c.colStatus, value: <StatusBadge value={r.status} map={statusMap} /> },
                  { label: c.diffType, value: typeMap[r.diffType]?.label ?? r.diffType },
                  { label: c.sourceLabel, value: srcMap[r.source]?.label ?? r.source },
                  { label: c.axis, value: r.axis },
                  { label: c.colChannel, value: r.payChannel },
                  { label: c.paymentNo, value: r.paymentNo },
                  { label: c.orderNo, value: r.orderNo ? <PayRefCell value={r.orderNo} href={orderHref(r.orderNo)} /> : "—" },
                  { label: c.channelAmountLabel, value: r.channelAmountMinor != null ? amt(r.channelAmountMinor, cur) : "—" },
                  { label: c.platformAmountLabel, value: r.platformAmountMinor != null ? amt(r.platformAmountMinor, cur) : "—" },
                  { label: c.diffAmount, value: r.diffMinor != null ? amt(r.diffMinor, cur) : "—" },
                  { label: c.claimedBy, value: d.record.claimedBy },
                  { label: c.bizDate, value: r.billDate },
                  { label: c.colCreated, value: fmtTime(r.createdAt) },
                  { label: c.memo, value: r.note, full: true },
                  { label: c.resolution, value: r.resolution, full: true },
                ] }]}
                actions={<DetailActions ctx={ctx} keys={d.allowedActions.filter((a) => a === "claim" || a === "close")} />}
              />
            );
          }}
        </DetailLoader>
      ),
    },
    { key: "claim", label: c.claimDiff, kind: "confirm", perm: "pay:recon-diff:manage", visible: (r) => r?.status === "PENDING",
      confirm: () => ({ title: c.claimDiffTitle }),
      run: (r) => run(async () => api.reconDiffs.claim(r!.differenceNo, { expectedVersion: await versionOf(r!.differenceNo), reason: c.claimDiffReason })) },
    {
      key: "close", label: c.closeDiff, kind: "drawer", perm: "pay:recon-diff:manage", title: () => c.closeDiffTitle, visible: (r) => r?.status === "PENDING",
      render: (ctx) => (
        <ActionForm notify={false}
          fields={[
            { key: "outcome", label: c.closeOutcome, type: "select", required: true, options: [{ value: "RESOLVED", label: c.outcomeResolved }, { value: "IGNORED", label: c.outcomeIgnored }] },
            { key: "reason", label: c.resolution, type: "textarea", required: true, maxLength: 255, rows: 3, help: c.closeReasonHelp },
          ]}
          initial={{ outcome: "RESOLVED", reason: "" }}
          draft={operator ? { operator, page: "pay-recon-diffs", action: "close", id: ctx.row!.differenceNo } : null}
          baseVersion={ctx.row!.version}
          onSubmit={(v) => run(async () => api.reconDiffs.close(ctx.row!.differenceNo, { expectedVersion: await versionOf(ctx.row!.differenceNo), reason: String(v.reason), outcome: v.outcome as "RESOLVED" }))}
          onDone={ctx.back ?? ctx.close} />
      ),
    },
  ];
  const columns: Column<ReconDiffRow>[] = [
    { header: c.differenceNo, cell: (r) => <PayRefCell value={r.differenceNo} /> },
    { header: c.diffType, cell: (r) => typeMap[r.diffType]?.label ?? r.diffType },
    { header: c.colChannel, cell: (r) => r.payChannel ?? "—" },
    { header: c.orderNo, cell: (r) => r.orderNo ?? "—" },
    { header: c.diffAmount, numeric: true, cell: (r) => (r.diffMinor != null ? amt(r.diffMinor, cur) : "—") },
    { header: c.sourceLabel, cell: (r) => srcMap[r.source]?.label ?? r.source },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: c.colCreated, cell: (r) => fmtTime(r.createdAt) },
  ];
  return (
    <PayListPage<ReconDiffRow> header={header} help={c.reconDesc}
      notice={coverage.data && <Notice tone={coverage.data.channelBillConnected ? "info" : "warning"} className="mb-4">{coverage.data.channelBillConnected ? c.coverageOk : c.coverageWarn}</Notice>}
      search={list.keyword} onSearch={list.setKeyword} searchPlaceholder={c.reconSearch}
      filters={<>
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />
        <FilterSelect value={list.filters.diffType} onChange={(v) => list.setFilter("diffType", v)} options={typeMap} allLabel={c.diffType} />
      </>}
      query={list.query} page={list.page} size={list.size} onPage={list.setPage} onSize={list.setSize}
      columns={columns} rowKey={(r) => r.differenceNo} actions={actions} resolve={(id) => api.reconDiffs.detail(id).then((d) => d.record.difference)}
      draftPage="pay-recon-diffs" empty={c.emptyReconDiffs} />
  );
}
