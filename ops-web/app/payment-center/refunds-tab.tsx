"use client";

// 退款订单（TR03）。near-pay 只有 v1：一次最多返回最近 200 笔、不分页 —— 在前端按状态 / 关键字筛选后分页。
// 行操作：查看 · 同意 / 驳回（理由弹框，pay:refund:review）。复核要两个不同的人，申请人不能复核自己的（near-pay 强制）。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell } from "@/app/payment-center/_kit/pay-kit";
import { payCenterHref } from "@/app/payment-center/module";
import { REFUND_STATUSES, type RefundListRow } from "@/app/payment-center/_api/types";
import { fmtTime, money } from "@/lib/utils";
import { useRun, useStatusMap, useTabList, usePc } from "./shared";

const NAME = "refunds";
const PENDING = ["PENDING_REVIEW", "PENDING_SECOND_REVIEW"];

export function RefundsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const run = useRun(NAME);
  const statusMap = useStatusMap("refundst", REFUND_STATUSES, { SUCCEEDED: "success", PENDING_REVIEW: "warning", PENDING_SECOND_REVIEW: "warning", REJECTED: "danger", FAILED: "danger", DISPATCHED: "info" });
  const list = useTabList<RefundListRow, { status: string }>(NAME, { status: "" }, async (api, q) => {
    const all = await api.refunds.list({ status: q.status as string | undefined });
    const kw = String(q.keyword ?? "").trim().toLowerCase();
    const hit = kw ? all.filter((r) => r.payRefundNo.toLowerCase().includes(kw) || r.payOrderNo.toLowerCase().includes(kw)) : all;
    const page = Number(q.page), size = Number(q.size);
    return { records: hit.slice((page - 1) * size, page * size), total: hit.length };
  });
  const { api } = list;
  const orderHref = (no: string) => `${payCenterHref("orders")}&action=view&id=${encodeURIComponent(no)}`;
  const review = (approve: boolean) => (r: RefundListRow | null, reason: string) => run(() => api.refunds.review(r!.payRefundNo, { approve, reason }));

  const actions: ActionSpec<RefundListRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[560px]", title: () => c.refundDetailTitle,
      render: (ctx) => {
        const r = ctx.row!;
        return (
          <DetailBody
            sections={[{ title: c.sectionBasic, fields: [
              { label: c.refundNo, value: <PayRefCell value={r.payRefundNo} /> },
              { label: c.colStatus, value: <StatusBadge value={r.status} map={statusMap} /> },
              { label: c.orderNo, value: <PayRefCell value={r.payOrderNo} href={orderHref(r.payOrderNo)} /> },
              { label: c.colAmount, value: money(r.amountMinor, r.currency) },
              { label: c.requestedByCol, value: r.requestedBy },
              { label: c.colCreated, value: fmtTime(r.createdAt) },
              { label: c.reasonField, value: r.reason, full: true },
              { label: c.hitReasons, value: r.hitReasons, full: true },
            ] }]}
            actions={<DetailActions ctx={ctx} keys={["reject", "approve"]} />}
          />
        );
      },
    },
    { key: "approve", label: c.refundApprove, kind: "dialog", perm: "pay:refund:review", visible: (r) => !!r && PENDING.includes(r.status),
      confirm: () => ({ title: c.refundApproveTitle, desc: c.refundReviewDesc, reasonHint: c.reasonHint }), run: review(true) },
    { key: "reject", label: c.refundReject, kind: "dialog", perm: "pay:refund:review", danger: true, visible: (r) => !!r && PENDING.includes(r.status),
      confirm: () => ({ title: c.refundRejectTitle, danger: true, reasonHint: c.reasonHint }), run: review(false) },
  ];

  const columns: Column<RefundListRow>[] = [
    { header: c.refundNo, cell: (r) => <PayRefCell value={r.payRefundNo} /> },
    { header: c.orderNo, cell: (r) => <PayRefCell value={r.payOrderNo} href={orderHref(r.payOrderNo)} /> },
    { header: c.colAmount, numeric: true, cell: (r) => money(r.amountMinor, r.currency) },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: c.requestedByCol, cell: (r) => r.requestedBy ?? "—" },
    { header: c.colCreated, cell: (r) => fmtTime(r.createdAt) },
  ];

  return (
    <PayListPage<RefundListRow>
      header={header}
      help={`${c.refundsDesc} ${c.refundsLimitNote}`}
      search={list.keyword}
      onSearch={list.setKeyword}
      searchPlaceholder={c.ordersSearch}
      filters={<FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => r.payRefundNo}
      actions={actions}
      empty={c.emptyRefundsList}
    />
  );
}
