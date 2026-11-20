"use client";

// 异常交易（TR08）。行操作：查看（只读，行内已全）· 退款处理（理由弹框）· 忽略（理由弹框）。
// near-pay 侧这两条命令没有版本号与幂等号，只认原因。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell } from "@/components/payment/pay-kit";
import { EXCEPTION_KINDS, EXCEPTION_STATUSES, type ExceptionRow } from "@/lib/pay-ops/types";
import { payCenterHref } from "@/lib/pay-ops/center-tabs";
import { fmtTime, money } from "@/lib/utils";
import { useRun, useStatusMap, useTabList, usePc } from "./shared";

const NAME = "exceptions";

export function ExceptionsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const run = useRun(NAME);
  const statusMap = useStatusMap("ex", EXCEPTION_STATUSES, { OPEN: "warning", AUTO_REFUNDING: "info", RESOLVED: "success" });
  const kindMap = useStatusMap("exkind", EXCEPTION_KINDS);
  const list = useTabList<ExceptionRow, { status: string }>(NAME, { status: "OPEN" }, (api, q) => api.exceptions.list(q));
  const { api } = list;
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
      empty={c.emptyExceptions}
    />
  );
}
