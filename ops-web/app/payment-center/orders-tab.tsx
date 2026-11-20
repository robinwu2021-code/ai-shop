"use client";

// 支付订单（TR01）。行操作：查看（详情含尝试、退款、调用日志）· 补查（确认）· 人工确认到账（抽屉）· 人工结案（理由弹框）。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { CheckboxField } from "@/components/ui/checkbox";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import { Notice } from "@/components/ui/notice";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell, useDraftOperator } from "@/components/payment/pay-kit";
import { ORDER_STATUSES, type AttemptRow, type OrderRow, type RefundRow } from "@/lib/pay-ops/types";
import { fmtTime, money } from "@/lib/utils";
import { DetailLoader, MiniTable, datetimePattern, parseLocalDateTime, toMinor, usePayCenterApi, useRun, useStatusMap, useTabList, usePc } from "./shared";

const NAME = "orders";
const TONES = { SUCCEEDED: "success", PARTIALLY_REFUNDED: "warning", REFUNDED: "warning", FAILED: "danger", AWAITING_PAYMENT: "info", AWAITING_CONFIRM: "warning", AUTHORIZED: "info" } as const;

export function OrdersTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const operator = useDraftOperator();
  const run = useRun(NAME);
  const statusMap = useStatusMap("order", ORDER_STATUSES, TONES);
  const list = useTabList<OrderRow, { status: string; needsAttention: boolean }>(NAME, { status: "", needsAttention: false }, (api, q) => api.orders.list(q));
  const { api } = list;

  const actions: ActionSpec<OrderRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => c.orderDetailTitle,
      render: (ctx) => (
        <DetailLoader name={NAME} id={ctx.row!.payOrderNo} load={() => api.orders.detail(ctx.row!.payOrderNo)}>
          {(d) => {
            const o = d.record.order;
            return (
              <DetailBody
                sections={[
                  { title: c.sectionBasic, fields: [
                    { label: c.orderNo, value: <PayRefCell value={o.payOrderNo} /> },
                    { label: c.colStatus, value: <StatusBadge value={o.status} map={statusMap} /> },
                    { label: c.outOrderNo, value: o.outOrderNo },
                    { label: c.colChannel, value: o.payChannel },
                    { label: c.colMerchant, value: o.merchantName },
                    { label: c.colStore, value: o.storeName },
                    { label: c.colAmount, value: money(o.amountMinor, o.currency) },
                    { label: c.paidAmount, value: money(o.paidMinor, o.currency) },
                    { label: c.refundedAmount, value: money(o.refundedMinor, o.currency) },
                    { label: c.payMethod, value: o.payMethod },
                    { label: c.colCreated, value: fmtTime(o.createdAt) },
                    { label: c.succeededAt, value: fmtTime(o.succeededAt) },
                    { label: c.followup, value: o.followupStatus },
                  ] },
                  { title: c.sectionAttempts, content: <MiniTable<AttemptRow> rows={d.record.attempts} rowKey={(r) => r.paymentNo} empty={c.emptyAttempts} columns={[
                    { header: c.tradeNo, cell: (r) => r.tradeNo ?? "—" },
                    { header: c.colChannel, cell: (r) => r.payChannel },
                    { header: c.colAmount, numeric: true, cell: (r) => money(r.amountMinor, r.currency) },
                    { header: c.colStatus, cell: (r) => r.status },
                    { header: c.colCreated, cell: (r) => fmtTime(r.at) },
                  ]} /> },
                  { title: c.sectionRefunds, content: <MiniTable<RefundRow> rows={d.record.refunds} rowKey={(r) => r.payRefundNo} empty={c.emptyRefunds} columns={[
                    { header: c.orderNo, cell: (r) => r.payRefundNo },
                    { header: c.colAmount, numeric: true, cell: (r) => money(r.amountMinor, r.currency) },
                    { header: c.colStatus, cell: (r) => r.status },
                    { header: c.reasonField, cell: (r) => r.reason ?? "—" },
                  ]} /> },
                  { title: c.sectionLogs, content: <OrderLogs no={o.payOrderNo} /> },
                ]}
                actions={<DetailActions ctx={ctx} keys={["recover", "manualSettle", "dismiss"]} />}
              />
            );
          }}
        </DetailLoader>
      ),
    },
    {
      key: "recover", label: c.recover, kind: "confirm", perm: "pay:order:manage",
      visible: (r) => !!r && ["AWAITING_PAYMENT", "AWAITING_CONFIRM", "AUTHORIZED"].includes(r.status),
      confirm: () => ({ title: c.recoverTitle, desc: c.recoverDesc }),
      run: (r) => run(() => api.orders.recover(r!.payOrderNo)),
    },
    {
      key: "manualSettle", label: c.manualSettle, kind: "drawer", perm: "pay:order:compensate", title: () => c.manualSettleTitle,
      visible: (r) => !!r?.payChannel && ["AWAITING_PAYMENT", "AUTHORIZED", "AWAITING_CONFIRM", "CLOSED", "FAILED"].includes(r.status),
      render: (ctx) => (
        <>
          <Notice tone="warning" className="mb-4">{c.manualSettleNote}</Notice>
          <ActionForm
          notify={false}
            fields={[
              { key: "amount", label: c.settleAmount, type: "number", required: true, min: 0.01 },
              { key: "paidAt", label: c.settlePaidAt, required: true, pattern: datetimePattern(c), help: c.settlePaidAtHelp },
              { key: "channelTradeNo", label: c.settleTradeNo, maxLength: 64 },
              { key: "reason", label: c.reasonField, type: "textarea", required: true, maxLength: 255, rows: 2, help: c.reasonHelp },
            ]}
            initial={{ amount: ctx.row!.amountMinor / 100, paidAt: "", channelTradeNo: "", reason: "" }}
            draft={operator ? { operator, page: "pay-orders", action: "manualSettle", id: ctx.row!.payOrderNo } : null}
            onSubmit={(v) => run(() => api.orders.manualSettle(ctx.row!.payOrderNo, {
              amountMinor: toMinor(v.amount), paidAt: parseLocalDateTime(v.paidAt), channelTradeNo: String(v.channelTradeNo || "") || undefined, reason: String(v.reason),
            }))}
            onDone={ctx.back ?? ctx.close}
          />
        </>
      ),
    },
    {
      key: "dismiss", label: c.dismiss, kind: "dialog", perm: "pay:order:manage", danger: true,
      visible: (r) => r?.followupStatus === "ESCALATED",
      confirm: () => ({ title: c.dismissTitle, desc: c.dismissDesc, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.orders.dismiss(r!.payOrderNo, reason)),
    },
  ];

  const columns: Column<OrderRow>[] = [
    { header: c.orderNo, cell: (r) => <PayRefCell value={r.payOrderNo} /> },
    { header: c.colMerchant, cell: (r) => r.merchantName ?? "—" },
    { header: c.colStore, cell: (r) => r.storeName ?? "—" },
    { header: c.colAmount, numeric: true, cell: (r) => money(r.amountMinor, r.currency) },
    { header: c.colChannel, cell: (r) => r.payChannel ?? "—" },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: c.colCreated, cell: (r) => fmtTime(r.createdAt) },
  ];

  return (
    <PayListPage<OrderRow>
      header={header}
      help={c.ordersDesc}
      search={list.keyword}
      onSearch={list.setKeyword}
      searchPlaceholder={c.ordersSearch}
      filters={<>
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />
        <CheckboxField label={c.ordersNeedsAttention} checked={list.filters.needsAttention} onChange={(v) => list.setFilter("needsAttention", v === true)} />
      </>}
      query={list.query}
      page={list.page}
      size={list.size}
      onPage={list.setPage}
      onSize={list.setSize}
      columns={columns}
      rowKey={(r) => r.payOrderNo}
      actions={actions}
      empty={c.emptyOrders}
      resolve={(id) => api.orders.detail(id).then((d) => d.record.order)}
      draftPage="pay-orders"
    />
  );
}

function OrderLogs({ no }: { no: string }) {
  const c = usePc();
  const api = usePayCenterApi();
  return (
    <DetailLoader name={NAME} id={`${no}:logs`} load={() => api.orders.logs(no)}>
      {(d) => <MiniTable rows={d.entries} rowKey={(r) => `${r.kind}:${r.at}:${r.title}`} empty={c.emptyLogs} columns={[
        { header: c.colCreated, cell: (r) => fmtTime(r.at) },
        { header: c.subject, cell: (r) => r.title },
        { header: c.colStatus, cell: (r) => r.status ?? "—" },
        { header: "ms", numeric: true, cell: (r) => r.elapsedMs ?? "—" },
      ]} />}
    </DetailLoader>
  );
}
