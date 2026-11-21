"use client";

// 异常交易（TR08）。行操作：查看（只读，行内已全）· 退款处理（理由弹框）· 忽略（理由弹框）。
// near-pay 侧这两条命令没有版本号与幂等号，只认原因。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell } from "@/app/payment-center/_kit/pay-kit";
import { EXCEPTION_KINDS, EXCEPTION_STATUSES, type ExceptionRow } from "@/app/payment-center/_api/types";
import { payCenterHref } from "@/app/payment-center/module";
import { fmtTime, money } from "@/lib/utils";
import { useQuery, keepPreviousData } from "@tanstack/react-query";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { ErrorState, Skeleton } from "@/components/ui/misc";
import type { PayCenterApi } from "@/app/payment-center/_api/api";
import type { EscalatedRow } from "@/app/payment-center/_api/types";
import { MiniTable, useRun, useStatusMap, useTabList, usePc } from "./shared";
import { useCac } from "./channel-accounts";

const NAME = "exceptions";

export function ExceptionsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const run = useRun(NAME);
  const statusMap = useStatusMap("ex", EXCEPTION_STATUSES, { OPEN: "warning", AUTO_REFUNDING: "info", RESOLVED: "success" });
  const kindMap = useStatusMap("exkind", EXCEPTION_KINDS);
  const list = useTabList<ExceptionRow, { status: string }>(NAME, { status: "OPEN" }, (api, q) => api.exceptions.list(q));
  const { api } = list;
  const ca = useCac();
  const orderHref = (no: string) => `${payCenterHref("orders")}&action=view&id=${encodeURIComponent(no)}`;

  const actions: ActionSpec<ExceptionRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", title: () => c.exDetailTitle, width: "w-[560px]",
      render: (ctx) => {
        const r = ctx.row!;
        return (
          <DetailBody
            sections={[{ title: c.sectionBasic, fields: [
              { label: c.exKind, value: kindMap[r.kind]?.label ?? r.kind },
              { label: c.colStatus, value: <StatusBadge value={r.status} map={statusMap} /> },
              { label: c.orderNo, value: <PayRefCell value={r.payOrderNo} href={orderHref(r.payOrderNo)} /> },
              { label: c.colAmount, value: money(r.amountMinor, r.currency) },
              { label: c.colCreated, value: fmtTime(r.createdAt) },
              { label: c.exHandledBy, value: r.handledBy },
              { label: c.exNote, value: r.note, full: true },
            ] }]}
            actions={<DetailActions ctx={ctx} keys={["refund", "ignore"]} />}
          />
        );
      },
    },
    {
      key: "refund", label: c.exRefund, kind: "dialog", perm: "pay:order:manage", visible: (r) => r?.status === "OPEN",
      confirm: () => ({ title: c.exRefundTitle, desc: c.exRefundDesc, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.exceptions.refund(r!.id, reason)),
    },
    {
      key: "ignore", label: c.exIgnore, kind: "dialog", perm: "pay:order:manage", danger: true, visible: (r) => r?.status === "OPEN",
      confirm: () => ({ title: c.exIgnoreTitle, desc: c.exIgnoreDesc, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.exceptions.ignore(r!.id, reason)),
    },
  ];

  const columns: Column<ExceptionRow>[] = [
    { header: c.orderNo, cell: (r) => <PayRefCell value={r.payOrderNo} href={orderHref(r.payOrderNo)} /> },
    { header: c.exKind, cell: (r) => kindMap[r.kind]?.label ?? r.kind },
    { header: c.colAmount, numeric: true, cell: (r) => money(r.amountMinor, r.currency) },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: c.exHandledBy, cell: (r) => r.handledBy ?? "—" },
    { header: c.colCreated, cell: (r) => fmtTime(r.createdAt) },
  ];

  return (
    <PayListPage<ExceptionRow>
      header={header}
      help={c.exceptionsDesc}
      filters={<FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => String(r.id)}
      actions={actions}
      pageActions={[{
        key: "escalated", label: ca.escalated, kind: "drawer", width: "w-[880px]", perm: "pay:order:view", title: () => ca.escalatedTitle,
        render: () => <EscalatedPanel api={api} orderHref={orderHref} />,
      }]}
      empty={c.emptyExceptions}
    />
  );
}

/** 转人工（near-pay `/payment-escalations`）：自动恢复用尽、待人工跟进的订单；处理在订单详情里做 */
function EscalatedPanel({ api, orderHref }: { api: PayCenterApi; orderHref: (no: string) => string }) {
  const c = usePc();
  const ca = useCac();
  const [storeNo, setStoreNo] = React.useState("");
  const [applied, setApplied] = React.useState("");
  const [page, setPage] = React.useState(1);
  const q = useQuery({ queryKey: ["pay-center", NAME, "escalations", api.source, applied, page], queryFn: () => api.exceptions.escalations({ storeNo: applied || undefined, page, size: 20 }), placeholderData: keepPreviousData });
  const pages = q.data ? Math.max(1, Math.ceil(q.data.total / 20)) : 1;
  return (
    <div className="space-y-3">
      <p className="txt-caption text-muted-foreground">{ca.escalatedNote}</p>
      <form className="flex items-center gap-2" onSubmit={(e) => { e.preventDefault(); setApplied(storeNo.trim()); setPage(1); }}>
        <Input aria-label={ca.storeFilter} placeholder={ca.storeFilter} value={storeNo} maxLength={64} onChange={(e) => setStoreNo(e.target.value)} className="max-w-60" />
        <Button size="sm" variant="outline" type="submit">{ca.filterApply}</Button>
      </form>
      {q.isPending ? <Skeleton className="h-24 w-full" /> : q.error ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : (
        <MiniTable<EscalatedRow> rows={q.data.records} rowKey={(r) => String(r.followup.id)} empty={ca.emptyEscalated} columns={[
          { header: c.orderNo, cell: (r) => <PayRefCell value={r.order.payOrderNo} href={orderHref(r.order.payOrderNo)} /> },
          { header: c.storeName, cell: (r) => r.order.storeName },
          { header: c.colAmount, numeric: true, cell: (r) => money(r.order.amountMinor, r.order.currency) },
          { header: ca.followupOp, cell: (r) => `${r.followup.operation} · ${r.followup.payChannel}` },
          { header: ca.followupAttempts, numeric: true, cell: (r) => r.followup.attempts },
          { header: ca.followupLast, cell: (r) => r.followup.lastResult ?? "—" },
          { header: ca.followupAt, cell: (r) => fmtTime(r.followup.createdAt) },
        ]} />
      )}
      {pages > 1 && (
        <div className="flex items-center justify-end gap-2 txt-caption">
          <Button size="sm" variant="ghost" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>‹</Button>
          <span>{page} / {pages}</span>
          <Button size="sm" variant="ghost" disabled={page >= pages} onClick={() => setPage((p) => p + 1)}>›</Button>
        </div>
      )}
    </div>
  );
}
