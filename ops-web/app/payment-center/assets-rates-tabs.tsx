"use client";

// P3a：储值账户 / 储值交易（TR06 / TR07，只读）与渠道成本 / 佣金规则（OP05 / OP06，只增不改）。
// 契约：near-pay docs/api/handbook/08-运营端P3a接口落地.md。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import { Notice } from "@/components/ui/notice";
import type { FieldDef } from "@/components/ui/form-drawer";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell, useDraftOperator } from "@/app/payment-center/_kit/pay-kit";
import { RATE_STATUSES, type ChannelCostRow, type CommissionRuleRow, type StoredValueAccountRow, type StoredValueTransactionRow } from "@/app/payment-center/_api/types";
import { fmtTime, money } from "@/lib/utils";
import { DetailLoader, MiniTable, datetimePattern, parseLocalDateTime, reasonField, toMinor, useRun, useStatusMap, useTabList, usePc, usePayCenterApi, type C, amt, useInstanceCurrency } from "./shared";

const bps = (n: number) => `${(n / 100).toFixed(2)}%`;
const signed = (n: number, cur?: string) => `${n > 0 ? "+" : ""}${money(n, cur)}`;

function txColumns(c: C): Column<StoredValueTransactionRow>[] {
  return [
    { header: c.transactionNo, cell: (t) => <PayRefCell value={t.transactionNo} /> },
    { header: c.bizTypeLabel, cell: (t) => t.bizType },
    { header: c.principalDelta, numeric: true, cell: (t) => signed(t.principalDeltaMinor, t.currency) },
    { header: c.bonusDelta, numeric: true, cell: (t) => signed(t.bonusDeltaMinor, t.currency) },
    { header: c.afterBalance, numeric: true, cell: (t) => money(t.afterPrincipalMinor + t.afterBonusMinor, t.currency) },
    { header: c.occurredAtLabel, cell: (t) => fmtTime(t.occurredAt) },
  ];
}

export function SvAccountsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const statusMap = useStatusMap("svacc", ["ACTIVE", "FROZEN"], { ACTIVE: "success", FROZEN: "warning" });
  const list = useTabList<StoredValueAccountRow, { status: string }>("sv-accounts", { status: "" }, (api, q) => api.storedValue.accounts({ ...q, keyword: undefined, accountNo: q.keyword }));
  const { api } = list;
  const actions: ActionSpec<StoredValueAccountRow>[] = [{
    key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => c.svAccountDetailTitle,
    render: (ctx) => (
      <DetailLoader name="sv-accounts" id={ctx.row!.accountNo} load={() => api.storedValue.account(ctx.row!.accountNo)}>
        {(d) => {
          const a = d.record.account;
          return <DetailBody sections={[
            { title: c.sectionBasic, fields: [
              { label: c.svAccountNo, value: <PayRefCell value={a.accountNo} /> },
              { label: c.colStatus, value: <StatusBadge value={a.status} map={statusMap} /> },
              { label: c.colMerchant, value: a.merchantNo },
              { label: c.colStore, value: a.storeNo },
              { label: c.customerNo, value: a.customerNo },
              { label: c.userNo, value: a.userNoMasked },
              { label: c.principal, value: money(a.principalMinor, a.currency) },
              { label: c.bonus, value: money(a.bonusMinor, a.currency) },
              { label: c.held, value: money(a.heldPrincipalMinor + a.heldBonusMinor, a.currency) },
              { label: c.available, value: money(a.availableMinor, a.currency) },
              { label: c.issuerNo, value: a.issuerNo },
              { label: c.colUpdated, value: fmtTime(a.updatedAt) },
            ] },
            { title: c.sectionRecentTx, content: <MiniTable rows={d.record.recentTransactions} rowKey={(t) => t.transactionNo} empty={c.emptyRecentTx} columns={txColumns(c)} /> },
          ]} />;
        }}
      </DetailLoader>
    ),
  }];
  const columns: Column<StoredValueAccountRow>[] = [
    { header: c.svAccountNo, cell: (r) => <PayRefCell value={r.accountNo} /> },
    { header: c.colMerchant, cell: (r) => r.merchantNo },
    { header: c.userNo, cell: (r) => r.userNoMasked ?? "—" },
    { header: c.principal, numeric: true, cell: (r) => money(r.principalMinor, r.currency) },
    { header: c.bonus, numeric: true, cell: (r) => money(r.bonusMinor, r.currency) },
    { header: c.available, numeric: true, cell: (r) => money(r.availableMinor, r.currency) },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
  ];
  return (
    <PayListPage<StoredValueAccountRow> header={header} help={c.svAccountsDesc} search={list.keyword} onSearch={list.setKeyword} searchPlaceholder={c.svAccountsSearch}
      filters={<FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />}
      query={list.query} page={list.page} size={list.size} onPage={list.setPage} onSize={list.setSize}
      columns={columns} rowKey={(r) => r.accountNo} actions={actions} empty={c.emptySvAccounts} />
  );
}

export function SvTransactionsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const list = useTabList<StoredValueTransactionRow, Record<string, never>>("sv-transactions", {}, (api, q) => api.storedValue.transactions({ ...q, keyword: undefined, transactionNo: q.keyword }));
  const actions: ActionSpec<StoredValueTransactionRow>[] = [{
    key: "view", label: c.view, kind: "drawer", width: "w-[560px]", title: () => c.svTxDesc,
    render: (ctx) => {
      const t = ctx.row!;
      return <DetailBody sections={[{ title: c.sectionBasic, fields: [
        { label: c.transactionNo, value: <PayRefCell value={t.transactionNo} /> },
        { label: c.bizTypeLabel, value: t.bizType },
        { label: c.svAccountNo, value: t.accountNo },
        { label: c.bizNoLabel, value: t.bizNo },
        { label: c.colMerchant, value: t.merchantNo },
        { label: c.colStore, value: t.storeNo },
        { label: c.principalDelta, value: signed(t.principalDeltaMinor, t.currency) },
        { label: c.bonusDelta, value: signed(t.bonusDeltaMinor, t.currency) },
        { label: c.afterBalance, value: `${money(t.afterPrincipalMinor, t.currency)} + ${money(t.afterBonusMinor, t.currency)}` },
        { label: c.occurredAtLabel, value: fmtTime(t.occurredAt) },
      ] }]} />;
    },
  }];
  return (
    <PayListPage<StoredValueTransactionRow> header={header} help={c.svTxDesc} search={list.keyword} onSearch={list.setKeyword} searchPlaceholder={c.svTxSearch}
      query={list.query} page={list.page} size={list.size} onPage={list.setPage} onSize={list.setSize}
      columns={txColumns(c)} rowKey={(r) => r.transactionNo} actions={actions} empty={c.emptySvTx} />
  );
}

function costFields(c: C): FieldDef[] {
  return [
    { key: "payChannel", label: c.channelCode, required: true, maxLength: 32, readOnlyOnEdit: true },
    { key: "payMethod", label: c.payMethodLabel, maxLength: 32, help: c.dimHelp, readOnlyOnEdit: true },
    { key: "legalForm", label: c.legalFormLabel, maxLength: 32, help: c.dimHelp, readOnlyOnEdit: true },
    { key: "market", label: c.market, maxLength: 8, help: c.dimHelp, readOnlyOnEdit: true },
    { key: "rateBps", label: c.rateBps, type: "number", required: true, min: 0, max: 10000, help: c.rateBpsHelp },
    { key: "minFee", label: c.minFee, type: "number", min: 0 },
    { key: "effectiveFrom", label: c.effectiveFrom, required: true, pattern: datetimePattern(c), help: c.effectiveFromHelp },
    reasonField(c),
  ];
}

export function ChannelCostsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const operator = useDraftOperator();
  const run = useRun("channel-costs");
  const api = usePayCenterApi();
  const statusMap = useStatusMap("rate", RATE_STATUSES, { ACTIVE: "success", SCHEDULED: "info", SUPERSEDED: "muted", DISABLED: "danger" });
  const cur = useInstanceCurrency();
  const list = useTabList<ChannelCostRow, { status: string }>("channel-costs", { status: "" }, (a, q) => a.channelCosts.list({ ...q, keyword: undefined, payChannel: q.keyword }));
  const save = (v: Record<string, unknown>) => ({
    reason: String(v.reason), payChannel: String(v.payChannel).trim(), payMethod: String(v.payMethod ?? "").trim() || undefined,
    legalForm: String(v.legalForm ?? "").trim() || undefined, market: String(v.market ?? "").trim() || undefined,
    rateBps: Number(v.rateBps), minFeeMinor: v.minFee === "" || v.minFee == null ? undefined : toMinor(v.minFee), effectiveFrom: parseLocalDateTime(v.effectiveFrom),
  });
  const actions: ActionSpec<ChannelCostRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => c.costDetailTitle,
      render: (ctx) => (
        <DetailLoader name="channel-costs" id={ctx.row!.costNo} load={async () => ({ d: await api.channelCosts.detail(ctx.row!.costNo), v: await api.channelCosts.versions(ctx.row!.costNo) })}>
          {({ d, v }) => {
            const r = d.record;
            return <DetailBody sections={[
              { title: c.sectionBasic, fields: [
                { label: c.revision, value: <PayRefCell value={r.costNo} /> },
                { label: c.colStatus, value: <StatusBadge value={r.status} map={statusMap} /> },
                { label: c.colChannel, value: r.payChannel },
                { label: c.payMethodLabel, value: r.payMethod },
                { label: c.legalFormLabel, value: r.legalForm },
                { label: c.market, value: r.market },
                { label: c.rateBps, value: `${r.rateBps} (${bps(r.rateBps)})` },
                { label: c.minFee, value: amt(r.minFeeMinor, cur) },
                { label: c.effectiveFrom, value: fmtTime(r.effectiveFrom) },
                { label: c.colCreated, value: `${fmtTime(r.createdAt)} · ${r.createdBy ?? ""}` },
                { label: c.reasonField, value: r.remark, full: true },
              ] },
              { title: c.sectionRevisions, content: <MiniTable rows={v.filter((x) => x.costNo !== r.costNo)} rowKey={(x) => x.costNo} empty={c.emptyCostVersions} columns={[
                { header: c.revision, cell: (x) => x.costNo },
                { header: c.rateBps, numeric: true, cell: (x) => bps(x.rateBps) },
                { header: c.effectiveFrom, cell: (x) => fmtTime(x.effectiveFrom) },
                { header: c.colStatus, cell: (x) => <StatusBadge value={x.status} map={statusMap} /> },
              ]} /> },
            ]} actions={<DetailActions ctx={ctx} keys={d.allowedActions.includes("revise") ? ["revise"] : []} />} />;
          }}
        </DetailLoader>
      ),
    },
    {
      key: "revise", label: c.reviseCost, kind: "drawer", perm: "pay:channel-cost:manage", width: "w-[560px]", title: () => c.reviseCostTitle, visible: (r) => r?.status !== "DISABLED",
      render: (ctx) => (
        <>
          <Notice tone="info" className="mb-4">{c.reviseNote}</Notice>
          <ActionForm notify={false} fields={costFields(c)} isEdit
            initial={{ payChannel: ctx.row!.payChannel, payMethod: ctx.row!.payMethod, legalForm: ctx.row!.legalForm, market: ctx.row!.market, rateBps: ctx.row!.rateBps, minFee: ctx.row!.minFeeMinor / 100, effectiveFrom: "", reason: "" }}
            draft={operator ? { operator, page: "pay-channel-costs", action: "revise", id: ctx.row!.costNo } : null}
            onSubmit={(v) => run(async () => api.channelCosts.revise(ctx.row!.costNo, { ...save(v), expectedVersion: (await api.channelCosts.detail(ctx.row!.costNo)).version }))}
            onDone={ctx.back ?? ctx.close} />
        </>
      ),
    },
  ];
  const pageActions: ActionSpec<ChannelCostRow>[] = [{
    key: "create", label: c.create, kind: "drawer", perm: "pay:channel-cost:manage", width: "w-[560px]", title: () => c.createCostTitle,
    render: (ctx) => (
      <ActionForm notify={false} fields={costFields(c)}
        initial={{ payChannel: "", payMethod: "", legalForm: "", market: "TW", rateBps: "", minFee: 0, effectiveFrom: "", reason: "" }}
        draft={operator ? { operator, page: "pay-channel-costs", action: "create" } : null}
        onSubmit={(v) => run(() => api.channelCosts.create(save(v)))} onDone={ctx.close} />
    ),
  }];
  const columns: Column<ChannelCostRow>[] = [
    { header: c.colChannel, cell: (r) => r.payChannel },
    { header: c.payMethodLabel, cell: (r) => r.payMethod },
    { header: c.market, cell: (r) => r.market },
    { header: c.rateBps, numeric: true, cell: (r) => bps(r.rateBps) },
    { header: c.minFee, numeric: true, cell: (r) => amt(r.minFeeMinor, cur) },
    { header: c.effectiveFrom, cell: (r) => fmtTime(r.effectiveFrom) },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
  ];
  return (
    <PayListPage<ChannelCostRow> header={header} help={c.costsDesc} search={list.keyword} onSearch={list.setKeyword} searchPlaceholder={c.channelsSearch}
      filters={<FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />}
      query={list.query} page={list.page} size={list.size} onPage={list.setPage} onSize={list.setSize}
      columns={columns} rowKey={(r) => r.costNo} actions={actions} pageActions={pageActions}
      resolve={(id) => api.channelCosts.detail(id).then((d) => d.record)} draftPage="pay-channel-costs" empty={c.emptyCosts} />
  );
}

export function CommissionRulesTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const operator = useDraftOperator();
  const run = useRun("commission-rules");
  const api = usePayCenterApi();
  const statusMap = useStatusMap("rate", RATE_STATUSES, { ACTIVE: "success", SCHEDULED: "info", SUPERSEDED: "muted" });
  const list = useTabList<CommissionRuleRow, { status: string }>("commission-rules", { status: "" }, (a, q) => a.commissionRules.list({ ...q, keyword: undefined, businessMode: q.keyword }));
  const actions: ActionSpec<CommissionRuleRow>[] = [{
    key: "view", label: c.view, kind: "drawer", title: () => c.commissionDetailTitle,
    render: (ctx) => {
      const r = ctx.row!;
      return <DetailBody sections={[{ title: c.sectionBasic, fields: [
        { label: c.revision, value: <PayRefCell value={r.ruleNo} /> },
        { label: c.colStatus, value: <StatusBadge value={r.status} map={statusMap} /> },
        { label: c.businessMode, value: r.businessMode },
        { label: c.trafficSourceLabel, value: r.trafficSource },
        { label: c.rateBps, value: `${r.rateBps} (${bps(r.rateBps)})` },
        { label: c.effectiveFrom, value: fmtTime(r.effectiveFrom) },
        { label: c.colCreated, value: r.createdAt ? `${fmtTime(r.createdAt)} · ${r.createdBy ?? ""}` : "—" },
        { label: c.reasonField, value: r.remark, full: true },
      ] }]} />;
    },
  }];
  const pageActions: ActionSpec<CommissionRuleRow>[] = [{
    key: "create", label: c.create, kind: "drawer", perm: "pay:fee-plan:manage", title: () => c.createCommissionTitle,
    render: (ctx) => (
      <>
        <Notice tone="info" className="mb-4">{c.createCommissionNote}</Notice>
        <ActionForm notify={false}
          fields={[
            { key: "businessMode", label: c.businessMode, required: true, maxLength: 32 },
            { key: "trafficSource", label: c.trafficSourceLabel, required: true, maxLength: 32 },
            { key: "rateBps", label: c.rateBps, type: "number", required: true, min: 0, max: 10000, help: c.rateBpsHelp },
            { key: "effectiveFrom", label: c.effectiveFrom, required: true, pattern: datetimePattern(c), help: c.effectiveFromHelp },
            reasonField(c),
          ]}
          initial={{ businessMode: "", trafficSource: "", rateBps: "", effectiveFrom: "", reason: "" }}
          draft={operator ? { operator, page: "pay-commission-rules", action: "create" } : null}
          onSubmit={(v) => run(() => api.commissionRules.create({ reason: String(v.reason), businessMode: String(v.businessMode).trim(), trafficSource: String(v.trafficSource).trim(), rateBps: Number(v.rateBps), effectiveFrom: parseLocalDateTime(v.effectiveFrom) }))}
          onDone={ctx.close} />
      </>
    ),
  }];
  const columns: Column<CommissionRuleRow>[] = [
    { header: c.businessMode, cell: (r) => r.businessMode },
    { header: c.trafficSourceLabel, cell: (r) => r.trafficSource },
    { header: c.rateBps, numeric: true, cell: (r) => bps(r.rateBps) },
    { header: c.effectiveFrom, cell: (r) => fmtTime(r.effectiveFrom) },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
  ];
  return (
    <PayListPage<CommissionRuleRow> header={header} help={c.commissionsDesc}
      filters={<FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />}
      query={list.query} page={list.page} size={list.size} onPage={list.setPage} onSize={list.setSize}
      columns={columns} rowKey={(r) => r.ruleNo} actions={actions} pageActions={pageActions} draftPage="pay-commission-rules" empty={c.emptyCommissions} />
  );
}
