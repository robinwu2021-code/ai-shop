"use client";

// P3a 资金结算：资金流水（FI03）、结算记录（FI06）、提现申请（FI07）、出款记录（FI08）。
// 契约：near-pay docs/api/handbook/08-运营端P3a接口落地.md。资金流水、结算记录只读；本服务不打款，提现「通过」后由渠道出款。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import { Notice } from "@/components/ui/notice";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell, useDraftOperator } from "@/components/payment/pay-kit";
import { PAYOUT_STATUSES, SETTLEMENT_STATUSES, WITHDRAWAL_STATUSES, type LedgerEntryRow, type PayoutRow, type SettlementRow, type WithdrawalRow } from "@/lib/pay-ops/types";
import { fmtTime, money } from "@/lib/utils";
import { DetailLoader, MiniTable, useRun, useStatusMap, useTabList, usePc, usePayCenterApi, amt, useInstanceCurrency } from "./shared";

export function LedgerTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const list = useTabList<LedgerEntryRow, { direction: string }>("ledger-entries", { direction: "" }, (api, q) => api.ledger.list({ ...q, keyword: undefined, voucherNo: q.keyword }));
  const { api } = list;
  const dc = (d: string) => (d === "D" ? c.debit : c.credit);
  const dirMap = { D: { label: c.debit, tone: "muted" as const }, C: { label: c.credit, tone: "muted" as const } };
  const actions: ActionSpec<LedgerEntryRow>[] = [{
    key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => c.ledgerDetailTitle,
    render: (ctx) => (
      <DetailLoader name="ledger-entries" id={ctx.row!.entryNo} load={() => api.ledger.detail(ctx.row!.entryNo)}>
        {(d) => {
          const v = d.record.voucher;
          return <DetailBody sections={[
            { title: c.sectionVoucher, fields: [
              { label: c.voucherNo, value: <PayRefCell value={v.voucherNo} /> },
              { label: c.voucherType, value: v.voucherType },
              { label: c.bizDate, value: v.bizDate },
              { label: c.totalLabel, value: money(v.totalMinor, v.currency) },
              { label: c.reversalOf, value: v.reversalOfVoucherNo },
              { label: c.colCreated, value: fmtTime(v.createdAt) },
              { label: c.memo, value: v.memo, full: true },
            ] },
            { title: c.sectionLines, content: <MiniTable rows={v.lines} rowKey={(l) => l.entryNo} empty={c.emptyVoucherLines} columns={[
              { header: "#", cell: (l) => l.lineNo },
              { header: c.account, cell: (l) => l.account },
              { header: c.debitCredit, cell: (l) => dc(l.direction) },
              { header: c.colAmount, numeric: true, cell: (l) => money(l.amountMinor, l.currency) },
              { header: c.orderNo, cell: (l) => l.payOrderNo ?? "—" },
            ]} /> },
          ]} />;
        }}
      </DetailLoader>
    ),
  }];
  const columns: Column<LedgerEntryRow>[] = [
    { header: c.voucherNo, cell: (r) => <PayRefCell value={r.entryNo} /> },
    { header: c.voucherType, cell: (r) => r.voucherType },
    { header: c.account, cell: (r) => r.account },
    { header: c.debitCredit, cell: (r) => dc(r.direction) },
    { header: c.colAmount, numeric: true, cell: (r) => money(r.amountMinor, r.currency) },
    { header: c.orderNo, cell: (r) => r.payOrderNo ?? "—" },
    { header: c.bizDate, cell: (r) => r.bizDate },
  ];
  return (
    <PayListPage<LedgerEntryRow> header={header} help={c.ledgerDesc} search={list.keyword} onSearch={list.setKeyword} searchPlaceholder={c.ledgerSearch}
      filters={<FilterSelect value={list.filters.direction} onChange={(v) => list.setFilter("direction", v)} options={dirMap} allLabel={c.debitCredit} />}
      query={list.query} page={list.page} size={list.size} onPage={list.setPage} onSize={list.setSize}
      columns={columns} rowKey={(r) => r.entryNo} actions={actions} empty={c.emptyLedger} />
  );
}

export function SettlementsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const statusMap = useStatusMap("settle", SETTLEMENT_STATUSES, { RELEASED: "success", RECONCILED: "info", BLOCKED: "danger", RECONCILING: "warning" });
  const scopeMap = useStatusMap("rscope", ["SELF_ONLY", "BOTH"], { BOTH: "success", SELF_ONLY: "warning" });
  const payoutMap = useStatusMap("payout", PAYOUT_STATUSES, { PAID: "success", MATCHED: "success", FAILED: "danger" });
  const list = useTabList<SettlementRow, { status: string }>("settlements", { status: "" }, (api, q) => api.settlements.list({ ...q, keyword: undefined, settlementNo: q.keyword }));
  const { api } = list;
  const actions: ActionSpec<SettlementRow>[] = [{
    key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => c.settlementDetailTitle,
    render: (ctx) => (
      <DetailLoader name="settlements" id={ctx.row!.settlementNo} load={() => api.settlements.detail(ctx.row!.settlementNo)}>
        {(d) => {
          const s = d.record.settlement;
          return <DetailBody sections={[
            { title: c.sectionBasic, fields: [
              { label: c.settlementNo, value: <PayRefCell value={s.settlementNo} /> },
              { label: c.colStatus, value: <StatusBadge value={s.status} map={statusMap} /> },
              { label: c.colMerchant, value: s.merchantNo },
              { label: c.colChannel, value: s.payChannel },
              { label: c.settleCycle, value: s.settleCycle },
              { label: c.period, value: `${fmtTime(s.periodFrom)} – ${fmtTime(s.periodTo)}`, full: true },
              { label: c.billCount, value: s.billCount },
              { label: c.grossAmount, value: money(s.grossMinor, s.currency) },
              { label: c.netAmount, value: money(s.netMinor, s.currency) },
              { label: c.reconScope, value: <StatusBadge value={s.reconScope} map={scopeMap} /> },
              { label: c.dueAt, value: fmtTime(s.dueAt) },
              { label: c.releasedAt, value: fmtTime(s.releasedAt) },
              { label: c.blockedReason, value: s.blockedReason, full: true },
            ] },
            { title: c.sectionSettlementPayouts, content: <MiniTable rows={d.record.payouts} rowKey={(p) => p.payoutNo} empty={c.emptySettlementPayouts} columns={[
              { header: c.payoutNo, cell: (p) => p.payoutNo },
              { header: c.colAmount, numeric: true, cell: (p) => money(p.amountMinor, p.currency) },
              { header: c.colStatus, cell: (p) => <StatusBadge value={p.status} map={payoutMap} /> },
              { header: c.paymentRef, cell: (p) => p.paymentRef ?? "—" },
            ]} /> },
          ]} />;
        }}
      </DetailLoader>
    ),
  }];
  const columns: Column<SettlementRow>[] = [
    { header: c.settlementNo, cell: (r) => <PayRefCell value={r.settlementNo} /> },
    { header: c.colMerchant, cell: (r) => r.merchantNo },
    { header: c.netAmount, numeric: true, cell: (r) => money(r.netMinor, r.currency) },
    { header: c.billCount, numeric: true, cell: (r) => r.billCount },
    { header: c.reconScope, cell: (r) => <StatusBadge value={r.reconScope} map={scopeMap} /> },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: c.dueAt, cell: (r) => fmtTime(r.dueAt) },
  ];
  return (
    <PayListPage<SettlementRow> header={header} help={c.settlementsDesc} search={list.keyword} onSearch={list.setKeyword} searchPlaceholder={c.settlementsSearch}
      filters={<FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />}
      query={list.query} page={list.page} size={list.size} onPage={list.setPage} onSize={list.setSize}
      columns={columns} rowKey={(r) => r.settlementNo} actions={actions} empty={c.emptySettlements} />
  );
}

export function WithdrawalsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const run = useRun("withdrawals");
  const statusMap = useStatusMap("wd", WITHDRAWAL_STATUSES, { PENDING: "warning", APPROVED: "info", PAID: "success", REJECTED: "danger", FAILED: "danger" });
  const list = useTabList<WithdrawalRow, { status: string }>("withdrawals", { status: "PENDING" }, (api, q) => api.withdrawals.list({ ...q, keyword: undefined, withdrawalNo: q.keyword }));
  const { api } = list;
  const cur = useInstanceCurrency();
  const review = (decision: "APPROVE" | "REJECT") => (r: WithdrawalRow | null, reason: string) => run(() => api.withdrawals.review(r!.withdrawalNo, { expectedVersion: r!.version, reason, decision }));
  const actions: ActionSpec<WithdrawalRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[560px]", title: () => c.withdrawalDetailTitle,
      render: (ctx) => {
        const w = ctx.row!;
        return <DetailBody sections={[{ title: c.sectionBasic, fields: [
          { label: c.withdrawalNo, value: <PayRefCell value={w.withdrawalNo} /> },
          { label: c.colStatus, value: <StatusBadge value={w.status} map={statusMap} /> },
          { label: c.colMerchant, value: w.merchantName ?? w.merchantNo },
          { label: c.merchantNo, value: w.merchantNo },
          { label: c.colAmount, value: amt(w.amountMinor, cur) },
          { label: c.availableBalance, value: amt(w.availableBalanceMinor, cur) },
          { label: c.bankAccount, value: w.bankAccountMasked, full: true },
          { label: c.appliedAt, value: fmtTime(w.appliedAt) },
          { label: c.decidedBy, value: w.decidedBy ? `${w.decidedBy} · ${fmtTime(w.decidedAt)}` : "—" },
          { label: c.remarkLabel, value: w.remark, full: true },
        ] }]} actions={<DetailActions ctx={ctx} keys={["reject", "cancel", "approve"]} />} />;
      },
    },
    { key: "approve", label: c.wdApprove, kind: "dialog", perm: "pay:withdrawal:review", visible: (r) => !!r && ["PENDING", "FAILED"].includes(r.status),
      confirm: () => ({ title: c.wdApproveTitle, desc: c.wdApproveDesc, reasonHint: c.reasonHint }), run: review("APPROVE") },
    { key: "reject", label: c.wdReject, kind: "dialog", perm: "pay:withdrawal:review", danger: true, visible: (r) => !!r && ["PENDING", "FAILED"].includes(r.status),
      confirm: () => ({ title: c.wdRejectTitle, desc: c.wdRejectDesc, danger: true, reasonHint: c.wdRejectDesc }), run: review("REJECT") },
    { key: "cancel", label: c.wdCancel, kind: "dialog", perm: "pay:withdrawal:manage", danger: true, visible: (r) => r?.status === "PENDING",
      confirm: () => ({ title: c.wdCancelTitle, danger: true, reasonHint: c.reasonHint }),
      run: (r, reason) => run(() => api.withdrawals.cancel(r!.withdrawalNo, { expectedVersion: r!.version, reason })) },
  ];
  const columns: Column<WithdrawalRow>[] = [
    { header: c.withdrawalNo, cell: (r) => <PayRefCell value={r.withdrawalNo} /> },
    { header: c.colMerchant, cell: (r) => r.merchantName ?? r.merchantNo },
    { header: c.colAmount, numeric: true, cell: (r) => amt(r.amountMinor, cur) },
    { header: c.availableBalance, numeric: true, cell: (r) => amt(r.availableBalanceMinor, cur) },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: c.appliedAt, cell: (r) => fmtTime(r.appliedAt) },
  ];
  return (
    <PayListPage<WithdrawalRow> header={header} help={c.withdrawalsDesc} search={list.keyword} onSearch={list.setKeyword} searchPlaceholder={c.withdrawalsSearch}
      filters={<FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />}
      query={list.query} page={list.page} size={list.size} onPage={list.setPage} onSize={list.setSize}
      columns={columns} rowKey={(r) => r.withdrawalNo} actions={actions} resolve={(id) => api.withdrawals.detail(id).then((d) => d.record)} empty={c.emptyWithdrawals} />
  );
}

export function PayoutsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const operator = useDraftOperator();
  const run = useRun("payouts");
  const api = usePayCenterApi();
  const statusMap = useStatusMap("payout", PAYOUT_STATUSES, { PAID: "success", MATCHED: "success", EXPORTED: "info", FAILED: "danger" });
  const chMap = useStatusMap("pch", ["MANUAL", "BANK_API"]);
  const list = useTabList<PayoutRow, { status: string }>("payouts", { status: "" }, (a, q) => a.payouts.list({ ...q, keyword: undefined, payoutNo: q.keyword }));
  const actions: ActionSpec<PayoutRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[560px]", title: () => c.payoutDetailTitle,
      render: (ctx) => (
        <DetailLoader name="payouts" id={ctx.row!.payoutNo} load={() => api.payouts.detail(ctx.row!.payoutNo)}>
          {(d) => {
            const p = d.record.payout;
            return <DetailBody sections={[
              { title: c.sectionBasic, fields: [
                { label: c.payoutNo, value: <PayRefCell value={p.payoutNo} /> },
                { label: c.colStatus, value: <StatusBadge value={p.status} map={statusMap} /> },
                { label: c.batchNo, value: p.batchNo },
                { label: c.colMerchant, value: p.merchantNo },
                { label: c.accountName, value: p.accountName },
                { label: c.bankName, value: [p.bankName, p.bankBranch].filter(Boolean).join(" · ") },
                { label: c.accountNo, value: p.accountNoMasked },
                { label: c.colAmount, value: money(p.amountMinor, p.currency) },
                { label: c.payoutChannel, value: chMap[p.channel]?.label ?? p.channel },
                { label: c.paymentRef, value: p.paymentRef },
                { label: c.paidAt, value: p.paidAt ? `${fmtTime(p.paidAt)} · ${p.paidBy ?? ""}` : "—" },
                { label: c.failReason, value: p.failReason, full: true },
              ] },
              { title: c.sectionSettleDocs, content: <MiniTable rows={d.record.settleDocumentNos.map((n, i) => ({ n, s: d.record.settleNos[i] ?? "" }))} rowKey={(x) => x.n} empty={c.emptySettleDocs} columns={[
                { header: c.settlementNo, cell: (x) => x.n },
                { header: c.subject, cell: (x) => x.s || "—" },
              ]} /> },
            ]} actions={<DetailActions ctx={ctx} keys={d.allowedActions.includes("receipt") ? ["receipt"] : []} />} />;
          }}
        </DetailLoader>
      ),
    },
    {
      key: "receipt", label: c.receipt, kind: "drawer", perm: "pay:payout:manage", title: () => c.receiptTitle, visible: (r) => !!r && ["PENDING", "EXPORTED"].includes(r.status),
      render: (ctx) => (
        <>
          <Notice tone="warning" className="mb-4">{c.receiptNote}</Notice>
          <ActionForm notify={false}
            fields={[
              { key: "result", label: c.receiptResult, type: "select", required: true, options: [{ value: "PAID", label: c.receiptPaid }, { value: "FAILED", label: c.receiptFailed }] },
              { key: "paymentRef", label: c.paymentRef, maxLength: 64, help: c.paymentRefHelp, disabledWhen: (v) => v.result !== "PAID" },
              { key: "reason", label: c.reasonField, type: "textarea", required: true, maxLength: 255, rows: 2, help: c.reasonHelp },
            ]}
            initial={{ result: "PAID", paymentRef: "", reason: "" }}
            draft={operator ? { operator, page: "pay-payouts", action: "receipt", id: ctx.row!.payoutNo } : null}
            baseVersion={ctx.row!.version}
            onSubmit={(v) => run(async () => {
              if (v.result === "PAID" && !String(v.paymentRef ?? "").trim()) throw new Error(c.paymentRefHelp);
              return api.payouts.receipt(ctx.row!.payoutNo, { expectedVersion: ctx.row!.version, reason: String(v.reason), result: v.result as "PAID", paymentRef: v.result === "PAID" ? String(v.paymentRef).trim() : null });
            })}
            onDone={ctx.back ?? ctx.close} />
        </>
      ),
    },
  ];
  const columns: Column<PayoutRow>[] = [
    { header: c.payoutNo, cell: (r) => <PayRefCell value={r.payoutNo} /> },
    { header: c.batchNo, cell: (r) => r.batchNo },
    { header: c.accountName, cell: (r) => r.accountName ?? "—" },
    { header: c.colAmount, numeric: true, cell: (r) => money(r.amountMinor, r.currency) },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: c.colCreated, cell: (r) => fmtTime(r.createdAt) },
  ];
  return (
    <PayListPage<PayoutRow> header={header} help={c.payoutsDesc} search={list.keyword} onSearch={list.setKeyword} searchPlaceholder={c.payoutsSearch}
      filters={<FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />}
      query={list.query} page={list.page} size={list.size} onPage={list.setPage} onSize={list.setSize}
      columns={columns} rowKey={(r) => r.payoutNo} actions={actions} resolve={(id) => api.payouts.detail(id).then((d) => d.record.payout)}
      draftPage="pay-payouts" empty={c.emptyPayouts} />
  );
}
