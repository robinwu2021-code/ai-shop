"use client";

// 结算与资金（矩阵 P-12.1）。它是「按商家拆单 + 分账」链路的收口 ——
// 把商家的报备状态、自提点的服务费率、订单的流量来源、售后的回退标记这四个
// **已存在的字段**接起来。不接上，前面几个域的那些字段就是死的。
//
// 提现与税（P-12.2）矩阵标 P1 二期，本批不做，导航里保持待建。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill, useCopy } from "@/lib/use-copy";
import { FINANCE_COPY } from "./copy";
import { usePaging } from "@/lib/use-paging";
import { usePageTab } from "@/lib/use-page-tab";
import { MAX_SPLIT_RETRY, SETTLE_FREEZE_MIN_DAYS } from "@/lib/constants";
import { money } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useEditableConfig } from "@/lib/use-editable-config";
import { notify } from "@/lib/notify";
import type { AfterSale, Settlement, SplitRecord, TrafficSource } from "@/lib/types";
import { SettleStatusBadge, useSettleStatusMap, useTrafficSourceMap } from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
// 提现与发票各自成块 —— 与结算那四个 tab 只共用文案表
import { WithdrawTab } from "./withdraw-tab";
import { InvoiceTab } from "./invoice-tab";
// 费率单独成块：它与结算那几个 tab 只共用文案表，且形状是版本化的、与配置卡完全不同
import { FeeRuleTab } from "./fee-rule-tab";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FilterSelect } from "@/components/ui/filter-select";
import { ConfigCard } from "@/components/ui/config-card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { StatRow, Pagination, StatCard } from "@/components/ui/misc";
import { StatusBadge } from "@/components/ui/status-badge";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { useConfirm } from "@/components/ui/confirm-dialog";

type Copy = (typeof FINANCE_COPY)["zh"];
const TABS = (c: Copy) => [
  { key: "settlements", label: c.tabSettlements },
  { key: "splits", label: c.tabSplits },
  { key: "refund-back", label: c.tabRefundBack },
  { key: "rates", label: c.tabRates },
  { key: "withdraw", label: c.tabWithdraw },
  { key: "invoice", label: c.tabInvoice },
];

const TRAFFIC_LABEL = (c: Copy): Record<TrafficSource, string> => ({
  MERCHANT_OWNED: c.trafficMerchantOwned,
  PLATFORM: c.trafficPlatform,
  INVITE: c.trafficInvite,
  CHANNEL: c.trafficChannel,
});

/** 费率以万分比存，展示成百分比 —— 财务说的是「5%」不是「500 个万分点」。 */
const pct = (bp: number) => `${(bp / 100).toFixed(2)}%`;

export default function FinancePage() {
  return <Suspense fallback={null}><FinanceInner /></Suspense>;
}

function FinanceInner() {
  const c = useCopy(FINANCE_COPY);
  const tabs = TABS(c);
  const trafficLabel = TRAFFIC_LABEL(c);
  const qc = useQueryClient();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();

  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setKeyword(""); setStatus(""); });

  const { page, setPage, size, setSize } = usePaging();
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");

  const canExecute = allow("finance:settle:execute");
  const canEditRate = allow("finance:rate:update");
  const canWithdraw = allow("finance:withdraw:approve");
  const canInvoice = allow("finance:invoice:read");
  const settleStatusMap = useSettleStatusMap();
  const trafficMap = useTrafficSourceMap();

  const settleQ = { keyword, status, page, size };
  const settlements = useQuery({ queryKey: ["settlements", settleQ], queryFn: () => api.listSettlements(settleQ), enabled: tab === "settlements" });
  const splitQ = { keyword, page, size };
  const splits = useQuery({ queryKey: ["splits", splitQ], queryFn: () => api.listSplitRecords(splitQ), enabled: tab === "splits" });
  const backs = useQuery({ queryKey: ["refund-backs"], queryFn: () => api.listRefundSplitBacks(), enabled: tab === "refund-back" });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["settlements"] });
    qc.invalidateQueries({ queryKey: ["refund-backs"] });
    qc.invalidateQueries({ queryKey: ["after-sales"] });
  };

  const execSplit = useMutation({
    mutationFn: (settleNo: string) => api.executeSplit(settleNo),
    onSuccess: (s) => { invalidate(); notify.success(fill(c.toastSplitDone, { no: s.settleNo })); },
  });
  const freezeBack = useMutation({
    mutationFn: (settleNo: string) => api.freezeBackSettlement(settleNo),
    onSuccess: () => { invalidate(); notify.success(c.toastFrozenBack); },
  });
  const execBack = useMutation({
    mutationFn: (asNo: string) => api.executeRefundSplitBack(asNo),
    onSuccess: () => { invalidate(); notify.success(c.toastRefundBack); },
  });

  const settleColumns: Column<Settlement>[] = [
    { header: c.colSettleNo, cell: (s) => s.settleNo, numeric: true, align: "start" },
    { header: c.colMerchant, cell: (s) => s.merchantName },
    { header: c.colPeriod, cell: (s) => s.period },
    { header: c.colOrderCount, cell: (s) => s.orderCount, numeric: true },
    { header: c.colGross, cell: (s) => money(s.grossAmount), numeric: true },
    { header: c.colPlatformFee, cell: (s) => money(s.platformFee), numeric: true },
    { header: c.colServiceFee, cell: (s) => money(s.serviceFee), numeric: true },
    { header: c.colNet, cell: (s) => money(s.netAmount), numeric: true },
    {
      header: c.colRetry,
      numeric: true,
      // 到上限就不是"再点一次"能解决的了，颜色要能区分
      cell: (s) =>
        s.retryCount >= MAX_SPLIT_RETRY
          ? <Badge tone="danger">{fill(c.retryMaxed, { n: s.retryCount })}</Badge>
          : s.retryCount > 0
            ? <Badge tone="warning">{s.retryCount}</Badge>
            : <span className="text-muted-foreground">0</span>,
    },
    { header: c.colStatus, cell: (s) => <SettleStatusBadge value={s.status} /> },
    {
      header: c.colActions,
      cell: (s) => {
        if (!canExecute) return <span className="text-muted-foreground">-</span>;
        if (s.status === "SPLIT" || s.status === "FROZEN_BACK") return <span className="text-muted-foreground">{c.settleClosed}</span>;
        return (
          <div className="flex flex-nowrap items-center gap-2">
            <Button
              size="sm"
              onClick={async () => {
                const ok = await confirm({
                  title: fill(c.confirmSplitTitle, { no: s.settleNo }),
                  desc: fill(c.confirmSplitDesc, { name: s.merchantName, amount: money(s.netAmount) }),
                  danger: true, confirmText: c.confirmSplitOk, requireText: s.settleNo,
                });
                if (ok) execSplit.mutate(s.settleNo);
              }}
            >
              {s.status === "FAILED" ? c.btnRetrySplit : c.btnSplit}
            </Button>
            <Button size="sm" variant="outline" onClick={() => freezeBack.mutate(s.settleNo)}>{c.btnFreezeBack}</Button>
          </div>
        );
      },
    },
  ];

  const splitColumns: Column<SplitRecord>[] = [
    { header: c.colSplitNo, cell: (r) => r.splitNo, numeric: true, align: "start" },
    { header: c.colOrder, cell: (r) => r.orderNo, numeric: true, align: "start" },
    { header: c.colMerchant, cell: (r) => r.merchantName },
    {
      header: c.colTraffic,
      // R16：自带客流零佣金。列表里必须能看出"这单为什么不抽佣"
      cell: (r) => <StatusBadge map={trafficMap} value={r.trafficSource} />,
    },
    { header: c.colFeeRate, cell: (r) => pct(r.feeRate), numeric: true },
    { header: c.colGrossAmount, cell: (r) => money(r.grossAmount), numeric: true },
    { header: c.colPlatformFee, cell: (r) => money(r.platformFee), numeric: true },
    { header: c.colPickup, cell: (r) => r.pickupNo ?? "—" },
    { header: c.colServiceFee, cell: (r) => money(r.serviceFee), numeric: true },
    { header: c.colNet, cell: (r) => money(r.netAmount), numeric: true },
  ];

  const backColumns: Column<AfterSale>[] = [
    { header: c.colAsNo, cell: (a) => a.asNo, numeric: true, align: "start" },
    { header: c.colOrderNo, cell: (a) => a.orderNo, numeric: true, align: "start" },
    { header: c.colMerchant, cell: (a) => a.merchantName },
    { header: c.colRefundAmount, cell: (a) => money(a.amount), numeric: true },
    {
      header: c.colShare,
      // 回退要按裁决时判的比例分摊，所以这里必须显示它 —— 否则财务只能回去翻售后单
      cell: (a) =>
        a.share
          ? fill(c.shareText, { p: a.share.platform, m: a.share.merchant, k: a.share.pickup })
          : <span className="text-[var(--destructive)]">{c.shareUndecided}</span>,
    },
    { header: c.colVerdict, cell: (a) => a.verdict ?? "—", className: "whitespace-normal", width: "20rem" },
    {
      header: c.colActions,
      cell: (a) =>
        canExecute ? (
          <Button
            size="sm"
            onClick={async () => {
              const ok = await confirm({
                title: fill(c.confirmBackTitle, { no: a.asNo }),
                desc: fill(c.confirmBackDesc, { amount: money(a.amount) }),
                danger: true, confirmText: c.confirmBackOk,
              });
              if (ok) execBack.mutate(a.asNo);
            }}
          >
            {c.btnExecuteBack}
          </Button>
        ) : <span className="text-muted-foreground">-</span>,
    },
  ];

  const rows = settlements.data?.records ?? [];
  const pendingAmount = rows.filter((s) => s.status !== "SPLIT" && s.status !== "FROZEN_BACK").reduce((n, s) => n + s.netAmount, 0);

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab !== "rates" && !canExecute && (
        <ReadOnlyNotice what={c.readOnlyWhat} perm="finance:settle:execute" note={c.readOnlyNote} className="mb-3" />
      )}

      {tab === "settlements" && (
        <>
          <StatRow>
            <StatCard label={c.kpiPending} value={money(pendingAmount)} sub={c.kpiPendingSub} />
            <StatCard label={c.kpiPageCount} value={rows.length} />
            <StatCard
              label={c.kpiMaxed}
              value={rows.filter((s) => s.retryCount >= MAX_SPLIT_RETRY).length}
              sub={c.kpiMaxedSub}
              tone={rows.some((s) => s.retryCount >= MAX_SPLIT_RETRY) ? "down" : undefined}
            />
          </StatRow>
          <Notice className="mb-3">
            {c.settleNotice}
          </Notice>
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchSettle}>
            <FilterSelect aria-label={c.filterStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={settleStatusMap} allLabel={c.filterStatusAll} />
          </Toolbar>
          <DataTable
            columns={settleColumns} rows={settlements.data?.records} loading={settlements.isLoading}
            error={settlements.error} onRetry={() => settlements.refetch()}
            rowKey={(s) => s.settleNo}
            empty={c.emptySettle}
          />
          <Pagination page={page} size={size} onSize={setSize} total={settlements.data?.total ?? 0} onPage={setPage} />
        </>
      )}

      {tab === "splits" && (
        <>
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchSplit} />
          <DataTable
            columns={splitColumns} rows={splits.data?.records} loading={splits.isLoading}
            error={splits.error} onRetry={() => splits.refetch()}
            rowKey={(r) => r.splitNo}
            empty={c.emptySplit}
          />
          <Pagination page={page} size={size} onSize={setSize} total={splits.data?.total ?? 0} onPage={setPage} />
        </>
      )}

      {tab === "refund-back" && (
        <>
          <Notice className="mb-3">
            {c.refundBackNotice}
          </Notice>
          <DataTable
            columns={backColumns} rows={backs.data} loading={backs.isLoading}
            error={backs.error} onRetry={() => backs.refetch()}
            rowKey={(a) => a.asNo}
            empty={c.emptyRefundBack}
          />
        </>
      )}

      {tab === "withdraw" && (
        <>
          {!canWithdraw && <ReadOnlyNotice what={c.withdrawReadOnlyWhat} perm="finance:withdraw:approve" className="mb-3" />}
          <WithdrawTab c={c} canApprove={canWithdraw} />
        </>
      )}

      {tab === "invoice" && <InvoiceTab c={c} canEdit={canInvoice} />}

      {tab === "rates" && <FeeRuleTab c={c} canEdit={canEditRate} />}

      {dialog}
    </div>
  );
}
