"use client";

// 履约调度（矩阵 P-5.1）。
//
// ⚠️ 平台侧只做**调度与监控**，真正的核销动作在 B 端核销台（B-10.2）。
// 这两块必须成对交付：只做本页，货到了自提点仍然没人能核销（矩阵 §七「自提履约」链路）。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill, useCopy } from "@/lib/use-copy";
import { FULFILLMENT_COPY } from "./copy";
import { usePaging } from "@/lib/use-paging";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { MIN_OVERDUE_GRACE_HOURS } from "@/lib/constants";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useEditableConfig } from "@/lib/use-editable-config";
import { notify } from "@/lib/notify";
import type { ArrivalBatch, BatchStatus, OverdueAction, RedeemStat, SortingRow } from "@/lib/types";
import { BatchStatusBadge, useBatchStatusMap } from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
// 快递与运费各自成块，与调度那几个 tab 只共用文案表 —— 拆出去，页面才不会长到读不动
import { ExpressTab } from "./express-tab";
import { FreightTab } from "./freight-tab";
import { CarrierTab } from "./carrier-tab";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FilterSelect } from "@/components/ui/filter-select";
import { ConfigCard } from "@/components/ui/config-card";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { StatRow, Pagination, StatCard } from "@/components/ui/misc";
import { Progress } from "@/components/ui/progress";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";

type Copy = (typeof FULFILLMENT_COPY)["zh"];
const TAB_KEYS = ["batches", "sorting", "redeem", "express", "freight", "carrier", "overdue"] as const;

/** 批次的下一步：状态机只允许一条路（见 lib/types/fulfillment.ts）。 */
const NEXT_STATUS: Partial<Record<BatchStatus, { to: BatchStatus; labelKey: keyof Copy }>> = {
  PLANNED: { to: "DISPATCHED", labelKey: "nextDispatched" },
  DISPATCHED: { to: "ARRIVED", labelKey: "nextArrived" },
  ARRIVED: { to: "SIGNED", labelKey: "nextSigned" },
};

const ACTION_OPTIONS: { value: OverdueAction; labelKey: keyof Copy }[] = [
  { value: "POSTPONE", labelKey: "actionPostpone" },
  { value: "VOID", labelKey: "actionVoid" },
];

export default function FulfillmentPage() {
  return <Suspense fallback={null}><FulfillmentInner /></Suspense>;
}

function FulfillmentInner() {
  const c = useCopy(FULFILLMENT_COPY);
  const tabs = useNavTabs("/fulfillment", TAB_KEYS);
  const qc = useQueryClient();
  const allow = useCan();

  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setKeyword(""); });

  const { page, setPage, size, setSize } = usePaging();
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");

  const canDispatch = allow("fulfillment:batch:read");
  const canEditRule = allow("fulfillment:rule:update");
  const batchStatusMap = useBatchStatusMap();

  const batchQ = { keyword, status, page, size };
  const batches = useQuery({
    queryKey: ["batches", batchQ],
    queryFn: () => api.listArrivalBatches(batchQ),
    enabled: tab === "batches",
  });
  const sorting = useQuery({
    queryKey: ["sorting"],
    queryFn: () => api.listSorting(),
    enabled: tab === "sorting",
  });
  const redeem = useQuery({
    queryKey: ["redeem"],
    queryFn: () => api.listRedeemStats(),
    enabled: tab === "redeem",
  });
  const rule = useQuery({
    queryKey: ["overdue-rule"],
    queryFn: () => api.getOverdueRule(),
    enabled: tab === "overdue",
  });

  const statusMut = useMutation({
    mutationFn: (v: { batchNo: string; status: BatchStatus }) => api.setBatchStatus(v.batchNo, v.status),
    onSuccess: (b) => {
      qc.invalidateQueries({ queryKey: ["batches"] });
      // 签收会让这批货进入分拣视图，顺手让分拣页失效，避免运营切过去看到旧数据
      qc.invalidateQueries({ queryKey: ["sorting"] });
      notify.success(fill(c.toastBatchUpdated, { no: b.batchNo }));
    },
  });

  const { form: editing, set: setField, reset: resetForm } = useEditableConfig(rule.data, (d) => ({
    action: d.action,
    graceHours: String(d.graceHours),
    maxPostpone: String(d.maxPostpone),
  }));

  const saveRule = useMutation({
    mutationFn: () =>
      api.saveOverdueRule({
        action: editing!.action,
        graceHours: Number(editing!.graceHours),
        maxPostpone: Number(editing!.maxPostpone),
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["overdue-rule"] });
      resetForm();
      notify.success(c.toastRuleSaved);
    },
  });

  const batchColumns: Column<ArrivalBatch>[] = [
    { header: c.colBatchNo, cell: (b) => b.batchNo, numeric: true, align: "start" },
    { header: c.colPickup, cell: (b) => b.pickupName },
    { header: c.colCommunity, cell: (b) => b.communityName },
    { header: c.colPlanArrive, cell: (b) => fmtTime(b.planArriveAt) },
    { header: c.colVehicle, cell: (b) => b.vehicle },
    { header: c.colItemCount, cell: (b) => b.itemCount, numeric: true },
    // 一个批次混装多家商家的货（按商家拆单的必然结果），分拣时要按这个数预判工作量
    { header: c.colMerchantCount, cell: (b) => b.merchantCount, numeric: true },
    { header: c.colStatus, cell: (b) => <BatchStatusBadge value={b.status} /> },
    {
      header: c.colActions,
      cell: (b) => {
        const next = NEXT_STATUS[b.status];
        if (!next) return <span className="text-muted-foreground">{c.batchDone}</span>;
        if (!canDispatch) return <span className="text-muted-foreground">-</span>;
        return (
          <Button size="sm" variant="outline" onClick={() => statusMut.mutate({ batchNo: b.batchNo, status: next.to })}>
            {c[next.labelKey]}
          </Button>
        );
      },
    },
  ];

  const sortingColumns: Column<SortingRow>[] = [
    { header: c.colPickup, cell: (r) => r.pickupName },
    { header: c.colSkuNo, cell: (r) => r.skuNo, numeric: true, align: "start" },
    { header: c.colTitle, cell: (r) => r.title },
    { header: c.colSupplier, cell: (r) => r.merchantName },
    { header: c.colQty, cell: (r) => r.qty, numeric: true },
    {
      header: c.colShortQty,
      numeric: true,
      // 缺件直连售后责任判定，0 与非 0 是两种性质，不能都渲染成灰数字
      cell: (r) => (r.shortQty > 0 ? <Badge tone="danger">{r.shortQty}</Badge> : <span className="text-muted-foreground">0</span>),
    },
  ];

  const redeemColumns: Column<RedeemStat>[] = [
    { header: c.colPickup, cell: (r) => r.pickupName },
    { header: c.colCommunity, cell: (r) => r.communityName },
    { header: c.colPending, cell: (r) => r.pending, numeric: true },
    { header: c.colRedeemed, cell: (r) => r.redeemed, numeric: true },
    {
      header: c.colOverdue,
      numeric: true,
      cell: (r) => (r.overdue > 0 ? <span className="text-[var(--destructive)]">{r.overdue}</span> : 0),
    },
    {
      header: c.colRate,
      width: "12rem",
      cell: (r) => (
        <div className="flex items-center gap-2">
          <Progress value={Math.round(r.rate * 100)} total={100} showText={false} className="w-24" />
          <span className="tabular-nums text-muted-foreground">{Math.round(r.rate * 100)}%</span>
        </div>
      ),
    },
  ];

  const totalPending = (redeem.data?.records ?? []).reduce((n, r) => n + r.pending, 0);
  const totalOverdue = (redeem.data?.records ?? []).reduce((n, r) => n + r.overdue, 0);

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      <Notice className="mb-3">
        {c.notice}
      </Notice>

      {tab === "batches" && (
        <>
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchPlaceholder}>
            <FilterSelect aria-label={c.filterStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={batchStatusMap} allLabel={c.filterStatusAll} />
          </Toolbar>
          <DataTable
            columns={batchColumns}
            rows={batches.data?.records}
            loading={batches.isLoading}
            error={batches.error}
            onRetry={() => batches.refetch()}
            rowKey={(b) => b.batchNo}
            empty={c.emptyBatches}
          />
          <Pagination page={page} size={size} onSize={setSize} total={batches.data?.total ?? 0} onPage={setPage} />
        </>
      )}

      {tab === "sorting" && (
        <>
          <Notice className="mb-3">{c.sortingNotice}</Notice>
          <DataTable
            columns={sortingColumns}
            rows={sorting.data?.records}
            loading={sorting.isLoading}
            error={sorting.error}
            onRetry={() => sorting.refetch()}
            rowKey={(r) => `${r.pickupNo}-${r.skuNo}`}
            empty={c.emptySorting}
          />
        </>
      )}

      {tab === "redeem" && (
        <>
          <StatRow>
            <StatCard label={c.kpiPending} value={totalPending} sub={c.kpiPendingSub} />
            <StatCard label={c.kpiOverdue} value={totalOverdue} sub={totalOverdue > 0 ? c.kpiOverdueSub : c.kpiOverdueNone} tone={totalOverdue > 0 ? "down" : undefined} />
            <StatCard label={c.kpiPickups} value={(redeem.data?.records ?? []).length} />
          </StatRow>
          <DataTable
            columns={redeemColumns}
            rows={redeem.data?.records}
            loading={redeem.isLoading}
            error={redeem.error}
            onRetry={() => redeem.refetch()}
            rowKey={(r) => r.pickupNo}
            empty={c.emptyRedeem}
          />
        </>
      )}

      {tab === "express" && (
        <>
          {!canEditRule && <ReadOnlyNotice what={c.expressReadOnlyWhat} perm="fulfillment:rule:update" className="mb-3" />}
          <ExpressTab c={c} canEdit={canEditRule} />
        </>
      )}

      {tab === "freight" && (
        <>
          {!canEditRule && <ReadOnlyNotice what={c.freightReadOnlyWhat} perm="fulfillment:rule:update" className="mb-3" />}
          <FreightTab c={c} canEdit={canEditRule} />
        </>
      )}

      {tab === "carrier" && (
        <>
          {!canEditRule && <ReadOnlyNotice what={c.carrierReadOnlyWhat} perm="fulfillment:rule:update" className="mb-3" />}
          <CarrierTab c={c} canEdit={canEditRule} />
        </>
      )}

      {tab === "overdue" && (
        <ConfigCard
          title={c.ruleTitle}
          readOnly={!canEditRule && (
              <ReadOnlyNotice what={c.ruleReadOnlyWhat} perm="fulfillment:rule:update" note={c.ruleReadOnlyNote} className="mb-3" />
            )}
          onSave={() => saveRule.mutate()}
          saving={saveRule.isPending}
          canSave={canEditRule}
          updatedAt={rule.data?.updatedAt}
          updatedBy={rule.data?.updatedBy}
        >
          {editing && (
            <>
                <div className="space-y-1">
                  <Label htmlFor="of-action">{c.fieldAction}</Label>
                  <Select
                    id="of-action"
                    className="w-full"
                    disabled={!canEditRule}
                    value={editing.action}
                    onChange={(e) => setField("action", e.target.value as OverdueAction)}
                  >
                    {ACTION_OPTIONS.map((o) => <option key={o.value} value={o.value}>{c[o.labelKey]}</option>)}
                  </Select>
                </div>
                <div className="space-y-1">
                  <Label htmlFor="of-grace" required>{c.fieldGrace}</Label>
                  <Input
                    id="of-grace"
                    className="w-full"
                    disabled={!canEditRule}
                    value={editing.graceHours}
                    onChange={(e) => setField("graceHours", e.target.value)}
                  />
                  <p className="txt-caption text-muted-foreground">
                    {fill(c.graceHint, { n: MIN_OVERDUE_GRACE_HOURS })}
                  </p>
                </div>
                {editing.action === "POSTPONE" && (
                  <div className="space-y-1">
                    <Label htmlFor="of-max" required>{c.fieldMaxPostpone}</Label>
                    <Input
                      id="of-max"
                      className="w-full"
                      disabled={!canEditRule}
                      value={editing.maxPostpone}
                      onChange={(e) => setField("maxPostpone", e.target.value)}
                    />
                    <p className="txt-caption text-muted-foreground">{c.maxPostponeHint}</p>
                  </div>
                )}
            </>
          )}
        </ConfigCard>
      )}
    </div>
  );
}
